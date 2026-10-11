package dev.mcc.game;

import dev.mcc.core.Database;
import dev.mcc.core.MainThread;
import dev.mcc.core.ServerPlatform;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/** Durable moderation: commit before game changes, memory-only async admission/chat checks. */
public final class PunishmentEngine implements Listener,AutoCloseable {
    public static final long MAX_SECONDS=315360000L;
    public record Request(UUID uuid,String targetName,String type,String reason,long seconds) {}
    /** Injection boundary for meaningful persistence/lifecycle tests without a fake Bukkit server. */
    public interface Enforcement {
        CompletableFuture<Void> initialize(PunishmentEngine engine);
        CompletableFuture<Map<String,Object>> apply(Database.Punishment row,boolean restoring);
        CompletableFuture<Void> remove(Database.Punishment row);
    }
    private final Database db;
    private final Enforcement enforcement;
    private final BiConsumer<String,Object> events;
    private final Clock clock;
    private final JavaPlugin plugin;
    private final MainThread main;
    private final ConcurrentHashMap<UUID,Database.Punishment> mutes=new ConcurrentHashMap<>(),bans=new ConcurrentHashMap<>();
    private final Semaphore inflight=new Semaphore(32);
    private final Set<Long> processing=ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed=new AtomicBoolean(),expiring=new AtomicBoolean();
    private final Map<String,Rate> rates=new HashMap<>();
    private volatile BooleanSupplier externalManaged = () -> false;
    private record Rate(double tokens,long at) {}

