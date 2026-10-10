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

/** Existing chunks only. Mutable worlds/tickets stay on the server thread; workers see snapshots or paths. */
final class WorldTerrainEngine implements AutoCloseable {
    static final int MAX_CACHE=64,MAX_PENDING=64,MAX_LOADS=2,MAX_TICKETS=8;
    private record Key(UUID world,int x,int z) {}
    static final class CapacityException extends IllegalStateException { CapacityException(){super("Редактор занят; повторите запрос");} }
    private record Cached(long expiresAt,Map<String,Object> dto) {}
    private record Loaded(Node node,Chunk chunk,Throwable error,long epoch) {}
    private record Parsed(Key key,Map<String,Object> dto,Throwable error,long epoch,long sampledAt) {}
    private record Index(long expiresAt,CompletableFuture<Map<String,Object>> future) {}
    private static final class Node {
        final Key key;final World world;final long epoch;
        final CompletableFuture<Void> loaded=new CompletableFuture<>();final Set<String> actors=new HashSet<>();
        boolean surface,started;long requestedAt=System.currentTimeMillis();Chunk chunk;
        Node(Key key,World world,long epoch){this.key=key;this.world=world;this.epoch=epoch;}
    }
    private static final class Held {
        final Chunk chunk;final boolean added;final Map<String,Long> actors=new HashMap<>();boolean snapshot;
        Held(Chunk chunk,boolean added){this.chunk=chunk;this.added=added;}
    }
    private final JavaPlugin plugin;
    private final LinkedHashMap<Key,Cached> cache=new LinkedHashMap<>(80,.75f,true);
    private final Map<Key,Node> pending=new HashMap<>();private final Deque<Node> queued=new ArrayDeque<>(),ready=new ArrayDeque<>();
    private final Map<Key,Held> held=new HashMap<>();private final Map<String,CompletableFuture<Void>> details=new HashMap<>();
    private final LinkedHashMap<UUID,Index> indexes=new LinkedHashMap<>();
    private final ConcurrentLinkedQueue<Loaded> loaded=new ConcurrentLinkedQueue<>();private final ConcurrentLinkedQueue<Parsed> parsed=new ConcurrentLinkedQueue<>();
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(8),r->{Thread t=new Thread(r,"MCC-world-map");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private boolean enabled,closed;private long epoch,lastSnapshotNanos;private int loads;private BukkitTask task;
    WorldTerrainEngine(JavaPlugin plugin){this.plugin=plugin;}

    void enabled(boolean value){requireMainThread();enabled=value;if(!value){epoch++;if(task!=null){task.cancel();task=null;}for(Node n:List.copyOf(pending.values()))n.loaded.completeExceptionally(new IllegalStateException("Карта отключена"));for(var f:List.copyOf(details.values()))f.completeExceptionally(new IllegalStateException("Карта отключена"));details.clear();queued.clear();ready.clear();pending.clear();loaded.clear();parsed.clear();loads=0;for(Key key:List.copyOf(held.keySet()))release(key);cache.clear();indexes.clear();}}
    private void check(){if(!enabled||closed)throw new IllegalStateException("Модуль карты отключён");}
    Map<String,Object> terrain(String name,int cx,int cz,int radius,int step){
        requireMainThread();check();validateView(cx,cz,radius,step);World world=world(name);long now=System.currentTimeMillis();
        cache.entrySet().removeIf(e->e.getValue().expiresAt<now);List<Map<String,Object>> tiles=new ArrayList<>();boolean waiting=false;
        for(int z=cz-radius;z<=cz+radius;z++)for(int x=cx-radius;x<=cx+radius;x++){
            Key key=new Key(world.getUID(),x,z);Cached hit=cache.get(key);Map<String,Object> dto;
            if(hit!=null)dto=hit.dto;
            else {Node node=pending.get(key);if(node==null&&pending.size()<MAX_PENDING)node=enqueue(world,key,true,null);if(node!=null){node.surface=true;node.requestedAt=now;dto=placeholder(key,"loading","");}else dto=placeholder(key,"deferred","Очередь карты занята");}
            String status=(String)dto.get("status");waiting|=Set.of("loading","processing","deferred").contains(status);tiles.add(dto);
        }
        List<Map<String,Object>> markers=new ArrayList<>();int minX=(cx-radius)*16,minZ=(cz-radius)*16,span=(radius*2+1)*16;
        for(var player:world.getPlayers()){Location p=player.getLocation();if(p.getX()>=minX&&p.getX()<minX+span&&p.getZ()>=minZ&&p.getZ()<minZ+span)markers.add(map("uuid",player.getUniqueId().toString(),"name",player.getName(),"x",p.getX(),"y",p.getY(),"z",p.getZ()));}
        return map("world",name,"worldId",world.getUID().toString(),"cx",cx,"cz",cz,"radius",radius,"step",step,"tiles",tiles,"players",markers,"pending",waiting,"generatedOnly",true,"minY",world.getMinHeight(),"maxY",world.getMaxHeight(),"limits",map("cachedChunks",MAX_CACHE,"pendingChunks",MAX_PENDING,"asyncLoads",MAX_LOADS,"tickets",MAX_TICKETS),"metrics",map("cache",cache.size(),"pending",pending.size(),"loads",loads,"tickets",held.size(),"snapshotMs",lastSnapshotNanos/1_000_000d));
    }
    CompletableFuture<Void> prepareDetail(String actor,World world,int x,int z,int size){
        requireMainThread();check();if(actor==null||actor.isBlank()||actor.length()>128)throw new IllegalArgumentException("Некорректный пользователь");
        if(size<8||size>16)throw new IllegalArgumentException("Детальная область от8 до16 блоков");
        if(details.containsKey(actor))throw new IllegalStateException("Дождитесь загрузки выбранного участка");
        Set<Key> keys=new LinkedHashSet<>();int startX=x-size/2,startZ=z-size/2;
        for(int cz=Math.floorDiv(startZ,16);cz<=Math.floorDiv(startZ+size-1,16);cz++)for(int cx=Math.floorDiv(startX,16);cx<=Math.floorDiv(startX+size-1,16);cx++)keys.add(new Key(world.getUID(),cx,cz));
        long newNodes=keys.stream().filter(k->!pending.containsKey(k)&&!held.containsKey(k)).count();if(pending.size()+newNodes>MAX_PENDING)throw new IllegalStateException("Очередь карты занята");
        releaseActorOutside(actor,keys);List<CompletableFuture<Void>> futures=new ArrayList<>();
        for(Key key:keys){Held existing=held.get(key);if(existing!=null&&existing.chunk.isLoaded()){existing.actors.put(actor,System.currentTimeMillis()+45_000);futures.add(CompletableFuture.completedFuture(null));}
            else{Node node=enqueue(world,key,false,actor);queued.remove(node);if(!node.started)queued.addFirst(node);futures.add(node.loaded);}}
        CompletableFuture<Void> result=CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));details.put(actor,result);
        result.whenComplete((ignored,error)->{details.remove(actor);if(error!=null)releaseActor(actor);});start();return result;
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
    private Node enqueue(World world,Key key,boolean surface,String actor){Node node=pending.get(key);if(node==null){node=new Node(key,world,epoch);pending.put(key,node);queued.addLast(node);}node.surface|=surface;if(actor!=null)node.actors.add(actor);start();return node;}
    private void start(){if(task==null)task=Bukkit.getScheduler().runTaskTimer(plugin,this::tick,1,1);}
    private void tick(){
        long deadline=System.nanoTime()+1_000_000,now=System.currentTimeMillis();
        for(var entry:List.copyOf(held.entrySet())){entry.getValue().actors.entrySet().removeIf(e->e.getValue()<now);if(entry.getValue().actors.isEmpty()&&!entry.getValue().snapshot)release(entry.getKey());}
        Parsed p;for(int i=0;i<4&&(p=parsed.poll())!=null;i++){if(p.epoch!=epoch||!enabled)continue;Cached current=cache.get(p.key);if(current==null||!Objects.equals(current.dto.get("sampledAt"),p.sampledAt))continue;put(p.key,p.error==null?p.dto:placeholder(p.key,"error","Не удалось прочитать снимок"),p.error==null?120_000:5_000);}
        Loaded completion;while((completion=loaded.poll())!=null){if(completion.epoch!=epoch)continue;loads--;Node node=completion.node;
            if(completion.error!=null){retry(node,completion.error);continue;}
            if(completion.chunk==null){missing(node,"Здесь ещё нет сохранённого чанка");continue;}
            try{Chunk chunk=completion.chunk;if(!chunk.isLoaded()){retry(node,new IllegalStateException("Чанк выгрузился; повторите запрос"));continue;}if(!chunk.isGenerated()){missing(node,"Чанк ещё не сформирован");continue;}Held hold=held.get(node.key);if(hold==null){if(held.size()>=MAX_TICKETS)throw new CapacityException();hold=new Held(chunk,chunk.addPluginChunkTicket(plugin));held.put(node.key,hold);}for(String actor:node.actors)hold.actors.put(actor,now+45_000);node.chunk=chunk;hold.snapshot|=node.surface;node.loaded.complete(null);
                if(node.surface)ready.addLast(node);else{pending.remove(node.key);if(hold.actors.isEmpty())release(node.key);}
            }catch(RuntimeException ex){retry(node,ex);}}
        if(Bukkit.getTPS()[0]>=18&&System.nanoTime()<deadline&&!ready.isEmpty()){
            Node node=ready.removeFirst();try{long begin=System.nanoTime();ChunkSnapshot snapshot=node.chunk.getChunkSnapshot(true,false,false,false);lastSnapshotNanos=System.nanoTime()-begin;int minY=node.world.getMinHeight(),maxY=node.world.getMaxHeight();long sampledAt=now;put(node.key,map("cx",node.key.x,"cz",node.key.z,"status","processing","sampledAt",sampledAt),30_000);
                worker.execute(()->{try{parsed.add(new Parsed(node.key,sample(snapshot,node.key.x,node.key.z,minY,maxY,sampledAt),null,node.epoch,sampledAt));}catch(Throwable ex){parsed.add(new Parsed(node.key,null,ex,node.epoch,sampledAt));}});
            }catch(RuntimeException ex){put(node.key,placeholder(node.key,"error","Снимок недоступен; повторите запрос"),5_000);}
            finally{pending.remove(node.key);Held hold=held.get(node.key);if(hold!=null){hold.snapshot=false;if(hold.actors.isEmpty())release(node.key);}}
        }
        int admissionRounds=queued.size();
        if(Bukkit.getTPS()[0]>=18)while(loads<MAX_LOADS&&!queued.isEmpty()&&admissionRounds-->0&&System.nanoTime()<deadline){Node node=queued.removeFirst();if(!pending.containsKey(node.key))continue;if(node.actors.isEmpty()&&now-node.requestedAt>10_000){pending.remove(node.key);node.loaded.completeExceptionally(new IllegalStateException("Область просмотра изменилась"));continue;}
            if(Bukkit.getWorld(node.key.world)!=node.world){retry(node,new IllegalStateException("Мир выгружен"));continue;}
            if(!canAdmitLoad(held.containsKey(node.key),held.size(),loads)){queued.addLast(node);continue;}
            node.started=true;loads++;try{node.world.getChunkAtAsync(node.key.x,node.key.z,false,false).whenComplete((chunk,error)->retainLoaded(node,chunk,error));}catch(RuntimeException ex){loaded.add(new Loaded(node,null,ex,node.epoch));}}
        if(pending.isEmpty()&&held.isEmpty()&&loaded.isEmpty()&&parsed.isEmpty()&&worker.getActiveCount()==0&&worker.getQueue().isEmpty()){task.cancel();task=null;}
    }
    static boolean canAdmitLoad(boolean alreadyHeld,int tickets,int inflight){return alreadyHeld||tickets+inflight<MAX_TICKETS;}
    static String retryStatus(Throwable error){return error instanceof CapacityException?"deferred":"error";}
    private void retry(Node node,Throwable error){String status=retryStatus(error);pending.remove(node.key);String reason=error.getMessage();if(reason==null||reason.isBlank())reason="Не удалось загрузить чанк; повторите запрос";put(node.key,placeholder(node.key,status,reason),status.equals("deferred")?1_000:2_000);node.loaded.completeExceptionally(new IllegalStateException(reason));}
    private void missing(Node node,String reason){pending.remove(node.key);put(node.key,placeholder(node.key,"missing",reason),30_000);node.loaded.completeExceptionally(new IllegalArgumentException(reason));}
    private void put(Key key,Map<String,Object> dto,long ttl){cache.put(key,new Cached(System.currentTimeMillis()+ttl,dto));while(cache.size()>MAX_CACHE)cache.remove(cache.keySet().iterator().next());}
    private static Map<String,Object> placeholder(Key key,String status,String message){return map("cx",key.x,"cz",key.z,"status",status,"message",message);}
    // Paper completes this future on the main server thread. Retain before yielding: an idle
    // chunk can unload between completion and the following scheduler tick.
    private void retainLoaded(Node node,Chunk chunk,Throwable error){
        requireMainThread();if(node.epoch!=epoch||!enabled||closed)return;
        try{if(error==null&&chunk!=null&&chunk.isLoaded()&&chunk.isGenerated()){
            Held hold=held.get(node.key);if(hold==null){if(held.size()>=MAX_TICKETS)throw new CapacityException();hold=new Held(chunk,chunk.addPluginChunkTicket(plugin));held.put(node.key,hold);}
            hold.snapshot|=node.surface;for(String actor:node.actors)hold.actors.put(actor,System.currentTimeMillis()+45_000);
        }}catch(RuntimeException ex){error=ex;}
        loaded.add(new Loaded(node,chunk,error,node.epoch));
    }
    private void releaseActor(String actor){releaseActorOutside(actor,Set.of());}
    private void releaseActorOutside(String actor,Set<Key> retained){for(var entry:List.copyOf(held.entrySet())){if(retained.contains(entry.getKey()))continue;Held hold=entry.getValue();hold.actors.remove(actor);if(hold.actors.isEmpty()&&!hold.snapshot)release(entry.getKey());}}
    private void release(Key key){Held hold=held.remove(key);if(hold!=null&&hold.added)hold.chunk.removePluginChunkTicket(plugin);}
    private static World world(String name){World world=Bukkit.getWorld(name);if(world==null)throw new IllegalArgumentException("Мир не загружен");return world;}
    static void validateView(int cx,int cz,int radius,int step){if(Math.abs((long)cx)>1_874_990||Math.abs((long)cz)>1_874_990)throw new IllegalArgumentException("Координаты за пределами Minecraft");if(radius<0||radius>3)throw new IllegalArgumentException("Радиус viewport от0 до3 чанков");if(step!=1&&step!=2&&step!=4)throw new IllegalArgumentException("Детализация:1,2,4");}
    static Map<String,Object> sample(ChunkSnapshot snapshot,int cx,int cz,int minY,int maxY,long at){
        int depth=12;Integer[] heights=new Integer[256],blocks=new Integer[256],voxels=new Integer[256*depth];List<Map<String,Object>> palette=new ArrayList<>();Map<String,Integer> ids=new HashMap<>();
        for(int z=0;z<16;z++)for(int x=0;x<16;x++){int i=x+16*z,y=snapshot.getHighestBlockYAt(x,z);if(y<minY||y>=maxY)continue;heights[i]=y;for(int d=0;d<depth&&y-d>=minY;d++){Material material=snapshot.getBlockType(x,y-d,z);if(material.isAir())continue;BlockData data=snapshot.getBlockData(x,y-d,z);String state=data.getAsString();Integer id=ids.get(state);if(id==null){id=palette.size();ids.put(state,id);palette.add(map("material",material.name(),"blockData",state,"color",fallbackColor(material.name())));}voxels[i+256*d]=id;if(d==0)blocks[i]=id;}}
        return map("cx",cx,"cz",cz,"size",16,"step",1,"depth",depth,"status","ready","heights",Collections.unmodifiableList(Arrays.asList(heights)),"blocks",Collections.unmodifiableList(Arrays.asList(blocks)),"voxels",Collections.unmodifiableList(Arrays.asList(voxels)),"palette",List.copyOf(palette),"sampledAt",at,"generated",true);
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
