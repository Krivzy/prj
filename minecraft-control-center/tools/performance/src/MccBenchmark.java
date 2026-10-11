package fixture.benchmark;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.sun.management.ThreadMXBean;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.*;
import org.bukkit.block.data.type.Hopper;
import org.bukkit.block.data.type.Switch;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.management.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Explicitly disposable, bounded workloads. This plugin refuses to run without the fixture marker. */
public final class MccBenchmark extends JavaPlugin implements Listener {
    static final long SEED=6381247891023L;
    static final int BANKS=64,ITEMS_PER_BANK=27*64,CHUNKS=128,MAX_PENDING=4,MAX_TICKS=20_000;
    private final List<String> errors=new ArrayList<>(),assertions=new ArrayList<>();
    private final List<Map<String,Object>> phases=new ArrayList<>();
    private final List<Chest> inputs=new ArrayList<>(),outputs=new ArrayList<>();
    private final List<org.bukkit.block.Hopper> hoppers=new ArrayList<>();
    private final List<Block> levers=new ArrayList<>(),lamps=new ArrayList<>();
    private final List<Entity> actors=new ArrayList<>();
    private final AtomicInteger chunksDone=new AtomicInteger(),chunksPending=new AtomicInteger();
    private final AtomicBoolean chunkFailure=new AtomicBoolean();
    private final double[] mspt=new double[MAX_TICKS];
    private final long[] allocation=new long[MAX_TICKS],heap=new long[MAX_TICKS],operation=new long[MAX_TICKS];
    private final int[] completed=new int[MAX_TICKS],pending=new int[MAX_TICKS];
    private Path output;private Properties plan;private World mechanics,terrain;
    private ThreadMXBean allocationBean;private boolean allocationAvailable,collecting,finished,powered;
    private long previousAllocation,phaseStarted,previousHeap,operationNanos,phaseGcCount,phaseGcMillis,hopperStartCount;
    private int phaseIndex=-1,count,settle,ticksPerPhase,warmupTicks,unitsIssued,transitionChecks,forcedSaves;
    private String phase="initializing";private StringBuilder csv=new StringBuilder(2*1024*1024);
    private static final String[] ORDER={"idle-control","hopper-redstone","mob-ai","saved-chunk-read","fresh-chunk-generation","world-save"};

