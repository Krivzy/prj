package dev.mcc.game;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.*;

/** Passive ore observation. No punishments, block edits, chunk loading, or async Bukkit access. */
public final class OreMonitor implements Listener, AutoCloseable {
    public static final int MAX_PENDING = 2048, VERIFY_PER_TICK = 128;
    private sealed interface Pending permits Break, Place { long eligibleTick(); }
    private record Break(long eligibleTick,long occurredAt,BlockBreakEvent event,OreLedger.BlockKey key,String material,UUID player,String name,String world,boolean survival) implements Pending {}
    private record Place(long eligibleTick,long occurredAt,BlockPlaceEvent event,OreLedger.BlockKey key,String material) implements Pending {}
    private final JavaPlugin plugin;
    // Stable across module toggles; a new plugin/server boot invalidates UI acknowledgements.
    private final String bootId=UUID.randomUUID().toString();
    private final Map<OreKind,OreLedger.Threshold> thresholds;
    private final java.util.function.LongSupplier clock;
    private final ArrayDeque<Pending> pending=new ArrayDeque<>();
    private final Set<UUID> online=new HashSet<>();
    private OreLedger ledger;
    private volatile OreHistoryStore store;
    private CompletableFuture<Void> previousClose=CompletableFuture.completedFuture(null);
    private volatile Map<String,Object> cached,historySnapshot=Map.of();
    private volatile long generation;
    private volatile boolean enabled;
    private BukkitTask task;
    private long tick;
    private boolean closed;
    private volatile boolean refreshPending;
    private volatile String storageError="";

