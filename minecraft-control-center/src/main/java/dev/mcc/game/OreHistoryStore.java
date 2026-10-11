package dev.mcc.game;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** One bounded database worker; callers never perform SQLite or filesystem work. */
public final class OreHistoryStore {
    public static final int[] WINDOWS = {300, 1800, 3600};
    private static final OreKind[] ORES = OreKind.values();
    private static final int MAX_QUEUE = 8;
    public record Limits(int maxBufferedBuckets, int maxRecentRows, int maxRecentPlayers) {
        public Limits { if (maxBufferedBuckets < 1 || maxBufferedBuckets > 8192 || maxRecentRows < 1 || maxRecentRows > 262144 || maxRecentPlayers < 1 || maxRecentPlayers > 512) throw new IllegalArgumentException("Invalid ore history limits"); }
        public static Limits defaults() { return new Limits(2048, 131072, 256); }
    }
    private record Key(UUID player, long second) {}
    private static final class Delta {
        final UUID player; final long second; final long[] counts = new long[ORES.length];
        String name, world; long lastAt;
        Delta(OreLedger.Observation event) { player = event.player(); second = Math.floorDiv(event.at(), 1000); add(event); }
        void add(OreLedger.Observation event) { counts[event.ore().ordinal()] = Math.addExact(counts[event.ore().ordinal()], 1); if (event.at() >= lastAt) { lastAt = event.at(); name = event.name(); world = event.world(); } }
    }
    private record Batch(long sequence, List<Delta> rows) {}
    private static final class PlayerRow {
        final String id, name, world; final long lastAt, total; final long[] allTime;
        final long[][] windows = new long[3][ORES.length];
        PlayerRow(ResultSet row) throws SQLException { id=row.getString("uuid");name=row.getString("name");world=row.getString("world");lastAt=row.getLong("last_mined");total=row.getLong("total");allTime=new long[ORES.length];for(OreKind ore:ORES)allTime[ore.ordinal()]=row.getLong(ore.id()); }
    }
    private final Path file;
    private final Limits limits;
    private final Map<OreKind, OreLedger.Threshold> thresholds;
    private final LongSupplier clock;
    private final String session = UUID.randomUUID().toString();
    private final ThreadPoolExecutor worker;
    private final Object lock = new Object(), taskLock = new Object();
    private LinkedHashMap<Key, Delta> buffer = new LinkedHashMap<>();
    private Batch retry;
    private boolean flushing, accepting = true;
    private long nextSequence, dropped;
    private final long[] coverage = new long[3], durableCoverage = new long[3];
    private boolean coverageDirty;
    private CompletableFuture<Void> closing;
    private Connection connection; // worker only
    private boolean initialized;
    private long trackingSince, recentEvicted, durableDropped;
    private boolean priorUnclean, observationGaps;
    private volatile boolean loading = true;
    private volatile String error = "";
    private volatile long lastFlushAt;
    private volatile Set<UUID> online = Set.of();

