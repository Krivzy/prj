package dev.mcc.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ModulePolicyTest {
    @Test void invalidUpdateCannotPartiallyChangeFlags(){
        Map<String,Boolean> current=Map.of("metrics",true,"integrations",true);
        Map<String,Object> update=new LinkedHashMap<>();update.put("metrics",false);update.put("authentication",false);
        assertThrows(IllegalArgumentException.class,()->ModulePolicy.updated(current,update));assertEquals(true,current.get("metrics"));
        assertThrows(IllegalArgumentException.class,()->ModulePolicy.updated(current,List.of()));assertThrows(IllegalArgumentException.class,()->ModulePolicy.updated(current,Map.of("metrics","false")));
    }
    @Test void omittedNewModulesRetainDefaultsWhenLoadingOldSettings(){
        Map<String,Boolean> configured=new HashMap<>();for(String key:ModulePolicy.KEYS)configured.put(key,true);
        Map<String,Boolean> updated=ModulePolicy.updated(configured,Map.of("chat",false,"map",false));assertEquals(false,updated.get("chat"));assertEquals(true,updated.get("oreMonitor"));assertEquals(true,updated.get("integrations"));assertThrows(UnsupportedOperationException.class,()->updated.put("metrics",false));
    }
    @Test void disabledWorkCannotBeReachedThroughReadRoutesOrActionsButCoreCleanupRemains(){
        for(String path:List.of("/api/players","/api/players/uuid/inventory","/api/map/images/tile","/api/plugins/catalog/install","/api/plugins/manage","/api/plugins/remove/plan","/api/plugins/remove","/api/plugins/restore","/api/integrations/advancedban/query","/api/ore-monitor/history","/api/metrics","/api/automations/profiles","/api/entities"))assertThrows(IllegalStateException.class,()->ModulePolicy.require(ModulePolicy.routeModule(path),key->false));
        for(String action:List.of("player.heal","players.heal_all","inventory.set","mob.spawn","event.start","console.command"))assertThrows(IllegalStateException.class,()->ModulePolicy.require(ModulePolicy.actionModule(action),key->false));
        for(String path:List.of("/api/auth/login","/api/server/health","/api/settings","/api/server/profiles/apply","/api/backups","/api/punishments/provider"))assertDoesNotThrow(()->ModulePolicy.require(ModulePolicy.routeModule(path),key->false));assertDoesNotThrow(()->ModulePolicy.require(ModulePolicy.actionModule("event.stop"),key->false));
    }
    @Test void cachedSnapshotIsScrubbedWithoutMutatingPreviousOrLosingHealth(){
        Map<String,Object> source=new LinkedHashMap<>(Map.of("players",List.of("old-player"),"entityCount",500,"heapUsed",200,"cpu",50,"activeEvents",List.of("old-event"),"timestamp",100,"status","online","worlds",List.of(Map.of("name","world","entityCount",400))));
        Map<String,Object> result=ModulePolicy.filterSnapshot(source,key->false);assertEquals("online",result.get("status"));assertEquals(100,result.get("timestamp"));for(String key:List.of("players","entityCount","heapUsed","cpu","activeEvents"))assertFalse(result.containsKey(key));assertFalse(((Map<?,?>)((List<?>)result.get("worlds")).getFirst()).containsKey("entityCount"));assertEquals(500,source.get("entityCount"));assertTrue(((Map<?,?>)((List<?>)source.get("worlds")).getFirst()).containsKey("entityCount"));
    }
    @Test void externalPunishmentOwnershipNeverDependsOnIntegrationUiOrPlayerSnapshots(){
        assertTrue(ModulePolicy.topicAllowed("punishments",key->false));assertTrue(ModulePolicy.topicAllowed("state",key->false));assertFalse(ModulePolicy.topicAllowed("inventory",key->false));assertFalse(ModulePolicy.topicAllowed("automation",key->false));
    }
    @Test void metricsOffScrubsLoadedChunkCacheWhileEnabledEntityCountersRemain(){
        Map<String,Object> original=Map.of("loadedChunks",321,"tps",20,"platformMetrics",true,"performanceGuard","ok","worlds",List.of(Map.of("name","world","loadedChunks",321,"entityCount",45)));
        Map<String,Object> filtered=ModulePolicy.filterSnapshot(original,key->!key.equals("metrics"));
        Map<?,?> world=(Map<?,?>)((List<?>)filtered.get("worlds")).getFirst();assertFalse(world.containsKey("loadedChunks"));assertEquals(45,world.get("entityCount"));for(String key:List.of("loadedChunks","tps","platformMetrics","performanceGuard"))assertFalse(filtered.containsKey(key));
        assertEquals(321,((Map<?,?>)((List<?>)original.get("worlds")).getFirst()).get("loadedChunks"));
    }
}