    public OreMonitor(JavaPlugin plugin,ConfigurationSection configuration){
        requireMain();this.plugin=plugin;
        EnumMap<OreKind,OreLedger.Threshold> values=new EnumMap<>(OreKind.class);
        for(OreKind ore:OreKind.values()){
            int yellow=configuration==null?ore.yellow:configuration.getInt("thresholds."+ore.id()+".yellow",ore.yellow);
            int red=configuration==null?ore.red:configuration.getInt("thresholds."+ore.id()+".red",ore.red);
            values.put(ore,new OreLedger.Threshold(yellow,red));
        }
        thresholds=Map.copyOf(values);
        long baseMillis=System.currentTimeMillis(),baseNanos=System.nanoTime();
        clock=()->baseMillis+(System.nanoTime()-baseNanos)/1_000_000;
        ledger=new OreLedger(OreLedger.Limits.defaults(),thresholds,clock);
        cached=empty();
    }
    public void start(){enabled(true);}
    /** Dynamic module lifecycle: no store/thread is created for disabled startup. */
    public synchronized void enabled(boolean value){
        requireMain();if(closed&&value)throw new IllegalStateException("Ore monitor closed");if(enabled==value)return;
        generation++;enabled=value;refreshPending=false;
        if(!value){
            if(task!=null){task.cancel();task=null;}HandlerList.unregisterAll(this);
            while(!pending.isEmpty()){pending.removeFirst();ledger.droppedPending();}
            if(store!=null){noteCoverage();previousClose=store.closeAsync();store=null;}
            ledger.clearTransient();
            online.clear();historySnapshot=Map.of();cached=empty();return;
        }
        storageError="";ledger=new OreLedger(OreLedger.Limits.defaults(),thresholds,clock);
        store=new OreHistoryStore(plugin.getDataFolder().toPath().resolve("ore-history.sqlite"),thresholds,clock,OreHistoryStore.Limits.defaults(),previousClose);
        for(var player:Bukkit.getOnlinePlayers())if(online.size()<512)online.add(player.getUniqueId());
        Bukkit.getPluginManager().registerEvents(this,plugin);
        task=Bukkit.getScheduler().runTaskTimer(plugin,this::advance,1,1);
        refresh();
    }
    /** HTTP workers receive immutable cached DTOs, never Bukkit queries. */
    public Map<String,Object> snapshot(){return cached;}
    public CompletableFuture<Map<String,Object>> history(String query,int offset,int limit){
        OreHistoryStore active=store;long token=generation;
        if(!enabled||active==null)return CompletableFuture.failedFuture(new IllegalStateException("Ore monitoring disabled"));
        return active.history(query,offset,limit).thenApply(value->{if(!enabled||generation!=token)throw new IllegalStateException("Ore monitoring changed");return value;});
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void onBreak(BlockBreakEvent event){
        if(!enabled||closed)return;Block block=event.getBlock();String material=block.getType().name();OreLedger.BlockKey key=key(block);
        if(OreKind.fromMaterial(material)==null&&!ledger.isTracked(key))return;
        offer(new Break(tick+1,ledger.observationTime(),event,key,material,event.getPlayer().getUniqueId(),event.getPlayer().getName(),block.getWorld().getName(),event.getPlayer().getGameMode()==GameMode.SURVIVAL));
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void onPlace(BlockPlaceEvent event){
        if(!enabled||closed||!event.canBuild())return;Block block=event.getBlockPlaced();String material=block.getType().name();OreLedger.BlockKey key=key(block);
        if(OreKind.fromMaterial(material)==null&&!ledger.isTracked(key))return;
        offer(new Place(tick+1,ledger.observationTime(),event,key,material));
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event){if(enabled&&online.size()<512)online.add(event.getPlayer().getUniqueId());}
    @EventHandler(priority=EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event){online.remove(event.getPlayer().getUniqueId());}
    private void offer(Pending value){if(pending.size()>=MAX_PENDING)ledger.droppedPending();else pending.addLast(value);}
    private void advance(){
        requireMain();tick++;long deadline=System.nanoTime()+750_000;
        for(int processed=0;processed<VERIFY_PER_TICK&&!pending.isEmpty()&&System.nanoTime()<deadline;processed++){
            if(pending.peekFirst().eligibleTick()>tick)break;Pending action=pending.removeFirst();
            if(action instanceof Place place){if(!place.event.isCancelled()&&place.event.canBuild())ledger.placed(place.key,place.material,place.occurredAt);}
            else if(action instanceof Break broken){
                if(broken.event.isCancelled())continue;
                World world=Bukkit.getWorld(broken.key.world());
                if(world==null||!world.isChunkLoaded(Math.floorDiv(broken.key.x(),16),Math.floorDiv(broken.key.z(),16))){ledger.skippedVerification();continue;}
                if(world.getBlockAt(broken.key.x(),broken.key.y(),broken.key.z()).getType().name().equals(broken.material)){ledger.skippedVerification();continue;}
                if(broken.survival&&OreKind.fromMaterial(broken.material)!=null){
                    OreLedger.Observation observation=ledger.mined(broken.player,broken.name,broken.world,broken.key,broken.material,broken.occurredAt);
                    if(observation!=null)store.offer(observation);
                }else ledger.forgetPlaced(broken.key);
            }
        }
        if(tick%100==0)refresh();
    }
    private void refresh(){
        if(store==null)return;store.online(online);noteCoverage();store.flush();
        publish();
        if(refreshPending)return;refreshPending=true;long token=generation;
        store.snapshot(clock.getAsLong()).whenComplete((value,failure)->{
            synchronized(OreMonitor.this){
                if(generation!=token)return;
                if(failure==null){historySnapshot=value;storageError="";}else storageError="storage_unavailable";
                refreshPending=false;
            }
        });
    }
    private void noteCoverage(){Map<String,Object> loss=ledger.coverage(pending.size());store.noteCoverage((long)loss.get("verificationSkipped"),(long)loss.get("pendingDropped"),(long)loss.get("placedEvicted"));}
    private void publish(){
        LinkedHashMap<String,Object> value=new LinkedHashMap<>(historySnapshot.isEmpty()?empty():historySnapshot);
        Map<String,Object> coverage=new LinkedHashMap<>(ledger.coverage(pending.size()));
        boolean verificationPartial=Boolean.TRUE.equals(coverage.get("partial"));
        Object raw=value.get("historyCoverage");if(raw instanceof Map<?,?> history)history.forEach((key,item)->coverage.put(String.valueOf(key),item));
        boolean partial=verificationPartial||Boolean.TRUE.equals(coverage.get("partial"))||!storageError.isEmpty();
        coverage.put("partial",partial);value.put("coverage",Map.copyOf(coverage));
        value.put("ores",oreDefinitions());
        if(store!=null)value.put("persistence",store.state());
        value.put("enabled",enabled);value.put("bootId",bootId);cached=Map.copyOf(value);
    }
    private List<Map<String,Object>> oreDefinitions(){return Arrays.stream(OreKind.values()).map(ore->Map.<String,Object>of("id",ore.id(),"material",ore.material,"yellow",thresholds.get(ore).yellow(),"red",thresholds.get(ore).red())).toList();}
    private Map<String,Object> empty(){
        LinkedHashMap<String,Object> value=new LinkedHashMap<>();value.put("generatedAt",clock.getAsLong());value.put("startedAt",0L);value.put("trackingSince",0L);value.put("observedSeconds",0L);value.put("windowSeconds",3600);value.put("windows",List.of(300,1800,3600));value.put("indicatorWindow",300);value.put("windowResolutionSeconds",1);value.put("ores",oreDefinitions());value.put("players",List.of());value.put("topPlayers",List.of());value.put("coverage",ledger.coverage(0));value.put("limits",Map.of("maxPlayers",256,"maxPlaced",65536,"maxBufferedBuckets",2048,"maxRecentRows",131072));value.put("persistence",Map.of("status",enabled?"loading":"disabled","pendingBlocks",0L,"droppedBlocks",0L,"lastFlushAt",0L,"errorCode",""));value.put("enabled",enabled);
        value.put("bootId",bootId);return Map.copyOf(value);
    }
    private static OreLedger.BlockKey key(Block block){return new OreLedger.BlockKey(block.getWorld().getUID(),block.getX(),block.getY(),block.getZ());}
    private static void requireMain(){if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Ore monitor requires server main thread");}
    @Override public void close(){
        requireMain();if(closed)return;enabled(false);closed=true;
        try{previousClose.get(3,TimeUnit.SECONDS);}catch(Exception failure){plugin.getLogger().warning("Ore history final flush was not confirmed; persisted coverage remains incomplete.");}
    }
}