    public OreHistoryStore(Path file, Map<OreKind, OreLedger.Threshold> thresholds, LongSupplier clock) {
        this(file, thresholds, clock, Limits.defaults(), CompletableFuture.completedFuture(null));
    }
    public OreHistoryStore(Path file, Map<OreKind, OreLedger.Threshold> thresholds, LongSupplier clock, Limits limits, CompletableFuture<Void> predecessor) {
        this.file = Objects.requireNonNull(file).toAbsolutePath().normalize(); this.thresholds = Map.copyOf(thresholds); this.clock = Objects.requireNonNull(clock); this.limits = Objects.requireNonNull(limits);
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(MAX_QUEUE), task -> { Thread thread = new Thread(task, "mcc-ore-history"); thread.setDaemon(true); return thread; }, new ThreadPoolExecutor.AbortPolicy());
        worker.execute(() -> { try { predecessor.get(5, TimeUnit.SECONDS); open(); } catch (Exception failure) { error="storage_unavailable"; } finally { loading=false; } });
    }
    /** Main-thread observation is coalesced by player/second. Fortune and drops never enter this API. */
    public boolean offer(OreLedger.Observation event) {
        Objects.requireNonNull(event);
        synchronized (lock) {
            if (!accepting) return false;
            Key key = new Key(event.player(), Math.floorDiv(event.at(), 1000)); Delta delta = buffer.get(key);
            if (delta != null) { delta.add(event); return true; }
            if (buffer.size() >= limits.maxBufferedBuckets) { dropped++; return false; }
            buffer.put(key, new Delta(event)); return true;
        }
    }
    public void online(Set<UUID> players) { online = Set.copyOf(players); }
    /** Cumulative session losses, supplied on-main; repeated samples never add them twice. */
    public void noteCoverage(long skipped, long pendingDropped, long placedEvicted) {
        if(skipped<0||pendingDropped<0||placedEvicted<0)throw new IllegalArgumentException("Invalid ore coverage");
        synchronized(lock){long[] sample={skipped,pendingDropped,placedEvicted};for(int i=0;i<sample.length;i++)if(sample[i]>coverage[i]){coverage[i]=sample[i];coverageDirty=true;}}
    }
    private <T> CompletableFuture<T> submit(Callable<T> operation) {
        CompletableFuture<T> future = new CompletableFuture<>();
        synchronized (taskLock) {
            synchronized (lock) { if (!accepting) { future.completeExceptionally(new IllegalStateException("Ore history closed")); return future; } }
            if (worker.getQueue().size() >= MAX_QUEUE - 1) { future.completeExceptionally(new IllegalStateException("Ore history queue is full")); return future; }
            try { worker.execute(() -> { try { requireOpen(); future.complete(operation.call()); } catch (Exception failure) { error="storage_unavailable"; future.completeExceptionally(new IllegalStateException("Ore history unavailable", failure)); } }); }
            catch (RejectedExecutionException failure) { future.completeExceptionally(new IllegalStateException("Ore history closed", failure)); }
        }
        return future;
    }
    public CompletableFuture<Void> flush() {
        Batch batch;
        synchronized (lock) {
            if (!accepting || flushing) return CompletableFuture.completedFuture(null);
            if (retry != null) batch = retry;
            else if (!buffer.isEmpty()) { batch = new Batch(++nextSequence, List.copyOf(buffer.values())); buffer = new LinkedHashMap<>(); }
            else if(coverageDirty) batch = new Batch(++nextSequence,List.of());
            else return CompletableFuture.completedFuture(null);
            coverageDirty=false;
            flushing = true; retry = batch;
        }
        CompletableFuture<Void> future = submit(() -> { persist(batch); return null; });
        future.whenComplete((ignored, failure) -> { synchronized (lock) { if (failure == null && retry == batch) retry = null; flushing = false; } });
        return future;
    }
    public CompletableFuture<Map<String, Object>> snapshot(long now) { return submit(() -> snapshotOnWorker(now)); }
    public CompletableFuture<Map<String, Object>> history(String query, int offset, int limit) {
        if (query == null || query.length() > 64 || offset < 0 || offset > 1_000_000 || limit < 1 || limit > 50) return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid ore history page"));
        String match = "%" + query.toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return submit(() -> {
            String where = " WHERE search LIKE ? ESCAPE '\\'";
            LinkedHashMap<String,PlayerRow> players = new LinkedHashMap<>(); long total;
            try (PreparedStatement statement=connection.prepareStatement("SELECT count(*) FROM ore_totals"+where)) { statement.setString(1,match);try(ResultSet result=statement.executeQuery()){result.next();total=result.getLong(1);} }
            try (PreparedStatement statement=connection.prepareStatement("SELECT * FROM ore_totals"+where+" ORDER BY total DESC,uuid ASC LIMIT ? OFFSET ?")) {
                statement.setString(1,match);statement.setInt(2,limit);statement.setInt(3,offset);
                try(ResultSet result=statement.executeQuery()){while(result.next()){PlayerRow row=new PlayerRow(result);players.put(row.id,row);}}
            }
            long now=clock.getAsLong();loadRecent(players,now);
            return Map.of("generatedAt",now,"trackingSince",trackingSince,"players",players.values().stream().map(this::json).toList(),"totalPlayers",total,"offset",offset,"limit",limit,"topPlayers",topFive(),"persistence",state());
        });
    }
    public Map<String,Object> state() {
        long pending, lost;
        synchronized(lock) { pending=buffer.values().stream().mapToLong(delta -> sum(delta.counts)).sum()+(retry==null?0:retry.rows.stream().mapToLong(delta->sum(delta.counts)).sum());lost=dropped; }
        LinkedHashMap<String,Object> state=new LinkedHashMap<>();state.put("status",loading?"loading":error.isEmpty()?"ready":"error");state.put("pendingBlocks",pending);state.put("droppedBlocks",lost);state.put("lastFlushAt",lastFlushAt);state.put("errorCode",error);
        return Map.copyOf(state);
    }
    private void requireOpen() throws SQLException { if (!initialized || connection == null || connection.isClosed()) throw new SQLException("Ore history not initialized"); }
    private void open() throws Exception {
        Path parent=file.getParent();
        if (!Files.isDirectory(parent) || Files.isSymbolicLink(parent) || Files.exists(file,LinkOption.NOFOLLOW_LINKS)&&(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(file))) throw new IllegalArgumentException("Invalid ore history path");
        connection=DriverManager.getConnection("jdbc:sqlite:"+file);
        try(Statement statement=connection.createStatement()){
            statement.execute("PRAGMA journal_mode=WAL");statement.execute("PRAGMA synchronous=FULL");statement.execute("PRAGMA busy_timeout=1000");
            statement.execute("PRAGMA cache_size=-1024");statement.execute("PRAGMA mmap_size=0");statement.execute("PRAGMA temp_store=FILE");
            String columns=Arrays.stream(ORES).map(ore->ore.id()+" INTEGER NOT NULL DEFAULT 0 CHECK(typeof("+ore.id()+")='integer' AND "+ore.id()+">=0)").reduce((a,b)->a+","+b).orElseThrow();
            statement.execute("CREATE TABLE IF NOT EXISTS ore_totals(uuid TEXT PRIMARY KEY,name TEXT NOT NULL,world TEXT NOT NULL,search TEXT NOT NULL,last_mined INTEGER NOT NULL,total INTEGER NOT NULL DEFAULT 0 CHECK(typeof(total)='integer' AND total>=0),"+columns+")");
            statement.execute("CREATE INDEX IF NOT EXISTS ore_total_rank ON ore_totals(total DESC,uuid ASC)");
            statement.execute("CREATE TABLE IF NOT EXISTS ore_recent(uuid TEXT NOT NULL,minute INTEGER NOT NULL,second INTEGER NOT NULL,ore INTEGER NOT NULL,count INTEGER NOT NULL,PRIMARY KEY(uuid,second,ore))");
            statement.execute("CREATE INDEX IF NOT EXISTS ore_recent_time ON ore_recent(second)");
            statement.execute("CREATE TABLE IF NOT EXISTS ore_meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS ore_batches(session TEXT PRIMARY KEY,sequence INTEGER NOT NULL,last_at INTEGER NOT NULL)");
        }
        trackingSince=number("tracking_since",clock.getAsLong());recentEvicted=number("recent_evicted",0);durableDropped=number("dropped_blocks",0);
        durableCoverage[0]=number("verification_skipped",0);durableCoverage[1]=number("verification_dropped",0);durableCoverage[2]=number("placement_evicted",0);
        priorUnclean=number("active",0)==1;observationGaps=number("observation_gaps",0)==1||number("closed_at",0)>0;
        set("tracking_since",trackingSince);set("active",1);set("observation_gaps",observationGaps?1:0);set("unclean_seen",number("unclean_seen",0)==1||priorUnclean?1:0);
        initialized=true;
    }
    private long number(String key,long fallback)throws SQLException{try(PreparedStatement statement=connection.prepareStatement("SELECT value FROM ore_meta WHERE key=?")){statement.setString(1,key);try(ResultSet result=statement.executeQuery()){return result.next()?Long.parseLong(result.getString(1)):fallback;}}}
    private void set(String key,long value)throws SQLException{try(PreparedStatement statement=connection.prepareStatement("INSERT INTO ore_meta VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")){statement.setString(1,key);statement.setString(2,Long.toString(value));statement.executeUpdate();}}
    private long[] coverageTotals(){synchronized(lock){return new long[]{Math.addExact(durableCoverage[0],coverage[0]),Math.addExact(durableCoverage[1],coverage[1]),Math.addExact(durableCoverage[2],coverage[2])};}}
    private void persistCoverage()throws SQLException{long[] totals=coverageTotals();set("verification_skipped",totals[0]);set("verification_dropped",totals[1]);set("placement_evicted",totals[2]);}
    private void persist(Batch batch)throws SQLException {
        requireOpen();connection.setAutoCommit(false);long previousEvicted=recentEvicted;
        try {
            long applied=0;try(PreparedStatement statement=connection.prepareStatement("SELECT sequence FROM ore_batches WHERE session=?")){statement.setString(1,session);try(ResultSet result=statement.executeQuery()){if(result.next())applied=result.getLong(1);}}
            if (batch.sequence>applied) {
                String names=Arrays.stream(ORES).map(OreKind::id).reduce((a,b)->a+","+b).orElseThrow();
                String placeholders=String.join(",",Collections.nCopies(ORES.length+6,"?"));
                String additions=Arrays.stream(ORES).map(ore->ore.id()+"=ore_totals."+ore.id()+"+excluded."+ore.id()).reduce((a,b)->a+","+b).orElseThrow();
                String sql="INSERT INTO ore_totals(uuid,name,world,search,last_mined,total,"+names+") VALUES("+placeholders+") ON CONFLICT(uuid) DO UPDATE SET name=CASE WHEN excluded.last_mined>=ore_totals.last_mined THEN excluded.name ELSE ore_totals.name END,world=CASE WHEN excluded.last_mined>=ore_totals.last_mined THEN excluded.world ELSE ore_totals.world END,search=CASE WHEN excluded.last_mined>=ore_totals.last_mined THEN excluded.search ELSE ore_totals.search END,last_mined=max(ore_totals.last_mined,excluded.last_mined),total=ore_totals.total+excluded.total,"+additions;
                try(PreparedStatement total=connection.prepareStatement(sql);PreparedStatement recent=connection.prepareStatement("INSERT INTO ore_recent VALUES(?,?,?,?,?) ON CONFLICT(uuid,second,ore) DO UPDATE SET count=ore_recent.count+excluded.count")){
                    for(Delta row:batch.rows){
                        total.setString(1,row.player.toString());total.setString(2,row.name);total.setString(3,row.world);total.setString(4,(row.name+" "+row.world).toLowerCase(Locale.ROOT));total.setLong(5,row.lastAt);total.setLong(6,sum(row.counts));
                        for(OreKind ore:ORES)total.setLong(7+ore.ordinal(),row.counts[ore.ordinal()]);total.addBatch();
                        for(OreKind ore:ORES)if(row.counts[ore.ordinal()]>0){recent.setString(1,row.player.toString());recent.setLong(2,Math.floorDiv(row.second,60));recent.setLong(3,row.second);recent.setInt(4,ore.ordinal());recent.setLong(5,row.counts[ore.ordinal()]);recent.addBatch();}
                    }
                    total.executeBatch();recent.executeBatch();
                }
                try(PreparedStatement statement=connection.prepareStatement("INSERT INTO ore_batches VALUES(?,?,?) ON CONFLICT(session) DO UPDATE SET sequence=excluded.sequence,last_at=excluded.last_at")){statement.setString(1,session);statement.setLong(2,batch.sequence);statement.setLong(3,clock.getAsLong());statement.executeUpdate();}
            }
            long lost;synchronized(lock){lost=dropped;}set("dropped_blocks",durableDropped+lost);
            persistCoverage();
            trim(clock.getAsLong());connection.commit();lastFlushAt=clock.getAsLong();error="";
        }catch(SQLException|RuntimeException failure){recentEvicted=previousEvicted;try{connection.rollback();}catch(SQLException rollback){failure.addSuppressed(rollback);}throw failure;}finally{connection.setAutoCommit(true);}
    }
    private void trim(long now)throws SQLException {
        try(PreparedStatement statement=connection.prepareStatement("DELETE FROM ore_recent WHERE second<=?")){statement.setLong(1,Math.floorDiv(now,1000)-3600);statement.executeUpdate();}
        long rows;try(Statement statement=connection.createStatement();ResultSet result=statement.executeQuery("SELECT count(*) FROM ore_recent")){result.next();rows=result.getLong(1);}
        if(rows>limits.maxRecentRows){try(PreparedStatement statement=connection.prepareStatement("DELETE FROM ore_recent WHERE rowid IN (SELECT rowid FROM ore_recent ORDER BY second ASC,uuid ASC,ore ASC LIMIT ?)")){statement.setLong(1,rows-limits.maxRecentRows);recentEvicted+=statement.executeUpdate();set("recent_evicted",recentEvicted);}}
        try(Statement statement=connection.createStatement()){statement.executeUpdate("DELETE FROM ore_batches WHERE session IN (SELECT session FROM ore_batches ORDER BY last_at DESC LIMIT -1 OFFSET 1024)");}
    }
    private Map<String,Object> snapshotOnWorker(long now)throws SQLException {
        trim(now);
        LinkedHashMap<String,PlayerRow> players=new LinkedHashMap<>();boolean truncated=false;
        try(PreparedStatement statement=connection.prepareStatement("SELECT * FROM ore_totals WHERE last_mined>? ORDER BY last_mined DESC,uuid ASC LIMIT ?")){
            statement.setLong(1,now-3600_000);statement.setInt(2,limits.maxRecentPlayers+1);
            try(ResultSet result=statement.executeQuery()){while(result.next()){if(players.size()==limits.maxRecentPlayers){truncated=true;break;}PlayerRow row=new PlayerRow(result);players.put(row.id,row);}}
        }
        loadRecent(players,now);
        List<Map<String,Object>> rows=players.values().stream().map(this::json).sorted(Comparator.<Map<String,Object>>comparingInt(row->switch((String)row.get("severity")){case"RED"->2;case"YELLOW"->1;default->0;}).reversed().thenComparing(Comparator.comparingLong((Map<String,Object>row)->(long)row.get("total")).reversed()).thenComparing(row->(String)row.get("id"))).toList();
        long lost;synchronized(lock){lost=dropped;}
        long[] losses=coverageTotals();boolean unclean=number("unclean_seen",0)==1;
        Map<String,Object> historyCoverage=new LinkedHashMap<>();historyCoverage.put("recentRowsEvicted",recentEvicted);historyCoverage.put("recentPlayersTruncated",truncated);historyCoverage.put("durableDroppedBlocks",durableDropped+lost);historyCoverage.put("possibleUnflushedLoss",unclean);historyCoverage.put("observationGaps",observationGaps);historyCoverage.put("verificationSkipped",losses[0]);historyCoverage.put("pendingDropped",losses[1]);historyCoverage.put("placedEvicted",losses[2]);historyCoverage.put("partial",recentEvicted>0||truncated||durableDropped+lost>0||losses[0]+losses[1]+losses[2]>0||observationGaps||unclean);
        LinkedHashMap<String,Object> snapshot=new LinkedHashMap<>();snapshot.put("generatedAt",now);snapshot.put("trackingSince",trackingSince);snapshot.put("startedAt",trackingSince);snapshot.put("observedSeconds",Math.min(3600,Math.max(0,(now-trackingSince)/1000)));snapshot.put("windowSeconds",3600);snapshot.put("indicatorWindow",300);snapshot.put("windowResolutionSeconds",1);snapshot.put("windows",List.of(300,1800,3600));snapshot.put("players",rows);snapshot.put("topPlayers",topFive());snapshot.put("historyCoverage",Map.copyOf(historyCoverage));snapshot.put("persistence",state());snapshot.put("limits",Map.of("maxBufferedBuckets",limits.maxBufferedBuckets,"maxRecentRows",limits.maxRecentRows,"maxPlayers",limits.maxRecentPlayers,"workerThreads",1,"queueTasks",MAX_QUEUE));
        return Map.copyOf(snapshot);
    }
    private void loadRecent(Map<String,PlayerRow> players,long now)throws SQLException{
        if(players.isEmpty())return;long second=Math.floorDiv(now,1000);
        String placeholders=String.join(",",Collections.nCopies(players.size(),"?"));
        try(PreparedStatement statement=connection.prepareStatement("SELECT uuid,second,ore,count FROM ore_recent WHERE uuid IN ("+placeholders+") AND second>? AND second<=?")){
            int index=1;for(String id:players.keySet())statement.setString(index++,id);statement.setLong(index++,second-3600);statement.setLong(index,second);
            try(ResultSet result=statement.executeQuery()){while(result.next()){PlayerRow row=players.get(result.getString(1));long at=result.getLong(2),count=result.getLong(4);int kind=result.getInt(3);if(kind<0||kind>=ORES.length||count<0)throw new SQLException("Invalid ore bucket");for(int i=0;i<WINDOWS.length;i++)if(at>second-WINDOWS[i])row.windows[i][kind]=Math.addExact(row.windows[i][kind],count);}}
        }
    }
    private List<Map<String,Object>> topFive()throws SQLException{List<Map<String,Object>> rows=new ArrayList<>();try(Statement statement=connection.createStatement();ResultSet result=statement.executeQuery("SELECT * FROM ore_totals WHERE total>0 ORDER BY total DESC,uuid ASC LIMIT 5")){while(result.next()){PlayerRow row=new PlayerRow(result);rows.add(Map.of("id",row.id,"name",row.name,"total",row.total,"counts",counts(row.allTime),"lastMinedAt",row.lastAt));}}return List.copyOf(rows);}
    private Map<String,Object> json(PlayerRow player){
        Map<String,Object> windows=new LinkedHashMap<>();for(int i=0;i<WINDOWS.length;i++)windows.put(String.valueOf(WINDOWS[i]),counts(player.windows[i]));windows.put("ALL_TIME",counts(player.allTime));
        List<String> flagged=new ArrayList<>();for(OreKind ore:ORES)if(player.windows[0][ore.ordinal()]>=thresholds.getOrDefault(ore,new OreLedger.Threshold(ore.yellow,ore.red)).yellow())flagged.add(ore.id());
        return Map.of("id",player.id,"name",player.name,"world",player.world,"online",online.contains(UUID.fromString(player.id)),"lastMinedAt",player.lastAt,"counts",Map.copyOf(windows),"total",sum(player.windows[2]),"allTimeTotal",player.total,"severity",OreLedger.severity(player.windows[0],thresholds),"flaggedOres",List.copyOf(flagged));
    }
    private static Map<String,Long> counts(long[] values){Map<String,Long> result=new LinkedHashMap<>();for(OreKind ore:ORES)result.put(ore.id(),values[ore.ordinal()]);return Map.copyOf(result);}
    private static long sum(long[] values){long sum=0;for(long value:values)sum=Math.addExact(sum,value);return sum;}

    public CompletableFuture<Void> closeAsync() {
        synchronized(taskLock){
            synchronized(lock){if(closing!=null)return closing;accepting=false;closing=new CompletableFuture<>();}
            try{worker.execute(()->{
                try{requireOpen();Batch pending;synchronized(lock){pending=retry;}if(pending!=null)persist(pending);List<Delta> finalRows;long lost;synchronized(lock){finalRows=List.copyOf(buffer.values());buffer.clear();retry=null;lost=dropped;}if(!finalRows.isEmpty())persist(new Batch(++nextSequence,finalRows));set("dropped_blocks",durableDropped+lost);persistCoverage();set("active",0);set("closed_at",clock.getAsLong());connection.close();closing.complete(null);}
                catch(Exception failure){error="shutdown_flush_failed";try{if(connection!=null)connection.close();}catch(SQLException ignored){}closing.completeExceptionally(new IllegalStateException("Ore history final flush failed",failure));}
                finally{worker.shutdown();}
            });}catch(RejectedExecutionException failure){closing.completeExceptionally(failure);worker.shutdown();}
            return closing;
        }
    }
}
