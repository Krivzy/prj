package dev.mcc.game;

import org.bukkit.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

import static dev.mcc.game.GameService.*;

/** Existing chunks only. Worlds stay on the server thread; workers see snapshots or captured paths. */
final class WorldTerrainEngine implements AutoCloseable {
    static final int MAX_CACHE=64,MAX_PENDING=64,MAX_LOADS=1,MAX_TICKETS=0;
    private record Key(UUID world,int x,int z) {}
    static final class CapacityException extends IllegalStateException { CapacityException(){super("Карта занята; повторите запрос");} }
    private record Cached(long expiresAt,Map<String,Object> dto) {}
    private record Prepared(Node node,SavedChunkStatus.State state,Throwable error,long epoch) {}
    private record Parsed(Key key,Map<String,Object> dto,Throwable error,long epoch,long sampledAt) {}
    private record Index(long expiresAt,CompletableFuture<Map<String,Object>> future) {}
    private static final class Node {
        final Key key;final World world;final long epoch;final Path folder;final String dimension;
        long requestedAt=System.currentTimeMillis();
        Node(Key key,World world,long epoch){this.key=key;this.world=world;this.epoch=epoch;folder=world.getWorldFolder().toPath();dimension=switch(world.getEnvironment()){case NETHER->"the_nether";case THE_END->"the_end";default->"overworld";};}
    }
    private final JavaPlugin plugin;
    private final LinkedHashMap<Key,Cached> cache=new LinkedHashMap<>(80,.75f,true);
    private final Map<Key,Node> pending=new HashMap<>();private final Deque<Node> queued=new ArrayDeque<>();
    private final LinkedHashMap<UUID,Index> indexes=new LinkedHashMap<>();
    private final ConcurrentLinkedQueue<Parsed> parsed=new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Prepared> prepared=new ConcurrentLinkedQueue<>();
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(8),r->{Thread t=new Thread(r,"MCC-world-map");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private boolean enabled,closed;private long epoch,lastSnapshotNanos;private int loads,lastSnapshotTick=Integer.MIN_VALUE;private BukkitTask task;
    WorldTerrainEngine(JavaPlugin plugin){this.plugin=plugin;}

    void enabled(boolean value){requireMainThread();enabled=value;if(!value){epoch++;if(task!=null){task.cancel();task=null;}queued.clear();pending.clear();parsed.clear();prepared.clear();loads=0;cache.clear();indexes.clear();}}
    private void check(){if(!enabled||closed)throw new IllegalStateException("Модуль карты отключён");}
    Map<String,Object> terrain(String name,int cx,int cz,int radius,int step){
        requireMainThread();check();validateView(cx,cz,radius,step);World world=world(name);long now=System.currentTimeMillis();
        cache.entrySet().removeIf(e->e.getValue().expiresAt<now);List<Map<String,Object>> tiles=new ArrayList<>();boolean waiting=false;
        for(int z=cz-radius;z<=cz+radius;z++)for(int x=cx-radius;x<=cx+radius;x++){
            Key key=new Key(world.getUID(),x,z);Cached hit=cache.get(key);Map<String,Object> dto;
            if(hit!=null)dto=hit.dto;
            else {Node node=pending.get(key);if(node==null&&pending.size()<MAX_PENDING)node=enqueue(world,key);if(node!=null){node.requestedAt=now;dto=placeholder(key,"loading","");}else dto=placeholder(key,"deferred","Очередь карты занята");}
            String status=(String)dto.get("status");waiting|=Set.of("loading","processing","deferred").contains(status);tiles.add(dto);
        }
        List<Map<String,Object>> markers=new ArrayList<>();int minX=(cx-radius)*16,minZ=(cz-radius)*16,span=(radius*2+1)*16;
        for(var player:world.getPlayers()){Location p=player.getLocation();if(p.getX()>=minX&&p.getX()<minX+span&&p.getZ()>=minZ&&p.getZ()<minZ+span)markers.add(map("uuid",player.getUniqueId().toString(),"name",player.getName(),"x",p.getX(),"y",p.getY(),"z",p.getZ()));}
        return map("world",name,"worldId",world.getUID().toString(),"cx",cx,"cz",cz,"radius",radius,"step",step,"tiles",tiles,"players",markers,"pending",waiting,"generatedOnly",true,"minY",world.getMinHeight(),"maxY",world.getMaxHeight(),"limits",map("cachedChunks",MAX_CACHE,"pendingChunks",MAX_PENDING,"asyncLoads",MAX_LOADS,"tickets",MAX_TICKETS),"metrics",map("cache",cache.size(),"pending",pending.size(),"loads",loads,"tickets",0,"snapshotMs",lastSnapshotNanos/1_000_000d));
    }
    CompletableFuture<Map<String,Object>> index(String name){
        requireMainThread();check();World world=world(name);Index hit=indexes.get(world.getUID());long now=System.currentTimeMillis();if(hit!=null&&hit.expiresAt>now)return hit.future;
        Path folder=world.getWorldFolder().toPath();String dimension=switch(world.getEnvironment()){case NETHER->"the_nether";case THE_END->"the_end";default->"overworld";};
        UUID uid=world.getUID();CompletableFuture<Map<String,Object>> future=new CompletableFuture<>();
        try{worker.execute(()->{try{future.complete(scanIndex(folder,name,dimension));}catch(Exception ex){future.completeExceptionally(ex);}});}catch(RejectedExecutionException ex){future.completeExceptionally(new IllegalStateException("Очередь индекса карты занята"));}
        indexes.put(uid,new Index(now+120_000,future));while(indexes.size()>4)indexes.remove(indexes.keySet().iterator().next());return future;
    }
    void invalidate(String name,int x,int z){requireMainThread();World world=world(name);cache.remove(new Key(world.getUID(),Math.floorDiv(x,16),Math.floorDiv(z,16)));}
    void invalidateWorld(String name){requireMainThread();World world=world(name);UUID uid=world.getUID();cache.keySet().removeIf(key->key.world.equals(uid));}
    private Node enqueue(World world,Key key){Node node=pending.get(key);if(node==null){node=new Node(key,world,epoch);pending.put(key,node);queued.addLast(node);}start();return node;}
    private void start(){if(task==null)task=Bukkit.getScheduler().runTaskTimer(plugin,this::tick,1,1);}
    private void tick(){
        long deadline=System.nanoTime()+1_000_000,now=System.currentTimeMillis();
        Parsed p;for(int i=0;i<4&&(p=parsed.poll())!=null;i++){if(p.epoch!=epoch||!enabled)continue;Cached current=cache.get(p.key);if(current==null||!Objects.equals(current.dto.get("sampledAt"),p.sampledAt))continue;put(p.key,p.error==null?p.dto:placeholder(p.key,"error","Не удалось прочитать снимок"),p.error==null?120_000:5_000);}
        Prepared preflight;for(int i=0;Bukkit.getTPS()[0]>=18&&i<MAX_LOADS&&System.nanoTime()<deadline&&(preflight=prepared.poll())!=null;i++) {
            if(preflight.epoch!=epoch||!enabled)continue;Node node=preflight.node;
            if(preflight.error!=null){loads--;retry(node,preflight.error);continue;}
            if(!pending.containsKey(node.key)||Bukkit.getWorld(node.key.world)!=node.world){loads--;pending.remove(node.key);continue;}
            try{if(!SavedChunkStatus.loadOnlyFull(preflight.state,()->node.world.getChunkAtAsync(node.key.x,node.key.z,false,false).whenComplete((chunk,error)->retainLoaded(node,chunk,error)))){loads--;missing(node,"Чанк не сохранён полностью");}}
            catch(RuntimeException ex){loads--;retry(node,ex);}
        }
        int admissionRounds=queued.size();
        if(Bukkit.getTPS()[0]>=18)while(loads<MAX_LOADS&&!queued.isEmpty()&&admissionRounds-->0&&System.nanoTime()<deadline){Node node=queued.removeFirst();if(!pending.containsKey(node.key))continue;if(now-node.requestedAt>10_000){pending.remove(node.key);continue;}
            if(Bukkit.getWorld(node.key.world)!=node.world){retry(node,new IllegalStateException("Мир выгружен"));continue;}
            loads++;try{worker.execute(()->{try{prepared.add(new Prepared(node,SavedChunkStatus.read(node.folder,node.dimension,node.key.x,node.key.z),null,node.epoch));}catch(Exception ex){prepared.add(new Prepared(node,null,ex,node.epoch));}});}catch(RejectedExecutionException ex){prepared.add(new Prepared(node,null,new CapacityException(),node.epoch));}}
        if(pending.isEmpty()&&parsed.isEmpty()&&prepared.isEmpty()&&worker.getActiveCount()==0&&worker.getQueue().isEmpty()){task.cancel();task=null;}
    }
    static boolean canSnapshot(int tick,int previousTick,double tps){return tps>=18&&tick!=previousTick;}
    static String retryStatus(Throwable error){return error instanceof CapacityException?"deferred":"error";}
    private void retry(Node node,Throwable error){String status=retryStatus(error);pending.remove(node.key);String reason=error.getMessage();if(reason==null||reason.isBlank())reason="Не удалось загрузить чанк; повторите запрос";put(node.key,placeholder(node.key,status,reason),status.equals("deferred")?1_000:2_000);}
    private void missing(Node node,String reason){pending.remove(node.key);put(node.key,placeholder(node.key,"missing",reason),30_000);}
    private void put(Key key,Map<String,Object> dto,long ttl){cache.put(key,new Cached(System.currentTimeMillis()+ttl,dto));while(cache.size()>MAX_CACHE)cache.remove(cache.keySet().iterator().next());}
    private static Map<String,Object> placeholder(Key key,String status,String message){return map("cx",key.x,"cz",key.z,"status",status,"message",message);}
    // Paper invokes this callback on the main thread while its transient CHUNK_LOAD ticket
    // is still held. Capture now; never add an ENTITY_TICKING plugin ticket or carry a Chunk
    // into the next tick, which can promote neighboring saved proto chunks.
    private void retainLoaded(Node node,Chunk chunk,Throwable error){
        requireMainThread();if(node.epoch!=epoch||!enabled||closed)return;loads--;pending.remove(node.key);
        if(error!=null){retry(node,error);return;}if(chunk==null){missing(node,"Здесь ещё нет сохранённого чанка");return;}
        try{
            if(!chunk.isLoaded()){retry(node,new IllegalStateException("Чанк выгрузился; повторите запрос"));return;}
            if(!chunk.isGenerated()){missing(node,"Чанк ещё не сформирован");return;}
            int tick=Bukkit.getCurrentTick();if(!canSnapshot(tick,lastSnapshotTick,Bukkit.getTPS()[0])){retry(node,new CapacityException());return;}
            lastSnapshotTick=tick;long begin=System.nanoTime();ChunkSnapshot snapshot=chunk.getChunkSnapshot(true,false,false,false);lastSnapshotNanos=System.nanoTime()-begin;
            int minY=node.world.getMinHeight(),maxY=node.world.getMaxHeight();long sampledAt=System.currentTimeMillis();put(node.key,map("cx",node.key.x,"cz",node.key.z,"status","processing","sampledAt",sampledAt),30_000);
            worker.execute(()->{try{parsed.add(new Parsed(node.key,sample(snapshot,node.key.x,node.key.z,minY,maxY,sampledAt),null,node.epoch,sampledAt));}catch(Throwable ex){parsed.add(new Parsed(node.key,null,ex,node.epoch,sampledAt));}});
        }catch(RejectedExecutionException ex){retry(node,new CapacityException());}catch(RuntimeException ex){retry(node,ex);}
    }
    private static World world(String name){World world=Bukkit.getWorld(name);if(world==null)throw new IllegalArgumentException("Мир не загружен");return world;}
    static void validateView(int cx,int cz,int radius,int step){if(Math.abs((long)cx)>1_874_990||Math.abs((long)cz)>1_874_990)throw new IllegalArgumentException("Координаты за пределами Minecraft");if(radius<0||radius>3)throw new IllegalArgumentException("Радиус viewport от0 до3 чанков");if(step!=1&&step!=2&&step!=4)throw new IllegalArgumentException("Детализация:1,2,4");}
    /** One surface BlockData read per column; no volume/deep-block read or world mutation. */
    static Map<String,Object> sample(ChunkSnapshot snapshot,int cx,int cz,int minY,int maxY,long at){
        Integer[] heights=new Integer[256],blocks=new Integer[256];
        List<Map<String,Object>> palette=new ArrayList<>();Map<String,Integer> ids=new HashMap<>();
        for(int z=0;z<16;z++)for(int x=0;x<16;x++){
            int i=x+16*z,y=snapshot.getHighestBlockYAt(x,z);
            if(y<minY||y>=maxY)continue;
            BlockData data=snapshot.getBlockData(x,y,z);Material material=data.getMaterial();
            if(material==Material.AIR||material==Material.CAVE_AIR||material==Material.VOID_AIR)continue;
            heights[i]=y;String state=data.getAsString();Integer id=ids.get(state);
            if(id==null){id=palette.size();ids.put(state,id);palette.add(map("material",material.name(),"blockData",state,"color",fallbackColor(material.name())));}
            blocks[i]=id;
        }
        return map("cx",cx,"cz",cz,"size",16,"step",1,"status","ready","heights",Collections.unmodifiableList(Arrays.asList(heights)),"blocks",Collections.unmodifiableList(Arrays.asList(blocks)),"palette",List.copyOf(palette),"sampledAt",at,"generated",true);
    }
    private static String fallbackColor(String name){if(name.contains("WATER"))return "#348caf";if(name.contains("LEAVES")||name.contains("GRASS")||name.contains("MOSS"))return "#77a35e";if(name.contains("SAND"))return "#d7c695";if(name.contains("DIRT")||name.contains("LOG"))return "#917353";if(name.contains("SNOW"))return "#e8eef3";return "#949c9f";}
    static Map<String,Object> scanIndex(Path folder,String name,String dimension)throws IOException{
        List<Path> candidates=List.of(folder.resolve("region"),folder.resolve("dimensions/minecraft").resolve(dimension).resolve("region"),folder.resolve(dimension.equals("the_nether")?"DIM-1":dimension.equals("the_end")?"DIM1":".").resolve("region"));
        Path directory=candidates.stream().filter(p->Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)).findFirst().orElse(null);List<Map<String,Object>> regions=new ArrayList<>();int minX=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE,total=0,count=0;boolean truncated=false;
        if(directory!=null){Pattern pattern=Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");try(DirectoryStream<Path> files=Files.newDirectoryStream(directory,"r.*.*.mca")){for(Path file:files){if(++total>2048){truncated=true;break;}Matcher match=pattern.matcher(file.getFileName().toString());if(!match.matches()||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))continue;int rx=Integer.parseInt(match.group(1)),rz=Integer.parseInt(match.group(2));if(Math.abs((long)rx)>58594||Math.abs((long)rz)>58594)continue;byte[] header=new byte[4096];try(FileChannel channel=FileChannel.open(file,StandardOpenOption.READ)){ByteBuffer buffer=ByteBuffer.wrap(header);while(buffer.hasRemaining()&&channel.read(buffer)>=0){}if(buffer.hasRemaining())continue;}byte[] bitmap=presence(header);int cells=0;for(int i=0;i<1024;i++)if((bitmap[i>>3]&(1<<(i&7)))!=0){int x=rx*32+i%32,z=rz*32+i/32;minX=Math.min(minX,x);maxX=Math.max(maxX,x);minZ=Math.min(minZ,z);maxZ=Math.max(maxZ,z);cells++;}if(cells>0){count+=cells;regions.add(map("rx",rx,"rz",rz,"chunks",cells,"presence",Base64.getEncoder().encodeToString(bitmap)));}}}}
        return map("world",name,"regions",regions,"savedChunks",count,"bounds",count==0?null:map("minX",minX,"maxX",maxX,"minZ",minZ,"maxZ",maxZ),"truncated",truncated,"maxRegions",2048,"scannedAt",System.currentTimeMillis(),"source","Anvil location headers; saved coverage, terrain requires fully generated chunks","generatedOnly",true);
    }
    static byte[] presence(byte[] header){if(header.length!=4096)throw new IllegalArgumentException("Anvil header must be4096bytes");byte[] result=new byte[128];for(int i=0;i<1024;i++){int p=i*4,offset=((header[p]&255)<<16)|((header[p+1]&255)<<8)|(header[p+2]&255);if(offset>=2&&(header[p+3]&255)>0)result[i>>3]|=(byte)(1<<(i&7));}return result;}
    @Override public void close(){requireMainThread();enabled(false);closed=true;worker.shutdownNow();}
}
