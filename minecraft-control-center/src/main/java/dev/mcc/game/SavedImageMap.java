package dev.mcc.game;

import dev.mcc.core.Json;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.regex.*;
import java.util.stream.Stream;
import java.util.function.LongSupplier;
import static dev.mcc.game.GameService.map;

/** Persistent imagery. Viewer requests only read PNG/cache metadata; rendering is autonomous. */
public final class SavedImageMap implements AutoCloseable {
    public static final int TILE_SIZE=256,MAX_LEVEL=20,MAX_DIRTY=2048,QUANTUM_MS=8;
    public record Tile(byte[] png,String etag,long modifiedAt) {}
    public record WorldInfo(String name,UUID id,Path folder,String dimension,int minY,int maxY,Map<String,Object> spawn) {}
    private record Key(int level,int x,int y) {}
    private record Bounds(int minU,int minV,int maxU,int maxV) {boolean intersects(int u,int v){return minU<u+256&&maxU>=u&&minV<v+256&&maxV>=v;}}
    private record ChunkInfo(int cx,int cz,long stamp,Bounds bounds) {}
    private record Surface(int[] heights,int[] colors) {}
    private record Pixel(int x,int z,int y,int rgb) {}
    private static final Pattern REGION=Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private static final Pattern IMAGE=Pattern.compile("(-?\\d+)_(-?\\d+)\\.png");
    private static final int MAGIC=0x4d434336,MAX_PNG=1_048_576;
    private final Path root;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String,State> worlds=new ConcurrentHashMap<>();
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"MCC-map-images");t.setDaemon(true);t.setPriority(Thread.MIN_PRIORITY);return t;});
    private final LinkedHashMap<String,Tile> images=new LinkedHashMap<>(80,.75f,true);
    private volatile boolean enabled,paused,closed;
    private int cursor;
    public SavedImageMap(Path root){this(root,System::currentTimeMillis);}
    SavedImageMap(Path root,LongSupplier clock){this.root=root.toAbsolutePath().normalize();this.clock=clock;worker.schedule(this::run,100,TimeUnit.MILLISECONDS);}
    public void enabled(boolean value){enabled=value;}
    public void paused(boolean value){paused=value;}
    public void register(WorldInfo world){worlds.compute(world.name,(name,old)->{if(old!=null&&old.info.id.equals(world.id)){old.info=world;return old;}return new State(world,root.resolve(world.id.toString()));});}
    public Map<String,Object> manifest(String name){check();State s=world(name);return s.snapshot;}
    /** Strictly cache/file-only; no scheduling or region/chunk access here. */
    public Tile tile(String name,int level,int x,int y)throws IOException{
        check();validateTile(level,x,y);State s=world(name);String id=s.info.id+":"+level+":"+x+":"+y;
        // Invalidation after an atomic render cannot race an older read back into the LRU.
        synchronized(images){Tile hit=images.get(id);if(hit!=null)return hit;
            Path file=tilePath(s,new Key(level,x,y));if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))return null;
            if(Files.size(file)>MAX_PNG)throw new IOException("Cached PNG exceeds image budget");byte[] png=Files.readAllBytes(file);
            Tile result=new Tile(png,'"'+sha(png)+'"',Files.getLastModifiedTime(file,LinkOption.NOFOLLOW_LINKS).toMillis());
            images.put(id,result);while(images.size()>64||images.values().stream().mapToLong(t->t.png.length).sum()>8_388_608)images.remove(images.keySet().iterator().next());return result;
        }
    }
    public static void validateTile(int level,int x,int y){if(level<0||level>MAX_LEVEL||Math.abs((long)x)>1_000_000||Math.abs((long)y)>1_000_000)throw new IllegalArgumentException("Invalid image tile coordinates");}
    private void check(){if(!enabled||closed)throw new IllegalStateException("Модуль карты отключён");}
    private State world(String name){State state=worlds.get(name);if(state==null)throw new IllegalArgumentException("Мир не загружен");return state;}
    private static long coordinate(int x,int z){return ((long)x<<32)|(z&0xffffffffL);}
    private final class State {
        volatile WorldInfo info;final Path cache;
        volatile Map<String,Object> snapshot;
        final Map<Long,ChunkInfo> chunks=new HashMap<>();final Map<Long,Long> nonFull=new HashMap<>();final Set<Long> seen=new HashSet<>();
        final LinkedHashSet<Key> dirty=new LinkedHashSet<>();
        final LinkedHashMap<Long,Surface> surfaces=new LinkedHashMap<>(40,.75f,true);
        boolean bootstrapped,scan,forced,recoveringImages,cleanPending,startupScanPending;int imageCoherenceVersion;Stream<Path> restore;Iterator<Path> restoreIterator;DirectoryStream<Path> regions;Iterator<Path> regionIterator;
        Iterator<ChunkInfo> recoveryChunks;DirectoryStream<Path> recoveryTiles;Iterator<Path> recoveryTileIterator;
        byte[] header;int rx,rz,slot;long savedSlots,scanned,scanAt,lastForce,updatedAt,revision,failures,cachedCount,scanFailures,decodedRecords;String error="";Bounds bounds;
        State(WorldInfo info,Path cache){this.info=info;this.cache=cache;publish(this);}
    }
    private void run(){long start=System.nanoTime();try{if(!closed&&enabled&&!worlds.isEmpty()){List<State> states=new ArrayList<>(worlds.values());State s=states.get(Math.floorMod(cursor++,states.size()));
            // Cached manifest restoration is safe while paused; no Anvil read or image render runs.
            if(!s.bootstrapped){bootstrap(s);publish(s);return;}
            if(paused){publish(s);return;}
            for(int records=0;records<4&&System.nanoTime()-start<QUANTUM_MS*1_000_000L;records++){if(!s.bootstrapped){bootstrap(s);break;}if(s.restoreIterator!=null){if(s.restoreIterator.hasNext()){Path file=s.restoreIterator.next();try{ChunkInfo c=readInfo(file);s.chunks.put(coordinate(c.cx,c.cz),c);include(s,c.bounds);}catch(IOException ex){s.failures++;}continue;}s.restore.close();s.restore=null;s.restoreIterator=null;if(s.chunks.size()!=s.cachedCount)s.recoveringImages=true;if(s.recoveringImages)beginImageRecovery(s);}
                // Finish a bounded batch of saved records before baking shared image tiles.
                // Rendering after every FULL record repeatedly redraws the same image on large worlds.
                if(!s.dirty.isEmpty()&&(s.dirty.size()>=MAX_DIRTY||!s.scan&&!hasImageRecovery(s))){Key key=s.dirty.iterator().next();s.dirty.remove(key);try{render(s,key);}catch(IOException ex){s.failures++;s.error="Сохранённые изображения временно недоступны";}break;}
                if(hasImageRecovery(s)){recoverImage(s);continue;}
                if(!s.scan){if(!s.startupScanPending&&clock.getAsLong()-s.scanAt<60_000)break;beginScan(s);}scanOne(s);
            }commitCleanImages(s);publish(s);
        }else if(!closed)for(State s:worlds.values())publish(s);
    }catch(Exception ex){for(State s:worlds.values()){s.error="Фоновое чтение карты временно недоступно";s.failures++;publish(s);}}
        finally{if(!closed){long elapsed=Math.max(1,(System.nanoTime()-start)/1_000_000);worker.schedule(this::run,Math.max(50,Math.min(5000,elapsed*9)),TimeUnit.MILLISECONDS);}}
    }
    private void bootstrap(State s)throws IOException{
        Files.createDirectories(s.cache.resolve("surfaces"));Files.createDirectories(s.cache.resolve("tiles"));Path metadata=s.cache.resolve("manifest.json");
        s.recoveringImages=true;
        if(Files.isRegularFile(metadata,LinkOption.NOFOLLOW_LINKS)&&Files.size(metadata)<32768){try{Map<String,Object> saved=Json.object(Files.readString(metadata));if(saved.get("bounds") instanceof Map<?,?> b)s.bounds=new Bounds(((Number)b.get("minU")).intValue(),((Number)b.get("minV")).intValue(),((Number)b.get("maxU")).intValue(),((Number)b.get("maxV")).intValue());s.revision=((Number)saved.getOrDefault("revision",0)).longValue();s.updatedAt=((Number)saved.getOrDefault("updatedAt",0)).longValue();s.scanAt=((Number)saved.getOrDefault("scannedAt",0)).longValue();s.cachedCount=((Number)saved.getOrDefault("renderedChunks",0)).longValue();s.imageCoherenceVersion=((Number)saved.getOrDefault("imageCoherenceVersion",0)).intValue();String status=String.valueOf(saved.get("status"));s.recoveringImages=s.imageCoherenceVersion!=1||!(status.equals("ready")||status.equals("empty"))||((Number)saved.getOrDefault("pendingTiles",-1)).longValue()!=0;}catch(RuntimeException ignored){s.recoveringImages=true;}}
        if(Files.exists(dirtyMarker(s),LinkOption.NOFOLLOW_LINKS))s.recoveringImages=true;
        s.restore=Files.walk(s.cache.resolve("surfaces"),2);s.restoreIterator=s.restore.filter(p->p.getFileName().toString().endsWith(".bin")&&Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).iterator();s.bootstrapped=true;
    }
    private void beginScan(State s)throws IOException{
        s.scan=true;s.cleanPending=true;s.seen.clear();s.savedSlots=0;s.scanned=0;s.scanFailures=0;s.header=null;s.forced=clock.getAsLong()-s.lastForce>=300_000;
        Path directory=SavedSurfaceReader.regionDirectory(s.info.folder,s.info.dimension);
        if(directory==null){finishScan(s);return;}s.regions=Files.newDirectoryStream(directory,"r.*.*.mca");s.regionIterator=s.regions.iterator();
    }
    private void scanOne(State s)throws IOException{
        if(!s.scan)return;
        if(s.header==null||s.slot>=1024){s.header=null;while(s.regionIterator.hasNext()){Path file=s.regionIterator.next();Matcher m=REGION.matcher(file.getFileName().toString());if(!m.matches()||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))continue;long rx=Long.parseLong(m.group(1)),rz=Long.parseLong(m.group(2));if(Math.abs(rx)>58594||Math.abs(rz)>58594)continue;
                byte[] header=new byte[8192];try(FileChannel channel=FileChannel.open(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){ByteBuffer b=ByteBuffer.wrap(header);while(b.hasRemaining()){if(channel.read(b)<=0)throw new EOFException("Incomplete Anvil header");}}catch(IOException ex){s.failures++;s.scanFailures++;continue;}
                s.rx=(int)rx;s.rz=(int)rz;s.slot=0;s.header=header;for(int i=0;i<1024;i++)if(validSlot(header,i))s.savedSlots++;break;
            }if(s.header==null){finishScan(s);return;}}
        // Empty slots cost no decompression, but are still bounded per quantum.
        int advances=0;while(s.slot<1024&&advances++<64){int slot=s.slot++;if(!validSlot(s.header,slot))continue;int cx=s.rx*32+slot%32,cz=s.rz*32+slot/32;long key=coordinate(cx,cz),stamp=((long)ByteBuffer.wrap(s.header,slot*4,4).getInt()<<32)|(ByteBuffer.wrap(s.header,4096+slot*4,4).getInt()&0xffffffffL);s.seen.add(key);s.scanned++;
            ChunkInfo old=s.chunks.get(key);Long absent=s.nonFull.get(key);if(!s.forced&&(old!=null&&old.stamp==stamp||absent!=null&&absent.longValue()==stamp))return;
            try{s.decodedRecords++;SavedSurfaceReader.Surface surface=SavedSurfaceReader.read(s.info.folder,s.info.dimension,cx,cz);if(surface==null){remove(s,key);s.nonFull.put(key,stamp);return;}s.nonFull.remove(key);int[] colors=new int[256];for(int i=0;i<256;i++)colors[i]=surface.materials()[i]==null?0:color(surface.materials()[i]);Bounds bounds=surfaceBounds(cx,cz,surface.heights(),colors);ChunkInfo info=new ChunkInfo(cx,cz,stamp,bounds);Surface data=new Surface(surface.heights(),colors);boolean unchanged=sameSurface(s,old,data,bounds);
                // Forced scans validate Anvil contents even with an unchanged header stamp.
                // An identical image needs only a new persisted stamp when that stamp changed.
                if(!unchanged)markImagesDirty(s);if(!unchanged||old.stamp!=stamp)writeSurface(s,info,data);s.chunks.put(key,info);s.surfaces.put(key,data);trimSurfaces(s);include(s,bounds);if(!unchanged){if(old!=null)dirty(s,old.bounds);dirty(s,bounds);}
            }catch(IOException ex){s.failures++;s.scanFailures++;/* retain last good PNG/surface until next background pass */}return;
        }
    }
    static boolean validSlot(byte[] header,int i){int p=i*4;return (((header[p]&255)<<16)|((header[p+1]&255)<<8)|(header[p+2]&255))>=2&&(header[p+3]&255)>0;}
    private void finishScan(State s)throws IOException{
        if(s.regions!=null)s.regions.close();s.regions=null;s.regionIterator=null;s.header=null;s.scan=false;s.startupScanPending=false;s.scanAt=clock.getAsLong();if(s.forced)s.lastForce=s.scanAt;
        for(long key:new ArrayList<>(s.chunks.keySet()))if(!s.seen.contains(key))remove(s,key);s.nonFull.keySet().removeIf(key->!s.seen.contains(key));s.bounds=null;for(ChunkInfo c:s.chunks.values())include(s,c.bounds);
    }
    private void remove(State s,long key)throws IOException{ChunkInfo old=s.chunks.get(key);if(old!=null){markImagesDirty(s);s.chunks.remove(key);dirty(s,old.bounds);s.surfaces.remove(key);Files.deleteIfExists(surfacePath(s,old.cx,old.cz));}}
    private static Path dirtyMarker(State s){return s.cache.resolve("images-dirty");}
    private static void markImagesDirty(State s)throws IOException{s.cleanPending=true;if(!Files.exists(dirtyMarker(s),LinkOption.NOFOLLOW_LINKS))atomic(dirtyMarker(s),new byte[]{1});}
    private static boolean hasImageRecovery(State s){return s.recoveryChunks!=null||s.recoveryTileIterator!=null;}
    private static void beginImageRecovery(State s)throws IOException{
        markImagesDirty(s);s.startupScanPending=true;s.recoveryChunks=s.chunks.values().iterator();Path tiles=s.cache.resolve("tiles/0");
        if(Files.isDirectory(tiles,LinkOption.NOFOLLOW_LINKS)){s.recoveryTiles=Files.newDirectoryStream(tiles,"*.png");s.recoveryTileIterator=s.recoveryTiles.iterator();}
    }
    private static void recoverImage(State s)throws IOException{
        if(s.recoveryChunks!=null){if(s.recoveryChunks.hasNext()){dirty(s,s.recoveryChunks.next().bounds);return;}s.recoveryChunks=null;}
        // Existing base tiles also cover areas whose surface was removed just before interruption.
        if(s.recoveryTileIterator!=null){if(s.recoveryTileIterator.hasNext()){Path file=s.recoveryTileIterator.next();Matcher m=IMAGE.matcher(file.getFileName().toString());if(m.matches()&&Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)){try{int x=Integer.parseInt(m.group(1)),y=Integer.parseInt(m.group(2));validateTile(0,x,y);s.dirty.add(new Key(0,x,y));}catch(IllegalArgumentException ignored){}}return;}s.recoveryTiles.close();s.recoveryTiles=null;s.recoveryTileIterator=null;}
    }
    private void commitCleanImages(State s)throws IOException{
        if(s.cleanPending&&s.restoreIterator==null&&!hasImageRecovery(s)&&!s.startupScanPending&&!s.scan&&s.dirty.isEmpty()){
            s.recoveringImages=false;s.imageCoherenceVersion=1;
            // The durable ready manifest precedes journal deletion, but readers remain
            // rendering until both operations complete. A crash still retains recovery.
            atomic(s.cache.resolve("manifest.json"),Json.write(snapshot(s,true)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Files.deleteIfExists(dirtyMarker(s));s.cleanPending=false;publish(s);
        }
    }
    private static Bounds surfaceBounds(int cx,int cz,int[] h,int[] colors){int minU=Integer.MAX_VALUE,minV=Integer.MAX_VALUE,maxU=Integer.MIN_VALUE,maxV=Integer.MIN_VALUE;for(int z=0;z<16;z++)for(int x=0;x<16;x++){int i=x+z*16;if(colors[i]==0)continue;int u=(cx*16+x-cz*16-z)*2,v=cx*16+x+cz*16+z-h[i]-1;minU=Math.min(minU,u-2);maxU=Math.max(maxU,u+2);minV=Math.min(minV,v);maxV=Math.max(maxV,v+18);}return minU==Integer.MAX_VALUE?null:new Bounds(minU,minV,maxU,maxV);}
    private static void include(State s,Bounds b){if(b==null)return;s.bounds=s.bounds==null?b:new Bounds(Math.min(s.bounds.minU,b.minU),Math.min(s.bounds.minV,b.minV),Math.max(s.bounds.maxU,b.maxU),Math.max(s.bounds.maxV,b.maxV));}
    private static void dirty(State s,Bounds b){if(b==null)return;for(int y=Math.floorDiv(b.minV,256);y<=Math.floorDiv(b.maxV,256);y++)for(int x=Math.floorDiv(b.minU,256);x<=Math.floorDiv(b.maxU,256);x++)s.dirty.add(new Key(0,x,y));}
    private static Path surfacePath(State s,int cx,int cz){return s.cache.resolve("surfaces").resolve(Math.floorDiv(cx,32)+"_"+Math.floorDiv(cz,32)).resolve(cx+"_"+cz+".bin");}
    private static Path tilePath(State s,Key key){return s.cache.resolve("tiles").resolve(Integer.toString(key.level)).resolve(key.x+"_"+key.y+".png");}
    private static void writeSurface(State s,ChunkInfo info,Surface surface)throws IOException{ByteArrayOutputStream bytes=new ByteArrayOutputStream(2100);try(DataOutputStream out=new DataOutputStream(bytes)){out.writeInt(MAGIC);out.writeInt(info.cx);out.writeInt(info.cz);out.writeLong(info.stamp);out.writeBoolean(info.bounds!=null);if(info.bounds!=null){out.writeInt(info.bounds.minU);out.writeInt(info.bounds.minV);out.writeInt(info.bounds.maxU);out.writeInt(info.bounds.maxV);}for(int h:surface.heights)out.writeInt(h);for(int rgb:surface.colors)out.writeInt(rgb);}atomic(surfacePath(s,info.cx,info.cz),bytes.toByteArray());}
    private static ChunkInfo readInfo(Path path)throws IOException{long size=Files.size(path);if(size!=2069&&size!=2085)throw new IOException("Invalid surface cache size");try(DataInputStream in=new DataInputStream(Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS))){if(in.readInt()!=MAGIC)throw new IOException("Invalid surface cache version");int x=in.readInt(),z=in.readInt();if(Math.abs((long)x)>1_875_008||Math.abs((long)z)>1_875_008)throw new IOException("Invalid cached chunk coordinates");long stamp=in.readLong();Bounds b=in.readBoolean()?new Bounds(in.readInt(),in.readInt(),in.readInt(),in.readInt()):null;if(b!=null&&(b.minU>b.maxU||b.minV>b.maxV||(long)b.maxU-b.minU>128||(long)b.maxV-b.minV>8192))throw new IOException("Invalid cached surface bounds");return new ChunkInfo(x,z,stamp,b);}}
    private static Surface surface(State s,ChunkInfo info)throws IOException{long key=coordinate(info.cx,info.cz);Surface hit=s.surfaces.get(key);if(hit!=null)return hit;Path path=surfacePath(s,info.cx,info.cz);if(Files.size(path)>4096)throw new IOException("Surface budget exceeded");try(DataInputStream in=new DataInputStream(Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS))){if(in.readInt()!=MAGIC)throw new IOException("Surface cache version mismatch");in.skipNBytes(16);if(in.readBoolean())in.skipNBytes(16);int[] heights=new int[256],colors=new int[256];for(int i=0;i<256;i++)heights[i]=in.readInt();for(int i=0;i<256;i++)colors[i]=in.readInt();Surface data=new Surface(heights,colors);s.surfaces.put(key,data);trimSurfaces(s);return data;}}
    private static boolean sameSurface(State s,ChunkInfo old,Surface data,Bounds bounds){
        if(old==null||!Objects.equals(old.bounds,bounds))return false;
        try{Surface cached=surface(s,old);return Arrays.equals(cached.heights,data.heights)&&Arrays.equals(cached.colors,data.colors);}
        catch(IOException ex){return false;/* A valid saved record repairs an unreadable cache entry. */}
    }
    private static void trimSurfaces(State s){while(s.surfaces.size()>32)s.surfaces.remove(s.surfaces.keySet().iterator().next());}
    private void render(State s,Key key)throws IOException{
        BufferedImage image=key.level==0?renderBase(s,key):renderParent(s,key);ByteArrayOutputStream out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);byte[] png=out.toByteArray();if(png.length>MAX_PNG)throw new IOException("PNG budget exceeded");Path file=tilePath(s,key);String etag=sha(png);
        if(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)&&Files.size(file)<=MAX_PNG&&sha(Files.readAllBytes(file)).equals(etag)){if(s.recoveringImages&&key.level<MAX_LEVEL)s.dirty.add(new Key(key.level+1,Math.floorDiv(key.x,2),Math.floorDiv(key.y,2)));return;}
        atomic(file,png);s.error="";s.updatedAt=clock.getAsLong();s.revision=s.updatedAt;String id=s.info.id+":"+key.level+":"+key.x+":"+key.y;synchronized(images){images.remove(id);}if(key.level<MAX_LEVEL)s.dirty.add(new Key(key.level+1,Math.floorDiv(key.x,2),Math.floorDiv(key.y,2)));
        persistManifest(s);
    }
    private static BufferedImage renderBase(State s,Key key)throws IOException{
        int u=key.x*256,v=key.y*256;List<Pixel> pixels=new ArrayList<>();int minX=(int)Math.floor((u/2d+v+s.info.minY)/2)-4,maxX=(int)Math.ceil(((u+256)/2d+v+256+s.info.maxY)/2)+4;
        int minZ=(int)Math.floor((-(u+256)/2d+v+s.info.minY)/2)-4,maxZ=(int)Math.ceil((-u/2d+v+256+s.info.maxY)/2)+4;
        for(int cz=Math.floorDiv(minZ,16);cz<=Math.floorDiv(maxZ,16);cz++)for(int cx=Math.floorDiv(minX,16);cx<=Math.floorDiv(maxX,16);cx++){ChunkInfo info=s.chunks.get(coordinate(cx,cz));if(info==null||info.bounds==null||!info.bounds.intersects(u,v))continue;Surface surface=surface(s,info);for(int z=0;z<16;z++)for(int x=0;x<16;x++){int i=x+z*16;if(surface.colors[i]!=0)pixels.add(new Pixel(cx*16+x,cz*16+z,surface.heights[i],surface.colors[i]));}if(pixels.size()>131_072)throw new IOException("Image column budget exceeded");}
        pixels.sort(Comparator.comparingInt((Pixel p)->p.x+p.z).thenComparingInt(Pixel::y));BufferedImage image=new BufferedImage(256,256,BufferedImage.TYPE_INT_ARGB);Graphics2D g=image.createGraphics();
        try{for(Pixel p:pixels){int x=(p.x-p.z)*2-u,y=p.x+p.z-p.y-1-v;if(x<-2||x>258||y<-18||y>256)continue;g.setColor(new Color(shade(p.rgb,.58)));g.fillPolygon(new int[]{x-2,x,x,x-2},new int[]{y+1,y+2,y+14,y+13},4);g.setColor(new Color(shade(p.rgb,.76)));g.fillPolygon(new int[]{x,x+2,x+2,x},new int[]{y+2,y+1,y+13,y+14},4);g.setColor(new Color(shade(p.rgb,1+((p.x*13+p.z*7)&3)*.025)));g.fillPolygon(new int[]{x,x+2,x,x-2},new int[]{y,y+1,y+2,y+1},4);}}finally{g.dispose();}return image;
    }
    private static BufferedImage renderParent(State s,Key key)throws IOException{BufferedImage result=new BufferedImage(256,256,BufferedImage.TYPE_INT_ARGB);Graphics2D g=result.createGraphics();g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);try{for(int z=0;z<2;z++)for(int x=0;x<2;x++){Path file=tilePath(s,new Key(key.level-1,key.x*2+x,key.y*2+z));if(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)&&Files.size(file)<=MAX_PNG){BufferedImage child=ImageIO.read(file.toFile());if(child!=null&&child.getWidth()==256&&child.getHeight()==256)g.drawImage(child,x*128,z*128,128,128,null);}}}finally{g.dispose();}return result;}
    private static int shade(int rgb,double factor){int r=Math.min(255,(int)(((rgb>>16)&255)*factor)),g=Math.min(255,(int)(((rgb>>8)&255)*factor)),b=Math.min(255,(int)((rgb&255)*factor));return (r<<16)|(g<<8)|b;}
    static int color(String material){String n=material.toUpperCase(Locale.ROOT);if(n.contains("WATER")||n.contains("ICE"))return 0x3786ad;if(n.contains("LAVA")||n.contains("MAGMA"))return 0xe47932;if(n.contains("SNOW")||n.contains("QUARTZ"))return 0xe6eade;if(n.contains("LEAVES")||n.contains("MOSS")||n.contains("GRASS")||n.contains("VINE"))return 0x638e45;if(n.contains("SAND")||n.contains("END_STONE"))return 0xd6c08b;if(n.contains("NETHERRACK")||n.contains("CRIMSON"))return 0x9b484b;if(n.contains("LOG")||n.contains("WOOD")||n.contains("PLANK")||n.contains("DIRT")||n.contains("MUD")||n.contains("SOUL"))return 0x88704f;if(n.contains("DEEPSLATE")||n.contains("OBSIDIAN")||n.contains("BLACKSTONE"))return 0x41434d;if(n.contains("PURPUR")||n.contains("AMETHYST"))return 0x9a7eae;return 0x91958c;}
    private static String sha(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception ex){throw new IllegalStateException(ex);}}
    private static void atomic(Path file,byte[] bytes)throws IOException{Files.createDirectories(file.getParent());Path temp=file.resolveSibling(file.getFileName()+"."+UUID.randomUUID()+".tmp");try{Files.write(temp,bytes,StandardOpenOption.CREATE_NEW);try{Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException ex){Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}}finally{Files.deleteIfExists(temp);}}
    private void persistManifest(State s)throws IOException{publish(s);atomic(s.cache.resolve("manifest.json"),Json.write(s.snapshot).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    static int overviewLevel(int minU,int minV,int maxU,int maxV){long span=Math.max((long)maxU-minU,(long)maxV-minV);int level=0;while(level<MAX_LEVEL&&((long)TILE_SIZE<<level)<span)level++;return level;}
    private void publish(State s){s.snapshot=snapshot(s,false);}
    private Map<String,Object> snapshot(State s,boolean durableCleanCommit){Bounds b=s.bounds;String failure=s.error.isEmpty()&&s.scanFailures>0&&s.chunks.isEmpty()&&!s.scan&&s.restoreIterator==null?"Сохранённые участки не удалось прочитать. Формат карты недоступен.":s.error;String status=!failure.isEmpty()?"error":paused?"paused":!s.bootstrapped||s.restoreIterator!=null||hasImageRecovery(s)||s.startupScanPending||s.scan||!s.dirty.isEmpty()||s.cleanPending&&!durableCleanCommit?"rendering":s.chunks.isEmpty()?"empty":"ready";return Collections.unmodifiableMap(map("world",s.info.name,"worldId",s.info.id.toString(),"tileSize",256,"maxLevel",b==null?0:overviewLevel(b.minU,b.minV,b.maxU,b.maxV),"projection",map("xScale",2,"zScale",1,"heightScale",1),"bounds",b==null?null:map("minU",b.minU,"minV",b.minV,"maxU",b.maxU,"maxV",b.maxV),"spawn",s.info.spawn,"status",status,"renderedChunks",s.restoreIterator!=null?Math.max(s.chunks.size(),s.cachedCount):s.chunks.size(),"savedSlots",s.savedSlots,"imageCoherenceVersion",s.imageCoherenceVersion,"scannedRecords",s.scanned,"decodedRecords",s.decodedRecords,"nonFullRecords",s.nonFull.size(),"revision",s.revision,"pendingTiles",s.dirty.size(),"updatedAt",s.updatedAt,"scannedAt",s.scanAt,"failures",s.failures,"scanFailures",s.scanFailures,"message",failure,"generatedOnly",true,"backgroundOnly",true,"limits",map("chunkLoads",0,"pluginTickets",0,"workerThreads",1,"quantumMs",8,"dutyPercent",10,"updateSeconds",60,"rescanSeconds",300)));}
    @Override public void close(){
        closed=true;enabled=false;worker.shutdownNow();boolean interrupted=false,terminated=worker.isTerminated();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(!terminated){long remaining=deadline-System.nanoTime();if(remaining<=0)break;try{terminated=worker.awaitTermination(remaining,TimeUnit.NANOSECONDS);}catch(InterruptedException ex){interrupted=true;}}
        if(interrupted)Thread.currentThread().interrupt();
        if(!terminated)throw new IllegalStateException("Image map worker did not stop within its shutdown budget");
        for(State s:worlds.values()){try{if(s.regions!=null)s.regions.close();if(s.restore!=null)s.restore.close();if(s.recoveryTiles!=null)s.recoveryTiles.close();}catch(IOException ignored){}}synchronized(images){images.clear();}
    }
}
