package dev.mcc.game;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.CompletableFuture;

import static dev.mcc.game.GameService.*;

/** Original block palette, heightmap sampling, bounded cache and no chunk loads. */
public final class MapEngine implements AutoCloseable {
    private record TileKey(UUID world, int x, int z, int size) {}
    private record Cached(long expiresAt, Map<String, Object> tile) {}
    private final JavaPlugin plugin;
    private final WorldTerrainEngine terrain;
    private final LinkedHashMap<TileKey, Cached> cache = new LinkedHashMap<>();
    private final Deque<Job> jobs = new ArrayDeque<>();
    private final Deque<RegionJob> regions = new ArrayDeque<>();
    private boolean enabled;
    private BukkitTask task;

    public MapEngine(JavaPlugin plugin) { this.plugin = plugin;terrain=new WorldTerrainEngine(plugin); }

    public void enabled(boolean value) {
        requireMainThread(); enabled = value;terrain.enabled(value);
        if (!value) { if (task != null) { task.cancel(); task = null; } for (Job job : jobs) job.future.completeExceptionally(new IllegalStateException("Модуль карты отключён")); for(RegionJob region:regions)region.future.completeExceptionally(new IllegalStateException("Модуль карты отключён")); jobs.clear(); regions.clear(); cache.clear(); }
    }

    public Map<String,Object> terrain(String name,int cx,int cz,int radius,int step){requireMainThread();return terrain.terrain(name,cx,cz,radius,step);}
    public CompletableFuture<Map<String,Object>> worldIndex(String name){requireMainThread();return terrain.index(name);}
    public CompletableFuture<Map<String,Object>> detail(String actor,String name,int x,int y,int z,int size){
        requireMainThread();if(!enabled)throw new IllegalStateException("Модуль карты отключён");
        if(Math.abs((long)x)>29999800||Math.abs((long)z)>29999800||y< -2048||y>2048)throw new IllegalArgumentException("Координаты карты вне мира");
        World world=Bukkit.getWorld(name);if(world==null)throw new IllegalArgumentException("Мир не загружен");
        return terrain.prepareDetail(actor,world,x,z,size).thenCompose(ignored->{int startX=x-size/2,startZ=z-size/2;for(int cz=Math.floorDiv(startZ,16);cz<=Math.floorDiv(startZ+size-1,16);cz++)for(int cx=Math.floorDiv(startX,16);cx<=Math.floorDiv(startX+size-1,16);cx++)terrain.invalidate(name,cx*16,cz*16);return region(name,x,y,z,size);});
    }
    public void invalidateTerrain(String name,int x,int z){requireMainThread();terrain.invalidate(name,x,z);}
    public void invalidateWorld(String name){requireMainThread();terrain.invalidateWorld(name);}

    public CompletableFuture<Map<String, Object>> request(String name, int centerX, int centerZ, int size) {
        requireMainThread();
        if (!enabled) throw new IllegalStateException("Модуль карты отключён");
        if (size < 16 || size > 64) throw new IllegalArgumentException("Размер tile от 16 до 64");
        if (Math.abs((long)centerX) > 29999800 || Math.abs((long)centerZ) > 29999800) throw new IllegalArgumentException("Координаты карты вне допустимого диапазона");
        World world = Bukkit.getWorld(name); if (world == null) throw new IllegalArgumentException("Мир не загружен");
        int span = size * 2; int x = Math.floorDiv(centerX, span) * span, z = Math.floorDiv(centerZ, span) * span;
        TileKey key = new TileKey(world.getUID(), x, z, size);
        long now = System.currentTimeMillis();
        cache.entrySet().removeIf(e -> e.getValue().expiresAt <= now);
        Cached cached = cache.get(key);
        if (cached != null) return CompletableFuture.completedFuture(withMarkers(world, cached.tile));
        for (Job job : jobs) if (job.key.equals(key) && !job.future.isCancelled()) return job.future;
        if (jobs.size()+regions.size() >= 4) throw new IllegalStateException("Очередь карты заполнена");
        if (Bukkit.getTPS()[0] < 18) throw new IllegalStateException("Performance Guard: карта приостановлена при TPS < 18");
        Job job = new Job(key, world, now); jobs.addLast(job);
        if (task == null) task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
        return job.future;
    }

