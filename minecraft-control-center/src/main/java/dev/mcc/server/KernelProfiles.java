package dev.mcc.server;

import dev.mcc.core.Json;
import dev.mcc.core.PrivateFiles;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.concurrent.atomic.AtomicBoolean;

/** Independent, bounded kernel preset manager. File IO never runs on the Bukkit tick thread. */
public final class KernelProfiles implements AutoCloseable {
    public record WorldState(String name,Path folder,int viewDistance,int simulationDistance) {}
    public record RuntimeState(String kernel,String minecraftVersion,boolean liveDistances,List<WorldState> worlds) {
        public RuntimeState { worlds=List.copyOf(worlds);if(worlds.size()>128)throw new IllegalArgumentException("Too many worlds for a managed profile"); }
    }
    public interface RuntimeAccess {
        CompletableFuture<RuntimeState> snapshot();
        /** Public API only; implementations must recheck authority and restore partial live changes on error. */
        CompletableFuture<List<Map<String,Object>>> applyDistances(int view,int simulation,BooleanSupplier authority);
    }
    public enum Profile {
        CUSTOM("custom",0,0), VANILLA("vanilla",10,10), VANILLA_PERFORMANCE("vanilla-performance",10,8), VANILLA_THROUGHPUT("vanilla-throughput",0,0), OPTIMIZED("optimized",8,6), MAXIMUM_OPTIMIZED("maximum-optimized",0,0);
        final String id;final int view,simulation;
        Profile(String id,int view,int simulation){this.id=id;this.view=view;this.simulation=simulation;}
        boolean preservesDistances(){return this==CUSTOM||this==VANILLA_THROUGHPUT||this==MAXIMUM_OPTIMIZED;}
        static Profile parse(String id){for(Profile p:values())if(p.id.equals(id))return p;throw new IllegalArgumentException("Unknown kernel profile");}
    }
    private record Target(String relative,KernelConfigFiles.Kind kind,List<KernelConfigFiles.Edit> edits) {}
    private record Prepared(Target target,byte[] before,byte[] after,List<Map<String,Object>> changes) {}
    private record Plan(String id,String actor,Profile profile,long expires,String stateSha,RuntimeState runtime,List<Prepared> files) {}
    private record RejectedPlan(String actor,long until,String reason) {}
    private record StoredState(Map<String,Object> values,String sha) {}
    /** HTTP integration maps this genuine stale-plan condition to 409. Invalid input remains 400. */
    public static final class ProfileConflictException extends IllegalStateException {public ProfileConflictException(String message){super(message);}}
    static final int MAX_FILES=132,MAX_TOTAL_BYTES=8_388_608;
    static final long PLAN_TTL=300_000;
    private final Path root,data,stateFile;
    private final RuntimeAccess runtime;
    private final boolean startupHook;
    private final Clock clock;
    private final ThreadPoolExecutor worker;
    private final Set<CompletableFuture<?>> pending=ConcurrentHashMap.newKeySet();
    private final Map<String,Plan> plans=new LinkedHashMap<>();
    private final Map<String,RejectedPlan> rejectedPlans=new LinkedHashMap<>();
    private final FileChannel runtimeChannel;
    private final FileLock runtimeLock;
    private final AtomicBoolean leaseReleased=new AtomicBoolean();
    private volatile boolean closed;
    private volatile String startupMessage="";

