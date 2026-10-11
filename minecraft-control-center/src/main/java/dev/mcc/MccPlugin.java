package dev.mcc;

import dev.mcc.core.*;
import dev.mcc.game.GameService;
import dev.mcc.game.PunishmentEngine;
import dev.mcc.web.*;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.nio.file.*;
import java.lang.management.ManagementFactory;
import com.sun.management.OperatingSystemMXBean;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class MccPlugin extends JavaPlugin {
    private volatile Database db;private volatile Auth auth;private volatile Realtime realtime;private volatile WebServer web;
    private volatile Automation automation;private volatile Backup backup;private volatile LogTail tail;private volatile PluginStage pluginStage;
    private volatile PluginCatalog pluginCatalog;private volatile List<Map<String,Object>> catalogPlugins=List.of();private String catalogMinecraftVersion;
    private GameService game;private MainThread main;private volatile PunishmentEngine punishments;
    private ScheduledExecutorService background;
    private volatile Map<String,Object> latest=Map.of("status","starting");
    private volatile Map<String,Object> gameSnapshot=Map.of();
    private final ConcurrentHashMap<String,Boolean> modules=new ConcurrentHashMap<>();
    private volatile String profile="balanced";
    private final AtomicBoolean stopping=new AtomicBoolean(),refresh=new AtomicBoolean();
    private final ConcurrentLinkedDeque<Object> chatHistory=new ConcurrentLinkedDeque<>();
    private final AtomicLong snapshotCount=new AtomicLong(),snapshotNanos=new AtomicLong();
    private final long[] snapshotSamples=new long[512];private int sampleCursor,sampleSize;
    private volatile double snapshotP95;
    private int pulses;private long lastMetric, lastCleanup,lastPublishedTimestamp,lastHostMetrics;
    private final Map<String,Object> hostMetrics=new HashMap<>();
    private String bind;private int port;

    @Override public void onEnable(){
        try{
            Files.createDirectories(getDataFolder().toPath());PrivateFiles.restrict(getDataFolder().toPath(),true);
            saveDefaultConfig();for(String directory:List.of("logs","backups","assets-cache"))Files.createDirectories(getDataFolder().toPath().resolve(directory));
            bind=getConfig().getString("web.host","127.0.0.1");port=getConfig().getInt("web.port",8080);
            modules.put("chat",getConfig().getBoolean("modules.chat",true));modules.put("events",getConfig().getBoolean("modules.events",true));modules.put("automations",getConfig().getBoolean("modules.automations",true));modules.put("map",getConfig().getBoolean("modules.map",false));
            profile=getConfig().getString("performance.profile","balanced").toLowerCase(Locale.ROOT);if(!Set.of("eco","balanced","performance").contains(profile))profile="balanced";
            main=new MainThread(this);game=new GameService(this,this::gameEvent);
            catalogMinecraftVersion=Bukkit.getMinecraftVersion();catalogPlugins=List.copyOf(game.plugins());
            game.setConsoleAllowedCommands(new HashSet<>(getConfig().getStringList("console.allowed-commands")));
            background=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"MCC-Background");t.setDaemon(true);return t;});
            background.execute(this::initialize);
            Bukkit.getScheduler().runTaskTimer(this,this::capture,20L,20L);
            getCommand("mcc").setExecutor(this::command);
        }catch(Exception error){getLogger().severe("MCC initialization failed: "+error.getClass().getSimpleName()+": "+error.getMessage());Bukkit.getPluginManager().disablePlugin(this);}
    }
    private void initialize(){
        try{
            db=new Database(getDataFolder().toPath().resolve("database.db"));if(stopping.get()){db.close();return;}
            boolean localOnly=java.net.InetAddress.getByName(bind).isLoopbackAddress()&&!getConfig().getBoolean("web.allow-lan",false)&&getConfig().getString("web.public-origin","").isBlank();
            auth=new Auth(db,getDataFolder().toPath(),getConfig().getBoolean("auth.bootstrap-admin",true),localOnly);realtime=new Realtime(auth);
            punishments=new PunishmentEngine(this,db,main,this::gameEvent);punishments.start().get();
            List<Map<String,Object>> settings=db.documents("settings").get();if(!settings.isEmpty())loadSettings(settings.getFirst());
            Map<UUID,Long> mutes=new HashMap<>();for(Map<String,Object> mute:db.documents("mutes").get())if(mute.get("uuid") instanceof String uuid&&mute.get("expiresAt") instanceof Number expiry)mutes.put(UUID.fromString(uuid),expiry.longValue());
            Map<UUID,String> known=new HashMap<>();for(Map<String,Object> player:db.documents("known-players").get())if(player.get("uuid") instanceof String uuid&&player.get("name") instanceof String name)known.put(UUID.fromString(uuid),name);
            for(var mute:mutes.entrySet())if(mute.getValue()==0||mute.getValue()>System.currentTimeMillis()){
                if(!punishments.muted(mute.getKey()))punishments.importLegacyMute(mute.getKey(),known.getOrDefault(mute.getKey(),mute.getKey().toString()),mute.getValue()).get();
                db.deleteDocument("mutes",mute.getKey().toString()).get();
            }
            main.await(()->{game.setModerationPersistence(this::persistMute);game.setMuteLookup(punishments::muted);game.restoreKnownPlayers(known);game.setEventsEnabled(module("events"));game.setMapEnabled(module("map"));return null;});
            automation=new Automation(db,main,game);automation.setEvents(this::gameEvent);automation.enabled(module("automations"));backup=new Backup(db,getDataFolder().toPath());pluginStage=new PluginStage(db,getDataFolder().toPath());
            pluginCatalog=new PluginCatalog(db,getDataFolder().toPath(),catalogMinecraftVersion,()->catalogPlugins);
            tail=new LogTail(Path.of("logs","latest.log"),realtime);gameSnapshot=main.await(game::snapshot);publishSnapshot();
            if(stopping.get()){closeServices();return;}
            web=new WebServer(this,auth,db,main,game,realtime,bind,port,getConfig().getBoolean("web.allow-lan",false),getConfig().getString("web.public-origin",""),getConfig().getBoolean("web.secure-cookie",false));
            getLogger().info("Minecraft Control Center enabled!");getLogger().info("Dashboard: http://localhost:"+port);
            getLogger().info("REST API: Ready");getLogger().info("WebSocket: Online");getLogger().info("Real-Time Engine: Active");getLogger().info("Performance Guard: Active");
            if(auth.setupRequired())getLogger().info("First Owner: open dashboard locally; setup token is in plugins/MinecraftControlCenter/.setup-token (private file)");
            background.scheduleWithFixedDelay(this::backgroundPulse,1,1,TimeUnit.SECONDS);
        }catch(Exception error){getLogger().severe("MCC web subsystem unavailable: "+error.getClass().getSimpleName()+": "+error.getMessage());latest=Map.of("status","web_error","message","Check MCC startup logs");closeServices();}
    }
    private void capture(){
        if(stopping.get()||game==null)return;pulses++;
        int interval=profile.equals("eco")?5:profile.equals("performance")?1:2;
        if(Bukkit.getTPS()[0]<18)interval=Math.max(interval,5);
        if(pulses%interval!=0&&!refresh.getAndSet(false))return;
        long start=System.nanoTime();try{gameSnapshot=game.snapshot();catalogPlugins=List.copyOf(game.plugins());}catch(Exception e){getLogger().warning("MCC snapshot failed: "+e.getClass().getSimpleName());}
        long nanos=System.nanoTime()-start;snapshotCount.incrementAndGet();snapshotNanos.addAndGet(nanos);
        synchronized(snapshotSamples){snapshotSamples[sampleCursor++%512]=nanos;sampleSize=Math.min(512,sampleSize+1);long[] sorted=Arrays.copyOf(snapshotSamples,sampleSize);Arrays.sort(sorted);snapshotP95=sorted[(int)Math.floor((sampleSize-1)*.95)]/1e6;}
    }
    private void backgroundPulse(){
        if(stopping.get())return;try{
            publishSnapshot();auth.cleanup();if(tail!=null&&realtime.subscribers("console"))tail.pulse();if(punishments!=null)punishments.pulse().exceptionally(error->{getLogger().warning("MCC punishment synchronization deferred: "+error.getClass().getSimpleName());return null;});
            if(automation!=null&&module("automations"))automation.pulse();
            long now=System.currentTimeMillis();if(now-lastMetric>=10000){lastMetric=now;db.metric(latest);}
            if(now-lastCleanup>=3600000){lastCleanup=now;db.cleanup(getConfig().getInt("storage.retention-days",7));}
        }catch(Exception e){getLogger().warning("MCC background task failed: "+e.getClass().getSimpleName());}
    }
    private void publishSnapshot(){
        Map<String,Object> data=new LinkedHashMap<>(gameSnapshot);data.put("status","online");
        long now=System.currentTimeMillis();if(now-lastHostMetrics>=5000){lastHostMetrics=now;
            if(ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean os){double cpu=os.getProcessCpuLoad();hostMetrics.put("cpu",cpu<0?null:cpu*100);hostMetrics.put("systemRamUsed",os.getTotalMemorySize()-os.getFreeMemorySize());hostMetrics.put("systemRamTotal",os.getTotalMemorySize());}
            try{var disk=Files.getFileStore(getDataFolder().toPath());hostMetrics.put("diskUsed",disk.getTotalSpace()-disk.getUsableSpace());hostMetrics.put("diskTotal",disk.getTotalSpace());}catch(Exception ignored){}
        }data.putAll(hostMetrics);
        data.put("mcc",performance());latest=Collections.unmodifiableMap(data);
        long captured=((Number)data.getOrDefault("timestamp",0)).longValue();
        if(realtime!=null&&realtime.subscribers("state")&&captured!=lastPublishedTimestamp){lastPublishedTimestamp=captured;realtime.publish("state",latest);}
    }
    private void gameEvent(String topic,Object data){
        Database database=db;Realtime bus=realtime;
        if(topic.equals("chat")&&!module("chat"))return;
        if(topic.equals("chat")){chatHistory.add(data);while(chatHistory.size()>500)chatHistory.pollFirst();}
        if(topic.equals("trigger")&&data instanceof Map<?,?> raw){
            @SuppressWarnings("unchecked") Map<String,Object> context=(Map<String,Object>)raw;
            if(automation!=null&&module("automations"))automation.trigger((String)context.get("trigger"),context);
            if(database!=null&&context.containsKey("name"))database.document("known-players",(String)context.get("uuid"),context);
            refreshSoon();return;
        }
        if(topic.equals("timeline")&&database!=null)database.audit("minecraft","game.event","ok",data instanceof Map<?,?>?data:Map.of("event",data));
        String wireTopic=switch(topic){case "event"->"events";case "join","quit","death"->"players";case "player"->"inspector";default->topic;};
        if(bus!=null)bus.publish(wireTopic,data);
        if(Set.of("players","world","events","spawn").contains(topic))refreshSoon();
    }
    public void refreshSoon(){refresh.set(true);}
    private void persistMute(UUID uuid,Map<String,Object> state){
        Database storage=db;if(storage==null)return;
        Map<String,Object> row=new LinkedHashMap<>(state);row.put("uuid",uuid.toString());
        if(Boolean.FALSE.equals(row.get("muted")))storage.deleteDocument("mutes",uuid.toString());else storage.document("mutes",uuid.toString(),row);
    }
    public Map<String,Object> state(){return latest;}
    public boolean module(String key){return Boolean.TRUE.equals(modules.get(key));}
    public Automation automation(){return automation;}
    public Backup backup(){return backup;}
    public PluginStage pluginStage(){return pluginStage;}
    public PluginCatalog pluginCatalog(){PluginCatalog value=pluginCatalog;if(value==null)throw new IllegalStateException("Plugin catalog is not ready");return value;}
    public PunishmentEngine punishments(){return punishments;}
    public Path backupServerProperties() throws Exception {
        Path source=Path.of("server.properties").toAbsolutePath().normalize();
        if(!Files.isRegularFile(source)||Files.size(source)>1024*1024)throw new IllegalStateException("Standard server.properties backup is unavailable; configure the server offline");
        Path folder=getDataFolder().toPath().resolve("config-backups");Files.createDirectories(folder);PrivateFiles.restrict(folder,true);
        Path copy=folder.resolve("server-properties-"+System.currentTimeMillis()+"-"+UUID.randomUUID()+".backup");
        Files.copy(source,copy);PrivateFiles.restrict(copy,false);return copy;
    }
    public List<?> consoleHistory(){return tail==null?List.of():tail.history();}
    public List<?> chatHistory(){return new ArrayList<>(chatHistory);}
    public Map<String,Object> settings(){return Map.of("profile",profile,"modules",Map.copyOf(modules),"host",bind,"port",port,"performance",performance(),"limits",Map.of("websocketConnections",16,"httpQueue",128,"gameQueue",256,"spawnPerTick",5,"activeEvents",3),"restartRequired",List.of("web.host","web.port","web.public-origin","web.secure-cookie"));}
    private Map<String,Object> performance(){
        Map<String,Object> data=new LinkedHashMap<>();long count=snapshotCount.get();data.put("snapshotTasks",count);data.put("snapshotMeanMs",count==0?0:snapshotNanos.get()/1e6/count);data.put("snapshotP95Ms",snapshotP95);data.put("method","Wall-clock main-thread snapshots and dispatched actions; CPU is entire JVM, not MCC alone");
        if(main!=null){MainThread.MapStats s=main.stats();data.put("gameQueue",s.pending());data.put("actionTasks",s.tasks());data.put("actionMeanMs",s.meanMs());data.put("actionMaxMs",s.maxMs());}
        if(db!=null)data.putAll(db.stats());if(realtime!=null)data.putAll(realtime.stats());if(automation!=null)data.putAll(automation.stats());return data;
    }
    @SuppressWarnings("unchecked") private void loadSettings(Map<String,Object> body){
        String candidate=Json.text(body,"profile",profile);if(!Set.of("eco","balanced","performance").contains(candidate))throw new IllegalArgumentException("Unknown performance profile");profile=candidate;
        Object values=body.get("modules");if(values instanceof Map<?,?> map)for(String key:List.of("chat","events","automations","map"))if(map.get(key) instanceof Boolean enabled)modules.put(key,enabled);
    }
    public synchronized Map<String,Object> updateSettings(Map<String,Object> body)throws Exception{
        String candidate=Json.text(body,"profile",profile);if(!Set.of("eco","balanced","performance").contains(candidate))throw new IllegalArgumentException("Unknown performance profile");
        Map<String,Object> saved=new LinkedHashMap<>();saved.put("profile",candidate);Map<String,Boolean> proposed=new HashMap<>(modules);
        if(body.get("modules") instanceof Map<?,?> values){for(var e:values.entrySet()){if(!proposed.containsKey(e.getKey())||!(e.getValue() instanceof Boolean))throw new IllegalArgumentException("Unknown module or invalid value");proposed.put((String)e.getKey(),(Boolean)e.getValue());}}
        saved.put("modules",proposed);db.document("settings","runtime",saved).get();loadSettings(saved);
        if(automation!=null)automation.enabled(module("automations"));main.await(()->{game.setEventsEnabled(module("events"));game.setMapEnabled(module("map"));return null;});if(!module("chat"))chatHistory.clear();refreshSoon();return settings();
    }
    private boolean command(CommandSender sender,Command command,String label,String[] args){
        if(args.length>0&&Set.of("punish","revoke","history").contains(args[0].toLowerCase(Locale.ROOT))){if(punishments==null){sender.sendPlainMessage("Punishments are starting");return true;}return punishments.handleCommand(sender,args);}
        if(!(sender instanceof ConsoleCommandSender)&&!sender.hasPermission("mcc.status")){sender.sendPlainMessage("No permission");return true;}
        sender.sendPlainMessage("MCC: "+(web!=null?"online":"unavailable")+"; dashboard http://localhost:"+port+"; first setup token: plugins/MinecraftControlCenter/.setup-token");return true;
    }
    private void closeServices(){Automation a=automation;automation=null;if(a!=null)a.close();WebServer w=web;web=null;if(w!=null)w.close();PluginCatalog c=pluginCatalog;pluginCatalog=null;if(c!=null)c.close();PunishmentEngine p=punishments;punishments=null;if(p!=null)p.close();Realtime r=realtime;realtime=null;if(r!=null)r.close();Database d=db;db=null;if(d!=null)d.close();}
    @Override public void onDisable(){
        stopping.set(true);Automation a=automation;automation=null;if(a!=null)a.close();if(main!=null)main.close();if(game!=null)game.close();Bukkit.getScheduler().cancelTasks(this);
        if(background!=null)background.shutdownNow();closeServices();getLogger().info("Minecraft Control Center stopped; resources released.");
    }
}