    /** Actual volumetric data, x-fastest indexing. Null means unloaded; air is an ordinary palette entry. */
    public CompletableFuture<Map<String,Object>> region(String name,int centerX,int centerY,int centerZ,int size) {
        requireMainThread();
        if(!enabled)throw new IllegalStateException("Модуль карты отключён");
        if(size<8||size>32)throw new IllegalArgumentException("Размер 3D области от 8 до 32");
        if(centerY< -2048||centerY>2048)throw new IllegalArgumentException("Высота карты вне допустимого диапазона");
        if(Math.abs((long)centerX)>29999800||Math.abs((long)centerZ)>29999800)throw new IllegalArgumentException("Координаты карты вне допустимого диапазона");
        World world=Bukkit.getWorld(name);if(world==null)throw new IllegalArgumentException("Мир не загружен");
        if(size>world.getMaxHeight()-world.getMinHeight())throw new IllegalArgumentException("Высота мира недостаточна");
        int x=centerX-size/2,z=centerZ-size/2,y=Math.max(world.getMinHeight(),Math.min(world.getMaxHeight()-size,centerY-size/2));
        for(RegionJob job:regions)if(job.world==world&&job.x==x&&job.y==y&&job.z==z&&job.size==size&&!job.future.isDone())return job.future;
        if(jobs.size()+regions.size()>=4)throw new IllegalStateException("Очередь карты заполнена");
        if(Bukkit.getTPS()[0]<18)throw new IllegalStateException("Performance Guard: карта приостановлена при TPS < 18");
        RegionJob job=new RegionJob(world,x,y,z,size);regions.addLast(job);
        if(task==null)task=Bukkit.getScheduler().runTaskTimer(plugin,this::tick,1,1);
        return job.future;
    }

    private void tick() {
        long deadline = System.nanoTime() + 450000;
        int sampled = 0;
        if (!enabled) { enabled(false); return; }
        int rounds = jobs.size();
        while (!jobs.isEmpty() && sampled < 64 && System.nanoTime() <= deadline && rounds-- > 0) {
            Job job = jobs.removeFirst();
            if (job.future.isDone()) continue;
            if (System.currentTimeMillis() - job.startedAt > 20000) { job.future.completeExceptionally(new IllegalStateException("Карта не готова: сервер занят")); continue; }
            if (Bukkit.getWorld(job.key.world) != job.world) { job.future.completeExceptionally(new IllegalStateException("Мир выгружен")); continue; }
            if (Bukkit.getTPS()[0] < 18) { jobs.addLast(job); continue; }
            try {
                int budget = Math.max(1, 64 / Math.max(1, jobs.size() + 1));
                int local = 0;
                while (job.cursor < job.pixels.length && sampled < 64 && local++ < budget && System.nanoTime() <= deadline) {
                    int x = job.key.x + (job.cursor % job.key.size) * 2;
                    int z = job.key.z + (job.cursor / job.key.size) * 2;
                    if (job.world.isChunkLoaded(x >> 4, z >> 4)) {
                        int y = job.world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                        if (y >= job.world.getMinHeight() && y < job.world.getMaxHeight()) job.pixels[job.cursor] = color(job.world.getBlockAt(x, y, z).getType());
                    }
                    job.cursor++; sampled++;
                }
                if (job.cursor == job.pixels.length) {
                    Map<String, Object> tile = map("world", job.world.getName(), "x", job.key.x, "z", job.key.z, "step", 2, "size", job.key.size,
                            "pixels", Collections.unmodifiableList(Arrays.asList(job.pixels)), "sampledAt", System.currentTimeMillis(), "loadedOnly", true);
                    cache.put(job.key, new Cached(System.currentTimeMillis() + 300000, tile));
                    while (cache.size() > 8) cache.remove(cache.keySet().iterator().next());
                    job.future.complete(withMarkers(job.world, tile));
                } else jobs.addLast(job);
            } catch (RuntimeException ex) { job.future.completeExceptionally(ex); }
        }
        int regionRounds=regions.size(),regionSamples=0;
        // Volumetric reads share the tick's 1ms ceiling, with a hard256 block cap.
        long volumeDeadline=deadline+550_000;
        while(!regions.isEmpty()&&regionSamples<256&&System.nanoTime()<volumeDeadline&&regionRounds-->0) {
            RegionJob job=regions.removeFirst();if(job.future.isDone())continue;
            if(System.currentTimeMillis()-job.startedAt>20_000){job.future.completeExceptionally(new IllegalStateException("3D карта не готова: сервер занят"));continue;}
            if(Bukkit.getWorld(job.world.getUID())!=job.world){job.future.completeExceptionally(new IllegalStateException("Мир выгружен"));continue;}
            if(Bukkit.getTPS()[0]<18){regions.addLast(job);continue;}
            try {
                int local=0;
                int budget=Math.max(1,256/Math.max(1,regions.size()+1));
                while(job.cursor<job.blocks.length&&regionSamples<256&&local++<budget&&System.nanoTime()<volumeDeadline) {
                    int x=job.x+job.cursor%job.size,z=job.z+(job.cursor/job.size)%job.size,y=job.y+job.cursor/(job.size*job.size);
                    if(job.world.isChunkLoaded(x>>4,z>>4)) {
                        BlockData data=job.world.getBlockAt(x,y,z).getBlockData();String key=data.getAsString();
                        Integer index=job.paletteIndex.get(key);
                        if(index==null) {
                            index=job.palette.size();job.paletteIndex.put(key,index);
                            org.bukkit.Color mapColor=data.getMapColor();String hex=String.format(Locale.ROOT,"#%06x",mapColor.asRGB());
                            job.palette.add(map("blockData",key,"material",data.getMaterial().name(),"color",hex,"occluding",data.isOccluding(),"air",data.getMaterial().isAir()));
                        }
                        job.blocks[job.cursor]=index;job.loaded++;
                    }
                    job.cursor++;regionSamples++;
                }
                if(job.cursor==job.blocks.length) {
                    List<Map<String,Object>> markers=new ArrayList<>();
                    for(Player player:job.world.getPlayers()){Location l=player.getLocation();if(l.getX()>=job.x&&l.getX()<job.x+job.size&&l.getZ()>=job.z&&l.getZ()<job.z+job.size)markers.add(map("uuid",player.getUniqueId().toString(),"name",player.getName(),"x",l.getX(),"y",l.getY(),"z",l.getZ(),"yaw",l.getYaw()));}
                    job.future.complete(map("world",job.world.getName(),"worldId",job.world.getUID().toString(),"origin",map("x",job.x,"y",job.y,"z",job.z),"size",job.size,"blocks",Collections.unmodifiableList(Arrays.asList(job.blocks)),"palette",List.copyOf(job.palette),"players",markers,"loadedOnly",true,"loadedBlocks",job.loaded,"totalBlocks",job.blocks.length,"sampledAt",System.currentTimeMillis()));
                }else regions.addLast(job);
            }catch(RuntimeException ex){job.future.completeExceptionally(ex);}
        }
        if (jobs.isEmpty() && regions.isEmpty() && task != null) { task.cancel(); task = null; }
    }