    @Override public void onEnable(){
        try{
            Path root=Path.of("").toRealPath(),expected=Path.of(System.getProperty("mcc.benchmark.fixture","")).toRealPath();
            if(!Boolean.getBoolean("mcc.benchmark.disposable")||!root.equals(expected)||!Files.isRegularFile(root.resolve("MCC-BENCHMARK-FIXTURE.json"),LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("A real disposable benchmark fixture is required");
            UUID run=UUID.fromString(System.getProperty("mcc.benchmark.run-id",""));
            output=getDataFolder().toPath();Files.createDirectories(output);plan=new Properties();try(var in=Files.newInputStream(output.resolve("plan.properties"))){plan.load(in);}
            if(!run.toString().equals(plan.getProperty("run.id")))throw new IllegalStateException("Fixture identity mismatch");
            ticksPerPhase=Integer.parseInt(plan.getProperty("phase.ticks","2400"));warmupTicks=Integer.parseInt(plan.getProperty("warmup.ticks","1200"));
            if(ticksPerPhase<40||ticksPerPhase>10_000||warmupTicks<20||warmupTicks>2400)throw new IllegalStateException("Workload outside its bounds");
            mechanics=Bukkit.getWorld("bench_mechanics");if(mechanics==null)throw new IllegalStateException("Expected mechanics world missing");
            terrain=Bukkit.getWorld("bench_terrain");if(terrain==null)terrain=Bukkit.createWorld(new WorldCreator("bench_terrain").seed(SEED).type(WorldType.NORMAL));
            if(terrain==null||terrain.getSeed()!=SEED)throw new IllegalStateException("Unexpected terrain seed");
            configure(mechanics);configure(terrain);prepareBanks();
            java.lang.management.ThreadMXBean bean=ManagementFactory.getThreadMXBean();if(bean instanceof ThreadMXBean allocated&&allocated.isThreadAllocatedMemorySupported()){allocationBean=allocated;if(!allocated.isThreadAllocatedMemoryEnabled())allocated.setThreadAllocatedMemoryEnabled(true);allocationAvailable=true;previousAllocation=allocated.getCurrentThreadAllocatedBytes();}
            csv.append("phase,tick_index,mspt,main_thread_allocated_delta_bytes,heap_used_bytes,operation_nanos,chunks_completed,chunks_pending\n");
            Bukkit.getPluginManager().registerEvents(this,this);
            getServer().getScheduler().runTaskTimer(this,this::step,1,1);
            getLogger().info("MCC benchmark fixture enabled; this is a bounded experimental workload, not an overall performance score.");
        }catch(Exception failure){errors.add(failure.getClass().getSimpleName()+": "+Objects.toString(failure.getMessage(),""));finished=true;writeResult(false);getServer().getPluginManager().disablePlugin(this);}
    }
    private void configure(World world){world.setDifficulty(Difficulty.NORMAL);world.setGameRule(GameRule.DO_MOB_SPAWNING,false);world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE,false);world.setGameRule(GameRule.DO_WEATHER_CYCLE,false);world.setGameRule(GameRule.RANDOM_TICK_SPEED,0);world.setTime(1000);world.setStorm(false);world.setThundering(false);world.setAutoSave(false);world.setViewDistance(8);world.setSimulationDistance(8);}
    private void set(World world,int x,int y,int z,Material material){world.getBlockAt(x,y,z).setType(material,false);}
    private void prepareBanks(){
        int y=80;
        for(int i=0;i<BANKS;i++){
            int x=(i%8)*8,z=(i/8)*6;
            mechanics.getChunkAt(x>>4,z>>4).setForceLoaded(true);
            for(int dx=-1;dx<7;dx++)for(int dz=-1;dz<3;dz++)set(mechanics,x+dx,y-1,z+dz,Material.STONE);
            set(mechanics,x,y,z,Material.HOPPER);Hopper data=(Hopper)mechanics.getBlockAt(x,y,z).getBlockData();data.setFacing(org.bukkit.block.BlockFace.EAST);mechanics.getBlockAt(x,y,z).setBlockData(data,true);
            set(mechanics,x,y+1,z,Material.CHEST);set(mechanics,x+1,y,z,Material.CHEST);
            inputs.add((Chest)mechanics.getBlockAt(x,y+1,z).getState());outputs.add((Chest)mechanics.getBlockAt(x+1,y,z).getState());hoppers.add((org.bukkit.block.Hopper)mechanics.getBlockAt(x,y,z).getState());
            inputs.get(i).getBlockInventory().clear();outputs.get(i).getBlockInventory().clear();hoppers.get(i).getInventory().clear();
            Block lever=mechanics.getBlockAt(x+2,y,z);lever.setType(Material.LEVER,false);Switch switchData=(Switch)lever.getBlockData();switchData.setAttachedFace(FaceAttachable.AttachedFace.FLOOR);switchData.setFacing(org.bukkit.block.BlockFace.NORTH);switchData.setPowered(false);lever.setBlockData(switchData,true);levers.add(lever);
            for(int dx=3;dx<6;dx++)mechanics.getBlockAt(x+dx,y,z).setType(Material.REDSTONE_WIRE,true);
            Block lamp=mechanics.getBlockAt(x+6,y,z);lamp.setType(Material.REDSTONE_LAMP,true);lamps.add(lamp);
        }
    }
    private void toggle(boolean on){powered=on;for(Block block:levers){Switch data=(Switch)block.getBlockData();data.setPowered(on);block.setBlockData(data,true);}}
    private static long items(Inventory inventory){long total=0;for(ItemStack item:inventory.getContents())if(item!=null&&!item.getType().isAir())total+=item.getAmount();return total;}
    private long outputItems(){long total=0;for(Chest chest:outputs)total+=items(chest.getBlockInventory());return total;}
    private void start(String next){
        collecting=false;phase=next;count=0;settle=100;operationNanos=0;unitsIssued=0;chunksDone.set(0);chunksPending.set(0);chunkFailure.set(false);transitionChecks=0;forcedSaves=0;
        if(phase.equals("hopper-redstone")){for(Chest chest:inputs){chest.getBlockInventory().clear();for(int slot=0;slot<27;slot++)chest.getBlockInventory().setItem(slot,new ItemStack(Material.COBBLESTONE,64));}toggle(false);}
        if(phase.equals("mob-ai")){mechanics.setTime(18000);spawnActors();}
        if(phase.equals("idle-control"))settle=warmupTicks;
        getLogger().info("MCC benchmark phase: "+phase);
    }
    private void spawnActors(){
        for(int i=0;i<128;i++){
            int x=(i%16)*4,z=96+(i/16)*6;mechanics.getChunkAt(x>>4,z>>4).setForceLoaded(true);
            for(int dx=-1;dx<=2;dx++)for(int dz=-1;dz<=5;dz++)set(mechanics,x+dx,79,z+dz,Material.STONE);
            for(int dx=-1;dx<=2;dx++)for(int dy=80;dy<=83;dy++){set(mechanics,x+dx,dy,z+2,Material.GLASS);set(mechanics,x+dx,dy,z-1,Material.GLASS);set(mechanics,x+dx,dy,z+5,Material.GLASS);}
            for(int dy=80;dy<=83;dy++)for(int dz=-1;dz<=5;dz++){set(mechanics,x-1,dy,z+dz,Material.GLASS);set(mechanics,x+2,dy,z+dz,Material.GLASS);}
            Villager villager=mechanics.spawn(new Location(mechanics,x+.5,80,z+3.5),Villager.class);villager.setPersistent(true);villager.setInvulnerable(true);villager.setSilent(true);
            Zombie zombie=mechanics.spawn(new Location(mechanics,x+.5,80,z+.5),Zombie.class);zombie.setPersistent(true);zombie.setInvulnerable(true);zombie.setSilent(true);zombie.setTarget(villager);actors.add(villager);actors.add(zombie);
        }
    }
    private void step(){
        if(finished)return;
        try{
            if(phaseIndex<0){if(plan.getProperty("mode","measure").equals("template")){prepareTemplate();return;}phaseIndex=0;start(ORDER[phaseIndex]);return;}
            if(settle>0){settle--;if(settle==0){collecting=true;phaseStarted=System.nanoTime();phaseGcCount=gcCount();phaseGcMillis=gcMillis();hopperStartCount=outputItems();}return;}
            boolean chunkPhase=phase.equals("saved-chunk-read")||phase.equals("fresh-chunk-generation");
            if(count>=MAX_TICKS-1)throw new IllegalStateException("Workload exceeded maximum tick count");
            if(chunkPhase&&chunksDone.get()>=CHUNKS&&chunksPending.get()==0||!chunkPhase&&count>=ticksPerPhase){finishPhase();phaseIndex++;if(phaseIndex==ORDER.length){finished=true;mechanics.save();terrain.save();writeResult(errors.isEmpty());return;}start(ORDER[phaseIndex]);return;}
            if(chunkFailure.get())throw new IllegalStateException("Chunk future failed");
            if(phase.equals("hopper-redstone")){
                if(count%32==0)toggle(!powered);
                if(count%32==8){for(Block lamp:lamps)if(((Lightable)lamp.getBlockData()).isLit()!=powered)throw new IllegalStateException("Redstone lamp did not follow the real lever/wire signal");transitionChecks++;}
            }
            if(phase.equals("world-save")&&count%100==1){
                for(int i=0;i<BANKS;i++)set(mechanics,(i%8)*8,78,(i/8)*6,forcedSaves%2==0?Material.DEEPSLATE:Material.STONE);
                long began=System.nanoTime();mechanics.save();terrain.save();operationNanos=System.nanoTime()-began;forcedSaves++;
            }
            if(chunkPhase&&unitsIssued<CHUNKS&&chunksPending.get()<MAX_PENDING){
                int index=unitsIssued++;int base=phase.equals("saved-chunk-read")?128:512,x=base+index%16,z=base+index/16;chunksPending.incrementAndGet();long began=System.nanoTime();
                // Public async Paper API: queue admission latency and completion throughput are separate fields.
                if(plan.getProperty("generation.mode","parallel").equals("controlled")){
                    if(!terrain.loadChunk(x,z,phase.equals("fresh-chunk-generation")))throw new IllegalStateException("Expected synchronous chunk did not load");chunksDone.incrementAndGet();chunksPending.decrementAndGet();
                }else terrain.getChunkAtAsync(x,z,phase.equals("fresh-chunk-generation"),false).whenComplete((chunk,error)->{if(error!=null||chunk==null)chunkFailure.set(true);chunksDone.incrementAndGet();chunksPending.decrementAndGet();});operationNanos=System.nanoTime()-began;
            }
        }catch(Exception failure){errors.add(phase+": "+failure.getClass().getSimpleName()+": "+Objects.toString(failure.getMessage(),""));collecting=false;finished=true;writeResult(false);}
    }
    private void prepareTemplate(){
        collecting=false;
        if(unitsIssued<CHUNKS){int index=unitsIssued++;terrain.getChunkAt(128+index%16,128+index/16);return;}
        mechanics.save();terrain.save();assertions.add("Fresh template terrain read workload generated and saved before any comparison");finished=true;writeResult(true);
    }
    @EventHandler(priority=EventPriority.MONITOR) public void tick(ServerTickEndEvent event){
        long allocated=allocationAvailable?allocationBean.getCurrentThreadAllocatedBytes():-1,delta=allocationAvailable?Math.max(0,allocated-previousAllocation):-1;previousAllocation=allocated;
        if(!collecting||finished)return;
        if(count%20==0)previousHeap=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        if(count<MAX_TICKS){mspt[count]=event.getTickDuration();allocation[count]=delta;heap[count]=previousHeap;operation[count]=operationNanos;completed[count]=chunksDone.get();pending[count]=chunksPending.get();count++;operationNanos=0;}
    }
    private void finishPhase()throws Exception{
        collecting=false;Map<String,Object> values=new LinkedHashMap<>();values.put("name",phase);values.put("ticks",count);values.put("wallNanos",System.nanoTime()-phaseStarted);values.put("gcCollections",gcCount()-phaseGcCount);values.put("gcMillis",gcMillis()-phaseGcMillis);values.put("completedChunks",chunksDone.get());values.put("asyncQueueLimit",MAX_PENDING);
        if(phase.equals("hopper-redstone")){
            long total=0;for(int i=0;i<BANKS;i++)total+=items(inputs.get(i).getBlockInventory())+items(hoppers.get(i).getInventory())+items(outputs.get(i).getBlockInventory());
            if(total!=(long)BANKS*ITEMS_PER_BANK)throw new IllegalStateException("Hopper item conservation failed");long moved=outputItems()-hopperStartCount;if(moved<(long)BANKS*Math.max(0,count/8-4))throw new IllegalStateException("Hopper cadence fell below the matched vanilla 8-tick setting");assertions.add("Hopper inventory conservation and bounded 8-tick minimum cadence");if(transitionChecks<1)throw new IllegalStateException("No redstone transition was verified");assertions.add("Actual lever/wire/lamp transitions verified: "+transitionChecks);values.put("hopperMovedItems",moved);values.put("redstoneTransitionChecks",transitionChecks);toggle(false);
            for(Chest chest:inputs)chest.getBlockInventory().clear();for(Chest chest:outputs)chest.getBlockInventory().clear();for(org.bukkit.block.Hopper hopper:hoppers)hopper.getInventory().clear();
        }
        if(phase.equals("mob-ai")){if(actors.size()!=256||actors.stream().anyMatch(entity->!entity.isValid()))throw new IllegalStateException("Bounded mob population changed unexpectedly");assertions.add("256 persistent, invulnerable actors remained valid; matched activation-range=0 artificial AI load only");for(Entity entity:actors)entity.remove();actors.clear();mechanics.setTime(1000);}
        if(phase.equals("world-save")){if(forcedSaves<1)throw new IllegalStateException("No explicit dirty world save executed");values.put("forcedSaves",forcedSaves);assertions.add("Explicit dirty mechanics and generated terrain save calls completed: "+forcedSaves);}
        for(int i=0;i<count;i++)csv.append(phase).append(',').append(i).append(',').append(mspt[i]).append(',').append(allocation[i]).append(',').append(heap[i]).append(',').append(operation[i]).append(',').append(completed[i]).append(',').append(pending[i]).append('\n');
        phases.add(values);
    }
    private static long gcCount(){return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean->Math.max(0,bean.getCollectionCount())).sum();}
    private static long gcMillis(){return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean->Math.max(0,bean.getCollectionTime())).sum();}
    /** Equality sample over generated terrain block states, outside every measurement phase. */
    private String generationDigest()throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        for(int index=0;index<CHUNKS;index++){
            ChunkSnapshot snapshot=terrain.getChunkAt(512+index%16,512+index/16).getChunkSnapshot(false,false,false);
            for(int x=0;x<16;x+=4)for(int z=0;z<16;z+=4)for(int y=terrain.getMinHeight();y<terrain.getMaxHeight();y++){
                String block=snapshot.getBlockData(x,y,z).getAsString();digest.update(block.getBytes(StandardCharsets.UTF_8));digest.update((byte)0);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private void writeResult(boolean passed){
        try{
            if(output==null||plan==null)return;Map<String,Object> result=new LinkedHashMap<>();result.put("schema",1);result.put("passed",passed);result.put("scope","disposable-mcc-core-benchmark");result.put("runId",plan.getProperty("run.id"));result.put("mode",plan.getProperty("mode"));result.put("candidate",plan.getProperty("candidate"));result.put("minecraftVersion",Bukkit.getMinecraftVersion());result.put("serverVersion",Bukkit.getVersion());result.put("javaVersion",System.getProperty("java.version"));result.put("viewDistance",8);result.put("simulationDistance",8);result.put("normalTerrainSeed",SEED);result.put("hopperBanks",BANKS);result.put("mobActors",256);result.put("chunksPerWorkload",CHUNKS);result.put("mainThreadAllocationAvailable",allocationAvailable);result.put("allocationScope","Exact surviving Minecraft main-thread counter deltas. Async worker and short-lived thread allocations are not measured.");result.put("heapSamplingTicks",20);result.put("nominalHeapSamplingMillisAt20TPS",1000);result.put("phases",phases);result.put("assertions",assertions);result.put("errors",errors);
            result.put("plugins",Arrays.stream(Bukkit.getPluginManager().getPlugins()).map(plugin->Map.of("name",plugin.getName(),"version",plugin.getDescription().getVersion(),"enabled",plugin.isEnabled())).toList());
            if(passed&&!plan.getProperty("mode").equals("template")){result.put("generatedBlockStateSampleSha256",generationDigest());assertions.add("Generated block-state digest samples every fourth X/Z and all heights; it is not a complete vanilla equivalence proof");}
            Files.writeString(output.resolve("ticks.csv"),csv,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);Files.writeString(output.resolve("result.pending.json"),json(result),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);Files.move(output.resolve("result.pending.json"),output.resolve("result.json"),StandardCopyOption.ATOMIC_MOVE);
            getLogger().info("MCC benchmark fixture result written; normal server stop is required before snapshot/closure.");
        }catch(Exception failure){getLogger().severe("Benchmark result could not be written: "+failure.getClass().getSimpleName());}
    }
    private static String json(Object value){if(value==null)return "null";if(value instanceof String text)return "\""+text.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")+"\"";if(value instanceof Number||value instanceof Boolean)return value.toString();if(value instanceof Map<?,?> map){StringJoiner joined=new StringJoiner(",","{","}");map.forEach((key,item)->joined.add(json(String.valueOf(key))+":"+json(item)));return joined.toString();}if(value instanceof Iterable<?> list){StringJoiner joined=new StringJoiner(",","[","]");for(Object item:list)joined.add(json(item));return joined.toString();}throw new IllegalArgumentException("Unsupported JSON value");}
    @Override public void onDisable(){for(Entity actor:actors)if(actor.isValid())actor.remove();}
}