    public PunishmentEngine(JavaPlugin plugin,Database db,MainThread main,BiConsumer<String,Object> events){
        this.db=Objects.requireNonNull(db);this.plugin=Objects.requireNonNull(plugin);this.main=Objects.requireNonNull(main);this.events=Objects.requireNonNull(events);this.clock=Clock.systemUTC();this.enforcement=new BukkitEnforcement(plugin,main,()->closed.get()||externallyManaged());
    }
    public PunishmentEngine(Database db,Enforcement enforcement,BiConsumer<String,Object> events,Clock clock){this.db=Objects.requireNonNull(db);this.enforcement=Objects.requireNonNull(enforcement);this.events=Objects.requireNonNull(events);this.clock=Objects.requireNonNull(clock);this.plugin=null;this.main=null;}
    public void externalManaged(BooleanSupplier supplier) { externalManaged = Objects.requireNonNull(supplier); }
    private boolean externallyManaged() { try { return externalManaged.getAsBoolean(); } catch (RuntimeException error) { return true; } }
    private void requireNative() { if (closed.get()) throw new IllegalStateException("Punishments are unavailable"); if (externallyManaged()) throw new IllegalStateException("Наказания управляются внешним плагином; встроенное действие MCC отключено"); }
    private CompletableFuture<Map<String,Object>> applyNative(Database.Punishment row, boolean restoring) { try { requireNative(); return enforcement.apply(row, restoring); } catch (RuntimeException error) { return CompletableFuture.failedFuture(error); } }
    private CompletableFuture<Void> removeNative(Database.Punishment row) { try { requireNative(); return enforcement.remove(row); } catch (RuntimeException error) { return CompletableFuture.failedFuture(error); } }
    private CompletableFuture<Void> restoreNative(Database.Punishment row) {
        return applyNative(row,true).handle((result,error)->{
            if(externallyManaged())return CompletableFuture.<Void>completedFuture(null);
            if(error!=null)return CompletableFuture.<Void>failedFuture(unwrap(error));
            return db.punishmentSynced(row.id(),row.status()).thenApply(synced->(Void)null);
        }).thenCompose(value->value);
    }
    public static UUID uuid(String raw){try{UUID id=UUID.fromString(raw);if(!id.toString().equalsIgnoreCase(raw))throw new IllegalArgumentException();return id;}catch(Exception e){throw new IllegalArgumentException("Canonical player UUID is required");}}
    public static Request request(Map<String,Object> body){
        if(body==null||!(body.get("uuid") instanceof String raw)||!(body.get("type") instanceof String type))throw new IllegalArgumentException("uuid and type are required");
        UUID id=uuid(raw);type=type.toLowerCase(Locale.ROOT);if(!Set.of("warn","kick","mute","ban").contains(type))throw new IllegalArgumentException("Unknown punishment type");
        Object reasonValue=body.getOrDefault("reason","Административное наказание MCC");if(!(reasonValue instanceof String reason)||reason.isBlank()||reason.length()>500||reason.indexOf('\0')>=0)throw new IllegalArgumentException("Reason must contain 1–500 characters");
        Object nameValue=body.getOrDefault("targetName",id.toString());if(!(nameValue instanceof String name)||name.isBlank()||name.length()>100||name.indexOf('\0')>=0)throw new IllegalArgumentException("Invalid targetName");
        Object secondsValue=body.getOrDefault("seconds",0);if(!(secondsValue instanceof Number number))throw new IllegalArgumentException("seconds must be an integer");double value=number.doubleValue();if(!Double.isFinite(value)||value!=Math.rint(value)||value<0||value>MAX_SECONDS)throw new IllegalArgumentException("Duration must be 0–315360000 seconds");long seconds=number.longValue();
        if(Set.of("warn","kick").contains(type)&&seconds!=0)throw new IllegalArgumentException("warn and kick do not have a duration");
        return new Request(id,name,type,reason.trim(),seconds);
    }
    private static String actor(String actor){if(actor==null||actor.isBlank()||actor.length()>100||actor.indexOf('\0')>=0)throw new IllegalArgumentException("Invalid issuing actor");return actor;}
    private synchronized void admit(String actor){long now=clock.millis();Rate old=rates.get(actor);double tokens=old==null?8:Math.min(8,old.tokens()+Math.max(0,now-old.at())/1000.0*2);if(tokens<1)throw new IllegalStateException("Punishment rate limit reached");if(rates.size()>=512&&!rates.containsKey(actor))rates.entrySet().removeIf(entry->now-entry.getValue().at()>60000);if(rates.size()>=512&&!rates.containsKey(actor))throw new IllegalStateException("Punishment issuer limit reached");rates.put(actor,new Rate(tokens-1,now));}
    private void remember(Database.Punishment row){if(row.active(clock.millis())){if(row.type().equals("mute"))mutes.put(uuid(row.uuid()),row);if(row.type().equals("ban"))bans.put(uuid(row.uuid()),row);}}
    private void forget(Database.Punishment row){UUID id=uuid(row.uuid());mutes.computeIfPresent(id,(key,current)->current.id()==row.id()?null:current);bans.computeIfPresent(id,(key,current)->current.id()==row.id()?null:current);}
    public boolean muted(UUID id){if(externallyManaged())return false;Database.Punishment row=mutes.get(id);return row!=null&&row.active(clock.millis());}
    public Database.Punishment activeBan(UUID id){if(externallyManaged())return null;Database.Punishment row=bans.get(id);return row!=null&&row.active(clock.millis())?row:null;}
    public Map<String,Object> muteState(UUID id){if(externallyManaged())return Map.of("muted",false,"expiresAt",0,"externalManaged",true);Database.Punishment row=mutes.get(id);return row!=null&&row.active(clock.millis())?Map.of("muted",true,"expiresAt",row.expiresAt(),"reason",row.reason(),"punishmentId",row.id()):Map.of("muted",false,"expiresAt",0);}
    public CompletableFuture<Void> start(){
        return db.activePunishments().thenCompose(rows->{for(Database.Punishment row:rows)remember(row);return enforcement.initialize(this).thenCompose(unused->{CompletableFuture<Void> chain=CompletableFuture.completedFuture(null);for(Database.Punishment row:rows){if(row.active(clock.millis()))chain=chain.thenCompose(nothing->externallyManaged()?CompletableFuture.completedFuture(null):restoreNative(row));}return chain;});}).thenCompose(unused->synchronizePending(true)).thenCompose(unused->pulse());
    }
    public CompletableFuture<List<Map<String,Object>>> list(UUID id,int limit){if(limit<1||limit>200)return CompletableFuture.failedFuture(new IllegalArgumentException("Limit must be 1–200"));return db.punishmentHistory(id,limit).thenApply(rows->rows.stream().map(Database.Punishment::json).toList());}
    public CompletableFuture<Map<String,Object>> create(Map<String,Object> body,String actor){
        return createInternal(body,actor,true);
    }
    /** Called only by startup migration; bypasses issuer pacing, never validation or bounded queues. */
    public CompletableFuture<Map<String,Object>> importLegacyMute(UUID uuid,String targetName,long expiresAt){long now=clock.millis();if(expiresAt!=0&&expiresAt<=now)return CompletableFuture.completedFuture(Map.of("skipped",true));long seconds=expiresAt==0?0:Math.max(1,(expiresAt-now+999)/1000);return createInternal(Map.of("uuid",uuid.toString(),"targetName",targetName,"type","mute","reason","Migrated MCC mute","seconds",seconds),"migration",false);}
    private CompletableFuture<Map<String,Object>> createInternal(Map<String,Object> body,String actor,boolean rateLimited){
        final Request request;try{requireNative();request=request(body);actor=actor(actor);if(closed.get())throw new IllegalStateException("Punishments are unavailable");if(rateLimited)admit(actor);if(!inflight.tryAcquire())throw new IllegalStateException("Punishment queue is full");}catch(Exception e){return CompletableFuture.failedFuture(e);}
        final String issuer=actor;long now=clock.millis(),expiry=request.seconds()==0?0:Math.addExact(now,Math.multiplyExact(request.seconds(),1000));
        CompletableFuture<Map<String,Object>> future=db.createPunishment(request.uuid(),request.targetName(),request.type(),request.reason(),issuer,now,expiry).thenCompose(row->{processing.add(row.id());remember(row);return applyNative(row,false).handle((result,error)->{
            if(error==null)return db.punishmentSynced(row.id(),row.status()).thenCompose(synced->{Map<String,Object> response=new LinkedHashMap<>(synced.json());response.put("game",result);return audit(synced,"created",issuer).thenApply(unused->response);});
            forget(row);Throwable cause=unwrap(error);return db.failPunishment(row.id(),cause.getClass().getSimpleName()+": "+cause.getMessage()).thenCompose(failed->audit(failed,"failed",issuer)).thenCompose(unused->CompletableFuture.<Map<String,Object>>failedFuture(cause));
        }).thenCompose(value->value).whenComplete((result,error)->processing.remove(row.id()));});return future.whenComplete((result,error)->inflight.release());
    }
    public CompletableFuture<Map<String,Object>> revoke(long id,String actor){
        try{requireNative();actor=actor(actor);if(id<1)throw new IllegalArgumentException("Invalid punishment ID");if(closed.get())throw new IllegalStateException("Punishments are unavailable");}catch(Exception e){return CompletableFuture.failedFuture(e);}
        final String issuer=actor;return db.revokePunishment(id,issuer,clock.millis()).thenCompose(row->{forget(row);processing.add(row.id());return removeNative(row).thenCompose(unused->db.punishmentSynced(row.id(),row.status())).thenCompose(synced->audit(synced,"revoked",issuer).thenApply(unused->synced.json())).whenComplete((result,error)->processing.remove(row.id()));});
    }
    public CompletableFuture<Map<String,Object>> revokeType(UUID uuid,String type,String actor){Database.Punishment row=type.equals("mute")?mutes.get(uuid):type.equals("ban")?bans.get(uuid):null;if(row==null)return CompletableFuture.failedFuture(new IllegalArgumentException("No active MCC punishment of this type"));return revoke(row.id(),actor);}
    public CompletableFuture<Void> pulse(){
        if(closed.get()||externallyManaged()||!expiring.compareAndSet(false,true))return CompletableFuture.completedFuture(null);
        return db.expirePunishments(clock.millis()).thenCompose(rows->{CompletableFuture<Void> chain=CompletableFuture.completedFuture(null);for(Database.Punishment row:rows){forget(row);Map<String,Object> json=new LinkedHashMap<>(row.json());json.put("status","expired");chain=chain.thenCompose(unused->db.audit("system","punishment.expired","ok",json).thenRun(()->events.accept("punishments",Map.of("operation","expired","punishment",json))));}return chain;}).thenCompose(unused->synchronizePending(false)).whenComplete((result,error)->expiring.set(false));
    }
    private CompletableFuture<Void> synchronizePending(boolean startup){
        if(externallyManaged())return CompletableFuture.completedFuture(null);
        return db.pendingPunishments().thenCompose(rows->{CompletableFuture<Void> chain=CompletableFuture.completedFuture(null);for(Database.Punishment row:rows){if(row.status().equals("active")||row.status().equals("applied")&&!startup)continue;chain=chain.thenCompose(unused->{if(externallyManaged()||!processing.add(row.id()))return CompletableFuture.completedFuture(null);CompletableFuture<?> apply=row.status().equals("applied")?applyNative(row,false):removeNative(row);return apply.handle((result,error)->{if(externallyManaged())return CompletableFuture.<Void>completedFuture(null);if(error==null)return db.punishmentSynced(row.id(),row.status()).thenApply(synced->(Void)null);if(!row.status().equals("applied"))return CompletableFuture.<Void>failedFuture(unwrap(error));return db.failPunishment(row.id(),"Recovery: "+unwrap(error).getClass().getSimpleName()).thenCompose(failed->removeNative(failed).thenCompose(nothing->db.punishmentSynced(failed.id(),failed.status()))).thenApply(synced->(Void)null);}).thenCompose(value->value).whenComplete((result,error)->processing.remove(row.id()));});}return chain;});
    }
    private CompletableFuture<Void> audit(Database.Punishment row,String operation,String actor){return db.audit(actor,"punishment."+operation,errorStatus(operation),row.json()).thenRun(()->events.accept("punishments",Map.of("operation",operation,"punishment",row.json())));}
    private static String errorStatus(String operation){return operation.equals("failed")?"error":"ok";}
    private static Throwable unwrap(Throwable error){while((error instanceof CompletionException||error instanceof ExecutionException)&&error.getCause()!=null)error=error.getCause();return error;}
    private static String display(Database.Punishment row){return "MCC · "+row.type().toUpperCase(Locale.ROOT)+" #"+row.id()+"\n"+row.reason()+(row.expiresAt()==0?"":"\nДо: "+java.time.Instant.ofEpochMilli(row.expiresAt()));}
    @EventHandler(priority=EventPriority.HIGHEST) public void login(AsyncPlayerPreLoginEvent event){Database.Punishment row=activeBan(event.getUniqueId());if(!externallyManaged()&&row!=null)event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED,display(row));}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void chat(AsyncPlayerChatEvent event){if(!muted(event.getPlayer().getUniqueId())||externallyManaged())return;event.setCancelled(true);UUID id=event.getPlayer().getUniqueId();main.call(()->{Player live=Bukkit.getPlayer(id);if(!externallyManaged()&&live!=null){Database.Punishment row=mutes.get(id);live.sendMessage(row==null?"Чат заблокирован MCC":display(row));}return null;});}
    public boolean handleCommand(CommandSender sender,String[] args){
        if(args.length==0||!Set.of("punish","revoke","history").contains(args[0].toLowerCase(Locale.ROOT)))return false;
        if(!(sender instanceof ConsoleCommandSender)&&!sender.hasPermission("mcc.punishments")){sender.sendMessage("Нет прав mcc.punishments");return true;}
        String actor="minecraft:"+sender.getName();CompletableFuture<?> operation;
        try {
            if(args[0].equalsIgnoreCase("revoke")){if(args.length!=2)throw new IllegalArgumentException("/mcc revoke <id>");if(!(sender instanceof ConsoleCommandSender)&&!sender.hasPermission("mcc.punishments.revoke"))throw new SecurityException("Нет прав revoke");operation=revoke(Long.parseLong(args[1]),actor);}
            else if(args[0].equalsIgnoreCase("history")){if(args.length!=2)throw new IllegalArgumentException("/mcc history <known-name|uuid>");operation=list(commandUuid(args[1]),20);}
            else {if(args.length<5)throw new IllegalArgumentException("/mcc punish <warn|kick|mute|ban> <known-name|uuid> <seconds; 0=permanent> <reason>");String type=args[1].toLowerCase(Locale.ROOT);if(!(sender instanceof ConsoleCommandSender)&&!sender.hasPermission("mcc.punishments."+type))throw new SecurityException("Нет прав на это наказание");UUID uuid=commandUuid(args[2]);Player target=Bukkit.getPlayer(uuid);Map<String,Object> body=new LinkedHashMap<>();body.put("uuid",uuid.toString());body.put("targetName",target==null?args[2]:target.getName());body.put("type",type);body.put("seconds",Long.parseLong(args[3]));body.put("reason",String.join(" ",Arrays.copyOfRange(args,4,args.length)));operation=create(body,actor);}
            operation.whenComplete((result,error)->main.call(()->{if(error!=null)sender.sendMessage("MCC: "+unwrap(error).getMessage());else if(result instanceof List<?> history){sender.sendMessage("MCC: "+history.size()+" записей");for(Object entry:history){if(entry instanceof Map<?,?> row)sender.sendMessage("#"+row.get("id")+" "+row.get("type")+" · "+row.get("status")+" · "+row.get("reason"));}}else if(result instanceof Map<?,?> row)sender.sendMessage("MCC: #"+row.get("id")+" "+row.get("type")+" · "+row.get("status"));return null;}));
        }catch(Exception error){sender.sendMessage("MCC: "+error.getMessage());}return true;
    }
    private static UUID commandUuid(String value){try{return uuid(value);}catch(IllegalArgumentException notUuid){org.bukkit.OfflinePlayer cached=ServerPlatform.cachedPlayer(value);if(cached!=null)return cached.getUniqueId();throw new IllegalArgumentException("Игрок не найден в доступном кеше. Укажите canonical UUID для offline игрока.");}}
    @Override public void close(){closed.set(true);mutes.clear();bans.clear();if(plugin!=null)HandlerList.unregisterAll(this);}
    private static final class BukkitEnforcement implements Enforcement {
        private final JavaPlugin plugin;private final MainThread main;private final BooleanSupplier externalManaged;
        BukkitEnforcement(JavaPlugin plugin,MainThread main,BooleanSupplier externalManaged){this.plugin=plugin;this.main=main;this.externalManaged=externalManaged;}
        @Override public CompletableFuture<Void> initialize(PunishmentEngine engine){return main.call(()->{if(engine.closed.get())throw new IllegalStateException("Punishments are unavailable");Bukkit.getPluginManager().registerEvents(engine,plugin);return null;});}
        @Override public CompletableFuture<Map<String,Object>> apply(Database.Punishment row,boolean restoring){return main.call(()->{
            if(externalManaged.getAsBoolean())throw new IllegalStateException("Native enforcement is managed externally");
            UUID id=uuid(row.uuid());Player player=Bukkit.getPlayer(id);boolean delivered=false;
            switch(row.type()){
                case "warn"->{if(player!=null){player.sendMessage(display(row));delivered=true;}}
                case "kick"->{if(player==null)throw new IllegalArgumentException("Player is not online");player.kickPlayer(display(row));delivered=true;}
                case "mute"->{if(player!=null&&!restoring){player.sendMessage(display(row));delivered=true;}}
                case "ban"->{if(row.expiresAt()>0&&row.expiresAt()<=System.currentTimeMillis())return Map.of("delivered",false,"expired",true);var target=Bukkit.getOfflinePlayer(id);var profile=ServerPlatform.profile(target);var list=Bukkit.<org.bukkit.ban.ProfileBanList>getBanList(BanList.Type.PROFILE);var current=list.getBanEntry(profile);String source="MCCPunishment:"+row.id();if(current!=null&&list.isBanned(profile)&&!source.equals(current.getSource())){if(restoring)return Map.of("delivered",false,"foreignBanPreserved",true);throw new IllegalStateException("A separate native ban exists; it was preserved");}target.ban(display(row),row.expiresAt()==0?(Instant)null:Instant.ofEpochMilli(row.expiresAt()),source);if(player!=null){player.kickPlayer(display(row));delivered=true;}}
                default->throw new IllegalArgumentException("Unknown punishment type");
            }
            return Map.of("delivered",delivered,"online",player!=null,"restored",restoring);
        });}
        @Override public CompletableFuture<Void> remove(Database.Punishment row){return main.call(()->{if(externalManaged.getAsBoolean())throw new IllegalStateException("Native enforcement is managed externally");if(row.type().equals("ban")){var target=Bukkit.getOfflinePlayer(uuid(row.uuid()));var list=Bukkit.<org.bukkit.ban.ProfileBanList>getBanList(BanList.Type.PROFILE);var entry=list.getBanEntry(ServerPlatform.profile(target));if(entry!=null&&("MCCPunishment:"+row.id()).equals(entry.getSource()))list.pardon(ServerPlatform.profile(target));}return null;});}
    }
}
