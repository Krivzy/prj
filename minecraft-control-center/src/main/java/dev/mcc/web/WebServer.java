package dev.mcc.web;

import dev.mcc.MccPlugin;
import dev.mcc.core.*;
import dev.mcc.game.GameService;
import io.undertow.*;
import io.undertow.server.*;
import io.undertow.server.handlers.CookieImpl;
import io.undertow.util.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

public final class WebServer implements AutoCloseable {
    private static final AttachmentKey<Auth.Session> SESSION=AttachmentKey.create(Auth.Session.class);
    private static final HttpString CSRF=new HttpString("X-MCC-CSRF");
    private static final Set<String> MOD_ACTIONS=Set.of("player.warn","player.kick","player.ban","player.unban","player.mute","player.unmute","player.heal","player.feed","player.message","chat.broadcast","player.freeze","player.unfreeze");
    private static final Set<String> CONFIRMED=Set.of("player.kick","player.ban","player.ipban","player.ban_ip","player.unban_ip","player.op","player.deop","player.kill","player.clear_inventory","player.remove_item","inventory.set","mob.modify","world.border","console.command","server.settings");
    private final MccPlugin plugin;private final Auth auth;private final Database db;private final MainThread main;private final GameService game;private final Realtime realtime;
    private final Set<String> origins,hosts;private final boolean secureCookie;private final RateLimiter rate=new RateLimiter();private Undertow server;
    private final ThreadPoolExecutor http=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(128),r->{Thread t=new Thread(r,"MCC-HTTP");t.setDaemon(true);return t;});
    private record Request(String fingerprint,CompletableFuture<Object> result,long created){}
    private final ConcurrentHashMap<String,Request> requests=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,byte[]> compressedAssets=new ConcurrentHashMap<>();
    public WebServer(MccPlugin plugin,Auth auth,Database db,MainThread main,GameService game,Realtime realtime,String host,int port,boolean allowLan,String publicOrigin,boolean secureCookie)throws Exception{
        this.plugin=plugin;this.auth=auth;this.db=db;this.main=main;this.game=game;this.realtime=realtime;this.secureCookie=secureCookie;
        InetAddress bind=InetAddress.getByName(host);if(!bind.isLoopbackAddress()&&!allowLan)throw new IllegalArgumentException("Non-loopback bind requires web.allow-lan: true");
        if(port<1024||port>65535)throw new IllegalArgumentException("HTTP port must be 1024–65535");
        Set<String> allowed=new HashSet<>();if(bind.isLoopbackAddress()){allowed.add("http://localhost:"+port);allowed.add("http://127.0.0.1:"+port);allowed.add("http://[::1]:"+port);}
        if(!publicOrigin.isBlank()){URI uri=URI.create(publicOrigin);if(!Set.of("http","https").contains(uri.getScheme())||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||!(uri.getPath()==null||uri.getPath().isEmpty()))throw new IllegalArgumentException("web.public-origin must be an origin without path");allowed.add(publicOrigin);}
        if(allowed.isEmpty())throw new IllegalArgumentException("LAN mode requires explicit web.public-origin");origins=Set.copyOf(allowed);Set<String> h=new HashSet<>();allowed.forEach(s->h.add(URI.create(s).getRawAuthority()));hosts=Set.copyOf(h);
        HttpHandler websocket=Handlers.websocket((exchange,channel)->realtime.connect(channel,exchange.getAttachment(SESSION)));
        server=Undertow.builder().addHttpListener(port,host).setIoThreads(1).setWorkerThreads(4)
            .setServerOption(UndertowOptions.MAX_ENTITY_SIZE,16*1024*1024L).setServerOption(UndertowOptions.MAX_HEADER_SIZE,16384)
            .setServerOption(UndertowOptions.REQUEST_PARSE_TIMEOUT,10000).setServerOption(UndertowOptions.NO_REQUEST_TIMEOUT,65000)
            .setHandler(e->{
                securityHeaders(e);String requestHost=e.getRequestHeaders().getFirst(Headers.HOST);
                if(requestHost==null||!hosts.contains(requestHost.toLowerCase(Locale.ROOT))){respond(e,403,Map.of("error","invalid_host","message","Host is not allowed"));return;}
                String origin=e.getRequestHeaders().getFirst(Headers.ORIGIN);if(origin!=null&&!origins.contains(origin)){respond(e,403,Map.of("error","invalid_origin","message","Origin is not allowed"));return;}
                if(e.getRequestPath().equals("/ws")){
                    Auth.Session s=session(e);if(s==null||origin==null){respond(e,401,Map.of("error","unauthorized","message","Authenticated same-origin WebSocket required"));return;}
                    e.putAttachment(SESSION,s);websocket.handleRequest(e);return;
                }
                boolean upload=e.getRequestPath().equals("/api/plugins/stage");e.setMaxEntitySize(upload?16*1024*1024L:65536L);
                if(!upload&&e.getRequestContentLength()>65536L){respond(e,413,Map.of("error","request_too_large","message","Maximum JSON request size is 64 KiB"));return;}
                if(e.isInIoThread()){try{e.dispatch(http,()->handle(e));}catch(RejectedExecutionException busy){respond(e,503,Map.of("error","busy","message","HTTP queue full"));}}else handle(e);
            }).build();
        try{server.start();}catch(Exception e){http.shutdownNow();throw new IOException("Cannot bind MCC dashboard to "+host+":"+port+"; check port availability",e);}
    }
    private void handle(HttpServerExchange e){
        try{
            e.startBlocking();String path=e.getRequestPath();boolean get=e.getRequestMethod().equals(Methods.GET),post=e.getRequestMethod().equals(Methods.POST),delete=e.getRequestMethod().equals(Methods.DELETE);
            if(!(get||post||delete)){respond(e,405,Map.of("error","method_not_allowed","message","Unsupported method"));return;}
            String ip=e.getSourceAddress().getAddress().getHostAddress();if(!rate.allow(ip+(path.startsWith("/api/")?":http":":assets"),path.startsWith("/api/")?300:5000,60000))throw new TooMany();
            if(!path.startsWith("/api/")){if(!get)throw new IllegalArgumentException("Invalid path");staticFile(e,path);return;}
            e.getResponseHeaders().put(Headers.CACHE_CONTROL,"no-store");Auth.Session s=session(e);
            if(get&&path.equals("/api/auth/status")){respond(e,200,auth.status(s));return;}
            if(post&&(path.equals("/api/auth/setup")||path.equals("/api/auth/login"))){
                if(!rate.allow(ip+":login",8,60000))throw new TooMany();Map<String,Object> body=body(e);
                String name=Json.text(body,"username",""),password=Json.text(body,"password","");
                Auth.Session newSession=path.endsWith("setup")?auth.setup(Json.text(body,"token",""),name,password,e.getSourceAddress().getAddress().isLoopbackAddress()):auth.login(name,password);
                cookie(e,newSession.id(),28800);db.audit(name,"auth.login","ok",Map.of("source",ip));respond(e,200,auth.status(newSession));return;
            }
            if(s==null){respond(e,401,Map.of("error","unauthorized","message","Sign in first"));return;}
            if(retiredMapEndpoint(path)){respond(e,410,Map.of("error","feature_removed","message","Карта доступна только для просмотра; строительство удалено"));return;}
            if(!get&&!Auth.constant(e.getRequestHeaders().getFirst(CSRF),s.csrf()))throw new SecurityException("CSRF token is missing or invalid");
            if(post&&path.equals("/api/plugins/stage")){owner(s);String filename=e.getRequestHeaders().getFirst(new HttpString("X-MCC-Filename"));if(filename==null)throw new IllegalArgumentException("X-MCC-Filename is required");Object staged=plugin.pluginStage().upload(filename,e.getInputStream());db.audit(s.name(),"plugins.stage","ok",Map.of("file",filename));respond(e,200,staged);return;}
            if(post&&path.equals("/api/auth/logout")){auth.logout(s);cookie(e,"",0);respond(e,200,Map.of("ok",true));return;}
            if(post&&path.equals("/api/auth/account")){
                if(!rate.allow(s.name()+":account",5,60000))throw new TooMany();Map<String,Object> values=body(e);
                Auth.Session changed=changeAccount(s,values);
                cookie(e,changed.id(),28800);db.audit(changed.name(),"auth.account.changed","ok",Map.of("previousUsername",s.name())).get();respond(e,200,auth.status(changed));return;
            }
            Object response;
            if(get){response=get(path,e,s);}else if(post){response=post(path,body(e),s);}else{
                admin(s);if(path.startsWith("/api/automations/")){plugin.automation().delete(path.substring("/api/automations/".length()));db.audit(s.name(),"automation.delete","ok",Map.of());response=Map.of("ok",true);}else throw new IllegalArgumentException("Unknown endpoint");
            }
            respond(e,response instanceof Map<?,?> map&&Boolean.TRUE.equals(map.get("pending"))?202:200,response);
        }catch(TooMany ex){e.getResponseHeaders().put(Headers.RETRY_AFTER,"60");respond(e,429,Map.of("error","rate_limit","message","Too many requests; retry shortly"));}
        catch(Exception ex){Throwable cause=ex;while((cause instanceof ExecutionException||cause instanceof CompletionException)&&cause.getCause()!=null)cause=cause.getCause();
            int code=cause.getClass().getSimpleName().equals("RequestTooBigException")?413:cause instanceof SecurityException?403:cause.getClass().getSimpleName().equals("InventoryConflictException")?409:cause instanceof IllegalArgumentException||cause instanceof java.util.zip.ZipException?400:cause instanceof IllegalStateException?503:500;
            String message=code==500?"Internal MCC error; see server logs":Objects.toString(cause.getMessage(),"Request failed");
            if(code==500)plugin.getLogger().warning("MCC request failed: "+cause.getClass().getSimpleName());respond(e,code,Map.of("error","request_failed","message",message));
        }
    }
    private Object get(String path,HttpServerExchange e,Auth.Session s)throws Exception{
        return switch(path){
            case "/api/state" -> plugin.state();
            case "/api/players" -> main.await(game::players);
            case "/api/worlds" -> {List<Map<String,Object>> worlds=main.await(game::worlds);if(!Set.of("Owner","Admin").contains(s.role())){List<Map<String,Object>> filtered=new ArrayList<>();for(Map<String,Object> w:worlds){Map<String,Object> c=new LinkedHashMap<>(w);c.remove("seed");filtered.add(c);}yield filtered;}yield worlds;}
            case "/api/catalogs" -> main.await(game::catalogs);
            case "/api/map/terrain" -> {mapEnabled();if(!rate.allow(s.name()+":map-terrain",90,60000))throw new TooMany();String world=query(e,"world","");int cx=Integer.parseInt(query(e,"cx","0")),cz=Integer.parseInt(query(e,"cz","0")),radius=Integer.parseInt(query(e,"radius","2")),step=Integer.parseInt(query(e,"step","1"));yield main.await(()->{active(s);return game.mapTerrain(world,cx,cz,radius,step);});}
            case "/api/map/index" -> {mapEnabled();if(!rate.allow(s.name()+":map-index",10,60000))throw new TooMany();String world=query(e,"world","");yield main.await(()->{active(s);return game.mapIndex(world);}).get(15,TimeUnit.SECONDS);}
            case "/api/punishments" -> {moderator(s);String uuid=query(e,"uuid","");yield plugin.punishments().list(uuid.isBlank()?null:dev.mcc.game.PunishmentEngine.uuid(uuid),Integer.parseInt(query(e,"limit","100"))).get();}
            case "/api/map" -> {if(!plugin.module("map"))throw new IllegalStateException("Map module is disabled; enable it in MCC settings");String world=query(e,"world","");int x=Integer.parseInt(query(e,"x","0")),z=Integer.parseInt(query(e,"z","0"));if(Math.abs((long)x)>29999800||Math.abs((long)z)>29999800)throw new IllegalArgumentException("Map coordinates out of bounds");yield main.await(()->game.mapTile(world,x,z,64)).get(25,TimeUnit.SECONDS);}
            case "/api/plugins" -> {admin(s);yield main.await(game::plugins);}
            case "/api/plugins/staged" -> {owner(s);yield plugin.pluginStage().list();}
            case "/api/events" -> main.await(game::activeEvents);
            case "/api/entities" -> {if(!rate.allow(s.name()+":entities",30,60000))throw new TooMany();Map<String,Object> params=new LinkedHashMap<>();params.put("world",query(e,"world",""));for(String coordinate:List.of("x","y","z"))params.put(coordinate,Double.parseDouble(query(e,coordinate,"0")));params.put("radius",Double.parseDouble(query(e,"radius","16")));yield main.await(()->game.entities(params));}
            case "/api/audit" -> {moderator(s);yield db.auditHistory().get();}
            case "/api/metrics" -> {String range=query(e,"range","5m");long duration=switch(range){case "5m"->300000;case "1h"->3600000;case "24h"->86400000;default->throw new IllegalArgumentException("Unknown metric range");};yield db.metrics(System.currentTimeMillis()-duration).get();}
            case "/api/automations" -> {admin(s);yield plugin.automation().list();}
            case "/api/automations/catalog" -> {admin(s);yield plugin.automation().catalog();}
            case "/api/backups" -> {owner(s);yield plugin.backup().list();}
            case "/api/settings" -> plugin.settings();
            case "/api/users" -> {owner(s);yield db.users().get();}
            case "/api/console" -> {admin(s);yield plugin.consoleHistory();}
            case "/api/chat" -> {moderator(s);yield plugin.chatHistory();}
            default -> {
                if(path.startsWith("/api/automations/runs/")){admin(s);active(s);String id=path.substring("/api/automations/runs/".length());UUID.fromString(id);yield plugin.automation().runStatus(id);}
                if(path.startsWith("/api/automations/")&&path.endsWith("/runs")){admin(s);active(s);String id=path.substring("/api/automations/".length(),path.length()-"/runs".length());UUID.fromString(id);yield plugin.automation().history(id,Integer.parseInt(query(e,"limit","10")));}
                if(path.startsWith("/api/automations/")&&path.endsWith("/export")){admin(s);String id=path.substring("/api/automations/".length(),path.length()-"/export".length());UUID.fromString(id);yield plugin.automation().exportLegacy(id);}
                if(path.startsWith("/api/actions/")){String id=path.substring(13);UUID.fromString(id);Request request=requests.get(s.name()+":"+id);if(request==null)throw new IllegalArgumentException("Unknown action ID");if(!request.result().isDone())yield pending(id);yield request.result().get();}if(path.startsWith("/api/players/")){String part=path.substring(13);if(part.endsWith("/inventory")){moderator(s);String uuid=part.substring(0,part.length()-10);yield main.await(()->game.inventory(uuid,query(e,"kind","main")));}UUID.fromString(part);yield main.await(()->game.player(part));}throw new IllegalArgumentException("Unknown endpoint");}
        };
    }
    @SuppressWarnings("unchecked") private Object post(String path,Map<String,Object> body,Auth.Session s)throws Exception{
        return switch(path){
            case "/api/action" -> action(body,s);
            case "/api/punishments" -> {moderator(s);Map<String,Object> values=punishmentBody(body);if(Set.of("ban","kick").contains(Json.text(values,"type","").toLowerCase(Locale.ROOT))&&!Boolean.TRUE.equals(body.get("confirmed")))throw new IllegalArgumentException("Explicit confirmation required");yield tracked("punishment.create",body,s,()->plugin.punishments().create(values,s.name()).thenApply(value->value));}
            case "/api/automations" -> {admin(s);active(s);Map<String,Object> saved=plugin.automation().save(body,s.name(),s.role());db.audit(s.name(),"automation.save","ok",Map.of("id",saved.get("id")));yield saved;}
            case "/api/backups" -> {owner(s);if(plugin.backup().list().size()>=20)throw new IllegalStateException("Backup limit reached; archive old backups offline");Object backup=plugin.backup().create();db.audit(s.name(),"backup.create","ok",Map.of("scope","MCC data"));yield backup;}
            case "/api/settings" -> {admin(s);Object result=plugin.updateSettings(body);db.audit(s.name(),"settings.update","ok",Map.of());yield result;}
            case "/api/users" -> {owner(s);String name=Json.text(body,"username","");auth.createUser(name,Json.text(body,"password",""),Json.text(body,"role","Viewer"));db.audit(s.name(),"users.create","ok",Map.of("username",name,"role",Json.text(body,"role","Viewer")));yield Map.of("ok",true);}
            default -> {
                if(path.startsWith("/api/automations/")&&path.endsWith("/run")){
                    admin(s);active(s);String ruleId=path.substring("/api/automations/".length(),path.length()-"/run".length());UUID.fromString(ruleId);
                    Object rawContext=body.getOrDefault("context",Map.of());if(!(rawContext instanceof Map<?,?>))throw new IllegalArgumentException("Run context must be an object");
                    Map<String,Object> context=(Map<String,Object>)rawContext;
                    yield tracked("automation.run."+ruleId,body,s,()->{try{active(s);if(!rate.allow(s.name()+":automation-run",10,60000))throw new TooMany();return CompletableFuture.completedFuture(plugin.automation().run(ruleId,s.name(),s.role(),context,Boolean.TRUE.equals(body.get("confirmed"))));}catch(Exception error){return CompletableFuture.failedFuture(error);}});
                }
                if(path.startsWith("/api/punishments/")&&path.endsWith("/revoke")){moderator(s);long id=Long.parseLong(path.substring("/api/punishments/".length(),path.length()-"/revoke".length()));yield tracked("punishment.revoke."+id,body,s,()->plugin.punishments().revoke(id,s.name()).thenApply(value->value));}throw new IllegalArgumentException("Unknown endpoint");}
        };
    }
    @SuppressWarnings("unchecked") private Object action(Map<String,Object> body,Auth.Session s)throws Exception{
        String id=Json.text(body,"id","");UUID.fromString(id);String action=Json.text(body,"action","");if(action.length()>64)throw new IllegalArgumentException("Invalid action");
        if(s.role().equals("Viewer")||(s.role().equals("Moderator")&&!MOD_ACTIONS.contains(action)))throw new SecurityException("Role does not allow this action");
        if(Set.of("console.command","player.op","player.deop","player.ipban","player.ban_ip","player.unban_ip","server.settings").contains(action))owner(s);
        Object raw=body.getOrDefault("params",Map.of());if(!(raw instanceof Map<?,?>))throw new IllegalArgumentException("Invalid parameters");Map<String,Object> params=(Map<String,Object>)raw;
        if(CONFIRMED.contains(action)&&!Boolean.TRUE.equals(body.get("confirmed")))throw new IllegalArgumentException("Explicit confirmation required");
        if(action.equals("mob.spawn")&&Set.of("WITHER","ENDER_DRAGON").contains(String.valueOf(params.get("type")).toUpperCase(Locale.ROOT))&&!Boolean.TRUE.equals(body.get("confirmed")))throw new IllegalArgumentException("Destructive mob type requires explicit confirmation");
        if(action.startsWith("event.")&&!plugin.module("events")&&!action.equals("event.stop"))throw new IllegalStateException("Game events module is disabled");
        if(action.startsWith("chat.")&&!plugin.module("chat"))throw new IllegalStateException("Chat module is disabled");
        if(!rate.allow(s.name()+":action",120,60000))throw new TooMany();
        long now=System.currentTimeMillis();requests.entrySet().removeIf(e->now-e.getValue().created()>600000&&e.getValue().result().isDone());if(requests.size()>=1024)throw new IllegalStateException("Action deduplication queue full");
        String key=s.name()+":"+id,fingerprint=Json.write(Map.of("action",action,"params",params));Request fresh=new Request(fingerprint,new CompletableFuture<>(),now);Request old=requests.putIfAbsent(key,fresh);
        if(old!=null){if(!old.fingerprint().equals(fingerprint))throw new IllegalArgumentException("Action ID was reused with different parameters");if(!old.result().isDone())return pending(id);return old.result().get();}
        try{
            if(action.equals("server.settings")&&params.containsKey("whitelist"))plugin.backupServerProperties();
            if(Set.of("player.warn","player.kick","player.ban","player.unban","player.mute","player.unmute").contains(action)){
                moderation(action,params,s).whenComplete((actual,error)->{if(error!=null)fresh.result().completeExceptionally(error);else db.audit(s.name(),action,"ok",redact(params)).whenComplete((unused,auditError)->{if(auditError!=null)fresh.result().completeExceptionally(new IllegalStateException("Punishment applied; audit is unavailable"));else fresh.result().complete(Map.of("ok",true,"result",actual,"sequence",realtime.sequence()));plugin.refreshSoon();});});
                try{return fresh.result().get(8,TimeUnit.SECONDS);}catch(TimeoutException timeout){return pending(id);}
            }
            Object actual=main.await(()->game.execute(action,params));Map<String,Object> response=Map.of("ok",true,"result",actual,"sequence",realtime.sequence());
            db.audit(s.name(),action,"ok",redact(params)).get();realtime.publish("timeline",Map.of("time",now,"actor",s.name(),"action",action,"status","ok"));plugin.refreshSoon();fresh.result().complete(response);return response;
        }catch(MainThread.PendingOperation pending){pending.future().whenComplete((actual,error)->{
            if(error!=null){db.audit(s.name(),action,"error",Map.of("message","Action failed"));fresh.result().completeExceptionally(error);}
            else{db.audit(s.name(),action,"ok",redact(params)).whenComplete((v,auditError)->{if(auditError!=null)fresh.result().completeExceptionally(new IllegalStateException("Action applied, audit storage failed; refresh state"));else fresh.result().complete(Map.of("ok",true,"result",actual,"sequence",realtime.sequence()));});plugin.refreshSoon();}
        });return pending(id);}
        catch(Exception error){db.audit(s.name(),action,"error",Map.of("message",error instanceof SecurityException?"Access denied":"Action failed"));fresh.result().completeExceptionally(error);throw error;}
    }
    private static Map<String,Object> pending(String id){return Map.of("ok",false,"pending",true,"id",id,"message","Operation has started; poll /api/actions/"+id+" for the actual result");}
    private void mapEnabled(){if(!plugin.module("map"))throw new IllegalStateException("Enable map in MCC settings");}
    private void active(Auth.Session session){if(auth.session(session.id())!=session)throw new SecurityException("Session changed; log in again");}
    private Auth.Session changeAccount(Auth.Session session,Map<String,Object> values)throws Exception{
        String name=Json.text(values,"username",session.name());if(name.isBlank())name=session.name();Auth.validateUsername(name);
        String destination=name;
        Auth.Session changed=auth.changeAccount(session,Json.text(values,"currentPassword",""),destination,Json.text(values,"password",""));
        requests.keySet().removeIf(key->key.startsWith(session.name()+":")||key.startsWith(destination+":"));return changed;
    }
    /** A stale editor client cannot reach any world mutation or chunk-detail worker. */
    static boolean retiredMapEndpoint(String path){return path.equals("/api/build")||path.startsWith("/api/build/")||path.equals("/api/map/detail")||path.equals("/api/map/region");}
    private Map<String,Object> punishmentBody(Map<String,Object> body)throws Exception{
        Map<String,Object> values=new LinkedHashMap<>(body);if(!values.containsKey("uuid")&&values.get("player") instanceof String id)values.put("uuid",id);if(!values.containsKey("seconds")&&values.containsKey("durationSeconds"))values.put("seconds",values.get("durationSeconds"));
        String uuid=Json.text(values,"uuid","");dev.mcc.game.PunishmentEngine.uuid(uuid);
        Map<String,Object> target=main.await(()->game.player(uuid));values.put("targetName",target.get("name"));return values;
    }
    private CompletableFuture<Object> moderation(String action,Map<String,Object> params,Auth.Session s)throws Exception{
        String uuid=Json.text(params,"uuid","");UUID id=dev.mcc.game.PunishmentEngine.uuid(uuid);
        if(action.equals("player.unmute")||action.equals("player.unban"))return plugin.punishments().revokeType(id,action.endsWith("unmute")?"mute":"ban",s.name()).thenApply(value->value);
        Map<String,Object> values=punishmentBody(params);values.put("type",action.substring("player.".length()));return plugin.punishments().create(values,s.name()).thenApply(value->value);
    }
    private Object tracked(String operation,Map<String,Object> body,Auth.Session s,Supplier<CompletableFuture<Object>> start)throws Exception{
        String id=Json.text(body,"id",UUID.randomUUID().toString());UUID.fromString(id);long now=System.currentTimeMillis();
        requests.entrySet().removeIf(entry->now-entry.getValue().created()>600000&&entry.getValue().result().isDone());if(requests.size()>=1024)throw new IllegalStateException("Action queue is full");
        String fingerprint=Json.write(Map.of("operation",operation,"body",body));Request fresh=new Request(fingerprint,new CompletableFuture<>(),now),existing=requests.putIfAbsent(s.name()+":"+id,fresh);Request request=existing==null?fresh:existing;
        if(existing!=null&&!existing.fingerprint().equals(fingerprint))throw new IllegalArgumentException("Action ID was reused with different parameters");
        if(existing==null)try{start.get().whenComplete((value,error)->{if(error!=null)fresh.result().completeExceptionally(error);else{db.audit(s.name(),operation,"ok",redact(body));fresh.result().complete(value);plugin.refreshSoon();}});}catch(Exception error){fresh.result().completeExceptionally(error);throw error;}
        try{return request.result().get(8,TimeUnit.SECONDS);}catch(TimeoutException error){return pending(id);}
    }
    static Object redact(Map<String,Object> params){Map<String,Object> safe=new LinkedHashMap<>();params.forEach((k,v)->safe.put(k,k.toLowerCase(Locale.ROOT).matches(".*(password|passwd|token|secret|api[-_]?key|private[-_]?key).*")?"[redacted]":redactValue(v)));return safe;}
    private static Object redactNested(Map<?,?> params){Map<String,Object> out=new LinkedHashMap<>();params.forEach((k,v)->out.put(String.valueOf(k),v));return redact(out);}
    private static Object redactValue(Object value){if(value instanceof Map<?,?> map)return redactNested(map);if(value instanceof List<?> list)return list.stream().map(WebServer::redactValue).toList();return value;}
    private Map<String,Object> body(HttpServerExchange e)throws IOException{
        String type=e.getRequestHeaders().getFirst(Headers.CONTENT_TYPE);if(type==null||!type.toLowerCase(Locale.ROOT).startsWith("application/json"))throw new IllegalArgumentException("application/json required");
        byte[] bytes=e.getInputStream().readNBytes(65537);if(bytes.length>65536)throw new IllegalArgumentException("Request too large");return Json.object(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
    }
    private Auth.Session session(HttpServerExchange e){var cookie=e.getRequestCookie("MCC_SESSION");return auth.session(cookie==null?null:cookie.getValue());}
    private void cookie(HttpServerExchange e,String id,int maxAge){e.setResponseCookie(new CookieImpl("MCC_SESSION",id).setHttpOnly(true).setSecure(secureCookie).setSameSiteMode("Strict").setPath("/").setMaxAge(maxAge));}
    private static void owner(Auth.Session s){if(!s.role().equals("Owner"))throw new SecurityException("Owner permission required");}
    private static void admin(Auth.Session s){if(!Set.of("Owner","Admin").contains(s.role()))throw new SecurityException("Admin permission required");}
    private static void moderator(Auth.Session s){if(s.role().equals("Viewer"))throw new SecurityException("Moderator permission required");}
    private static String query(HttpServerExchange e,String key,String fallback){Deque<String> q=e.getQueryParameters().get(key);return q==null?fallback:q.getFirst();}
    private static void securityHeaders(HttpServerExchange e){e.getResponseHeaders().put(new HttpString("X-Content-Type-Options"),"nosniff").put(new HttpString("X-Frame-Options"),"DENY").put(new HttpString("Referrer-Policy"),"no-referrer").put(new HttpString("Content-Security-Policy"),"default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");}
    private static void respond(HttpServerExchange e,int status,Object value){if(e.isResponseStarted())return;e.setStatusCode(status);e.getResponseHeaders().put(Headers.CONTENT_TYPE,"application/json; charset=utf-8");e.getResponseSender().send(Json.write(value));}
    private void staticFile(HttpServerExchange e,String path)throws IOException{
        if(path.contains("..")||path.contains("\\")||path.chars().anyMatch(c->c<32))throw new SecurityException("Invalid asset path");
        String name=path.equals("/")?"index.html":path.substring(1);if(!(name.equals("index.html")||name.startsWith("assets/")||name.startsWith("game-assets/")||name.startsWith("fonts/")||name.equals("favicon.svg"))){e.setStatusCode(404);e.endExchange();return;}
        try(InputStream in=plugin.getClass().getClassLoader().getResourceAsStream("web/"+name)){
            if(in==null){e.setStatusCode(404);e.endExchange();return;}
            byte[] bytes=in.readNBytes(4*1024*1024+1);if(bytes.length>4*1024*1024)throw new IOException("Asset too large");
            String mime=name.endsWith(".js")?"text/javascript":name.endsWith(".css")?"text/css":name.endsWith(".json")?"application/json":name.endsWith(".svg")?"image/svg+xml":name.endsWith(".woff2")?"font/woff2":name.endsWith(".woff")?"font/woff":name.endsWith(".ttf")?"font/ttf":name.endsWith(".png")?"image/png":name.endsWith(".webp")?"image/webp":name.endsWith(".gif")?"image/gif":name.endsWith(".jpg")?"image/jpeg":"text/html";
            boolean textual=Set.of("text/javascript","text/css","application/json","image/svg+xml","text/html").contains(mime);
            e.getResponseHeaders().put(Headers.CONTENT_TYPE,mime+(textual?"; charset=utf-8":"")).put(Headers.CACHE_CONTROL,name.equals("index.html")||name.endsWith("catalog.json")?"no-cache":(name.startsWith("game-assets/")||name.startsWith("fonts/"))?"public,max-age=3600":"public,max-age=31536000,immutable");
            String acceptEncoding=e.getRequestHeaders().getFirst(Headers.ACCEPT_ENCODING);
            if(textual&&bytes.length>1024&&acceptEncoding!=null&&Arrays.stream(acceptEncoding.split(",")).anyMatch(v->v.strip().equals("gzip"))){
                byte[] compressed=compressedAssets.get(name);
                if(compressed==null){ByteArrayOutputStream buffer=new ByteArrayOutputStream();try(java.util.zip.GZIPOutputStream gzip=new java.util.zip.GZIPOutputStream(buffer)){gzip.write(bytes);}compressed=buffer.toByteArray();if(compressedAssets.size()<64)compressedAssets.putIfAbsent(name,compressed);}
                e.getResponseHeaders().put(Headers.CONTENT_ENCODING,"gzip").put(Headers.VARY,"Accept-Encoding");bytes=compressed;
            }
            e.getResponseSender().send(java.nio.ByteBuffer.wrap(bytes));
        }
    }
    private static final class TooMany extends RuntimeException{}
    @Override public void close(){if(server!=null)server.stop();http.shutdownNow();}
}