    private Map<String, Object> withMarkers(World world, Map<String, Object> tile) {
        Map<String, Object> result = new LinkedHashMap<>(tile);
        List<Map<String, Object>> players = new ArrayList<>();
        int x = (int)tile.get("x"), z = (int)tile.get("z"), span = (int)tile.get("size") * 2;
        for (Player player : world.getPlayers()) { Location l = player.getLocation(); if (l.getX() >= x && l.getX() < x + span && l.getZ() >= z && l.getZ() < z + span) players.add(map("uuid", player.getUniqueId().toString(), "name", player.getName(), "x", l.getX(), "y", l.getY(), "z", l.getZ(), "yaw", l.getYaw())); }
        result.put("players", players); return result;
    }

    private static String color(Material material) {
        String name = material.name();
        if (name.contains("WATER") || name.contains("ICE")) return "#3d80ac";
        if (name.contains("LAVA") || name.contains("MAGMA")) return "#e78532";
        if (name.contains("SNOW") || name.contains("QUARTZ")) return "#d8e1d6";
        if (name.contains("LEAVES") || name.contains("MOSS") || name.contains("GRASS") || name.contains("VINE")) return "#5d8741";
        if (name.contains("SAND") || name.contains("END_STONE")) return "#ccb57c";
        if (name.contains("NETHERRACK") || name.contains("NETHER_WART") || name.contains("CRIMSON")) return "#8e4547";
        if (name.contains("LOG") || name.contains("WOOD") || name.contains("PLANK") || name.contains("DIRT") || name.contains("MUD") || name.contains("SOUL")) return "#85694d";
        if (name.contains("DEEPSLATE") || name.contains("OBSIDIAN") || name.contains("BLACKSTONE")) return "#45454e";
        if (name.contains("STONE") || name.contains("GRAVEL") || name.contains("ORE")) return "#89908b";
        if (name.contains("PURPUR") || name.contains("AMETHYST")) return "#947ba8";
        if (material.isAir()) return null;
        return "#7b9983";
    }

    @Override public void close() { requireMainThread(); enabled(false);terrain.close(); }

    private static final class Job {
        final TileKey key;
        final World world;
        final long startedAt;
        final String[] pixels;
        final CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        int cursor;
        Job(TileKey key, World world, long startedAt) { this.key = key; this.world = world; this.startedAt = startedAt; pixels = new String[key.size * key.size]; }
    }
    private static final class RegionJob {
        final World world;final int x,y,z,size;final long startedAt=System.currentTimeMillis();
        final Integer[] blocks;final List<Map<String,Object>> palette=new ArrayList<>();final Map<String,Integer> paletteIndex=new HashMap<>();
        final CompletableFuture<Map<String,Object>> future=new CompletableFuture<>();int cursor,loaded;
        RegionJob(World world,int x,int y,int z,int size){this.world=world;this.x=x;this.y=y;this.z=z;this.size=size;blocks=new Integer[size*size*size];}
    }
}
