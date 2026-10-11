package dev.mcc.core;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** A bounded single-writer queue: no JDBC operation runs on a Minecraft tick. */
public final class Database implements AutoCloseable {
    @FunctionalInterface public interface Work<T> { T run(Connection c) throws Exception; }
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(1024), r -> { Thread t=new Thread(r,"MCC-SQLite");t.setDaemon(true);return t; });
    private Connection connection;
    private final AtomicLong errors = new AtomicLong();
    private volatile double latencyMs;
    public Database(Path path) throws Exception {
        submit(c -> {
            Class.forName("org.sqlite.JDBC");
            connection=DriverManager.getConnection("jdbc:sqlite:"+path.toAbsolutePath());
            try(Statement s=connection.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");s.execute("PRAGMA busy_timeout=5000");
                s.execute("PRAGMA synchronous=NORMAL");s.execute("PRAGMA foreign_keys=ON");
                s.execute("PRAGMA cache_size=-2048");s.execute("PRAGMA mmap_size=0");
                s.execute("CREATE TABLE IF NOT EXISTS users(name TEXT PRIMARY KEY,hash TEXT NOT NULL,role TEXT NOT NULL CHECK(role IN ('Owner','Admin','Moderator','Viewer')))");
                s.execute("CREATE TABLE IF NOT EXISTS audit(id INTEGER PRIMARY KEY AUTOINCREMENT,time INTEGER NOT NULL,actor TEXT NOT NULL,action TEXT NOT NULL,status TEXT NOT NULL,details TEXT NOT NULL)");
                s.execute("CREATE INDEX IF NOT EXISTS audit_time ON audit(time)");
                s.execute("CREATE TABLE IF NOT EXISTS metrics(time INTEGER PRIMARY KEY,json TEXT NOT NULL)");
                s.execute("CREATE TABLE IF NOT EXISTS documents(namespace TEXT NOT NULL,id TEXT NOT NULL,json TEXT NOT NULL,PRIMARY KEY(namespace,id))");
                s.execute("CREATE TABLE IF NOT EXISTS punishments(id INTEGER PRIMARY KEY AUTOINCREMENT,target_uuid TEXT NOT NULL,target_name TEXT NOT NULL,type TEXT NOT NULL CHECK(type IN ('warn','kick','mute','ban')),reason TEXT NOT NULL,issued_by TEXT NOT NULL,created_at INTEGER NOT NULL,expires_at INTEGER NOT NULL DEFAULT 0,revoked_at INTEGER NOT NULL DEFAULT 0,revoked_by TEXT NOT NULL DEFAULT '',status TEXT NOT NULL CHECK(status IN ('active','applied','expired','revoked','failed')),error TEXT NOT NULL DEFAULT '',sync_pending INTEGER NOT NULL DEFAULT 1)");
                boolean syncColumn=false;try(ResultSet columns=s.executeQuery("PRAGMA table_info(punishments)")){while(columns.next())if(columns.getString("name").equals("sync_pending"))syncColumn=true;}if(!syncColumn)s.execute("ALTER TABLE punishments ADD COLUMN sync_pending INTEGER NOT NULL DEFAULT 1");
                s.execute("CREATE INDEX IF NOT EXISTS punishments_target ON punishments(target_uuid,id DESC)");
                s.execute("CREATE INDEX IF NOT EXISTS punishments_expiry ON punishments(status,expires_at)");
                s.execute("CREATE INDEX IF NOT EXISTS punishments_sync ON punishments(sync_pending,id)");
                s.execute("CREATE UNIQUE INDEX IF NOT EXISTS punishments_one_active ON punishments(target_uuid,type) WHERE status='active' AND type IN ('mute','ban')");
            } return null;
        }).get(30,TimeUnit.SECONDS);
        PrivateFiles.restrict(path,false);
    }
    public <T> CompletableFuture<T> submit(Work<T> task) {
        CompletableFuture<T> future=new CompletableFuture<>();
        try {worker.execute(() -> {
            long start=System.nanoTime();
            try {future.complete(task.run(connection));}
            catch(Exception e) {errors.incrementAndGet();future.completeExceptionally(e);}
            finally {latencyMs=(System.nanoTime()-start)/1e6;}
        });} catch(RejectedExecutionException e) {future.completeExceptionally(new IllegalStateException("Database queue is full or closed"));}
        return future;
    }
    public CompletableFuture<Map<String,Object>> user(String name) {
        return submit(c -> { try(PreparedStatement s=c.prepareStatement("SELECT name,hash,role FROM users WHERE name=?")) {
            s.setString(1,name);try(ResultSet r=s.executeQuery()) {return r.next()?Map.of("name",r.getString(1),"hash",r.getString(2),"role",r.getString(3)):null;}
        }});
    }
    public CompletableFuture<Integer> userCount() {return submit(c->{try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT count(*) FROM users")){r.next();return r.getInt(1);}});}
    /** The durable marker prevents recreating the weak local bootstrap account after rename/delete. */
    public CompletableFuture<Boolean> bootstrapAdmin(String hash) {
        return submit(c->{c.setAutoCommit(false);try {
            try(PreparedStatement s=c.prepareStatement("SELECT 1 FROM documents WHERE namespace='migrations' AND id='auth-bootstrap-admin-v1'");ResultSet r=s.executeQuery()){if(r.next()){c.commit();return false;}}
            boolean empty;try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT count(*) FROM users")){r.next();empty=r.getInt(1)==0;}
            boolean created=false;if(empty){try(PreparedStatement s=c.prepareStatement("INSERT INTO users(name,hash,role) VALUES('admin',?,'Owner')")){s.setString(1,hash);created=s.executeUpdate()==1;}}
            if(created){try(PreparedStatement s=c.prepareStatement("INSERT INTO documents VALUES('auth-default-account','admin',?)")){s.setString(1,Json.write(Map.of("name","admin")));s.executeUpdate();}}
            try(PreparedStatement s=c.prepareStatement("INSERT INTO documents(namespace,id,json) VALUES('migrations','auth-bootstrap-admin-v1',?)")){s.setString(1,Json.write(Map.of("completedAt",System.currentTimeMillis(),"created",created)));s.executeUpdate();}
            c.commit();return created;
        }catch(Exception e){c.rollback();throw e;}finally{c.setAutoCommit(true);}});
    }
    public CompletableFuture<Boolean> changeUser(String oldName,String expectedHash,String newName,String newHash) {
        return changeUser(oldName,expectedHash,newName,newHash,false);
    }
    public CompletableFuture<Boolean> changeUser(String oldName,String expectedHash,String newName,String newHash,boolean clearDefaultPassword) {
        return submit(c->{c.setAutoCommit(false);try {
            boolean changed;try(PreparedStatement s=c.prepareStatement("UPDATE users SET name=?,hash=? WHERE name=? AND hash=?")){s.setString(1,newName);s.setString(2,newHash);s.setString(3,oldName);s.setString(4,expectedHash);changed=s.executeUpdate()==1;}
            if(changed&&!oldName.equals(newName)){
                String source=null,destination=null;
                try(PreparedStatement s=c.prepareStatement("SELECT id,json FROM documents WHERE namespace='build-history' AND id IN (?,?)")){s.setString(1,oldName);s.setString(2,newName);try(ResultSet rows=s.executeQuery()){while(rows.next()){if(rows.getString(1).equals(oldName))source=rows.getString(2);else destination=rows.getString(2);}}}
                if(destination!=null){Map<String,Object> existing=Json.object(destination);if(!(existing.get("undo") instanceof List<?> undo)||!undo.isEmpty()||!(existing.get("redo") instanceof List<?> redo)||!redo.isEmpty())throw new IllegalArgumentException("The new username retains another build history");}
                try(PreparedStatement s=c.prepareStatement("DELETE FROM documents WHERE namespace='build-history' AND id IN (?,?)")){s.setString(1,oldName);s.setString(2,newName);s.executeUpdate();}
                if(source!=null){Map<String,Object> history=new LinkedHashMap<>(Json.object(source));history.put("actor",newName);try(PreparedStatement s=c.prepareStatement("INSERT INTO documents(namespace,id,json) VALUES('build-history',?,?)")){s.setString(1,newName);s.setString(2,Json.write(history));s.executeUpdate();}}
            }
            if(changed){boolean defaultAccount;try(PreparedStatement s=c.prepareStatement("SELECT 1 FROM documents WHERE namespace='auth-default-account' AND id=?")){s.setString(1,oldName);try(ResultSet r=s.executeQuery()){defaultAccount=r.next();}}
                if(defaultAccount){try(PreparedStatement s=c.prepareStatement("DELETE FROM documents WHERE namespace='auth-default-account' AND id=?")){s.setString(1,oldName);s.executeUpdate();}
                    if(!clearDefaultPassword){try(PreparedStatement s=c.prepareStatement("INSERT INTO documents VALUES('auth-default-account',?,?)")){s.setString(1,newName);s.setString(2,Json.write(Map.of("name",newName)));s.executeUpdate();}}}}
            c.commit();return changed;
        }catch(Exception e){c.rollback();throw e;}finally{c.setAutoCommit(true);}});
    }
    public record Punishment(long id,String uuid,String targetName,String type,String reason,String issuedBy,long createdAt,long expiresAt,long revokedAt,String revokedBy,String status,String error,boolean syncPending) {
        public boolean active(long now){return status.equals("active")&&(expiresAt==0||expiresAt>now);}
        public Map<String,Object> json(){Map<String,Object> row=new LinkedHashMap<>();row.put("id",id);row.put("uuid",uuid);row.put("targetName",targetName);row.put("type",type);row.put("reason",reason);row.put("issuedBy",issuedBy);row.put("createdAt",createdAt);row.put("expiresAt",expiresAt);row.put("revokedAt",revokedAt);row.put("revokedBy",revokedBy);row.put("status",status);row.put("error",error);row.put("syncPending",syncPending);return row;}
    }
    private static Punishment punishment(ResultSet r)throws SQLException{return new Punishment(r.getLong("id"),r.getString("target_uuid"),r.getString("target_name"),r.getString("type"),r.getString("reason"),r.getString("issued_by"),r.getLong("created_at"),r.getLong("expires_at"),r.getLong("revoked_at"),r.getString("revoked_by"),r.getString("status"),r.getString("error"),r.getInt("sync_pending")!=0);}
    private static Punishment punishment(Connection c,long id)throws SQLException {try(PreparedStatement s=c.prepareStatement("SELECT * FROM punishments WHERE id=?")){s.setLong(1,id);try(ResultSet r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Punishment not found");return punishment(r);}}}
    public CompletableFuture<Punishment> createPunishment(UUID uuid,String targetName,String type,String reason,String actor,long createdAt,long expiresAt) {
        return submit(c->{c.setAutoCommit(false);try {
            try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT count(*) FROM punishments WHERE status='active'")){r.next();if(r.getInt(1)>=10000)throw new IllegalStateException("Active punishment limit reached");}
            try(PreparedStatement s=c.prepareStatement("SELECT 1 FROM punishments WHERE target_uuid=? AND type=? AND status='active' AND type IN ('mute','ban')")){s.setString(1,uuid.toString());s.setString(2,type);try(ResultSet r=s.executeQuery()){if(r.next())throw new IllegalStateException("Active punishment already exists; revoke it first");}}
            try(PreparedStatement s=c.prepareStatement("INSERT INTO punishments(target_uuid,target_name,type,reason,issued_by,created_at,expires_at,status) VALUES(?,?,?,?,?,?,?,?)")){s.setString(1,uuid.toString());s.setString(2,targetName);s.setString(3,type);s.setString(4,reason);s.setString(5,actor);s.setLong(6,createdAt);s.setLong(7,expiresAt);s.setString(8,Set.of("mute","ban").contains(type)?"active":"applied");s.executeUpdate();}
            long id;try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT last_insert_rowid()")){r.next();id=r.getLong(1);}Punishment row=punishment(c,id);c.commit();return row;
        }catch(Exception e){c.rollback();throw e;}finally{c.setAutoCommit(true);}});
    }
    public CompletableFuture<List<Punishment>> activePunishments() {return submit(c->{List<Punishment> out=new ArrayList<>();try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT * FROM punishments WHERE status='active' ORDER BY id LIMIT 10001")){while(r.next())out.add(punishment(r));}if(out.size()>10000)throw new IllegalStateException("Too many active punishments");return out;});}
    public CompletableFuture<List<Punishment>> pendingPunishments() {return submit(c->{List<Punishment> out=new ArrayList<>();try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT * FROM punishments WHERE sync_pending=1 ORDER BY id LIMIT 10000")){while(r.next())out.add(punishment(r));}return out;});}
    public CompletableFuture<Punishment> punishmentSynced(long id,String expectedStatus) {return submit(c->{try(PreparedStatement s=c.prepareStatement("UPDATE punishments SET sync_pending=0 WHERE id=? AND status=?")){s.setLong(1,id);s.setString(2,expectedStatus);s.executeUpdate();}return punishment(c,id);});}
    public CompletableFuture<List<Punishment>> punishmentHistory(UUID uuid,int limit) {
        int bounded=Math.max(1,Math.min(200,limit));return submit(c->{List<Punishment> out=new ArrayList<>();String sql=uuid==null?"SELECT * FROM punishments ORDER BY id DESC LIMIT ?":"SELECT * FROM punishments WHERE target_uuid=? ORDER BY id DESC LIMIT ?";try(PreparedStatement s=c.prepareStatement(sql)){int i=1;if(uuid!=null)s.setString(i++,uuid.toString());s.setInt(i,bounded);try(ResultSet r=s.executeQuery()){while(r.next())out.add(punishment(r));}}return out;});
    }
    public CompletableFuture<Punishment> revokePunishment(long id,String actor,long now) {
        return submit(c->{c.setAutoCommit(false);try {Punishment existing=punishment(c,id);if(!existing.status().equals("active"))throw new IllegalStateException("Punishment is not active");try(PreparedStatement s=c.prepareStatement("UPDATE punishments SET status='revoked',revoked_at=?,revoked_by=?,sync_pending=1 WHERE id=? AND status='active'")){s.setLong(1,now);s.setString(2,actor);s.setLong(3,id);if(s.executeUpdate()!=1)throw new IllegalStateException("Punishment changed concurrently");}Punishment row=punishment(c,id);c.commit();return row;}catch(Exception e){c.rollback();throw e;}finally{c.setAutoCommit(true);}});
    }
    public CompletableFuture<List<Punishment>> expirePunishments(long now) {
        return submit(c->{c.setAutoCommit(false);try {List<Punishment> out=new ArrayList<>();try(PreparedStatement s=c.prepareStatement("SELECT * FROM punishments WHERE status='active' AND expires_at>0 AND expires_at<=? ORDER BY id LIMIT 10000")){s.setLong(1,now);try(ResultSet r=s.executeQuery()){while(r.next())out.add(punishment(r));}}try(PreparedStatement s=c.prepareStatement("UPDATE punishments SET status='expired',sync_pending=1 WHERE id=? AND status='active'")){for(Punishment row:out){s.setLong(1,row.id());s.addBatch();}s.executeBatch();}c.commit();return out;}catch(Exception e){c.rollback();throw e;}finally{c.setAutoCommit(true);}});
    }
    public CompletableFuture<Punishment> failPunishment(long id,String error) {return submit(c->{try(PreparedStatement s=c.prepareStatement("UPDATE punishments SET status='failed',error=?,sync_pending=1 WHERE id=? AND status IN ('active','applied')")){s.setString(1,error.length()>300?error.substring(0,300):error);s.setLong(2,id);s.executeUpdate();}return punishment(c,id);});}
    public CompletableFuture<Void> createUser(String name,String hash,String role,boolean first) {
        return submit(c->{c.setAutoCommit(false);try {
            if(first) {try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT count(*) FROM users")){if(r.next()&&r.getInt(1)!=0)throw new IllegalStateException("Owner already exists");}}
            try(PreparedStatement s=c.prepareStatement("INSERT INTO users VALUES(?,?,?)")){s.setString(1,name);s.setString(2,hash);s.setString(3,role);s.executeUpdate();}
            c.commit();return null;
        } catch(Exception e){c.rollback();throw e;}finally{c.setAutoCommit(true);}});
    }
    public CompletableFuture<List<Map<String,Object>>> users() {return submit(c->{List<Map<String,Object>> out=new ArrayList<>();try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT name,role FROM users ORDER BY name")){while(r.next())out.add(Map.of("name",r.getString(1),"role",r.getString(2)));}return out;});}
    public CompletableFuture<Boolean> deleteUser(String name) {
        return submit(c->{c.setAutoCommit(false);try{
            String role=null;try(PreparedStatement s=c.prepareStatement("SELECT role FROM users WHERE name=?")){s.setString(1,name);try(ResultSet r=s.executeQuery()){if(r.next())role=r.getString(1);}}
            if(role==null){c.commit();return false;}
            if(role.equals("Owner")){try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT count(*) FROM users WHERE role='Owner'")){r.next();if(r.getInt(1)<=1)throw new IllegalArgumentException("The last full administrator cannot be deleted");}}
            try(PreparedStatement s=c.prepareStatement("DELETE FROM users WHERE name=?")){s.setString(1,name);s.executeUpdate();}
            try(PreparedStatement s=c.prepareStatement("DELETE FROM documents WHERE namespace='auth-default-account' AND id=?")){s.setString(1,name);s.executeUpdate();}
            c.commit();return true;
        }catch(Exception error){c.rollback();throw error;}finally{c.setAutoCommit(true);}});
    }
    public CompletableFuture<Void> audit(String actor,String action,String status,Object details) {
        return submit(c->{try(PreparedStatement s=c.prepareStatement("INSERT INTO audit(time,actor,action,status,details) VALUES(?,?,?,?,?)")){
            s.setLong(1,System.currentTimeMillis());s.setString(2,actor);s.setString(3,action);s.setString(4,status);s.setString(5,Json.write(details));s.executeUpdate();}return null;});
    }
    public CompletableFuture<List<Map<String,Object>>> auditHistory() {
        return submit(c->{List<Map<String,Object>> out=new ArrayList<>();try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT id,time,actor,action,status,details FROM audit ORDER BY id DESC LIMIT 500")){
            while(r.next())out.add(Map.of("id",r.getLong(1),"time",r.getLong(2),"actor",r.getString(3),"action",r.getString(4),"status",r.getString(5),"details",Json.object(r.getString(6))));}return out;});
    }
    static Map<String,Object> compactMetric(Map<String,Object> value){
        Map<String,Object> out=new LinkedHashMap<>();
        for(String key:List.of("timestamp","mspt","cpu","heapUsed","heapMax","online","maxPlayers","entityCount","loadedChunks","uptimeSeconds"))if(value.get(key) instanceof Number number&&Double.isFinite(number.doubleValue()))out.put(key,number);
        if(value.get("performanceGuard") instanceof Boolean guard)out.put("performanceGuard",guard);
        Object tps=value.get("tps");if(tps instanceof Number number&&Double.isFinite(number.doubleValue()))out.put("tps",number);
        else if(tps instanceof double[] values){out.put("tps",Arrays.stream(values).limit(3).filter(Double::isFinite).toArray());}
        else if(tps instanceof List<?> values)out.put("tps",values.stream().limit(3).filter(v->v instanceof Number n&&Double.isFinite(n.doubleValue())).toList());
        return out;
    }
    public CompletableFuture<Void> metric(Map<String,Object> value) {Map<String,Object> sample=compactMetric(value);return submit(c->{try(PreparedStatement s=c.prepareStatement("INSERT OR REPLACE INTO metrics VALUES(?,?)")){s.setLong(1,System.currentTimeMillis());s.setString(2,Json.write(sample));s.executeUpdate();}return null;});}
    public CompletableFuture<List<Map<String,Object>>> metrics(long since) {
        return submit(c->{long now=System.currentTimeMillis(),width=Math.max(1,(Math.max(0,now-Math.max(0,since))+360)/360);List<Map<String,Object>> out=new ArrayList<>();
            // Select actual last samples per time bucket in SQLite before allocating/decoding JSON.
            try(PreparedStatement s=c.prepareStatement("SELECT time,json FROM metrics WHERE time IN (SELECT MAX(time) FROM metrics WHERE time>=? AND time<=? GROUP BY (time-?)/?) ORDER BY time LIMIT 361")){
                s.setLong(1,since);s.setLong(2,now);s.setLong(3,Math.max(0,since));s.setLong(4,width);
                try(ResultSet r=s.executeQuery()){while(r.next()){Map<String,Object> row=compactMetric(Json.object(r.getString(2)));row.put("time",r.getLong(1));out.add(row);}}}
            return out;});
    }
    public CompletableFuture<List<Map<String,Object>>> documents(String namespace) {return submit(c->{List<Map<String,Object>> out=new ArrayList<>();try(PreparedStatement s=c.prepareStatement("SELECT json FROM documents WHERE namespace=? ORDER BY id")){s.setString(1,namespace);try(ResultSet r=s.executeQuery()){while(r.next())out.add(Json.object(r.getString(1)));}}return out;});}
    public CompletableFuture<Void> document(String namespace,String id,Map<String,Object> value) {return submit(c->{try(PreparedStatement s=c.prepareStatement("INSERT INTO documents VALUES(?,?,?) ON CONFLICT(namespace,id) DO UPDATE SET json=excluded.json")){s.setString(1,namespace);s.setString(2,id);s.setString(3,Json.write(value));s.executeUpdate();}return null;});}
    public CompletableFuture<Void> deleteDocument(String namespace,String id) {return submit(c->{try(PreparedStatement s=c.prepareStatement("DELETE FROM documents WHERE namespace=? AND id=?")){s.setString(1,namespace);s.setString(2,id);s.executeUpdate();}return null;});}
    public CompletableFuture<Optional<Map<String,Object>>> document(String namespace,String id) {return submit(c->{try(PreparedStatement s=c.prepareStatement("SELECT json FROM documents WHERE namespace=? AND id=?")){s.setString(1,namespace);s.setString(2,id);try(ResultSet rows=s.executeQuery()){return rows.next()?Optional.of(Json.object(rows.getString(1))):Optional.empty();}}});}
    public CompletableFuture<Void> vacuumInto(Path destination) {return submit(c->{try(PreparedStatement s=c.prepareStatement("VACUUM INTO ?")){s.setString(1,destination.toAbsolutePath().toString());s.execute();}return null;});}
    public void cleanup(int days) {submit(c->{long older=System.currentTimeMillis()-TimeUnit.DAYS.toMillis(Math.max(1,Math.min(days,90)));for(String table:List.of("audit","metrics")){try(PreparedStatement s=c.prepareStatement("DELETE FROM "+table+" WHERE time<?")){s.setLong(1,older);s.executeUpdate();}}return null;});}
    public Map<String,Object> stats(){return Map.of("queue",worker.getQueue().size(),"sqlLatencyMs",latencyMs,"sqlErrors",errors.get());}
    @Override public void close() {
        try{submit(c->{if(c!=null){try(Statement s=c.createStatement()){s.execute("PRAGMA wal_checkpoint(TRUNCATE)");}c.close();}return null;}).get(5,TimeUnit.SECONDS);}catch(Exception ignored){}
        worker.shutdown();try{if(!worker.awaitTermination(5,TimeUnit.SECONDS))worker.shutdownNow();}catch(InterruptedException e){Thread.currentThread().interrupt();worker.shutdownNow();}
    }
}
