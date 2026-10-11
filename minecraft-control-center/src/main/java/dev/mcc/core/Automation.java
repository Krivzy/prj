package dev.mcc.core;

import dev.mcc.game.GameService;
import org.bukkit.Bukkit;
import org.bukkit.World;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/** Durable bounded typed graphs. JDBC and timers stay off Minecraft ticks. */
public final class Automation implements AutoCloseable {
    public interface Runtime {
        CompletableFuture<Object> read(String condition,Map<String,Object> config,Map<String,Object> context);
        CompletableFuture<Object> action(String action,Map<String,Object> params);
        default CompletableFuture<Object> action(String action,Map<String,Object> params,BooleanSupplier allowed){if(!allowed.getAsBoolean())return CompletableFuture.failedFuture(new CancellationException("Automation disabled"));return action(action,params);}
        default CompletableFuture<Object> read(String condition,Map<String,Object> config,Map<String,Object> context,BooleanSupplier allowed){if(!allowed.getAsBoolean())return CompletableFuture.failedFuture(new CancellationException("Automation disabled"));return read(condition,config,context);}
    }
    private static final Set<String> TERMINAL=Set.of("completed","failed","cancelled");
    private final ConcurrentHashMap<String,AutomationGraph.Compiled> rules=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,Long> lastRun=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,Map<String,Object>> profiles=new ConcurrentHashMap<>();
    private volatile Set<String> subscribers=Set.of();
    private final ArrayBlockingQueue<Signal> signals=new ArrayBlockingQueue<>(128);
    private final AtomicBoolean draining=new AtomicBoolean();private final Object documentsLock=new Object();
    private final AtomicLong lifecycleEpoch=new AtomicLong();
    private int ruleCursor;private long operationWindow;private int operationCount;
    private record Signal(String trigger,Map<String,Object> context,long createdAt,long epoch){}
    private final LinkedHashMap<String,Run> jobs=new LinkedHashMap<>();
    private final Map<String,Run> active=new HashMap<>();
    private final Database db;private final Runtime runtime;private final LongSupplier clock;
    private final ScheduledThreadPoolExecutor worker=new ScheduledThreadPoolExecutor(1,r->{Thread t=new Thread(r,"MCC-Automation");t.setDaemon(true);return t;});
    private volatile BiConsumer<String,Object> events=(topic,value)->{};
    private volatile boolean enabled=true,closed=false;
    private final AtomicLong runs=new AtomicLong(),errors=new AtomicLong(),rejected=new AtomicLong();

