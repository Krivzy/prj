package dev.mcc.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static dev.mcc.core.AutomationGraphTest.*;
import static org.junit.jupiter.api.Assertions.*;

class AutomationEventFlowTest {
    @TempDir Path directory;
    private static Map<String,Object> event(String id,String action,Map<String,Object> params){return node(id,"action",Map.of("action",action,"params",params));}
    private static Map<String,Object> start(){return event("spawn","event.start",Map.of("preset","fireworks"));}
    private static Map<String,Object> stop(){return event("stop","event.stop",Map.of("fromLastEvent",true));}
    private static final class Runtime implements Automation.Runtime {
        final String created=UUID.randomUUID().toString();final List<Map<String,Object>> calls=new CopyOnWriteArrayList<>();
        public CompletableFuture<Object> read(String condition,Map<String,Object> config,Map<String,Object> context){return CompletableFuture.completedFuture(true);}
        public CompletableFuture<Object> action(String action,Map<String,Object> params){calls.add(Map.of("action",action,"params",new LinkedHashMap<>(params)));return CompletableFuture.completedFuture(action.equals("event.start")?Map.of("id",created):Map.of("ok",true));}
    }
    private static Map<String,Object> terminal(Automation a,String id)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(System.nanoTime()<end){var row=a.runStatus(id);if(Set.of("completed","cancelled","failed").contains(row.get("status")))return row;Thread.sleep(5);}throw new AssertionError("Run did not terminate");}
    private static boolean waiting(Automation a,String run){for(Object value:(List<?>)a.runStatus(run).get("nodes")){var state=AutomationGraph.map(value,"state");if(state.get("id").equals("wait"))return state.get("status").equals("running");}return false;}
    @Test void StartPauseDelayResumeStopUsesOnlyThisRunsSuccessfulEventId()throws Exception{
        Runtime runtime=new Runtime();try(Database db=new Database(directory.resolve("db"));Automation a=new Automation(db,runtime,()->100000L)){
            var rule=graph(List.of(trigger(),start(),event("pause","event.pause",Map.of("fromLastEvent",true,"paused",true)),node("wait","delay",Map.of("seconds",.05)),event("resume","event.pause",Map.of("fromLastEvent",true,"paused",false)),stop()),List.of(edge("a","start","spawn","next"),edge("b","spawn","pause","next"),edge("c","pause","wait","next"),edge("d","wait","resume","next"),edge("e","resume","stop","next")));
            a.save(rule,"admin","Admin");String run=(String)a.run((String)rule.get("id"),"admin","Admin",Map.of("eventId",UUID.randomUUID().toString()),true).get("runId");assertEquals("completed",terminal(a,run).get("status"));assertEquals(List.of("event.start","event.pause","event.pause","event.stop"),runtime.calls.stream().map(c->c.get("action")).toList());
            for(var call:runtime.calls.subList(1,4)){var params=AutomationGraph.map(call.get("params"),"params");assertEquals(runtime.created,params.get("eventId"));assertFalse(params.containsKey("fromLastEvent"));}
        }
    }
    @Test void PreviousRunOrTriggerIdCannotSupplyFromLastEventAuthority()throws Exception{
        Runtime runtime=new Runtime();try(Database db=new Database(directory.resolve("db"));Automation a=new Automation(db,runtime,()->100000L)){
            var first=graph(List.of(trigger(),start()),List.of(edge("a","start","spawn","next")));a.save(first,"admin","Admin");String run=(String)a.run((String)first.get("id"),"admin","Admin",Map.of(),true).get("runId");assertEquals("completed",terminal(a,run).get("status"));
            var second=graph(List.of(trigger(),stop()),List.of(edge("b","start","stop","next")));a.save(second,"admin","Admin");String next=(String)a.run((String)second.get("id"),"admin","Admin",Map.of("eventId",runtime.created),true).get("runId");assertEquals("failed",terminal(a,next).get("status"));assertEquals(1,runtime.calls.size());
        }
    }
    @Test void CancellationDuringDelayCannotStopAnEventAfterOffOn()throws Exception{
        Runtime runtime=new Runtime();try(Database db=new Database(directory.resolve("db"));Automation a=new Automation(db,runtime,()->100000L)){
            var rule=graph(List.of(trigger(),start(),node("wait","delay",Map.of("seconds",.3)),stop()),List.of(edge("a","start","spawn","next"),edge("b","spawn","wait","next"),edge("c","wait","stop","next")));a.save(rule,"admin","Admin");String run=(String)a.run((String)rule.get("id"),"admin","Admin",Map.of(),true).get("runId");long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(System.nanoTime()<end&&!waiting(a,run))Thread.sleep(5);
            // Observe the delay specifically rather than relying on action completion timing.
            assertTrue(waiting(a,run));a.enabled(false);a.enabled(true);assertEquals("cancelled",terminal(a,run).get("status"));Thread.sleep(350);assertEquals(1,runtime.calls.size());
        }
    }
    @Test void EventIdSourcesAreExclusiveAndTyped(){AutomationGraph.validateAction("event.stop",Map.of("fromLastEvent",true));List<Map<String,Object>> invalid=List.of(Map.of("fromLastEvent",true,"eventId",UUID.randomUUID().toString()),Map.of("fromLastEvent",true,"fromTrigger",true),Map.of("fromLastEvent","true"));for(var params:invalid)assertThrows(IllegalArgumentException.class,()->AutomationGraph.validateAction("event.stop",params));}
}