    public KernelProfiles(Path serverRoot,Path pluginData,RuntimeAccess runtime,boolean startupHookConfigured)throws IOException {
        this(serverRoot,pluginData,runtime,startupHookConfigured,Clock.systemUTC());
    }
    KernelProfiles(Path serverRoot,Path pluginData,RuntimeAccess runtime,boolean startupHookConfigured,Clock clock)throws IOException {
        this.root=serverRoot.toRealPath();Path desired=pluginData.toAbsolutePath().normalize().resolve("kernel-profiles");
        if(!desired.startsWith(root))throw new IOException("Profile data must be inside the server directory");
        checkAncestors(root,desired);this.data=privateDirectory(desired);if(!data.startsWith(root))throw new IOException("Profile data must be inside the server directory");
        this.stateFile=data.resolve("profile.json");this.runtime=Objects.requireNonNull(runtime);this.startupHook=startupHookConfigured;this.clock=clock;
        runtimeChannel=FileChannel.open(data.resolve("runtime.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        try{runtimeLock=runtimeChannel.tryLock();if(runtimeLock==null)throw new IOException("Kernel profile runtime already owns this directory");}
        catch(IOException|RuntimeException e){runtimeChannel.close();throw e;}
        worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),r->{Thread t=new Thread(r,"mcc-kernel-profiles");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    }
    public CompletableFuture<Map<String,Object>> status(){return submit(()->status0(await(runtime.snapshot())));}
    public CompletableFuture<Map<String,Object>> plan(String actor,String id,BooleanSupplier authority){return submit(()->{
        permit(authority);validateActor(actor);Profile profile=Profile.parse(id);RuntimeState snapshot=await(runtime.snapshot());
        StoredState selected=readStoredState(stateFile);List<Prepared> files=profile==Profile.CUSTOM?List.of():prepare(root,snapshot,profile);
        permit(authority);for(Iterator<Plan> i=plans.values().iterator();i.hasNext();){Plan old=i.next();if(old.expires<clock.millis()){reject(old,"Profile plan expired; create another plan");i.remove();}}if(plans.size()>=8)throw new IllegalStateException("Too many pending profile plans");
        String planId=UUID.randomUUID().toString();Plan p=new Plan(planId,actor,profile,clock.millis()+PLAN_TTL,selected.sha,snapshot,files);plans.put(planId,p);
        Map<String,Object> out=planDto(p);out.put("currentProfile",selected.values.getOrDefault("profile","custom"));out.put("warnings",warnings(snapshot,startupHook));return out;
    });}
    public CompletableFuture<Map<String,Object>> apply(String actor,String planId,boolean confirmed,BooleanSupplier authority){return submit(()->{
        permit(authority);validateActor(actor);if(!confirmed)throw new IllegalArgumentException("Profile confirmation required");
        Plan p=plans.get(planId);if(p==null){RejectedPlan old=rejectedPlans.get(planId);if(old!=null&&old.until>=clock.millis()&&old.actor.equals(actor))throw new ProfileConflictException(old.reason);throw new IllegalArgumentException("Unknown profile plan");}if(!p.actor.equals(actor))throw new IllegalArgumentException("Unknown profile plan");plans.remove(planId);reject(p,"Profile plan already consumed; refresh and create another plan");
        if(p.expires<clock.millis()){reject(p,"Profile plan expired; create another plan");throw new ProfileConflictException("Profile plan expired; create another plan");}
        RuntimeState now=await(runtime.snapshot());if(!sameRuntime(p.runtime,now))throw new ProfileConflictException("Kernel or world configuration changed; create another plan");
        try(FileChannel c=FileChannel.open(data.resolve("apply.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);FileLock ignored=c.lock()){
            StoredState selected=readStoredState(stateFile);if(!selected.sha.equals(p.stateSha))throw new ProfileConflictException("Profile selection changed; create another plan");
            verifySources(root,p.files);permit(authority);
            boolean pending=p.profile!=Profile.CUSTOM&&(p.files.stream().anyMatch(f->!f.changes.isEmpty())||Boolean.TRUE.equals(selected.values.get("pendingRestart")));
            Map<String,Object> next=state(p.profile,now,actor,clock.millis());next.put("pendingRestart",pending);
            String backup=commit(root,data,stateFile,p.stateSha,p.files,next,()->{permit(authority);return true;});
            List<Map<String,Object>> live=List.of();String liveError="";
            // Files are a durable, intentional next-boot configuration. A public API failure is visible, never a fake live success.
            if(!p.profile.preservesDistances()&&now.liveDistances&&knownKernel(now.kernel)){
                try{live=await(runtime.applyDistances(p.profile.view,p.profile.simulation,()->!closed&&authority.getAsBoolean()));}
                catch(Exception e){liveError="Live distance change failed; inspect actual distances before restarting the saved profile";}
            }
            Map<String,Object> out=status0(await(runtime.snapshot()));out.put("backupId",backup);out.put("liveApplied",live);out.put("liveError",liveError);out.put("saved",true);return out;
        }
    });}
    /** Run asynchronously from onEnable. Custom leaves all kernel files byte-identical. */
    public CompletableFuture<Map<String,Object>> reconcileStartup(){return submit(()->{
        RuntimeState snapshot=await(runtime.snapshot());StoredState stored=readStoredState(stateFile);Map<String,Object> selected=stored.values;
        Profile profile=Profile.parse(String.valueOf(selected.getOrDefault("profile","custom")));
        if(profile==Profile.CUSTOM)return status0(snapshot);
        checkStoredIdentity(selected,snapshot.kernel,snapshot.minecraftVersion);
        try(FileChannel c=FileChannel.open(data.resolve("apply.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);FileLock ignored=c.lock()){
            List<Prepared> files=prepare(root,snapshot,profile);boolean drift=files.stream().anyMatch(f->!f.changes.isEmpty());
            Map<String,Object> next=state(profile,snapshot,String.valueOf(selected.getOrDefault("selectedBy","startup")),clock.millis());next.put("pendingRestart",drift);
            commit(root,data,stateFile,stored.sha,files,next,()->{if(closed)throw new CancellationException("MCC stopped");return true;});
            if(!profile.preservesDistances()&&snapshot.liveDistances)await(runtime.applyDistances(profile.view,profile.simulation,()->!closed));
            startupMessage=drift?"Managed settings were restored after the kernel read its files; restart is required":"";
            return status0(await(runtime.snapshot()));
        }
    });}

    private Map<String,Object> status0(RuntimeState snapshot)throws Exception {
        StoredState stored=readStoredState(stateFile);Map<String,Object> selected=stored.values;String id=String.valueOf(selected.getOrDefault("profile","custom"));
        boolean supported=knownKernel(snapshot.kernel);String reason="";List<Map<String,Object>> files=new ArrayList<>();
        if(supported){try{for(Target target:targets(root,snapshot,Profile.VANILLA)){Path file=resolve(root,target.relative);byte[] bytes=bounded(file,KernelConfigFiles.MAX_BYTES);KernelConfigFiles.merge(target.kind,bytes,target.edits);files.add(map("path",target.relative,"sha256",sha(bytes)));}}catch(IOException|IllegalArgumentException e){supported=false;reason=e.getMessage();}}
        else reason="Unknown or unsupported kernel; custom/files mode is preserved";
        List<Map<String,Object>> profiles=new ArrayList<>();for(Profile p:Profile.values())profiles.add(map("id",p.id,"available",p==Profile.CUSTOM||supported&&!(p==Profile.VANILLA_THROUGHPUT&&snapshot.kernel.equalsIgnoreCase("spigot")),"managed",p!=Profile.CUSTOM,"viewDistance",p.preservesDistances()?null:p.view,"simulationDistance",p.preservesDistances()?null:p.simulation,"preservesDistances",p.preservesDistances()));
        return map("kernel",snapshot.kernel,"minecraftVersion",snapshot.minecraftVersion,"profile",id,"managed",!id.equals("custom"),"pendingRestart",Boolean.TRUE.equals(selected.get("pendingRestart")),"revision",stored.sha,"capabilities",map("managedFiles",supported,"liveDistances",supported&&snapshot.liveDistances,"startupHookConfigured",startupHook,"startupReapply",startupHook?"before-kernel":"after-kernel-read","reason",reason),"profiles",profiles,"files",files,"warnings",warnings(snapshot,startupHook),"startupMessage",startupMessage,"worlds",snapshot.worlds.stream().map(w->map("name",w.name,"viewDistance",w.viewDistance,"simulationDistance",w.simulationDistance)).toList());
    }
    private static Map<String,Object> planDto(Plan p){List<Map<String,Object>> files=new ArrayList<>();for(Prepared f:p.files)files.add(map("path",f.target.relative,"beforeSha256",sha(f.before),"afterSha256",sha(f.after),"changes",f.changes));return map("planId",p.id,"profile",p.profile.id,"expiresAt",p.expires,"files",files,"liveDistances",!p.profile.preservesDistances()&&p.runtime.liveDistances,"preservesDistances",p.profile.preservesDistances(),"pendingRestart",p.files.stream().anyMatch(f->!f.changes.isEmpty()),"customPreservesFiles",p.profile==Profile.CUSTOM,"tradeoffs",tradeoffs(p.profile,p.runtime));}
    private static List<String> tradeoffs(Profile profile,RuntimeState runtime){
        if(profile==Profile.VANILLA_THROUGHPUT)return List.of("View and simulation distances, entity activation, mob spawning, villager ticking, item merging, collisions and all gamerules are preserved.","Redstone dust uses VANILLA and pathfinding updates remain enabled.","Autosaves are spread across ticks (24 chunks per tick); the save interval and full shutdown save remain unchanged.","The automatic Paper chunk worker and IO settings remain upstream defaults; no fixed excessive thread count is imposed.");
        if(profile!=Profile.MAXIMUM_OPTIMIZED)return List.of();
        List<String> out=new ArrayList<>(List.of("Current view and simulation distances are preserved in properties, world overrides and the live API.","Reduced entity activation, inactive villager ticking disabled and spawner AI disabled can change farms and villager restocking.","A shorter spawn radius, larger item merging and fewer entity collisions change gameplay near crowded farms."));
        out.add(runtime.kernel.equalsIgnoreCase("spigot")?"Spigot has no supported redstone implementation setting; that optimization is skipped.":"Alternate Current changes redstone dust update order. Existing circuits may behave differently; test them before adoption.");return List.copyOf(out);
    }
    private static List<String> warnings(RuntimeState runtime,boolean hook){List<String> out=new ArrayList<>();out.add("Vanilla means the managed vanilla-like settings, not exact Mojang behavior; plugins and unsupported exploits are untouched.");out.add("Managed YAML is merged semantically; comments and formatting may be normalized. Exact original bytes are backed up privately.");out.add("Optimized changes entity activation, spawn radius and redstone behavior; benchmark your farms before adopting it.");if(!runtime.liveDistances)out.add("This kernel lacks public per-world distance setters; distance changes require restart.");if(!hook)out.add("No prelaunch hook is configured; startup file drift is restored after kernel initialization and remains pending-restart.");return List.copyOf(out);}
    private static Map<String,Object> state(Profile p,RuntimeState r,String actor,long at){return map("schema",1,"profile",p.id,"kernel",r.kernel,"minecraftVersion",r.minecraftVersion,"selectedBy",actor,"selectedAt",at,"worldFolders",r.worlds.stream().map(w->w.folder.toAbsolutePath().normalize().toString()).toList());}
    private static boolean sameRuntime(RuntimeState a,RuntimeState b){return a.kernel.equals(b.kernel)&&a.minecraftVersion.equals(b.minecraftVersion)&&a.worlds.stream().map(w->w.folder.toAbsolutePath().normalize()).toList().equals(b.worlds.stream().map(w->w.folder.toAbsolutePath().normalize()).toList());}
    private static void checkStoredIdentity(Map<String,Object> selected,String kernel,String version)throws IOException {if(!kernel.equals(selected.get("kernel"))||!version.equals(selected.get("minecraftVersion"))||!knownKernel(kernel))throw new IOException("Kernel identity changed; managed startup refused");}
    static boolean knownKernel(String name){return Set.of("paper","purpur","spigot").contains(name.toLowerCase(Locale.ROOT));}
    private static List<Prepared> prepare(Path root,RuntimeState r,Profile p)throws IOException {
        if(!knownKernel(r.kernel))throw new IOException("Managed profiles are unsupported on this kernel");
        List<Prepared> out=new ArrayList<>();long total=0;
        for(Target t:targets(root,r,p)){byte[] before=bounded(resolve(root,t.relative),KernelConfigFiles.MAX_BYTES);KernelConfigFiles.Merged result=KernelConfigFiles.merge(t.kind,before,t.edits);total+=before.length+result.bytes().length;if(total>MAX_TOTAL_BYTES)throw new IOException("Profile configuration set exceeds 8 MiB");out.add(new Prepared(t,before,result.bytes(),result.changes()));}
        return List.copyOf(out);
    }
    private static List<Target> targets(Path root,RuntimeState r,Profile p)throws IOException {
        if(p==Profile.CUSTOM)return List.of();if(!knownKernel(r.kernel))throw new IOException("Unsupported kernel");
        if(p==Profile.VANILLA_THROUGHPUT){
            // A safe throughput preset never trims simulation or rewrites farm, spawn, activation or redstone timing settings.
            if(r.kernel.equalsIgnoreCase("spigot"))throw new IOException("Vanilla throughput requires Paper chunk save configuration");
            List<KernelConfigFiles.Edit> safe=List.of(edit("VANILLA","misc","redstone-implementation"),edit(true,"misc","update-pathfinding-on-block-update"),edit(24,"chunks","max-auto-save-chunks-per-tick"));
            List<Target> safeTargets=new ArrayList<>();safeTargets.add(new Target("config/paper-world-defaults.yml",KernelConfigFiles.Kind.PAPER,safe));Set<Path> seen=new HashSet<>();
            for(WorldState world:r.worlds){Path folder=world.folder.toAbsolutePath().normalize();if(!folder.startsWith(root))throw new IOException("World configuration is outside server directory");Path file=folder.resolve("paper-world.yml");if(Files.exists(file,LinkOption.NOFOLLOW_LINKS)&&seen.add(file))safeTargets.add(new Target(root.relativize(file).toString().replace('\\','/'),KernelConfigFiles.Kind.PAPER,safe));}
            if(safeTargets.size()>MAX_FILES)throw new IOException("Too many kernel configuration files");return List.copyOf(safeTargets);
        }
        List<Target> out=new ArrayList<>();if(!p.preservesDistances())out.add(new Target("server.properties",KernelConfigFiles.Kind.PROPERTIES,List.of(edit(p.view,"view-distance"),edit(p.simulation,"simulation-distance"))));
        Path spigot=resolve(root,"spigot.yml");Map<String,Object> spigotData=KernelConfigFiles.readYaml(KernelConfigFiles.Kind.SPIGOT,bounded(spigot,KernelConfigFiles.MAX_BYTES));
        Object settings=spigotData.get("world-settings");if(!(settings instanceof Map<?,?> worlds)||worlds.size()>129)throw new IOException("Unsupported Spigot world-settings layout");
        Set<String> names=new LinkedHashSet<>();names.add("default");for(Object key:worlds.keySet())if(key instanceof String name)names.add(name);else throw new IOException("Unsupported Spigot world name");
        List<KernelConfigFiles.Edit> edits=new ArrayList<>();boolean maximum=p==Profile.MAXIMUM_OPTIMIZED,optimized=p==Profile.OPTIMIZED||maximum;
        for(String world:names){String[] base={"world-settings",world};
            if(!p.preservesDistances()){edits.add(edit(p.view,base,"view-distance"));edits.add(edit(p.simulation,base,"simulation-distance"));}edits.add(edit(maximum?4:optimized?6:8,base,"mob-spawn-range"));
            edits.add(edit(!maximum,base,"tick-inactive-villagers"));edits.add(edit(maximum,base,"nerf-spawner-mobs"));edits.add(edit(maximum?3.0:optimized?2.5:0.5,base,"merge-radius","item"));edits.add(edit(maximum?2:8,base,"max-entity-collisions"));
            String[] categories={"animals","monsters","raiders","misc","water","villagers","flying-monsters"};int[] ranges=maximum?new int[]{16,16,32,8,8,16,16}:new int[]{32,32,64,16,16,32,32};
            for(int i=0;i<categories.length;i++)edits.add(edit(optimized?ranges[i]:0,base,"entity-activation-range",categories[i]));
        }
        out.add(new Target("spigot.yml",KernelConfigFiles.Kind.SPIGOT,List.copyOf(edits)));
        if(!r.kernel.equalsIgnoreCase("spigot")){
            List<KernelConfigFiles.Edit> paper=List.of(edit(optimized?"ALTERNATE_CURRENT":"VANILLA","misc","redstone-implementation"),edit(!optimized,"misc","update-pathfinding-on-block-update"),edit(p!=Profile.VANILLA,"environment","optimize-explosions"),edit(p==Profile.VANILLA?200:24,"chunks","max-auto-save-chunks-per-tick"),edit(p==Profile.VANILLA?"0s":"10s","chunks","delay-chunk-unloads-by"),edit(!optimized,"entities","behavior","only-merge-items-horizontally"),edit(maximum?2:8,"collisions","max-entity-collisions"));
            out.add(new Target("config/paper-world-defaults.yml",KernelConfigFiles.Kind.PAPER,paper));Set<Path> seen=new HashSet<>();
            for(WorldState world:r.worlds){Path folder=world.folder.toAbsolutePath().normalize();if(!folder.startsWith(root))throw new IOException("World configuration is outside server directory");Path file=folder.resolve("paper-world.yml");if(Files.exists(file,LinkOption.NOFOLLOW_LINKS)&&seen.add(file)){String relative=root.relativize(file).toString().replace('\\','/');out.add(new Target(relative,KernelConfigFiles.Kind.PAPER,paper));}}
        }
        if(r.kernel.equalsIgnoreCase("purpur"))out.add(new Target("purpur.yml",KernelConfigFiles.Kind.PURPUR,List.of(edit(false,"settings","use-alternate-keepalive"),edit(!optimized,"settings","tps-catchup"))));
        if(out.size()>MAX_FILES)throw new IOException("Too many kernel configuration files");return List.copyOf(out);
    }
    private static KernelConfigFiles.Edit edit(Object value,String...parts){return new KernelConfigFiles.Edit(List.of(parts),value);}
    private static KernelConfigFiles.Edit edit(Object value,String[] prefix,String...tail){List<String> p=new ArrayList<>(List.of(prefix));p.addAll(List.of(tail));return new KernelConfigFiles.Edit(p,value);}

    private static String commit(Path root,Path data,Path stateFile,String reviewedStateSha,List<Prepared> files,Map<String,Object> next,BooleanSupplier authority)throws Exception {
        checkAuthority(authority);verifySources(root,files);byte[] oldState=Files.exists(stateFile)?bounded(stateFile,65536):null;
        if(!(oldState==null?"missing":sha(oldState)).equals(reviewedStateSha))throw new ProfileConflictException("Profile metadata changed; create another plan");
        String id=UUID.randomUUID().toString();Path backup=privateDirectory(privateDirectory(data.resolve("backups")).resolve(id));
        List<Map<String,Object>> inventory=new ArrayList<>();List<Prepared> written=new ArrayList<>();byte[] stateBytes=Json.write(next).getBytes(java.nio.charset.StandardCharsets.UTF_8);if(stateBytes.length>65536)throw new IOException("Profile metadata exceeds bound");
        boolean stateWritten=false;
        try {
            for(Prepared f:files)if(!f.changes.isEmpty()){checkAuthority(authority);Path copy=backup.resolve(f.target.relative);privateDirectory(copy.getParent());writePrivateNew(copy,f.before);inventory.add(map("path",f.target.relative,"sha256",sha(f.before)));}
            if(oldState!=null){checkAuthority(authority);writePrivateNew(backup.resolve("previous-profile.json"),oldState);}
            checkAuthority(authority);writePrivateNew(backup.resolve("inventory.json"),Json.write(inventory).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            for(Prepared f:files)if(!f.changes.isEmpty()){checkAuthority(authority);Path file=resolve(root,f.target.relative);if(!shaFile(file).equals(sha(f.before)))throw new ProfileConflictException("Kernel configuration changed during apply");atomicReplace(file,f.after);written.add(f);checkAuthority(authority);}
            checkAuthority(authority);if(!shaFile(stateFile).equals(reviewedStateSha))throw new ProfileConflictException("Profile metadata changed during apply");atomicReplace(stateFile,stateBytes);stateWritten=true;checkAuthority(authority);return id;
        }catch(Exception failure){
            // A shutdown interrupt must not itself abort the recovery writes.
            boolean interrupted=Thread.interrupted();boolean incomplete=false;
            try{
                if(stateWritten)try{if(shaFile(stateFile).equals(sha(stateBytes))){if(oldState==null)Files.delete(stateFile);else atomicReplace(stateFile,oldState);}else incomplete=true;}catch(Exception rollback){incomplete=true;failure.addSuppressed(rollback);}
                Collections.reverse(written);for(Prepared f:written)try{Path file=resolve(root,f.target.relative);if(shaFile(file).equals(sha(f.after)))atomicReplace(file,f.before);else incomplete=true;}catch(Exception rollback){incomplete=true;failure.addSuppressed(rollback);}
                if(incomplete)throw new IOException("Profile rollback needs reconciliation; inspect private backup "+id,failure);throw failure;
            }finally{if(interrupted)Thread.currentThread().interrupt();}
        }
    }
    private void reject(Plan p,String reason){rejectedPlans.values().removeIf(old->old.until<clock.millis());if(rejectedPlans.size()>=64)rejectedPlans.remove(rejectedPlans.keySet().iterator().next());rejectedPlans.put(p.id,new RejectedPlan(p.actor,clock.millis()+PLAN_TTL,reason));}
    private static void verifySources(Path root,List<Prepared> files)throws IOException {for(Prepared f:files)if(!shaFile(resolve(root,f.target.relative)).equals(sha(f.before)))throw new ProfileConflictException("Kernel configuration changed; create another plan");}
    static Path resolve(Path root,String relative)throws IOException {
        Path file=root.resolve(relative).normalize();if(!file.startsWith(root)||file.equals(root))throw new IOException("Kernel path escaped server directory");checkAncestors(root,file);
        Path real=file.toRealPath();if(!real.startsWith(root)||Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("Kernel configuration link or path is unsupported");
        return file;
    }
    private static void checkAncestors(Path root,Path file)throws IOException {Path current=root;for(Path part:root.relativize(file)){current=current.resolve(part);if(Files.exists(current,LinkOption.NOFOLLOW_LINKS)&&(Files.isSymbolicLink(current)||!current.toRealPath().startsWith(root)))throw new IOException("Kernel path link is unsupported");}}
    static byte[] bounded(Path file,int max)throws IOException {BasicFileAttributes a=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);if(!a.isRegularFile()||a.size()>max)throw new IOException("Configuration file size or type is unsupported");byte[] bytes=Files.readAllBytes(file);if(bytes.length>max)throw new IOException("Configuration grew beyond bound");return bytes;}
    static Map<String,Object> readState(Path file)throws IOException {return readStoredState(file).values;}
    private static StoredState readStoredState(Path file)throws IOException {if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return new StoredState(map("schema",1,"profile","custom","pendingRestart",false),"missing");if(Files.isSymbolicLink(file))throw new IOException("Profile metadata link is unsupported");try{byte[] bytes=bounded(file,65536);Map<String,Object> d=Json.object(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));if(!Objects.equals(d.get("schema"),1))throw new IOException("Unsupported profile metadata schema");Profile.parse(String.valueOf(d.get("profile")));return new StoredState(d,sha(bytes));}catch(IllegalArgumentException e){throw new IOException("Invalid profile metadata",e);}}
    static String shaFile(Path path)throws IOException{return Files.exists(path,LinkOption.NOFOLLOW_LINKS)?sha(bounded(path,KernelConfigFiles.MAX_BYTES)):"missing";}
    static String sha(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
    static Path privateDirectory(Path path)throws IOException {Files.createDirectories(path);Path real=path.toRealPath();if(Files.isSymbolicLink(path))throw new IOException("Profile directory link is unsupported");PrivateFiles.restrict(real,true);return real;}
    static void writePrivateNew(Path path,byte[] bytes)throws IOException {Files.createFile(path);PrivateFiles.restrict(path,false);try(FileChannel c=FileChannel.open(path,StandardOpenOption.WRITE)){ByteBuffer b=ByteBuffer.wrap(bytes);while(b.hasRemaining())c.write(b);c.force(true);}if(!shaFile(path).equals(sha(bytes)))throw new IOException("Private configuration backup verification failed");}
    static void atomicReplace(Path file,byte[] bytes)throws IOException {
        Path temp=file.resolveSibling("."+file.getFileName()+".mcc-"+UUID.randomUUID()+".tmp");Files.createFile(temp);PrivateFiles.restrict(temp,false);
        try{try(FileChannel c=FileChannel.open(temp,StandardOpenOption.WRITE)){ByteBuffer b=ByteBuffer.wrap(bytes);while(b.hasRemaining())c.write(b);c.force(true);}if(!shaFile(temp).equals(sha(bytes)))throw new IOException("Staged configuration verification failed");
            if(Files.exists(file,LinkOption.NOFOLLOW_LINKS)){var posix=Files.getFileAttributeView(file,java.nio.file.attribute.PosixFileAttributeView.class);if(posix!=null)Files.setPosixFilePermissions(temp,posix.readAttributes().permissions());var acl=Files.getFileAttributeView(file,java.nio.file.attribute.AclFileAttributeView.class);if(acl!=null)Files.getFileAttributeView(temp,java.nio.file.attribute.AclFileAttributeView.class).setAcl(acl.getAcl());}
            Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(temp);}
    }
    private void permit(BooleanSupplier authority){if(closed||Thread.currentThread().isInterrupted())throw new CancellationException("Kernel profile service stopped");checkAuthority(authority);}
    private static void checkAuthority(BooleanSupplier authority){if(!authority.getAsBoolean())throw new SecurityException("Active Owner authority required");}
    private static void validateActor(String actor){if(actor==null||actor.isBlank()||actor.length()>128)throw new IllegalArgumentException("Invalid profile actor");}
    private <T>CompletableFuture<T> submit(CheckedSupplier<T> job){CompletableFuture<T> f=new CompletableFuture<>();pending.add(f);f.whenComplete((v,e)->pending.remove(f));try{worker.execute(()->{if(f.isDone())return;try{f.complete(job.get());}catch(Throwable e){f.completeExceptionally(e);}});}catch(RejectedExecutionException e){f.completeExceptionally(new IllegalStateException("Kernel profile queue unavailable"));}return f;}
    @FunctionalInterface private interface CheckedSupplier<T>{T get()throws Exception;}
    private static <T>T await(CompletableFuture<T> f)throws Exception {try{return f.get(30,TimeUnit.SECONDS);}catch(TimeoutException e){if(f.cancel(false))throw new IllegalStateException("Kernel runtime operation expired before execution");return f.get();}catch(ExecutionException e){if(e.getCause()instanceof Exception x)throw x;throw e;}}
    static Map<String,Object> map(Object...pairs){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)m.put(String.valueOf(pairs[i]),pairs[i+1]);return m;}
    @Override public void close(){closed=true;worker.shutdownNow();for(var f:pending)f.completeExceptionally(new CancellationException("Kernel profile service stopped"));
        // Never release the offline-bootstrap lease while a file writer/rollback still runs.
        if(worker.isTerminated()){releaseLease();return;}
        Thread cleanup=new Thread(()->{boolean interrupted=false;while(!worker.isTerminated())try{worker.awaitTermination(1,TimeUnit.SECONDS);}catch(InterruptedException e){interrupted=true;}releaseLease();if(interrupted)Thread.currentThread().interrupt();},"mcc-kernel-profile-close");cleanup.setDaemon(true);cleanup.start();
    }
    private void releaseLease(){if(leaseReleased.compareAndSet(false,true))try{runtimeLock.release();runtimeChannel.close();}catch(IOException ignored){}}

    /** Caller must hold its normal offline/supervisor lease. This prelaunch path needs no Bukkit classes. */
    static void adoptCoreIdentityOffline(Path root,Path pluginData,Path backup)throws Exception {
        Path stateFile=pluginData.resolve("kernel-profiles/profile.json");if(!Files.exists(stateFile,LinkOption.NOFOLLOW_LINKS))return;
        MccCoreInstaller.checkPath(root,stateFile,false);MccCoreInstaller.checkPath(root,backup,true);
        StoredState stored=readStoredState(stateFile);Map<String,Object> selected=stored.values;
        if("custom".equals(selected.get("profile"))||"Paper".equals(selected.get("kernel")))return;
        if(!"Purpur".equals(selected.get("kernel"))||!"26.3".equals(selected.get("minecraftVersion")))throw new IOException("Managed profile cannot migrate to this MCC Core foundation");
        byte[] before=bounded(stateFile,65536);Path previous=backup.resolve("previous-kernel-profile.json");
        if(Files.exists(previous,LinkOption.NOFOLLOW_LINKS)){MccCoreInstaller.checkPath(root,previous,false);if(!sha(bounded(previous,65536)).equals(sha(before)))throw new IOException("Core profile migration backup differs");}
        else writePrivateNew(previous,before);
        Map<String,Object> next=new LinkedHashMap<>(selected);next.put("kernel","Paper");next.put("migratedFromKernel","Purpur");next.put("mccCoreMigrationAt",System.currentTimeMillis());
        byte[] after=Json.write(next).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if(!shaFile(stateFile).equals(stored.sha))throw new IOException("Managed profile changed during Core migration");
        atomicReplace(stateFile,after);
        writePrivateNew(backup.resolve("profile-migration.json"),Json.write(map("beforeSha256",sha(before),"afterSha256",sha(after),"from","Purpur","to","Paper","worldDataChanged",false)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    /** Caller must hold its normal offline/supervisor lease. This prelaunch path needs no Bukkit classes. */
    public static Map<String,Object> reapplyOffline(Path serverRoot,Path pluginData,String kernel,String minecraftVersion)throws Exception {
        Path root=serverRoot.toRealPath();Path data=pluginData.resolve("kernel-profiles");if(!Files.exists(data))return map("profile","custom","changedFiles",0);
        data=data.toRealPath();if(!data.startsWith(root))throw new IOException("Profile data is outside server directory");Path stateFile=data.resolve("profile.json");StoredState stored=readStoredState(stateFile);Map<String,Object> selected=stored.values;Profile profile=Profile.parse(String.valueOf(selected.getOrDefault("profile","custom")));if(profile==Profile.CUSTOM)return map("profile","custom","changedFiles",0);
        checkStoredIdentity(selected,kernel,minecraftVersion);
        List<WorldState> worlds=new ArrayList<>();Object paths=selected.get("worldFolders");if(!(paths instanceof List<?> l)||l.size()>128)throw new IOException("Invalid managed world folders");for(Object path:l){if(!(path instanceof String s)||s.length()>1024)throw new IOException("Invalid managed world path");worlds.add(new WorldState("offline",Path.of(s),0,0));}
        RuntimeState r=new RuntimeState(kernel,minecraftVersion,false,worlds);
        try(FileChannel lease=FileChannel.open(data.resolve("runtime.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);FileLock guard=lease.tryLock()){
            if(guard==null)throw new IOException("Managed profile runtime is still active");
            try(FileChannel c=FileChannel.open(data.resolve("apply.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);FileLock ignored=c.lock()){
                List<Prepared> files=prepare(root,r,profile);Map<String,Object> next=new LinkedHashMap<>(selected);next.put("pendingRestart",false);next.put("lastPrelaunchAt",System.currentTimeMillis());String backup=commit(root,data,stateFile,stored.sha,files,next,()->true);
                return map("profile",profile.id,"changedFiles",files.stream().filter(f->!f.changes.isEmpty()).count(),"backupId",backup,"pendingRestart",false);
            }
        }
    }
}