    public Automation(Database db,MainThread main,GameService game)throws Exception {this(db,new AutomationGameRuntime(main,game),System::currentTimeMillis);}
    public Automation(Database db,Runtime runtime,LongSupplier clock)throws Exception {
        this.db=db;this.runtime=runtime;this.clock=clock;worker.setRemoveOnCancelPolicy(true);worker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        for(Map<String,Object> profile:db.documents("automation-profiles").get(10,TimeUnit.SECONDS)){try{Map<String,Object> clean=profile(profile);if(profiles.size()>=20)throw new IllegalArgumentException("Profile limit reached");profiles.put((String)clean.get("id"),clean);}catch(IllegalArgumentException error){errors.incrementAndGet();}}
        profiles.putIfAbsent(AutomationGraph.DEFAULT_PROFILE,Map.of("id",AutomationGraph.DEFAULT_PROFILE,"name","Основной профиль","enabled",true));
        for(Map<String,Object> stored:db.documents("automations").get(10,TimeUnit.SECONDS)){
            try {if(rules.size()>=100)throw new IllegalArgumentException("Automation limit reached");AutomationGraph.Compiled graph=AutomationGraph.compile(stored);if(!profiles.containsKey(graph.document().get("profileId")))throw new IllegalArgumentException("Profile missing");rules.put((String)graph.document().get("id"),graph);lastRun.put((String)graph.document().get("id"),clock.getAsLong());}
            catch(IllegalArgumentException e){errors.incrementAndGet();db.audit("automation","automation.load","error",Map.of("message","Invalid stored graph was excluded"));}
        }
        List<Map<String,Object>> storedRuns=db.documents("automation-runs").get(10,TimeUnit.SECONDS);storedRuns.sort(Comparator.comparingLong(Automation::createdAt));int discard=Math.max(0,storedRuns.size()-200);
        for(int i=0;i<storedRuns.size();i++){
            Map<String,Object> stored=storedRuns.get(i);String id=Json.text(stored,"runId","");if(id.isEmpty())continue;
            if(i<discard){db.deleteDocument("automation-runs",id);continue;}
            if(!TERMINAL.contains(stored.get("status"))){stored=new LinkedHashMap<>(stored);stored.put("status","cancelled");stored.put("finishedAt",clock.getAsLong());stored.put("error","Server restarted; unfinished runs are not resumed");List<Map<String,Object>> states=new ArrayList<>();if(stored.get("nodes") instanceof List<?> list)for(Object value:list){Map<String,Object> state=AutomationGraph.map(value,"node status");if(!Set.of("completed","skipped","failed","cancelled").contains(state.get("status"))){state.put("status","cancelled");state.put("finishedAt",clock.getAsLong());}states.add(state);}stored.put("nodes",states);db.document("automation-runs",id,stored).get(10,TimeUnit.SECONDS);}
            jobs.put(id,new Run(stored));
        }
        updateSubscribers();
    }
    private static Map<String,Object> profile(Map<String,Object> body){String id=AutomationGraph.uuid(AutomationGraph.text(body,"id",UUID.randomUUID().toString(),36)),name=AutomationGraph.text(body,"name","Новый профиль",80);if(name.isBlank())throw new IllegalArgumentException("Profile name required");return Map.of("id",id,"name",name,"enabled",AutomationGraph.bool(body.getOrDefault("enabled",false),"enabled"));}
    public List<Map<String,Object>> profiles(){return profiles.values().stream().sorted(Comparator.comparing(p->(String)p.get("name"))).map(Automation::copy).toList();}
    public Map<String,Object> saveProfile(Map<String,Object> body,String actor,String role)throws Exception{synchronized(documentsLock){admin(role);actor(actor);if(closed)throw new IllegalStateException("Automation engine is closed");Map<String,Object> clean=profile(body);String id=(String)clean.get("id");Map<String,Object> previous=profiles.get(id);if(previous==null&&profiles.size()>=20)throw new IllegalArgumentException("Profile limit reached");if(Boolean.TRUE.equals(clean.get("enabled"))&&(previous==null||!Boolean.TRUE.equals(previous.get("enabled")))&&!Boolean.TRUE.equals(body.get("confirmed")))throw new IllegalArgumentException("Explicit confirmation required to enable automatic actions");db.document("automation-profiles",id,clean).get(10,TimeUnit.SECONDS);profiles.put(id,clean);if(!Boolean.TRUE.equals(clean.get("enabled"))){List<Run> current;synchronized(this){current=new ArrayList<>(active.values());}for(Run run:current)if(id.equals(run.graph.document().get("profileId")))cancel(run,"Profile disabled");}updateSubscribers();db.audit(actor,"automation.profile.save","ok",Map.of("id",id));return copy(clean);}}
    public void deleteProfile(String id,String actor,String role)throws Exception{synchronized(documentsLock){admin(role);actor(actor);AutomationGraph.uuid(id);if(closed)throw new IllegalStateException("Automation engine is closed");if(id.equals(AutomationGraph.DEFAULT_PROFILE))throw new IllegalArgumentException("Default profile cannot be deleted");if(rules.values().stream().anyMatch(g->id.equals(g.document().get("profileId"))))throw new IllegalArgumentException("Remove or move profile rules first");db.deleteDocument("automation-profiles",id).get(10,TimeUnit.SECONDS);profiles.remove(id);updateSubscribers();}}
    private boolean profileEnabled(AutomationGraph.Compiled graph){Map<String,Object> profile=profiles.get(graph.document().get("profileId"));return profile!=null&&Boolean.TRUE.equals(profile.get("enabled"));}
    private void updateSubscribers(){Set<String> values=new HashSet<>();for(var graph:rules.values())if(Boolean.TRUE.equals(graph.document().get("enabled"))&&profileEnabled(graph))values.add(graph.trigger());subscribers=Set.copyOf(values);}
    public boolean hasSubscribers(String trigger){return enabled&&!closed&&subscribers.contains(trigger);}
    public void setEvents(BiConsumer<String,Object> events){this.events=Objects.requireNonNull(events);}
    public List<Map<String,Object>> list(){return rules.values().stream().sorted(Comparator.comparing(g->(String)g.document().get("name"))).map(g->copy(g.document())).toList();}
    public Map<String,Object> catalog(){return AutomationGraph.catalog();}
    public Map<String,Object> exportLegacy(String id){return AutomationGraph.exportLegacy(rule(id));}
    public Map<String,Object> save(Map<String,Object> body)throws Exception {Map<String,Object> trusted=new LinkedHashMap<>(body);trusted.put("confirmed",true);return save(trusted,"legacy-api","Admin");}
    public Map<String,Object> save(Map<String,Object> body,String actor,String role)throws Exception {synchronized(documentsLock){
        admin(role);actor(actor);if(closed)throw new IllegalStateException("Automation engine is closed");AutomationGraph.Compiled graph=AutomationGraph.compile(body);String id=(String)graph.document().get("id");if(!rules.containsKey(id)&&rules.size()>=100)throw new IllegalArgumentException("Automation limit reached");
        if(!profiles.containsKey(graph.document().get("profileId")))throw new IllegalArgumentException("Unknown profile");AutomationGraph.Compiled previous=rules.get(id);if(Boolean.TRUE.equals(graph.document().get("enabled"))&&(previous==null||!Boolean.TRUE.equals(previous.document().get("enabled")))&&!Boolean.TRUE.equals(body.get("confirmed")))throw new IllegalArgumentException("Explicit confirmation required to enable automatic actions");
        db.document("automations",id,graph.document()).get(10,TimeUnit.SECONDS);rules.put(id,graph);lastRun.put(id,clock.getAsLong());Run running;synchronized(this){running=active.get(id);}if(running!=null)cancel(running,"Rule was updated");updateSubscribers();db.audit(actor,"automation.save","ok",Map.of("id",id,"graphVersion",1));return copy(graph.document());
    }}
    public void delete(String id)throws Exception {synchronized(documentsLock){AutomationGraph.uuid(id);db.deleteDocument("automations",id).get(10,TimeUnit.SECONDS);rules.remove(id);lastRun.remove(id);lastRun.remove("manual:"+id);Run running;synchronized(this){running=active.get(id);}if(running!=null)cancel(running,"Rule deleted");updateSubscribers();}}
    public Map<String,Object> run(String ruleId,String actor,String role,Map<String,Object> context,boolean confirmed){admin(role);actor(actor);if(!confirmed)throw new IllegalArgumentException("Explicit confirmation is required to run automation actions");return start(rule(ruleId),actor,AutomationGraph.context(context),true);}
    public synchronized Map<String,Object> runStatus(String runId){AutomationGraph.uuid(runId);Run run=jobs.get(runId);if(run==null)throw new IllegalArgumentException("Unknown automation run");return run.snapshot();}
    public synchronized List<Map<String,Object>> history(String ruleId,int limit){AutomationGraph.uuid(ruleId);if(limit<1||limit>50)throw new IllegalArgumentException("History limit must be 1–50");List<Run> found=new ArrayList<>(jobs.values());Collections.reverse(found);return found.stream().filter(r->ruleId.equals(r.document.get("ruleId"))).limit(limit).map(Run::snapshot).toList();}
    public void trigger(String trigger,Map<String,Object> rawContext){
        long epoch=lifecycleEpoch.get();if(!hasSubscribers(trigger))return;Map<String,Object> context;try{context=AutomationGraph.context(rawContext);}catch(IllegalArgumentException e){errors.incrementAndGet();return;}
        if(!hasSubscribers(trigger))return;if(!signals.offer(new Signal(trigger,Map.copyOf(context),clock.getAsLong(),epoch))){rejected.incrementAndGet();return;}if(draining.compareAndSet(false,true))dispatch(this::drainSignals);
    }
    private void drainSignals(){for(int i=0;i<8;i++){Signal signal=signals.poll();if(signal==null||!enabled||closed){draining.set(false);if(!signals.isEmpty()&&enabled&&!closed&&draining.compareAndSet(false,true))dispatch(this::drainSignals);return;}if(clock.getAsLong()-signal.createdAt()>10000){rejected.incrementAndGet();continue;}List<AutomationGraph.Compiled> candidates=rules.values().stream().sorted(Comparator.comparing(g->(String)g.document().get("id"))).toList();int start=Math.floorMod(ruleCursor++,Math.max(1,candidates.size()));for(int n=0;n<candidates.size();n++){var graph=candidates.get((start+n)%candidates.size());if(!Boolean.TRUE.equals(graph.document().get("enabled"))||!profileEnabled(graph)||!signal.trigger().equals(graph.trigger()))continue;String world=(String)graph.document().get("world");if(!world.isEmpty()&&!world.equals(signal.context().get("world"))&&!signal.trigger().equals("interval"))continue;long cooldown=Math.max(((Number)graph.document().get("cooldownSeconds")).longValue(),graph.trigger().equals("interval")?graph.intervalSeconds():0)*1000;if(signal.createdAt()-lastRun.getOrDefault((String)graph.document().get("id"),signal.createdAt())<cooldown)continue;try{start(graph,"automation:"+graph.document().get("id"),signal.context(),false,signal.epoch());}catch(IllegalStateException error){rejected.incrementAndGet();}}}try{worker.schedule(this::drainSignals,100,TimeUnit.MILLISECONDS);}catch(RejectedExecutionException ignored){draining.set(false);}}
    public void pulse(){trigger("interval",Map.of());}
    public synchronized void enabled(boolean value){if(enabled!=value)lifecycleEpoch.incrementAndGet();enabled=value;if(!value){signals.clear();for(Run run:new ArrayList<>(active.values()))cancel(run,"Automation module disabled");}}
    public synchronized Map<String,Object> stats(){return Map.of("automationRuns",runs.get(),"automationErrors",errors.get(),"automationRejected",rejected.get(),"automationActive",active.size(),"automationQueuedSignals",signals.size(),"automationEnabledProfiles",profiles.values().stream().filter(p->Boolean.TRUE.equals(p.get("enabled"))).count());}

