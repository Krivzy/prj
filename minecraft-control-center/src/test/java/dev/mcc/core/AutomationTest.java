package dev.mcc.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static dev.mcc.core.AutomationGraphTest.*;
import static org.junit.jupiter.api.Assertions.*;

class AutomationTest {
    @TempDir Path directory;
    private final AtomicLong now=new AtomicLong(1_000_000);
    private static final class Fixture implements Automation.Runtime {
        final List<String> calls=new CopyOnWriteArrayList<>();final AtomicInteger online=new AtomicInteger();final AtomicInteger time=new AtomicInteger();volatile CompletableFuture<Object> pending;
        public CompletableFuture<Object> read(String condition,Map<String,Object> config,Map<String,Object> context){calls.add("read:"+condition);return CompletableFuture.completedFuture(condition.equals("onlineCount")?online.get():condition.equals("worldTime")?time.get():false);}
        public CompletableFuture<Object> action(String action,Map<String,Object> params){calls.add(action+":"+params.getOrDefault("time",params.getOrDefault("message",""))+":"+params.get("world"));if(pending!=null)return pending;if(action.equals("world.time"))time.set(((Number)params.get("time")).intValue());return CompletableFuture.completedFuture(Map.of("time",time.get(),"world",params.getOrDefault("world","")));}
    }
    private static Map<String,Object> waitFor(Automation engine,String id)throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<end){var job=engine.runStatus(id);if(Set.of("completed","failed","cancelled").contains(job.get("status")))return job;Thread.sleep(5);}throw new AssertionError("Automation did not finish");}
    private static void committed(Database db,String id,String status)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<end){var stored=db.document("automation-runs",id).get(2,TimeUnit.SECONDS);if(stored.isPresent()&&status.equals(stored.get().get("status")))return;Thread.sleep(5);}throw new AssertionError("Run state was not persisted");}
    private static String id(Map<String,Object> value){return (String)value.get("id");}
    private static String runId(Map<String,Object> value){return (String)value.get("runId");}
    @SuppressWarnings("unchecked") private static String nodeStatus(Map<String,Object> job,String id){for(Map<String,Object> state:(List<Map<String,Object>>)job.get("nodes"))if(id.equals(state.get("id")))return (String)state.get("status");throw new AssertionError("Missing node");}

    @Test void realBranchSelectionMultipleActionsAndAnyActivatedMergeRunInOrder()throws Exception{
        Fixture fixture=new Fixture();try(Database db=new Database(directory.resolve("db"));Automation engine=new Automation(db,fixture,now::get)){
            var graph=graph(List.of(trigger(),node("if","condition",Map.of("condition","onlineCount","operator","eq","value",0)),action("yes",6000),action("no",18000),action("merged",7000),action("tail",8000)),List.of(edge("1","start","if","next"),edge("2","if","yes","true"),edge("3","if","no","false"),edge("4","yes","merged","next"),edge("5","no","merged","next"),edge("6","merged","tail","next")));
            engine.save(graph,"owner","Owner");String first=runId(engine.run(id(graph),"owner","Owner",Map.of(),true));var job=waitFor(engine,first);assertEquals("completed",job.get("status"));assertEquals(List.of("read:onlineCount","world.time:6000:fixture","world.time:7000:fixture","world.time:8000:fixture"),fixture.calls);assertEquals("skipped",nodeStatus(job,"no"));assertEquals("completed",nodeStatus(job,"merged"));committed(db,first,"completed");
            fixture.calls.clear();fixture.online.set(1);now.addAndGet(1001);String second=runId(engine.run(id(graph),"admin","Admin",Map.of(),true));var negative=waitFor(engine,second);assertEquals("skipped",nodeStatus(negative,"yes"));assertEquals(List.of("read:onlineCount","world.time:18000:fixture","world.time:7000:fixture","world.time:8000:fixture"),fixture.calls);assertEquals(8000,fixture.time.get());assertEquals(2,engine.history(id(graph),50).size());
        }
    }
    @Test void roleConfirmationModuleAndInFlightLimitAreServerEnforced()throws Exception{
        Fixture fixture=new Fixture();fixture.pending=new CompletableFuture<>();try(Database db=new Database(directory.resolve("db"));Automation engine=new Automation(db,fixture,now::get)){
            var graph=simple();assertThrows(SecurityException.class,()->engine.save(graph,"viewer","Viewer"));engine.save(graph,"owner","Owner");assertThrows(SecurityException.class,()->engine.run(id(graph),"viewer","Viewer",Map.of(),true));assertThrows(SecurityException.class,()->engine.run(id(graph),"mod","Moderator",Map.of(),true));assertThrows(IllegalArgumentException.class,()->engine.run(id(graph),"owner","Owner",Map.of(),false));
            String started=runId(engine.run(id(graph),"owner","Owner",Map.of(),true));assertThrows(IllegalStateException.class,()->engine.run(id(graph),"owner","Owner",Map.of(),true));engine.enabled(false);assertEquals("cancelled",waitFor(engine,started).get("status"));assertThrows(IllegalStateException.class,()->engine.run(id(graph),"owner","Owner",Map.of(),true));
        }
    }
    @Test void delayedRunIsCancelledWithoutApplyingRemainingGameActions()throws Exception{
        Fixture fixture=new Fixture();try(Database db=new Database(directory.resolve("db"));Automation engine=new Automation(db,fixture,now::get)){
            var graph=graph(List.of(trigger(),node("wait","delay",Map.of("seconds",.3)),action("set",1)),List.of(edge("1","start","wait","next"),edge("2","wait","set","next")));engine.save(graph,"owner","Owner");String started=runId(engine.run(id(graph),"owner","Owner",Map.of(),true));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!nodeStatus(engine.runStatus(started),"wait").equals("running")&&System.nanoTime()<deadline)Thread.sleep(5);assertEquals("running",nodeStatus(engine.runStatus(started),"wait"));engine.delete(id(graph));assertEquals("cancelled",waitFor(engine,started).get("status"));Thread.sleep(350);assertTrue(fixture.calls.isEmpty());committed(db,started,"cancelled");
        }
    }
    @Test void startedUncancellableOperationReportsUncertaintyAndCannotActivateNextNode()throws Exception{
        Fixture fixture=new Fixture();fixture.pending=new CompletableFuture<>(){@Override public boolean cancel(boolean interrupt){return false;}};
        try(Database db=new Database(directory.resolve("db"));Automation engine=new Automation(db,fixture,now::get)){
            var graph=graph(List.of(trigger(),action("first",1),action("second",2)),List.of(edge("1","start","first","next"),edge("2","first","second","next")));engine.save(graph,"owner","Owner");String started=runId(engine.run(id(graph),"owner","Owner",Map.of(),true));long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(fixture.calls.isEmpty()&&System.nanoTime()<deadline)Thread.sleep(5);assertEquals(1,fixture.calls.size());engine.enabled(false);var job=waitFor(engine,started);assertEquals(true,job.get("effectMayComplete"));fixture.pending.complete(Map.of("ok",true));Thread.sleep(50);assertEquals(1,fixture.calls.size());assertEquals("cancelled",engine.runStatus(started).get("status"));committed(db,started,"cancelled");
        }
    }
    @Test void actionFailureStopsDownstreamAndKeepsTypedNodeError()throws Exception{
        Fixture fixture=new Fixture();fixture.pending=CompletableFuture.failedFuture(new IllegalArgumentException("World is not loaded"));try(Database db=new Database(directory.resolve("db"));Automation engine=new Automation(db,fixture,now::get)){
            var graph=graph(List.of(trigger(),action("first",1),action("second",2)),List.of(edge("1","start","first","next"),edge("2","first","second","next")));engine.save(graph,"owner","Owner");String started=runId(engine.run(id(graph),"owner","Owner",Map.of(),true));var job=waitFor(engine,started);assertEquals("failed",job.get("status"));assertEquals("failed",nodeStatus(job,"first"));assertEquals("skipped",nodeStatus(job,"second"));assertEquals(1,fixture.calls.size());assertEquals("World is not loaded",job.get("error"));
        }
    }
    @Test void fourGlobalRunsBoundQueueAndImmutableSnapshotsPreventExternalEdits()throws Exception{
        Fixture fixture=new Fixture();fixture.pending=new CompletableFuture<>();try(Database db=new Database(directory.resolve("db"));Automation engine=new Automation(db,fixture,now::get)){
            List<Map<String,Object>> graphs=new ArrayList<>();for(int i=0;i<5;i++){var graph=simple();graphs.add(graph);engine.save(graph,"owner","Owner");}for(int i=0;i<4;i++)engine.run(id(graphs.get(i)),"owner","Owner",Map.of(),true);assertThrows(IllegalStateException.class,()->engine.run(id(graphs.get(4)),"owner","Owner",Map.of(),true));assertEquals(4,engine.stats().get("automationActive"));var exported=engine.list();exported.getFirst().put("enabled",false);assertTrue(engine.list().stream().allMatch(g->Boolean.TRUE.equals(g.get("enabled"))));
        }
    }
    @Test void legacyRuleIsAdaptedWithoutDatabaseRewriteAndCooldownWorldFilterArePreserved()throws Exception{
        Fixture fixture=new Fixture();Path path=directory.resolve("db");Map<String,Object> legacy=Map.of("id",UUID.randomUUID().toString(),"name","Legacy","enabled",true,"world","fixture","trigger","join","intervalSeconds",60,"action","player.message","params",Map.of("message","Hi"));
        try(Database db=new Database(path)){
            db.document("automations",id(legacy),legacy).get();try(Automation engine=new Automation(db,fixture,now::get)){
                assertEquals(1,engine.list().getFirst().get("graphVersion"));assertFalse(db.document("automations",id(legacy)).get().orElseThrow().containsKey("graphVersion"));now.addAndGet(61000);String uuid=UUID.randomUUID().toString();engine.trigger("join",Map.of("world","other","uuid",uuid));assertEquals(0,engine.history(id(legacy),50).size());engine.trigger("join",Map.of("world","fixture","uuid",uuid));long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(engine.history(id(legacy),50).isEmpty()&&System.nanoTime()<end)Thread.sleep(5);String started=(String)engine.history(id(legacy),50).getFirst().get("runId");assertEquals("completed",waitFor(engine,started).get("status"));engine.trigger("join",Map.of("world","fixture","uuid",uuid));assertEquals(1,engine.history(id(legacy),50).size());assertEquals(List.of("player.message:Hi:fixture"),fixture.calls);
            }
        }
    }
    @Test void graphsPositionsHistoryAndInterruptedJobsSurviveSqliteReopenWithoutResuming()throws Exception{
        Fixture fixture=new Fixture();Path path=directory.resolve("db");var graph=simple();nodes(graph).get(1).put("pos",Map.of("x",432.5,"y",-20));String completed;
        try(Database db=new Database(path);Automation engine=new Automation(db,fixture,now::get)){
            engine.save(graph,"owner","Owner");completed=runId(engine.run(id(graph),"owner","Owner",Map.of(),true));assertEquals("completed",waitFor(engine,completed).get("status"));committed(db,completed,"completed");db.document("automation-runs","0f05e1a0-e5ab-48c2-ab99-3760985c856f",Map.of("runId","0f05e1a0-e5ab-48c2-ab99-3760985c856f","ruleId",id(graph),"status","running","createdAt",0,"nodes",List.of(Map.of("id","unknown","status","running")))).get();
        }
        fixture.calls.clear();try(Database db=new Database(path);Automation reopened=new Automation(db,fixture,now::get)){
            assertEquals("completed",reopened.runStatus(completed).get("status"));var restored=reopened.list().getFirst();assertEquals(432.5,AutomationGraph.number(AutomationGraph.map(nodes(restored).get(1).get("pos"),"pos").get("x"),-10000,10000,"x"));assertEquals("cancelled",reopened.runStatus("0f05e1a0-e5ab-48c2-ab99-3760985c856f").get("status"));committed(db,"0f05e1a0-e5ab-48c2-ab99-3760985c856f","cancelled");assertTrue(fixture.calls.isEmpty());
        }
    }
    @Test void intervalWaitsForCooldownAndDelayCompletesBeforeFollowingAction()throws Exception{
        Fixture fixture=new Fixture();try(Database db=new Database(directory.resolve("db"));Automation engine=new Automation(db,fixture,now::get)){
            var graph=graph(List.of(trigger(),node("wait","delay",Map.of("seconds",.1)),action("set",42)),List.of(edge("1","start","wait","next"),edge("2","wait","set","next")));engine.save(graph,"owner","Owner");engine.pulse();assertTrue(engine.history(id(graph),50).isEmpty());now.addAndGet(29999);engine.pulse();assertTrue(engine.history(id(graph),50).isEmpty());now.incrementAndGet();engine.pulse();long queuedDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(engine.history(id(graph),50).isEmpty()&&System.nanoTime()<queuedDeadline)Thread.sleep(5);assertEquals(1,engine.history(id(graph),50).size());String started=(String)engine.history(id(graph),50).getFirst().get("runId");var result=waitFor(engine,started);assertEquals("completed",result.get("status"));assertEquals("completed",nodeStatus(result,"wait"));assertEquals(List.of("world.time:42:fixture"),fixture.calls);engine.pulse();assertEquals(1,engine.history(id(graph),50).size());
        }
    }
    @Test void startupHistoryIsBoundedAndOldestDocumentsArePruned()throws Exception{
        Fixture fixture=new Fixture();try(Database db=new Database(directory.resolve("db"))){
            String rule=UUID.randomUUID().toString();List<String> ids=new ArrayList<>();for(int i=0;i<205;i++){String id=UUID.randomUUID().toString();ids.add(id);db.document("automation-runs",id,Map.of("runId",id,"ruleId",rule,"status","completed","createdAt",i,"nodes",List.of())).get();}
            try(Automation engine=new Automation(db,fixture,now::get)){assertEquals(50,engine.history(rule,50).size());assertThrows(IllegalArgumentException.class,()->engine.runStatus(ids.getFirst()));assertEquals(204,engine.runStatus(ids.getLast()).get("createdAt"));assertEquals(200,db.documents("automation-runs").get().size());assertTrue(fixture.calls.isEmpty());}
        }
    }
}
