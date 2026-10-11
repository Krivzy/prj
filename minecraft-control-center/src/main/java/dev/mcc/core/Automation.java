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
    }
    private static final Set<String> TERMINAL=Set.of("completed","failed","cancelled");
    private final ConcurrentHashMap<String,AutomationGraph.Compiled> rules=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,Long> lastRun=new ConcurrentHashMap<>();
    private final LinkedHashMap<String,Run> jobs=new LinkedHashMap<>();
    private final Map<String,Run> active=new HashMap<>();
    private final Database db;private final Runtime runtime;private final LongSupplier clock;
    private final ScheduledThreadPoolExecutor worker=new ScheduledThreadPoolExecutor(1,r->{Thread t=new Thread(r,"MCC-Automation");t.setDaemon(true);return t;});
    private volatile BiConsumer<String,Object> events=(topic,value)->{};
    private volatile boolean enabled=true,closed=false;
    private final AtomicLong runs=new AtomicLong(),errors=new AtomicLong(),rejected=new AtomicLong();

    public Automation(Database db,MainThread main,GameService game)throws Exception {this(db,gameRuntime(main,game),System::currentTimeMillis);}
    public Automation(Database db,Runtime runtime,LongSupplier clock)throws Exception {
        this.db=db;this.runtime=runtime;this.clock=clock;worker.setRemoveOnCancelPolicy(true);worker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        for(Map<String,Object> stored:db.documents("automations").get(10,TimeUnit.SECONDS)){
            try {if(rules.size()>=100)throw new IllegalArgumentException("Automation limit reached");AutomationGraph.Compiled graph=AutomationGraph.compile(stored);rules.put((String)graph.document().get("id"),graph);lastRun.put((String)graph.document().get("id"),clock.getAsLong());}
            catch(IllegalArgumentException e){errors.incrementAndGet();db.audit("automation","automation.load","error",Map.of("message","Invalid stored graph was excluded"));}
        }
        List<Map<String,Object>> storedRuns=db.documents("automation-runs").get(10,TimeUnit.SECONDS);storedRuns.sort(Comparator.comparingLong(Automation::createdAt));int discard=Math.max(0,storedRuns.size()-200);
        for(int i=0;i<storedRuns.size();i++){
            Map<String,Object> stored=storedRuns.get(i);String id=Json.text(stored,"runId","");if(id.isEmpty())continue;
            if(i<discard){db.deleteDocument("automation-runs",id);continue;}
            if(!TERMINAL.contains(stored.get("status"))){stored=new LinkedHashMap<>(stored);stored.put("status","cancelled");stored.put("finishedAt",clock.getAsLong());stored.put("error","Server restarted; unfinished runs are not resumed");List<Map<String,Object>> states=new ArrayList<>();if(stored.get("nodes") instanceof List<?> list)for(Object value:list){Map<String,Object> state=AutomationGraph.map(value,"node status");if(!Set.of("completed","skipped","failed","cancelled").contains(state.get("status"))){state.put("status","cancelled");state.put("finishedAt",clock.getAsLong());}states.add(state);}stored.put("nodes",states);db.document("automation-runs",id,stored).get(10,TimeUnit.SECONDS);}
            jobs.put(id,new Run(stored));
        }
    }
    private static Runtime gameRuntime(MainThread main,GameService game){return new Runtime(){
        public CompletableFuture<Object> action(String action,Map<String,Object> params){return main.call(()->game.execute(action,params));}
        public CompletableFuture<Object> read(String condition,Map<String,Object> config,Map<String,Object> context){return main.call(()->switch(condition){
            case "onlineCount" -> Bukkit.getOnlinePlayers().size();
            case "tps" -> {double[] value=ServerPlatform.tps();if(value.length==0)throw new UnsupportedOperationException("TPS measurement is not available yet");yield value[0];}
            case "playerOnline" -> {String uuid=Json.text(config,"uuid",Json.text(context,"uuid",""));AutomationGraph.uuid(uuid);yield Bukkit.getPlayer(UUID.fromString(uuid))!=null;}
            case "worldTime","weather" -> {String name=Json.text(config,"world",Json.text(context,"world",""));World world=Bukkit.getWorld(name);if(world==null)throw new IllegalArgumentException("Condition world is not loaded");yield condition.equals("worldTime")?world.getTime():world.isThundering()?"thunder":world.hasStorm()?"rain":"clear";}
            default -> throw new IllegalArgumentException("Unsupported condition");
        });}
    };}
    public void setEvents(BiConsumer<String,Object> events){this.events=Objects.requireNonNull(events);}
    public List<Map<String,Object>> list(){return rules.values().stream().sorted(Comparator.comparing(g->(String)g.document().get("name"))).map(g->copy(g.document())).toList();}
    public Map<String,Object> catalog(){return AutomationGraph.catalog();}
    public Map<String,Object> exportLegacy(String id){return AutomationGraph.exportLegacy(rule(id));}
    public Map<String,Object> save(Map<String,Object> body)throws Exception {return save(body,"legacy-api","Admin");}
    public synchronized Map<String,Object> save(Map<String,Object> body,String actor,String role)throws Exception {
        admin(role);actor(actor);if(closed)throw new IllegalStateException("Automation engine is closed");AutomationGraph.Compiled graph=AutomationGraph.compile(body);String id=(String)graph.document().get("id");if(!rules.containsKey(id)&&rules.size()>=100)throw new IllegalArgumentException("Automation limit reached");
        db.document("automations",id,graph.document()).get(10,TimeUnit.SECONDS);rules.put(id,graph);lastRun.put(id,clock.getAsLong());if(!Boolean.TRUE.equals(graph.document().get("enabled"))){Run running=active.get(id);if(running!=null)cancel(running,"Rule disabled");}db.audit(actor,"automation.save","ok",Map.of("id",id,"graphVersion",1));return copy(graph.document());
    }
    public synchronized void delete(String id)throws Exception {AutomationGraph.uuid(id);db.deleteDocument("automations",id).get(10,TimeUnit.SECONDS);rules.remove(id);lastRun.remove(id);lastRun.remove("manual:"+id);Run running=active.get(id);if(running!=null)cancel(running,"Rule deleted");}
    public Map<String,Object> run(String ruleId,String actor,String role,Map<String,Object> context,boolean confirmed){admin(role);actor(actor);if(!confirmed)throw new IllegalArgumentException("Explicit confirmation is required to run automation actions");return start(rule(ruleId),actor,AutomationGraph.context(context),true);}
    public synchronized Map<String,Object> runStatus(String runId){AutomationGraph.uuid(runId);Run run=jobs.get(runId);if(run==null)throw new IllegalArgumentException("Unknown automation run");return run.snapshot();}
    public synchronized List<Map<String,Object>> history(String ruleId,int limit){AutomationGraph.uuid(ruleId);if(limit<1||limit>50)throw new IllegalArgumentException("History limit must be 1–50");List<Run> found=new ArrayList<>(jobs.values());Collections.reverse(found);return found.stream().filter(r->ruleId.equals(r.document.get("ruleId"))).limit(limit).map(Run::snapshot).toList();}
    public void trigger(String trigger,Map<String,Object> rawContext){
        if(!enabled||closed||!AutomationGraph.TRIGGERS.contains(trigger))return;Map<String,Object> context;try{context=AutomationGraph.context(rawContext);}catch(IllegalArgumentException e){errors.incrementAndGet();return;}
        long now=clock.getAsLong();for(AutomationGraph.Compiled graph:rules.values()){
            if(!Boolean.TRUE.equals(graph.document().get("enabled"))||!trigger.equals(graph.trigger()))continue;String id=(String)graph.document().get("id"),world=(String)graph.document().get("world");if(!world.isEmpty()&&!world.equals(context.get("world"))&&!trigger.equals("interval"))continue;
            long cooldown=((Number)graph.document().get("cooldownSeconds")).longValue()*1000;if(trigger.equals("interval"))cooldown=Math.max(cooldown,graph.intervalSeconds()*1000L);if(now-lastRun.getOrDefault(id,now)<cooldown)continue;try{start(graph,"automation:"+id,context,false);}catch(IllegalStateException e){rejected.incrementAndGet();}
        }
    }
    public void pulse(){trigger("interval",Map.of());}
    public void enabled(boolean value){enabled=value;if(!value){List<Run> cancel;synchronized(this){cancel=new ArrayList<>(active.values());}for(Run run:cancel)dispatch(()->cancel(run,"Automation module disabled"));}}
    public synchronized Map<String,Object> stats(){return Map.of("automationRuns",runs.get(),"automationErrors",errors.get(),"automationRejected",rejected.get(),"automationActive",active.size());}

    private synchronized Map<String,Object> start(AutomationGraph.Compiled graph,String actor,Map<String,Object> context,boolean manual){
        if(closed||!enabled)throw new IllegalStateException("Automation module is unavailable");String ruleId=(String)graph.document().get("id");if(active.size()>=4||active.containsKey(ruleId))throw new IllegalStateException("Automation run queue is full or this rule is already running");
        if(manual&&lastRun.containsKey("manual:"+ruleId)&&clock.getAsLong()-lastRun.get("manual:"+ruleId)<1000)throw new IllegalStateException("Wait one second before running this rule again");
        if(!manual){long cooldown=((Number)graph.document().get("cooldownSeconds")).longValue()*1000;if(graph.trigger().equals("interval"))cooldown=Math.max(cooldown,graph.intervalSeconds()*1000L);if(clock.getAsLong()-lastRun.getOrDefault(ruleId,clock.getAsLong())<cooldown)throw new IllegalStateException("Automation cooldown has not elapsed");}
        Run run=new Run(graph,actor,context,clock.getAsLong());active.put(ruleId,run);jobs.put(run.id,run);lastRun.put(ruleId,clock.getAsLong());if(manual)lastRun.put("manual:"+ruleId,clock.getAsLong());trimHistory();dispatch(()->{run.deadline=worker.schedule(()->cancel(run,"Run exceeded its 90 second deadline"),90,TimeUnit.SECONDS);persist(run,()->{synchronized(Automation.this){if(run.terminal())return;run.document.put("status","running");}advance(run);});});return run.snapshot();
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
        synchronized(this){if(run.terminal())return;}
        if(node.type().equals("trigger")){complete(run,node,state,Map.of("trigger",run.graph.trigger()),"next");return;}
        if(node.type().equals("delay")){synchronized(this){if(run.terminal()||closed)return;double seconds=((Number)node.config().get("seconds")).doubleValue();run.timer=worker.schedule(()->complete(run,node,state,Map.of("seconds",seconds),"next"),Math.round(seconds*1000),TimeUnit.MILLISECONDS);}return;}
        try{
            Map<String,Object> context=new LinkedHashMap<>(run.context);String world=(String)run.graph.document().get("world");if(!world.isEmpty())context.putIfAbsent("world",world);CompletableFuture<Object> operation;
            synchronized(this){
                if(run.terminal()||closed)return;
                if(node.type().equals("condition"))operation=runtime.read((String)node.config().get("condition"),node.config(),context);
                else {Map<String,Object> params=new LinkedHashMap<>(AutomationGraph.map(node.config().get("params"),"params"));if(context.containsKey("uuid"))params.putIfAbsent("uuid",context.get("uuid"));if(context.containsKey("world"))params.putIfAbsent("world",context.get("world"));String action=(String)node.config().get("action");AutomationGraph.validateAction(action,params);operation=runtime.action(action,params);}
                run.operation=operation;
            }
            operation.whenComplete((result,error)->dispatch(()->{
                if(error!=null){failNode(run,state,error);return;}
                if(node.type().equals("condition")){try{boolean accepted=AutomationGraph.compare(result,(String)node.config().get("operator"),node.config().get("value"));complete(run,node,state,Map.of("value",result,"matched",accepted),accepted?"true":"false");}catch(Exception e){failNode(run,state,e);}}
                else complete(run,node,state,result,"next");
            }));
        }catch(Exception e){failNode(run,state,e);}
    }
    private void complete(Run run,AutomationGraph.Node node,Map<String,Object> state,Object result,String handle){synchronized(this){if(run.terminal())return;run.operation=null;state.put("status","completed");state.put("finishedAt",clock.getAsLong());state.put("result",summary(result));for(AutomationGraph.Edge edge:run.graph.outgoing(node.id(),handle))run.activated.add(edge.target());}persist(run,()->advance(run));}
    private void failNode(Run run,Map<String,Object> state,Throwable error){synchronized(this){if(run.terminal())return;run.operation=null;state.put("status","failed");state.put("finishedAt",clock.getAsLong());state.put("error",safeError(error));}finish(run,"failed",safeError(error));}
    private void cancel(Run run,String reason){synchronized(this){if(run.terminal())return;if(run.timer!=null)run.timer.cancel(false);if(run.operation!=null&&!run.operation.isDone()&&!run.operation.cancel(false))run.document.put("effectMayComplete",true);}finish(run,"cancelled",reason);}
    private void finish(Run run,String status,String error){
        Map<String,Object> snapshot;CompletableFuture<Void> write;
        synchronized(this){
            if(run.terminal())return;run.document.put("status",status);run.document.put("finishedAt",clock.getAsLong());if(error!=null)run.document.put("error",error);for(Map<String,Object> state:run.states.values())if(Set.of("pending","running").contains(state.get("status"))){state.put("status",status.equals("cancelled")?"cancelled":"skipped");state.put("finishedAt",clock.getAsLong());}
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
        if(result==null)return Map.of("ok",true);if(result instanceof Map<?,?> map){Map<String,Object> out=new LinkedHashMap<>();for(String key:List.of("value","matched","trigger","seconds","ok","name","world","time","weather","delivered","given","amount","uuid","message")){Object value=map.get(key);if(value instanceof Number||value instanceof Boolean||value instanceof String s&&s.length()<=240)out.put(key,value);}return out.isEmpty()?Map.of("ok",true):out;}if(result instanceof Number||result instanceof Boolean)return result;if(result instanceof String s)return s.substring(0,Math.min(s.length(),240));return Map.of("ok",true);
    }
    @Override public void close(){List<Run> cancel;synchronized(this){if(closed)return;closed=true;enabled=false;cancel=new ArrayList<>(active.values());}for(Run run:cancel)cancel(run,"MCC stopped; remaining graph nodes were cancelled");worker.shutdownNow();}
    private final class Run {
        final String id;final AutomationGraph.Compiled graph;final Map<String,Object> context;final Map<String,Object> document;final LinkedHashMap<String,Map<String,Object>> states=new LinkedHashMap<>();final Set<String> activated=new HashSet<>();int index;volatile CompletableFuture<?> operation;volatile ScheduledFuture<?> timer,deadline;
        Run(Map<String,Object> stored){id=(String)stored.get("runId");graph=null;context=Map.of();document=copy(stored);}
        Run(AutomationGraph.Compiled graph,String actor,Map<String,Object> context,long now){this.id=UUID.randomUUID().toString();this.graph=graph;this.context=Map.copyOf(context);document=new LinkedHashMap<>();document.put("runId",id);document.put("ruleId",graph.document().get("id"));document.put("name",graph.document().get("name"));document.put("actor",actor);document.put("status","queued");document.put("createdAt",now);document.put("finishedAt",0L);document.put("graphVersion",1);for(AutomationGraph.Node node:graph.order()){Map<String,Object> state=new LinkedHashMap<>();state.put("id",node.id());state.put("type",node.type());state.put("status","pending");states.put(node.id(),state);if(node.type().equals("trigger"))activated.add(node.id());}document.put("nodes",new ArrayList<>(states.values()));}
        boolean terminal(){return TERMINAL.contains(document.get("status"));}
        Map<String,Object> snapshot(){synchronized(Automation.this){return copy(document);}}
    }
}