    private Map<String,Object> start(AutomationGraph.Compiled graph,String actor,Map<String,Object> context,boolean manual){return start(graph,actor,context,manual,lifecycleEpoch.get());}
    private synchronized Map<String,Object> start(AutomationGraph.Compiled graph,String actor,Map<String,Object> context,boolean manual,long expectedEpoch){
        if(expectedEpoch!=lifecycleEpoch.get())throw new IllegalStateException("Stale automation signal from a previous module lifecycle");
        if(closed||!enabled)throw new IllegalStateException("Automation module is unavailable");String ruleId=(String)graph.document().get("id");if(active.size()>=4||active.containsKey(ruleId))throw new IllegalStateException("Automation run queue is full or this rule is already running");
        if(manual&&lastRun.containsKey("manual:"+ruleId)&&clock.getAsLong()-lastRun.get("manual:"+ruleId)<1000)throw new IllegalStateException("Wait one second before running this rule again");
        if(!manual){long cooldown=((Number)graph.document().get("cooldownSeconds")).longValue()*1000;if(graph.trigger().equals("interval"))cooldown=Math.max(cooldown,graph.intervalSeconds()*1000L);if(clock.getAsLong()-lastRun.getOrDefault(ruleId,clock.getAsLong())<cooldown)throw new IllegalStateException("Automation cooldown has not elapsed");}
        if(!manual&&(!Boolean.TRUE.equals(graph.document().get("enabled"))||!profileEnabled(graph)||rules.get(ruleId)!=graph))throw new IllegalStateException("Automatic rule is no longer enabled");
        Run run=new Run(graph,actor,context,clock.getAsLong());run.manual=manual;active.put(ruleId,run);jobs.put(run.id,run);lastRun.put(ruleId,clock.getAsLong());if(manual)lastRun.put("manual:"+ruleId,clock.getAsLong());trimHistory();dispatch(()->{run.deadline=worker.schedule(()->cancel(run,"Run exceeded its 90 second deadline"),90,TimeUnit.SECONDS);persist(run,()->{synchronized(Automation.this){if(run.terminal())return;run.document.put("status","running");}advance(run);});});return run.snapshot();
    }
    private void advance(Run run){
        AutomationGraph.Node node;Map<String,Object> state;
        synchronized(this){
            if(run.terminal())return;if(closed||!enabled){cancel(run,"Automation engine stopped");return;}if(run.index>=run.graph.order().size()){finish(run,"completed",null);return;}
            node=run.graph.order().get(run.index++);state=run.states.get(node.id());if(!run.activated.contains(node.id())){state.put("status","skipped");state.put("finishedAt",clock.getAsLong());persist(run,()->advance(run));return;}state.put("status","running");state.put("startedAt",clock.getAsLong());
        }
        persist(run,()->execute(run,node,state));
    }
    private void execute(Run run,AutomationGraph.Node node,Map<String,Object> state){
        synchronized(this){if(!allowed(run)){cancel(run,"Rule or profile changed before execution");return;}}
        if(node.type().equals("trigger")){complete(run,node,state,Map.of("trigger",run.graph.trigger()),"next");return;}
        if(node.type().equals("delay")){synchronized(this){if(run.terminal()||closed)return;double seconds=((Number)node.config().get("seconds")).doubleValue();run.timer=worker.schedule(()->complete(run,node,state,Map.of("seconds",seconds),"next"),Math.round(seconds*1000),TimeUnit.MILLISECONDS);}return;}
        synchronized(this){long now=System.nanoTime();if(now-operationWindow>=250000000L){operationWindow=now;operationCount=0;}if(operationCount>=8){run.timer=worker.schedule(()->execute(run,node,state),50,TimeUnit.MILLISECONDS);return;}operationCount++;}
        try{
            Map<String,Object> context=new LinkedHashMap<>(run.context);String world=(String)run.graph.document().get("world");if(!world.isEmpty())context.putIfAbsent("world",world);CompletableFuture<Object> operation;
            synchronized(this){
                if(!allowed(run))return;
                if(node.type().equals("condition"))operation=runtime.read((String)node.config().get("condition"),node.config(),context,()->allowed(run));
                else {Map<String,Object> params=new LinkedHashMap<>(AutomationGraph.map(node.config().get("params"),"params"));String action=(String)node.config().get("action");if(action.startsWith("player.")&&context.containsKey("uuid"))params.putIfAbsent("uuid",context.get("uuid"));if(context.containsKey("world"))params.putIfAbsent("world",context.get("world"));AutomationGraph.validateAction(action,params);boolean fromLast=Boolean.TRUE.equals(params.remove("fromLastEvent")),fromTrigger=Boolean.TRUE.equals(params.remove("fromTrigger"));if(fromLast){if(run.lastEventId==null)throw new IllegalArgumentException("This run has not successfully started an event");params.put("eventId",run.lastEventId);}else if(fromTrigger){if(!context.containsKey("eventId"))throw new IllegalArgumentException("This trigger has no event id");params.put("eventId",context.get("eventId"));}operation=runtime.action(action,params,()->allowed(run));}
                run.operation=operation;
            }
            operation.whenComplete((result,error)->dispatch(()->{
                if(error!=null){failNode(run,state,error);return;}
                if(node.type().equals("condition")){try{boolean accepted=AutomationGraph.compare(result,(String)node.config().get("operator"),node.config().get("value"));complete(run,node,state,Map.of("value",result,"matched",accepted),accepted?"true":"false");}catch(Exception e){failNode(run,state,e);}}
                else complete(run,node,state,result,"next");
            }));
        }catch(Exception e){failNode(run,state,e);}
    }
    private boolean allowed(Run run){return !closed&&enabled&&!run.stopped&&rules.get(run.graph.document().get("id"))==run.graph&&(run.manual||Boolean.TRUE.equals(run.graph.document().get("enabled"))&&profileEnabled(run.graph));}
    private void complete(Run run,AutomationGraph.Node node,Map<String,Object> state,Object result,String handle){synchronized(this){if(run.terminal())return;if(node.type().equals("action")&&node.config().get("action").equals("event.start")){try{if(!(result instanceof Map<?,?> map)||!(map.get("id") instanceof String id))throw new IllegalArgumentException("Event start returned no valid event id");run.lastEventId=AutomationGraph.uuid(id);}catch(IllegalArgumentException error){failNode(run,state,error);return;}}run.operation=null;state.put("status","completed");state.put("finishedAt",clock.getAsLong());state.put("result",summary(result));for(AutomationGraph.Edge edge:run.graph.outgoing(node.id(),handle))run.activated.add(edge.target());}persist(run,()->advance(run));}
    private void failNode(Run run,Map<String,Object> state,Throwable error){synchronized(this){if(run.terminal())return;run.operation=null;state.put("status","failed");state.put("finishedAt",clock.getAsLong());state.put("error",safeError(error));}finish(run,"failed",safeError(error));}
    private void cancel(Run run,String reason){synchronized(this){if(run.terminal())return;if(run.timer!=null)run.timer.cancel(false);if(run.operation!=null&&!run.operation.isDone()&&!run.operation.cancel(false))run.document.put("effectMayComplete",true);}finish(run,"cancelled",reason);}
    private void finish(Run run,String status,String error){
        Map<String,Object> snapshot;CompletableFuture<Void> write;
        synchronized(this){
            if(run.terminal())return;run.stopped=true;run.document.put("status",status);run.document.put("finishedAt",clock.getAsLong());if(error!=null)run.document.put("error",error);for(Map<String,Object> state:run.states.values())if(Set.of("pending","running").contains(state.get("status"))){state.put("status",status.equals("cancelled")?"cancelled":"skipped");state.put("finishedAt",clock.getAsLong());}
            if(run.deadline!=null)run.deadline.cancel(false);if(run.timer!=null)run.timer.cancel(false);active.remove(run.graph.document().get("id"),run);runs.incrementAndGet();if(!status.equals("completed"))errors.incrementAndGet();
            snapshot=run.snapshot();write=db.document("automation-runs",run.id,snapshot);
        }
        write.whenComplete((ignored,failure)->{if(failure!=null)errors.incrementAndGet();emit(snapshot);});db.audit((String)run.document.get("actor"),"automation.run",status,Map.of("ruleId",run.document.get("ruleId"),"runId",run.id,"message",error==null?"Completed":error));
    }
    private void persist(Run run,Runnable continuation){Map<String,Object> snapshot;CompletableFuture<Void> write;synchronized(this){if(run.terminal())return;snapshot=run.snapshot();write=db.document("automation-runs",run.id,snapshot);}write.whenComplete((unused,error)->dispatch(()->{if(error!=null){finish(run,"failed","History persistence failed; no further actions were started");return;}synchronized(this){if(run.terminal())return;emit(snapshot);}continuation.run();}));}
    private void emit(Object snapshot){try{events.accept("automation",snapshot);}catch(Exception e){errors.incrementAndGet();}}
    private void dispatch(Runnable operation){try{worker.execute(operation);}catch(RejectedExecutionException ignored){}}
    private synchronized void trimHistory(){while(jobs.size()>200){String remove=null;for(var entry:jobs.entrySet())if(entry.getValue().terminal()){remove=entry.getKey();break;}if(remove==null)break;jobs.remove(remove);db.deleteDocument("automation-runs",remove);}}
    private AutomationGraph.Compiled rule(String id){AutomationGraph.uuid(id);AutomationGraph.Compiled graph=rules.get(id);if(graph==null)throw new IllegalArgumentException("Unknown automation rule");return graph;}
    private static void admin(String role){if(!Set.of("Owner","Admin").contains(role))throw new SecurityException("Admin permission required for automations");}
    private static void actor(String actor){if(actor==null||actor.isBlank()||actor.length()>100)throw new IllegalArgumentException("Invalid actor");}
    private static long createdAt(Map<String,Object> value){Object at=value.get("createdAt");return at instanceof Number n?n.longValue():0;}
    private static Map<String,Object> copy(Map<String,Object> value){return Json.object(Json.write(value));}
    private static String safeError(Throwable error){while(error instanceof CompletionException||error instanceof ExecutionException){if(error.getCause()==null)break;error=error.getCause();}if(error instanceof IllegalArgumentException&&error.getMessage()!=null)return error.getMessage().substring(0,Math.min(error.getMessage().length(),240));return "Automation operation failed ("+error.getClass().getSimpleName()+")";}
    private static Object summary(Object result){
        if(result==null)return Map.of("ok",true);if(result instanceof Map<?,?> map){Map<String,Object> out=new LinkedHashMap<>();for(String key:List.of("id","value","matched","trigger","seconds","ok","name","world","time","weather","delivered","given","amount","uuid","message")){Object value=map.get(key);if(value instanceof Number||value instanceof Boolean||value instanceof String s&&s.length()<=240)out.put(key,value);}return out.isEmpty()?Map.of("ok",true):out;}if(result instanceof Number||result instanceof Boolean)return result;if(result instanceof String s)return s.substring(0,Math.min(s.length(),240));return Map.of("ok",true);
    }
    @Override public void close(){List<Run> cancel;synchronized(this){if(closed)return;closed=true;enabled=false;cancel=new ArrayList<>(active.values());}for(Run run:cancel)cancel(run,"MCC stopped; remaining graph nodes were cancelled");worker.shutdownNow();}
    private final class Run {
        final String id;final AutomationGraph.Compiled graph;final Map<String,Object> context;final Map<String,Object> document;final LinkedHashMap<String,Map<String,Object>> states=new LinkedHashMap<>();final Set<String> activated=new HashSet<>();int index;boolean manual;String lastEventId;volatile boolean stopped;volatile CompletableFuture<?> operation;volatile ScheduledFuture<?> timer,deadline;
        Run(Map<String,Object> stored){id=(String)stored.get("runId");graph=null;context=Map.of();document=copy(stored);}
        Run(AutomationGraph.Compiled graph,String actor,Map<String,Object> context,long now){this.id=UUID.randomUUID().toString();this.graph=graph;this.context=Map.copyOf(context);document=new LinkedHashMap<>();document.put("runId",id);document.put("ruleId",graph.document().get("id"));document.put("name",graph.document().get("name"));document.put("actor",actor);document.put("status","queued");document.put("createdAt",now);document.put("finishedAt",0L);document.put("graphVersion",1);for(AutomationGraph.Node node:graph.order()){Map<String,Object> state=new LinkedHashMap<>();state.put("id",node.id());state.put("type",node.type());state.put("status","pending");states.put(node.id(),state);if(node.type().equals("trigger"))activated.add(node.id());}document.put("nodes",new ArrayList<>(states.values()));}
        boolean terminal(){return TERMINAL.contains(document.get("status"));}
        Map<String,Object> snapshot(){synchronized(Automation.this){return copy(document);}}
    }
}
