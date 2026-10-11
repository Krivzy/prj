package dev.mcc.game;

import org.bukkit.*;
import dev.mcc.core.ServerPlatform;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static dev.mcc.game.GameService.*;

/** Main-thread descriptors/players only; terrain comes exclusively from persisted PNGs. */
public final class MapEngine implements AutoCloseable {
    private final JavaPlugin plugin;
    private final SavedImageMap images;
    private BukkitTask heartbeat;
    public MapEngine(JavaPlugin plugin){this.plugin=plugin;images=new SavedImageMap(plugin.getDataFolder().toPath().resolve("map-images/v1"));}
    public void enabled(boolean value){requireMainThread();images.enabled(value);if(value){capture();if(heartbeat==null)heartbeat=Bukkit.getScheduler().runTaskTimer(plugin,this::capture,20,20);}else if(heartbeat!=null){heartbeat.cancel();heartbeat=null;}}
    private void capture(){requireMainThread();images.paused(ServerPlatform.performanceGuard());for(World w:Bukkit.getWorlds()){Location p=w.getSpawnLocation();String dimension=switch(w.getEnvironment()){case NETHER->"the_nether";case THE_END->"the_end";default->"overworld";};images.register(new SavedImageMap.WorldInfo(w.getName(),w.getUID(),w.getWorldFolder().toPath(),dimension,w.getMinHeight(),w.getMaxHeight(),map("x",p.getX(),"y",p.getY(),"z",p.getZ())));}}
    public Map<String,Object> manifest(String name){return images.manifest(name);}
    public SavedImageMap.Tile image(String name,int level,int x,int y)throws IOException{return images.tile(name,level,x,y);}
    public Map<String,Object> players(String name){requireMainThread();World w=Bukkit.getWorld(name);if(w==null)throw new IllegalArgumentException("Мир не загружен");List<Map<String,Object>> players=new ArrayList<>();for(var player:w.getPlayers()){Location p=player.getLocation();players.add(map("uuid",player.getUniqueId().toString(),"name",player.getName(),"x",p.getX(),"y",p.getY(),"z",p.getZ()));}return map("world",name,"worldId",w.getUID().toString(),"players",players,"timestamp",System.currentTimeMillis());}
    // Compatibility signatures survive internal callers; WebServer retires these routes410.
    public Map<String,Object> terrain(String name,int cx,int cz,int radius,int step){throw new IllegalStateException("Surface JSON removed; use persistent image map");}
    public CompletableFuture<Map<String,Object>> worldIndex(String name){throw new IllegalStateException("Old index removed; use image manifest");}
    public CompletableFuture<Map<String,Object>> request(String name,int x,int z,int size){throw new IllegalStateException("Legacy terrain rendering removed");}
    public void invalidateTerrain(String name,int x,int z){/* Periodic saved-file scans observe changes after normal world save. */}
    public void invalidateWorld(String name){/* No game/world loading or saving is triggered by the renderer. */}
    @Override public void close(){requireMainThread();if(heartbeat!=null){heartbeat.cancel();heartbeat=null;}images.close();}
}
