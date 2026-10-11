package dev.mcc;

import dev.mcc.core.*;
import dev.mcc.game.GameService;
import dev.mcc.game.PunishmentEngine;
import dev.mcc.game.OreMonitor;
import dev.mcc.integration.IntegrationService;
import dev.mcc.server.*;
import dev.mcc.web.*;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import net.md_5.bungee.api.chat.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.nio.file.*;
import java.lang.management.ManagementFactory;
import com.sun.management.OperatingSystemMXBean;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class MccPlugin extends JavaPlugin {
    private volatile Database db;private volatile Auth auth;private volatile Realtime realtime;private volatile WebServer web;
    private volatile SetupService setup;private volatile LicenseService license;
    private volatile MccCoreInstaller coreInstaller;
    private volatile AutomationTriggers automationTriggers;private volatile Automation automation;private volatile Backup backup;private volatile LogTail tail;private volatile PluginStage pluginStage;
    private volatile PluginCatalog pluginCatalog;private volatile List<Map<String,Object>> catalogPlugins=List.of();private String catalogMinecraftVersion;
    private GameService game;private MainThread main;private volatile PunishmentEngine punishments;
    private volatile IntegrationService integrations;private OreMonitor oreMonitor;private KernelProfiles kernelProfiles;private RestartService restart;
    private ScheduledExecutorService background;
    private volatile Map<String,Object> latest=Map.of("status","starting");
    private volatile Map<String,Object> gameSnapshot=Map.of();
    private volatile Map<String,Boolean> modules=Map.of();
    private volatile String profile="balanced";
    private final AtomicBoolean stopping=new AtomicBoolean(),refresh=new AtomicBoolean();
    private final ConcurrentLinkedDeque<Object> chatHistory=new ConcurrentLinkedDeque<>();
    private final AtomicLong snapshotCount=new AtomicLong(),snapshotNanos=new AtomicLong();
    private final long[] snapshotSamples=new long[512];private int sampleCursor,sampleSize;
    private volatile double snapshotP95;
    private int pulses;private long lastMetric, lastCleanup,lastPublishedTimestamp,lastHostMetrics;
    private final Map<String,Object> hostMetrics=new ConcurrentHashMap<>();
    private String bind;private int port;

    @Override public void onEnable(){
        try{
            Files.createDirectories(getDataFolder().toPath());PrivateFiles.restrict(getDataFolder().toPath(),true);
            saveDefaultConfig();for(String directory:List.of("logs","backups","assets-cache"))Files.createDirectories(getDataFolder().toPath().resolve(directory));
            bind=getConfig().getString("web.host","127.0.0.1");port=DashboardPort.configured(getConfig());
            Map<String,Boolean> configured=new LinkedHashMap<>();for(String key:ModulePolicy.KEYS)configured.put(key,getConfig().getBoolean("modules."+key,true));modules=Map.copyOf(configured);
            profile=getConfig().getString("performance.profile","balanced").toLowerCase(Locale.ROOT);if(!Set.of("eco","balanced","performance").contains(profile))profile="balanced";
            if(Bukkit.getName().toLowerCase(Locale.ROOT).contains("folia"))throw new IllegalArgumentException("Folia region scheduling is not supported by this build; use a Bukkit main-thread kernel");
            main=new MainThread(this);
            integrations=new IntegrationService(this,main,(actor,write)->auth!=null&&auth.session(actor.id())==actor&&(write?actor.role().equals("Owner"):Set.of("Owner","Admin").contains(actor.role())));
            integrations.setEnabled(false);
            game=new GameService(this,this::gameEvent);game.externalManaged(integrations.externalPunishments());game.setCaptureModules(()->module("chat"),()->module("playerSnapshots"),()->module("entitySnapshots"));
            game.setEventsEnabled(false);game.setMapEnabled(false);game.setSnapshotModules(false,false,false);
            integrations.cachedPlayers(game::knownPlayerIdentities);
            oreMonitor=new OreMonitor(this,getConfig().getConfigurationSection("ore-monitor"));
            Path serverRoot=Path.of("").toAbsolutePath().normalize();restart=new RestartService(serverRoot);
            kernelProfiles=new KernelProfiles(serverRoot,getDataFolder().toPath(),new BukkitKernelRuntime(main),Boolean.getBoolean("mcc.launcher.profile-hook"));
            catalogMinecraftVersion=ServerPlatform.minecraftVersion();if(module("catalog"))catalogPlugins=List.copyOf(game.plugins());
            game.setConsoleAllowedCommands(new HashSet<>(getConfig().getStringList("console.allowed-commands")));
            background=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"MCC-Background");t.setDaemon(true);return t;});
            background.execute(this::initialize);
            Bukkit.getScheduler().runTaskTimer(this,this::capture,20L,20L);
            getCommand("mcc").setExecutor(this::command);
            Bukkit.getPluginManager().registerEvents(new Listener(){@EventHandler public void join(PlayerJoinEvent event){Bukkit.getScheduler().runTaskLater(MccPlugin.this,()->offerSetup(event.getPlayer()),20L);}},this);
        }catch(Exception error){getLogger().severe("MCC initialization failed: "+error.getClass().getSimpleName()+": "+error.getMessage());Bukkit.getPluginManager().disablePlugin(this);}
    }
    private void initialize(){
        try{
            db=new Database(getDataFolder().toPath().resolve("database.db"));if(stopping.get()){db.close();return;}
            auth=new Auth(db,getDataFolder().toPath(),false,false);license=new LicenseService(db,!auth.setupRequired());
            try{Path root=Path.of("").toAbsolutePath().normalize();coreInstaller=new MccCoreInstaller(root,getDataFolder().toPath(),root.resolve("plugins/MinecraftControlCenter.jar"));}catch(java.io.IOException unavailable){coreInstaller=null;}
            setup=new SetupService(auth,db,license,(uuid,name)->main.await(()->{Player player=Bukkit.getPlayer(uuid);return player!=null&&player.isOnline()&&player.getName().equals(name)&&setupPermission(player);}));
            realtime=new Realtime(auth,topic->license.topicAllowed(topic)&&ModulePolicy.topicAllowed(topic,this::module));
            punishments=new PunishmentEngine(this,db,main,this::gameEvent);punishments.externalManaged(integrations.externalPunishments());punishments.start().get();
            try{kernelProfiles.reconcileStartup().get(30,TimeUnit.SECONDS);}catch(Exception error){getLogger().warning("MCC kernel preset was not applied safely; use Settings to select Custom or review a compatible preset. "+error.getClass().getSimpleName());}
            List<Map<String,Object>> settings=db.documents("settings").get();if(!settings.isEmpty())loadSettings(settings.getFirst());
            Map<UUID,Long> mutes=new HashMap<>();for(Map<String,Object> mute:db.documents("mutes").get())if(mute.get("uuid") instanceof String uuid&&mute.get("expiresAt") instanceof Number expiry)mutes.put(UUID.fromString(uuid),expiry.longValue());
            Map<UUID,String> known=new HashMap<>();for(Map<String,Object> player:db.documents("known-players").get())if(player.get("uuid") instanceof String uuid&&player.get("name") instanceof String name)known.put(UUID.fromString(uuid),name);
            for(var mute:mutes.entrySet())if(mute.getValue()==0||mute.getValue()>System.currentTimeMillis()){
                if(integrations.externalPunishments().getAsBoolean())continue;
                if(!punishments.muted(mute.getKey()))punishments.importLegacyMute(mute.getKey(),known.getOrDefault(mute.getKey(),mute.getKey().toString()),mute.getValue()).get();
                db.deleteDocument("mutes",mute.getKey().toString()).get();
            }
            main.await(()->{game.setModerationPersistence(this::persistMute);game.setMuteLookup(punishments::muted);game.restoreKnownPlayers(known);game.setEventsEnabled(module("events"));game.setMapEnabled(module("map"));game.setSnapshotModules(module("playerSnapshots"),module("entitySnapshots"),module("metrics"));oreMonitor.enabled(module("oreMonitor"));return null;});
            automation=new Automation(db,main,game);automation.setEvents(this::gameEvent);automation.enabled(module("automations"));main.await(()->{if(module("automations"))automationTriggers=new AutomationTriggers(this,automation);return null;});backup=new Backup(db,getDataFolder().toPath());pluginStage=new PluginStage(db,getDataFolder().toPath());
            integrations.setEnabled(module("integrations"));if(module("catalog")){catalogPlugins=List.copyOf(main.await(game::plugins));pluginCatalog=new PluginCatalog(db,getDataFolder().toPath(),catalogMinecraftVersion,()->catalogPlugins);}
            if(module("console"))tail=new LogTail(Path.of("logs","latest.log"),realtime);gameSnapshot=main.await(game::snapshot);publishSnapshot();
            if(stopping.get()){closeServices();return;}
            web=new WebServer(this,auth,db,main,game,realtime,bind,port,getConfig().getBoolean("web.allow-lan",false),getConfig().getString("web.public-origin",""),getConfig().getBoolean("web.secure-cookie",false));
            MccCoreLink.ready(this,()->!stopping.get()&&isEnabled()&&web!=null&&web.isRunning());
            getLogger().info("Minecraft Control Center enabled!");getLogger().info("Dashboard: http://localhost:"+port);
            getLogger().info("REST API: Ready");getLogger().info("WebSocket: Online");getLogger().info("Real-Time Engine: Active");getLogger().info("Performance Guard: Active");
            if(auth.setupRequired()){getLogger().info("First Owner: an operator receives a private clickable setup link in game; console fallback: mcc setup <onlinePlayer>");main.await(()->{for(Player player:Bukkit.getOnlinePlayers())offerSetup(player);return null;});}
            background.scheduleWithFixedDelay(this::backgroundPulse,1,1,TimeUnit.SECONDS);
        }catch(Exception error){getLogger().severe("MCC web subsystem unavailable: "+error.getClass().getSimpleName()+": "+error.getMessage());latest=Map.of("status","web_error","message","Check MCC startup logs");stopping.set(true);Bukkit.getScheduler().runTask(this,()->{if(isEnabled())Bukkit.getPluginManager().disablePlugin(this);});}
    }
    private void capture(){
        if(stopping.get()||game==null)return;pulses++;
        int interval=profile.equals("eco")?5:profile.equals("performance")?1:2;
        if(ServerPlatform.lowTps())interval=Math.max(interval,5);
        if(realtime==null||!realtime.subscribers("state"))interval=Math.max(interval,10);
        if(pulses%interval!=0&&!refresh.getAndSet(false))return;
        long start=System.nanoTime();try{gameSnapshot=game.snapshot();if(module("catalog"))catalogPlugins=List.copyOf(game.plugins());}catch(Exception e){getLogger().warning("MCC snapshot failed: "+e.getClass().getSimpleName());}
        long nanos=System.nanoTime()-start;snapshotCount.incrementAndGet();snapshotNanos.addAndGet(nanos);
        synchronized(snapshotSamples){snapshotSamples[sampleCursor++%512]=nanos;sampleSize=Math.min(512,sampleSize+1);long[] sorted=Arrays.copyOf(snapshotSamples,sampleSize);Arrays.sort(sorted);snapshotP95=sorted[(int)Math.floor((sampleSize-1)*.95)]/1e6;}
    }
    private void backgroundPulse(){
        if(stopping.get())return;try{
            publishSnapshot();auth.cleanup();if(setup!=null)setup.cleanup();if(tail!=null&&realtime.subscribers("console"))tail.pulse();if(punishments!=null)punishments.pulse().exceptionally(error->{getLogger().warning("MCC punishment synchronization deferred: "+error.getClass().getSimpleName());return null;});
            if(automation!=null&&module("automations"))automation.pulse();
            long now=System.currentTimeMillis();if(module("metrics")&&now-lastMetric>=10000){lastMetric=now;db.metric(latest);}
            if(now-lastCleanup>=3600000){lastCleanup=now;db.cleanup(getConfig().getInt("storage.retention-days",7));}
        }catch(Exception e){getLogger().warning("MCC background task failed: "+e.getClass().getSimpleName());}
    }
    private synchronized void publishSnapshot(){
        Map<String,Object> data=new LinkedHashMap<>(ModulePolicy.filterSnapshot(gameSnapshot,this::module));data.put("status","online");data.put("modules",effectiveModules());if(license!=null){data.put("license",license.status());if(!license.fullAccess())for(String key:List.of("worlds","players","plugins","entities","activeEvents","events","pendingSpawnBatches"))data.remove(key);}
        long now=System.currentTimeMillis();if(module("metrics")&&now-lastHostMetrics>=5000){lastHostMetrics=now;
            if(ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean os){double cpu=os.getProcessCpuLoad();if(cpu<0)hostMetrics.remove("cpu");else hostMetrics.put("cpu",cpu*100);hostMetrics.put("systemRamUsed",os.getTotalMemorySize()-os.getFreeMemorySize());hostMetrics.put("systemRamTotal",os.getTotalMemorySize());}
            try{var disk=Files.getFileStore(getDataFolder().toPath());hostMetrics.put("diskUsed",disk.getTotalSpace()-disk.getUsableSpace());hostMetrics.put("diskTotal",disk.getTotalSpace());}catch(Exception ignored){}
        }if(module("metrics"))data.putAll(hostMetrics);
        data.put("mcc",performance());latest=ModulePolicy.filterSnapshot(data,this::module);
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
        if(topic.equals("timeline")&&module("timeline")&&database!=null)database.audit("minecraft","game.event","ok",data instanceof Map<?,?>?data:Map.of("event",data));
        String wireTopic=switch(topic){case "event"->"events";case "join","quit","death"->"players";case "player"->"inspector";default->topic;};
        if(bus!=null&&ModulePolicy.topicAllowed(wireTopic,this::module))bus.publish(wireTopic,data);
        if(Set.of("players","world","events","spawn").contains(topic))refreshSoon();
    }
    public void refreshSoon(){refresh.set(true);}
    private void persistMute(UUID uuid,Map<String,Object> state){
        Database storage=db;if(storage==null)return;
        Map<String,Object> row=new LinkedHashMap<>(state);row.put("uuid",uuid.toString());
        if(Boolean.FALSE.equals(row.get("muted")))storage.deleteDocument("mutes",uuid.toString());else storage.document("mutes",uuid.toString(),row);
    }
    public Map<String,Object> state(){return latest;}
    public boolean module(String key){LicenseService selected=license;return Boolean.TRUE.equals(modules.get(key))&&selected!=null&&selected.moduleAllowed(key);}
    public Map<String,Boolean> effectiveModules(){Map<String,Boolean> out=new LinkedHashMap<>();for(String key:ModulePolicy.KEYS)out.put(key,module(key));return Map.copyOf(out);}
    public SetupService setup(){return setup;}
    public LicenseService license(){return license;}
    public Map<String,Object> coreStatus(){MccCoreInstaller installer=coreInstaller;if(installer!=null)return installer.status();Map<String,Object> out=new LinkedHashMap<>(MccCoreLink.state());out.put("available",false);out.put("pendingRestart",false);out.put("message","Переход на MCC Core требует поддерживаемый Paper и подключённый MCC launcher. Текущее ядро продолжает работать.");return out;}
    public Map<String,Object> stageCore(String actor,java.util.function.BooleanSupplier authority)throws Exception{MccCoreInstaller installer=coreInstaller;if(installer==null)throw new UnsupportedOperationException("MCC Core staging is unavailable for this installation");return installer.stage(actor,authority);}
    public Map<String,Object> authStatus(Auth.Session session){Map<String,Object> out=new LinkedHashMap<>(auth.status(session));out.put("license",license.status());return out;}
    public Automation automation(){return automation;}
    public Backup backup(){return backup;}
    public PluginStage pluginStage(){return pluginStage;}
    public PluginCatalog pluginCatalog(){PluginCatalog value=pluginCatalog;if(value==null)throw new IllegalStateException("Plugin catalog is not ready");return value;}
    public PunishmentEngine punishments(){return punishments;}
    public IntegrationService integrations(){return integrations;}
    public OreMonitor oreMonitor(){return oreMonitor;}
    public KernelProfiles kernelProfiles(){return kernelProfiles;}
    public RestartService restart(){return restart;}
    public Path backupServerProperties() throws Exception {
        Path source=Path.of("server.properties").toAbsolutePath().normalize();
        if(!Files.isRegularFile(source)||Files.size(source)>1024*1024)throw new IllegalStateException("Standard server.properties backup is unavailable; configure the server offline");
        Path folder=getDataFolder().toPath().resolve("config-backups");Files.createDirectories(folder);PrivateFiles.restrict(folder,true);
        Path copy=folder.resolve("server-properties-"+System.currentTimeMillis()+"-"+UUID.randomUUID()+".backup");
        Files.copy(source,copy);PrivateFiles.restrict(copy,false);return copy;
    }
    public List<?> consoleHistory(){return tail==null?List.of():tail.history();}
    public List<?> chatHistory(){return new ArrayList<>(chatHistory);}
    public Map<String,Object> settings(){Map<String,Object> out=new LinkedHashMap<>();out.put("profile",profile);out.put("modules",Map.copyOf(modules));out.put("effectiveModules",effectiveModules());out.put("license",license.status());out.put("coreAlwaysActive",List.of("authentication","health","settings","backups","retention-cleanup","external-punishment-ownership"));out.put("host",bind);out.put("port",port);out.put("performance",performance());out.put("limits",Map.of("websocketConnections",16,"httpQueue",64,"gameQueue",256,"spawnPerTick",5,"activeEvents",3));out.put("restartRequired",List.of("web.host","web.port","web.public-origin","web.secure-cookie"));return out;}
    private Map<String,Object> performance(){
        Map<String,Object> data=new LinkedHashMap<>();long count=snapshotCount.get();data.put("snapshotTasks",count);data.put("snapshotMeanMs",count==0?0:snapshotNanos.get()/1e6/count);data.put("snapshotP95Ms",snapshotP95);data.put("method","Wall-clock main-thread snapshots and dispatched actions; CPU is entire JVM, not MCC alone");
        if(main!=null){MainThread.MapStats s=main.stats();data.put("gameQueue",s.pending());data.put("actionTasks",s.tasks());data.put("actionMeanMs",s.meanMs());data.put("actionMaxMs",s.maxMs());}
        if(db!=null)data.putAll(db.stats());if(realtime!=null)data.putAll(realtime.stats());if(automation!=null)data.putAll(automation.stats());if(web!=null)data.putAll(web.stats());return data;
    }
    @SuppressWarnings("unchecked") private void loadSettings(Map<String,Object> body){
        String candidate=Json.text(body,"profile",profile);if(!Set.of("eco","balanced","performance").contains(candidate))throw new IllegalArgumentException("Unknown performance profile");profile=candidate;
        modules=ModulePolicy.updated(modules,body.get("modules"));
    }
    public synchronized Map<String,Object> updateSettings(Map<String,Object> body)throws Exception{
        String candidate=Json.text(body,"profile",profile);if(!Set.of("eco","balanced","performance").contains(candidate))throw new IllegalArgumentException("Unknown performance profile");
        Map<String,Object> saved=new LinkedHashMap<>();saved.put("profile",candidate);Map<String,Boolean> proposed=ModulePolicy.updated(modules,body.get("modules"));
        saved.put("modules",proposed);db.document("settings","runtime",saved).get();loadSettings(saved);
        applyModuleConfiguration();return settings();
    }
    public synchronized Map<String,Object> updateLicense(String mode,String key)throws Exception{license.change(mode,key);applyModuleConfiguration();return license.status();}
    public synchronized void licenseAfterSetup()throws Exception{applyModuleConfiguration();}
    private void applyModuleConfiguration()throws Exception{
        if(automation!=null)automation.enabled(module("automations"));if(integrations!=null)integrations.setEnabled(module("integrations"));
        if(!module("catalog")){PluginCatalog old=pluginCatalog;pluginCatalog=null;if(old!=null)old.close();catalogPlugins=List.of();}else if(pluginCatalog==null){catalogPlugins=List.copyOf(main.await(game::plugins));pluginCatalog=new PluginCatalog(db,getDataFolder().toPath(),catalogMinecraftVersion,()->catalogPlugins);}
        if(!module("console"))tail=null;else if(tail==null)tail=new LogTail(Path.of("logs","latest.log"),realtime);if(!module("metrics"))hostMetrics.clear();
        if(realtime!=null)realtime.modulesChanged();lastPublishedTimestamp=-1;gameSnapshot=ModulePolicy.filterSnapshot(gameSnapshot,this::module);latest=ModulePolicy.filterSnapshot(latest,this::module);main.await(()->{if(!module("automations")){AutomationTriggers old=automationTriggers;automationTriggers=null;if(old!=null)old.close();}else if(automationTriggers==null)automationTriggers=new AutomationTriggers(this,automation);game.setEventsEnabled(module("events"));game.setMapEnabled(module("map"));game.setSnapshotModules(module("playerSnapshots"),module("entitySnapshots"),module("metrics"));oreMonitor.enabled(module("oreMonitor"));return null;});if(!module("chat"))chatHistory.clear();publishSnapshot();refreshSoon();
    }
    private static boolean setupPermission(Player player){return player.isOp()||player.hasPermission("mcc.setup");}
    private String dashboardOrigin(){String origin=getConfig().getString("web.public-origin","");return origin.isBlank()?"http://localhost:"+port:origin;}
    /** Capability is delivered solely to the authorized player, never to a console/logger. */
    private boolean offerSetup(Player player){
        SetupService service=setup;if(stopping.get()||web==null||service==null||auth==null||!auth.setupRequired()||!player.isOnline()||!setupPermission(player))return false;
        try{String token=service.issue(player.getUniqueId(),player.getName(),true);TextComponent link=new TextComponent("[MCC] Настроить панель →");link.setColor(net.md_5.bungee.api.ChatColor.GREEN);link.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL,dashboardOrigin()+"/#setup="+token));player.spigot().sendMessage(link);player.sendMessage("Ссылка личная и действует 10 минут. Никнейм будет взят из Minecraft. Не передавайте её другим.");return true;}
        catch(IllegalStateException limit){player.sendMessage(limit.getMessage());return false;}
    }
    private boolean command(CommandSender sender,Command command,String label,String[] args){
        if(args.length>0&&args[0].equalsIgnoreCase("setup")){
            if(sender instanceof ConsoleCommandSender){if(args.length!=2){sender.sendMessage("Usage: mcc setup <onlinePlayer>");return true;}Player target=Bukkit.getPlayerExact(args[1]);if(target==null||!setupPermission(target)){sender.sendMessage("The target must be an online operator or have mcc.setup permission");return true;}sender.sendMessage(offerSetup(target)?"Private setup link sent to the authorized player":"Setup is unavailable or already complete");return true;}
            if(sender instanceof Player player&&args.length==1&&setupPermission(player)){offerSetup(player);return true;}sender.sendMessage("Use /mcc setup as an operator; console may target an authorized online player");return true;
        }
        if(args.length>0&&Set.of("punish","revoke","history").contains(args[0].toLowerCase(Locale.ROOT))){if(punishments==null){sender.sendMessage("Punishments are starting");return true;}return punishments.handleCommand(sender,args);}
        if(!(sender instanceof ConsoleCommandSender)&&!sender.hasPermission("mcc.status")){sender.sendMessage("No permission");return true;}
        sender.sendMessage("MCC: "+(web!=null?"online":"unavailable")+"; dashboard "+dashboardOrigin());return true;
    }
    private void closeSafely(String name,Runnable close){try{close.run();}catch(Exception error){getLogger().warning("MCC cleanup failed for "+name+": "+error.getClass().getSimpleName());}}
    private void closeServices(){SetupService first=setup;setup=null;if(first!=null)closeSafely("setup",first::close);Automation a=automation;automation=null;if(a!=null)closeSafely("automation",a::close);WebServer w=web;web=null;if(w!=null)closeSafely("web",w::close);PluginCatalog c=pluginCatalog;pluginCatalog=null;if(c!=null)closeSafely("catalog",c::close);PunishmentEngine p=punishments;punishments=null;if(p!=null)closeSafely("punishments",p::close);Realtime r=realtime;realtime=null;if(r!=null)closeSafely("realtime",r::close);Database d=db;db=null;if(d!=null)closeSafely("database",d::close);}
    @Override public void onDisable(){
        MccCoreLink.disabled(this);
        stopping.set(true);AutomationTriggers triggers=automationTriggers;automationTriggers=null;if(triggers!=null)closeSafely("automation listeners",triggers::close);if(kernelProfiles!=null)closeSafely("kernel presets",kernelProfiles::close);if(oreMonitor!=null)closeSafely("ore monitor",oreMonitor::close);IntegrationService integration=integrations;integrations=null;if(integration!=null)closeSafely("integrations",integration::close);Automation a=automation;automation=null;if(a!=null)closeSafely("automation",a::close);if(main!=null)closeSafely("main-thread queue",main::close);if(game!=null)closeSafely("game",game::close);Bukkit.getScheduler().cancelTasks(this);
        if(background!=null)background.shutdownNow();closeServices();getLogger().info("Minecraft Control Center stopped; resources released.");
    }
}
