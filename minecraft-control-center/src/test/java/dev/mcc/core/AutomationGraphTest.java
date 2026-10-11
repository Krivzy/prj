package dev.mcc.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AutomationGraphTest {
    static Map<String,Object> node(String id,String type,Map<String,Object> config){return new LinkedHashMap<>(Map.of("id",id,"type",type,"config",new LinkedHashMap<>(config),"pos",Map.of("x",0,"y",0)));}
    static Map<String,Object> edge(String id,String source,String target,String handle){return new LinkedHashMap<>(Map.of("id",id,"source",source,"target",target,"sourceHandle",handle));}
    static Map<String,Object> graph(List<Map<String,Object>> nodes,List<Map<String,Object>> edges){Map<String,Object> body=new LinkedHashMap<>();body.put("id",UUID.randomUUID().toString());body.put("name","Fixture graph");body.put("enabled",true);body.put("confirmed",true);body.put("world","fixture");body.put("cooldownSeconds",30);body.put("graphVersion",1);body.put("nodes",new ArrayList<>(nodes));body.put("edges",new ArrayList<>(edges));return body;}
    static Map<String,Object> trigger(){return node("start","trigger",Map.of("trigger","interval","intervalSeconds",30));}
    static Map<String,Object> action(String id,int time){return node(id,"action",Map.of("action","world.time","params",Map.of("time",time)));}
    static Map<String,Object> simple(){return graph(List.of(trigger(),action("set",6000)),List.of(edge("a","start","set","next")));}
    @SuppressWarnings("unchecked") static List<Map<String,Object>> nodes(Map<String,Object> graph){return (List<Map<String,Object>>)graph.get("nodes");}
    @SuppressWarnings("unchecked") static List<Map<String,Object>> edges(Map<String,Object> graph){return (List<Map<String,Object>>)graph.get("edges");}

    @Test void legacyConversionAndExportKeepBehaviorAndMetadata(){
        Map<String,Object> item=Map.of("material","DIAMOND_SWORD","name","Fixture","enchantments",Map.of("minecraft:sharpness",3),"lore",List.of("one","two"));
        Map<String,Object> legacy=Map.of("id",UUID.randomUUID().toString(),"name","Legacy","enabled",false,"world","world","trigger","join","intervalSeconds",120,"action","player.give","params",Map.of("item",item));
        var compiled=AutomationGraph.compile(legacy);assertEquals(120,compiled.document().get("cooldownSeconds"));assertEquals("join",compiled.trigger());assertEquals(false,compiled.document().get("enabled"));assertEquals("world",compiled.document().get("world"));assertEquals(legacy,AutomationGraph.exportLegacy(compiled));assertFalse(legacy.containsKey("graphVersion"));
    }
    @Test void cycleDanglingDuplicateAndIncorrectBranchHandlesAreRejected(){
        var cycle=graph(List.of(trigger(),action("a",1),action("b",2)),List.of(edge("1","start","a","next"),edge("2","a","b","next"),edge("3","b","a","next")));assertThrows(IllegalArgumentException.class,()->AutomationGraph.compile(cycle));
        var dangling=simple();edges(dangling).add(edge("2","set","missing","next"));assertThrows(IllegalArgumentException.class,()->AutomationGraph.compile(dangling));
        var duplicate=simple();edges(duplicate).add(edge("2","start","set","next"));assertThrows(IllegalArgumentException.class,()->AutomationGraph.compile(duplicate));
        var wrongPort=simple();edges(wrongPort).getFirst().put("sourceHandle","true");assertThrows(IllegalArgumentException.class,()->AutomationGraph.compile(wrongPort));
        var duplicateNode=simple();nodes(duplicateNode).add(action("set",100));assertThrows(IllegalArgumentException.class,()->AutomationGraph.compile(duplicateNode));
    }
    @Test void onlyReachableSingleRootGraphAndBoundedDelaysAreAccepted(){
        var unreachable=simple();nodes(unreachable).add(action("orphan",2));assertThrows(IllegalArgumentException.class,()->AutomationGraph.compile(unreachable));
        var twoTriggers=simple();nodes(twoTriggers).add(node("other","trigger",Map.of("trigger","join")));assertThrows(IllegalArgumentException.class,()->AutomationGraph.compile(twoTriggers));
        var delay=graph(List.of(trigger(),node("d1","delay",Map.of("seconds",40)),node("d2","delay",Map.of("seconds",30)),action("a",1)),List.of(edge("1","start","d1","next"),edge("2","d1","d2","next"),edge("3","d2","a","next")));assertThrows(IllegalArgumentException.class,()->AutomationGraph.compile(delay));
        var self=simple();edges(self).add(edge("2","set","set","next"));assertThrows(IllegalArgumentException.class,()->AutomationGraph.compile(self));
    }
    @Test void finitePositionsIntegerBoundsAndCanonicalUuidsAreRequired(){
        var invalid=simple();nodes(invalid).getFirst().put("pos",Map.of("x",Double.NaN,"y",0));assertThrows(IllegalArgumentException.class,()->AutomationGraph.compile(invalid));
        for(Object value:List.of(-1,24000,1.5,Double.POSITIVE_INFINITY,"6000")){var g=simple();nodes(g).set(1,node("set","action",Map.of("action","world.time","params",Map.of("time",value))));assertThrows(IllegalArgumentException.class,()->AutomationGraph.compile(g));}
        assertThrows(IllegalArgumentException.class,()->AutomationGraph.context(Map.of("uuid","1-1-1-1-1")));assertThrows(IllegalArgumentException.class,()->AutomationGraph.context(Map.of("uuid","")));
    }
    @Test void privilegeActionsCodeNodesAndOversizedGraphsAreRejected(){
        for(String action:List.of("server.command","player.op","player.ban","server.stop","plugins.upload","map.build")){var g=simple();nodes(g).set(1,node("set","action",Map.of("action",action,"params",Map.of())));assertThrows(IllegalArgumentException.class,()->AutomationGraph.compile(g));}
        var script=simple();nodes(script).set(1,node("set","code",Map.of("javascript","console.log('x')")));assertThrows(IllegalArgumentException.class,()->AutomationGraph.compile(script));
        var large=simple();large.put("extra","x".repeat(65537));assertThrows(IllegalArgumentException.class,()->AutomationGraph.compile(large));
        var tooMany=simple();for(int i=0;i<31;i++)nodes(tooMany).add(action("extra"+i,1));assertThrows(IllegalArgumentException.class,()->AutomationGraph.compile(tooMany));
    }
    @Test void typedConditionsNormalizeOperatorsAndComplexGraphsCannotFlatten(){
        var g=graph(List.of(trigger(),node("if","condition",Map.of("condition","playerOnline","operator","neq","value",true)),action("yes",1),action("no",2)),List.of(edge("a","start","if","next"),edge("b","if","yes","true"),edge("c","if","no","false")));
        var compiled=AutomationGraph.compile(g);assertEquals("ne",compiled.order().get(1).config().get("operator"));assertTrue(AutomationGraph.compare(0,"gte",0));assertFalse(AutomationGraph.compare(20.0,"lt",19));assertTrue(AutomationGraph.compare("rain","ne","clear"));assertThrows(IllegalArgumentException.class,()->AutomationGraph.exportLegacy(compiled));
        var invalid=simple();nodes(invalid).add(node("if","condition",Map.of("condition","tps","value",21)));assertThrows(IllegalArgumentException.class,()->AutomationGraph.compile(invalid));
    }
}
