package dev.mcc.core;

import java.util.*;
import java.util.function.Predicate;

/** Optional work gates. Authentication, cleanup, configuration and punishment ownership are always core. */
public final class ModulePolicy {
    public static final List<String> KEYS=List.of("chat","events","automations","map","oreMonitor","integrations","metrics","playerSnapshots","entitySnapshots","timeline","catalog","console");
    private ModulePolicy(){}
    public static Map<String,Boolean> updated(Map<String,Boolean> current,Object raw){
        Map<String,Boolean> next=new LinkedHashMap<>(current);
        if(raw==null)return Map.copyOf(next);
        if(!(raw instanceof Map<?,?> values))throw new IllegalArgumentException("Modules must be an object");
        for(var entry:values.entrySet()){
            if(!(entry.getKey() instanceof String key)||!KEYS.contains(key)||!(entry.getValue() instanceof Boolean enabled))throw new IllegalArgumentException("Unknown module or invalid value");
            next.put(key,enabled);
        }
        return Map.copyOf(next);
    }
    public static String routeModule(String path){
        if(path.equals("/api/punishments/provider"))return ""; // Ownership detection never falls back to builtin.
        if(path.startsWith("/api/integrations"))return "integrations";
        if(path.startsWith("/api/ore-monitor"))return "oreMonitor";
        if(path.startsWith("/api/map/"))return "map";
        if(path.startsWith("/api/automations"))return "automations";
        if(path.startsWith("/api/events"))return "events";
        if(path.startsWith("/api/players"))return "playerSnapshots";
        if(path.equals("/api/entities")||path.equals("/api/catalogs"))return "entitySnapshots";
        if(path.startsWith("/api/plugins/catalog")||path.equals("/api/plugins/manage")||path.startsWith("/api/plugins/remove")||path.equals("/api/plugins/restore"))return "catalog";
        return switch(path){case "/api/metrics"->"metrics";case "/api/audit"->"timeline";case "/api/chat"->"chat";case "/api/console"->"console";default->"";};
    }
    public static String actionModule(String action){
        if(action.equals("event.stop"))return ""; // Safety cleanup remains available.
        if(action.startsWith("event."))return "events";
        if(action.startsWith("chat."))return "chat";
        if(action.startsWith("mob."))return "entitySnapshots";
        if(action.startsWith("player.")||action.startsWith("players.")||action.startsWith("inventory."))return "playerSnapshots";
        return action.equals("console.command")?"console":"";
    }
    public static String topicModule(String topic){
        return switch(topic){case "players","inspector","inventory"->"playerSnapshots";case "spawn"->"entitySnapshots";case "automation"->"automations";case "events"->"events";case "chat"->"chat";case "console"->"console";case "timeline"->"timeline";default->"";};
    }
    public static boolean topicAllowed(String topic,Predicate<String> enabled){String key=topicModule(topic);return key.isEmpty()||enabled.test(key);}
    public static void require(String key,Predicate<String> enabled){if(!key.isEmpty()&&!enabled.test(key))throw new IllegalStateException("MCC module is disabled: "+key);}
    /** Scrub old cached values immediately, before the next main-thread capture. */
    public static Map<String,Object> filterSnapshot(Map<String,Object> original,Predicate<String> enabled){
        Map<String,Object> next=new LinkedHashMap<>(original);
        if(!enabled.test("playerSnapshots"))for(String key:List.of("players","playerDetails","inventory"))next.remove(key);
        if(!enabled.test("entitySnapshots"))for(String key:List.of("entities","entityCount","mobs"))next.remove(key);
        if(!enabled.test("events"))for(String key:List.of("events","activeEvents"))next.remove(key);
        if(!enabled.test("metrics"))for(String key:List.of("heapUsed","heapMax","ramUsed","ramTotal","ramMax","memoryUsed","memoryMax","cpu","systemRamUsed","systemRamTotal","diskUsed","diskTotal","mspt","tpsHistory","tps","loadedChunks","platformMetrics","performanceGuard"))next.remove(key);
        if((!enabled.test("entitySnapshots")||!enabled.test("metrics"))&&next.get("worlds") instanceof List<?> worlds){List<Object> filtered=new ArrayList<>();for(Object world:worlds){if(world instanceof Map<?,?> source){Map<String,Object> copy=new LinkedHashMap<>();source.forEach((k,v)->{String key=String.valueOf(k);if(!(key.equals("entityCount")&&!enabled.test("entitySnapshots"))&&!(key.equals("loadedChunks")&&!enabled.test("metrics")))copy.put(key,v);});filtered.add(Collections.unmodifiableMap(copy));}else filtered.add(world);}next.put("worlds",List.copyOf(filtered));}
        return Collections.unmodifiableMap(next);
    }
}
