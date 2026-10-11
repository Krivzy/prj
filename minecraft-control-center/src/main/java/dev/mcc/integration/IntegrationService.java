package dev.mcc.integration;

import dev.mcc.core.Auth;
import dev.mcc.core.MainThread;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/** Optional public-API integrations. Discovery/locations stay on Bukkit's main thread. */
public final class IntegrationService implements AutoCloseable {
    public record Binding(String id,String title,String version,Object api,ClassLoader loader,Object identity,String unavailable) {}
    public interface Runtime {
        List<Binding> discover()throws Exception;
        boolean active(Binding binding)throws Exception;
        Location location(String world,int x,int y,int z)throws Exception;
        default boolean playerMatches(String name,UUID uuid)throws Exception{return false;}
    }
    private final Runtime runtime;
    private final BiPredicate<Auth.Session,Boolean> authority;
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(16),r->{Thread t=new Thread(r,"MCC-integrations");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final AtomicBoolean closed=new AtomicBoolean();
    private final Set<CompletableFuture<?>> pending=ConcurrentHashMap.newKeySet();
    private final Object[] stripes=new Object[32];
    private IntegrationPresence presence;
    public IntegrationService(JavaPlugin plugin,MainThread main,BiPredicate<Auth.Session,Boolean> authority){this(new BukkitRuntime(main),authority);presence=new IntegrationPresence(plugin);}
    public IntegrationService(Runtime runtime,BiPredicate<Auth.Session,Boolean> authority){this.runtime=Objects.requireNonNull(runtime);this.authority=Objects.requireNonNull(authority);Arrays.setAll(stripes,i->new Object());}
    public CompletableFuture<Map<String,Object>> discover(Auth.Session actor){return submit(()->{
        authorize(actor,false);List<Map<String,Object>> items=new ArrayList<>();String punishment="builtin";
        for(Binding b:runtime.discover()){
            if(b.id().equals("advancedban"))punishment=b.unavailable().isEmpty()?"advancedban":"unavailable";
            items.add(Map.of("id",b.id(),"title",b.title(),"version",b.version(),"available",b.unavailable().isEmpty(),"message",b.unavailable(),"capabilities",capabilities(b)));
        }
        authorize(actor,false);return Map.of("items",items,"punishmentProvider",punishment);
    });}
    /** Must gate built-in punishment startup/enforcement as well as UI; unavailable AB still owns moderation. */
    public CompletableFuture<String> punishmentProvider(Auth.Session actor){return discover(actor).thenApply(value->String.valueOf(value.get("punishmentProvider")));}
    /** Internal lifecycle lookup; do not block on it from the server thread. */
    public CompletableFuture<String> punishmentProvider(){return submit(()->{for(Binding b:runtime.discover())if(b.id().equals("advancedban"))return b.unavailable().isEmpty()?"advancedban":"unavailable";return "builtin";});}
    /** True for enabled AB even if its optional API is unsupported; never block game listeners. */
    public BooleanSupplier externalPunishments(){return ()->closed.get()||presence!=null&&presence.getAsBoolean();}
    public CompletableFuture<Map<String,Object>> query(Auth.Session actor,String id,Map<String,Object> body){Map<String,Object> input=copy(body);return submit(()->{
        Binding binding=bind(actor,id,false);Map<String,Object> result=switch(id){
            case "luckperms"->new LuckPermsIntegration(binding).query(input);
            case "coreprotect"->CoreProtectIntegration.lookup(binding,input,runtime);
            case "advancedban"->new AdvancedBanIntegration(binding).query(input);
            case "grim"->GrimIntegration.query(binding,input);
            default->throw new IllegalArgumentException("Unknown integration");
        };authorize(actor,false);if(!runtime.active(binding))throw new IllegalStateException("Plugin is no longer enabled");return result;
    });}
    public CompletableFuture<Map<String,Object>> mutate(Auth.Session actor,String id,Map<String,Object> body){Map<String,Object> input=copy(body);return submit(()->{
        IntegrationValidation.confirmed(input);Binding binding=bind(actor,id,true);
        Runnable guard=()->{try{authorize(actor,true);if(closed.get()||!runtime.active(binding))throw new IllegalStateException("Plugin is no longer enabled");}catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException("Plugin availability check failed",e);}};
        // Serialize local edits to each holder; provider/external edits are checked by an optimistic revision.
        String key=id+":"+input.getOrDefault("holderType","")+":"+input.getOrDefault("target",input.getOrDefault("uuid",""));
        synchronized(stripes[(key.hashCode()&Integer.MAX_VALUE)%stripes.length]){
            guard.run();return switch(id){
                case "luckperms"->new LuckPermsIntegration(binding).mutate(input,guard);
                case "advancedban"->{String player=IntegrationValidation.player(input,"player");UUID uuid=IntegrationValidation.uuid(IntegrationValidation.text(input,"uuid","",36));if(!runtime.playerMatches(player,uuid))throw new IllegalArgumentException("Player name and UUID must match a cached server player");yield new AdvancedBanIntegration(binding).mutate(input,actor.name(),guard);}
                default->throw new IllegalArgumentException("This integration is read-only");
            };
        }
    });}
    private Binding bind(Auth.Session actor,String id,boolean write)throws Exception {
        authorize(actor,write);for(Binding binding:runtime.discover())if(binding.id().equals(id)){if(!binding.unavailable().isEmpty())throw new UnsupportedOperationException(binding.unavailable());if(!runtime.active(binding))throw new IllegalStateException("Plugin is disabled");return binding;}throw new IllegalStateException("Plugin is not installed or enabled");
    }
    private void authorize(Auth.Session actor,boolean write){if(closed.get())throw new IllegalStateException("Integrations are stopped");if(actor==null||!(write?actor.role().equals("Owner"):Set.of("Owner","Admin").contains(actor.role()))||!authority.test(actor,write))throw new SecurityException(write?"An active Owner session is required":"An active administrator session is required");}
    private static Map<String,Object> copy(Map<String,Object> body){if(body==null||body.size()>20)throw new IllegalArgumentException("Invalid integration request");Map<String,Object> copy=new LinkedHashMap<>(body);if(copy.get("contexts") instanceof Map<?,?> contexts)copy.put("contexts",Map.copyOf(contexts));return Collections.unmodifiableMap(copy);}
    private static List<String> capabilities(Binding b){if(!b.unavailable().isEmpty())return List.of();return switch(b.id()){case "luckperms"->List.of("groups","loaded-users","user-lookup","nodes","inheritance","edit","create-group");case "coreprotect"->List.of("lookup","player-filter","block-filter","time-filter","pagination");case "advancedban"->List.of("player-punishments","issue","revoke");case "grim"->List.of("player-checks");default->List.of();};}
    private <T> CompletableFuture<T> submit(Callable<T> operation){
        AtomicInteger phase=new AtomicInteger();CompletableFuture<T> result=new CompletableFuture<>(){@Override public boolean cancel(boolean interrupt){return phase.compareAndSet(0,2)&&super.cancel(false);}};
        if(closed.get()){result.completeExceptionally(new IllegalStateException("Integrations are stopped"));return result;}pending.add(result);result.whenComplete((v,e)->pending.remove(result));
        try{workers.execute(()->{if(!phase.compareAndSet(0,1))return;try{if(closed.get())throw new IllegalStateException("Integrations are stopped");result.complete(operation.call());}catch(Throwable e){result.completeExceptionally(e);}});}catch(RejectedExecutionException e){result.completeExceptionally(new IllegalStateException("Integration queue is full; retry later"));}return result;
    }
    @Override public void close(){closed.set(true);if(presence!=null)presence.close();workers.shutdownNow();for(CompletableFuture<?> f:pending)f.cancel(false);try{workers.awaitTermination(1500,TimeUnit.MILLISECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    private static final class BukkitRuntime implements Runtime {
        private final MainThread main;
        BukkitRuntime(MainThread main){this.main=Objects.requireNonNull(main);}
        public List<Binding> discover()throws Exception{return main.await(()->{
            List<Binding> out=new ArrayList<>();for(String name:List.of("LuckPerms","CoreProtect","AdvancedBan","GrimAC")){
                Plugin plugin=Bukkit.getPluginManager().getPlugin(name);if(plugin==null||!plugin.isEnabled())continue;
                String id=name.equals("LuckPerms")?"luckperms":name.equals("CoreProtect")?"coreprotect":name.equals("AdvancedBan")?"advancedban":"grim";
                ClassLoader loader=plugin.getClass().getClassLoader();Object api=null;String error="";
                try{
                    api=switch(id){case "luckperms"->PublicApi.call(PublicApi.type(loader,"net.luckperms.api.LuckPermsProvider"),"get");case "coreprotect"->PublicApi.call(plugin,"getAPI");case "advancedban"->PublicApi.call(PublicApi.type(loader,"me.leoko.advancedban.manager.PunishmentManager"),"get");case "grim"->PublicApi.call(PublicApi.type(loader,"ac.grim.grimac.api.GrimAPIProvider"),"get");default->null;};
                    switch(id){case "luckperms"->LuckPermsIntegration.validate(api,loader);case "coreprotect"->CoreProtectIntegration.validate(api);case "advancedban"->AdvancedBanIntegration.validate(api,loader);case "grim"->GrimIntegration.validate(api,loader);}
                }catch(Exception|LinkageError e){error="Installed plugin API is unsupported or not ready";}
                out.add(new Binding(id,name,plugin.getDescription().getVersion(),api,loader,plugin,error));
            }return List.copyOf(out);
        });}
        public boolean active(Binding b)throws Exception{return main.await(()->{Plugin plugin=Bukkit.getPluginManager().getPlugin(b.title());return plugin==b.identity()&&plugin.isEnabled();});}
        public Location location(String name,int x,int y,int z)throws Exception{return main.await(()->{World world=Bukkit.getWorld(name);if(world==null)throw new IllegalArgumentException("World is not loaded");return new Location(world,x,y,z);});}
        public boolean playerMatches(String name,UUID uuid)throws Exception{return main.await(()->{
            org.bukkit.OfflinePlayer player=Bukkit.getPlayerExact(name);
            if(player==null){
                // Paper exposes a nonblocking cache lookup; Spigot has no equivalent.
                // Only call its public optional boundary, never resolve names over the network.
                try{Object cached=PublicApi.call(Bukkit.getServer(),"getOfflinePlayerIfCached",name);if(cached instanceof org.bukkit.OfflinePlayer known)player=known;}
                catch(UnsupportedOperationException ignored){/* Online-only identity validation on other kernels. */}
                catch(Exception e){throw new IllegalStateException("Cached player identity lookup failed",e);}
            }
            return player!=null&&player.getUniqueId().equals(uuid);
        });}
    }
}
