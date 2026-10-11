package dev.mcc.integration;

import dev.mcc.core.Auth;
import net.hnt8.advancedban.manager.PunishmentManager;
import net.hnt8.advancedban.utils.*;
import org.bukkit.Location;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class PunishmentEditorTest {
    private static final Auth.Session OWNER=new Auth.Session("test-only","owner","Owner","csrf",Long.MAX_VALUE,false),ADMIN=new Auth.Session("test-only","admin","Admin","csrf",Long.MAX_VALUE,false);
    private static final String UUID="00000000-0000-0000-0000-000000000001";
    private static final class Runtime implements IntegrationService.Runtime {
        final IntegrationService.Binding binding=IntegrationService.punishmentBinding("AdvancedBanZ","4.0.1",this,getClass().getClassLoader());
        public List<IntegrationService.Binding> discover(){return List.of(binding);}public boolean active(IntegrationService.Binding b){return b==binding;}public Location location(String world,int x,int y,int z){throw new UnsupportedOperationException();}
    }
    @BeforeEach void reset(){PunishmentManager.get().reset();}
    private IntegrationService service(){var service=new IntegrationService(new Runtime(),(s,w)->true);service.cachedPlayers(()->List.of(Map.of("name","KnownPlayer","uuid",UUID)));return service;}
    private static Map<String,Object> issue(){return Map.of("action","issue","player","KnownPlayer","uuid",UUID,"type","note","seconds",0,"reason","Synthetic fixture note","confirmed",true);}
    private static Map<String,Object> revoke(Map<?,?> row,int id){return Map.of("action","revoke","player","KnownPlayer","uuid",UUID,"punishmentId",id,"expectedRevision",row.get("revision"),"confirmed",true);}
    private static Map<?,?> first(Map<String,Object> page){return (Map<?,?>)((List<?>)page.get("items")).getFirst();}
    @Test void providerHistorySurvivesRevocationWithDistinctIdsAndActualSource()throws Exception {
        try(var service=service()){
            service.mutate(OWNER,"advancedban",issue()).get(2,TimeUnit.SECONDS);
            var history=first(service.query(ADMIN,"advancedban",Map.of("action","history")).get());assertEquals(100,history.get("historyId"));assertEquals(1,history.get("activeId"));assertEquals(true,history.get("identityVerified"));assertEquals(UUID,history.get("identityUuid"));assertEquals("AdvancedBanZ",((Map<?,?>)history.get("source")).get("title"));
            assertInstanceOf(IllegalStateException.class,assertThrows(ExecutionException.class,()->service.mutate(OWNER,"advancedban",revoke(history,100)).get()).getCause());assertEquals(0,PunishmentManager.get().deletes);
            service.mutate(OWNER,"advancedban",revoke(history,1)).get();var after=first(service.query(ADMIN,"advancedban",Map.of("action","history")).get());assertEquals("inactive",after.get("status"));assertEquals(0,after.get("activeId"));assertEquals(false,after.get("revocationDetailsAvailable"));assertEquals(1,PunishmentManager.get().history.size());
        }
    }
    @Test void replacedProviderRecordRejectsStaleRevokeBeforeAnyDelete()throws Exception {
        try(var service=service()){
            var saved=(Map<?,?>)service.mutate(OWNER,"advancedban",issue()).get().get("punishment");var old=PunishmentManager.get().rows.get(1);
            PunishmentManager.get().rows.put(1,new Punishment(old.getName(),old.getUuid(),"Edited outside MCC",old.getOperator(),old.getType(),old.getStart(),old.getEnd(),null,1));
            assertInstanceOf(ConcurrentModificationException.class,assertThrows(ExecutionException.class,()->service.mutate(OWNER,"advancedban",revoke(saved,1)).get()).getCause());assertEquals(0,PunishmentManager.get().deletes);
        }
    }
    @Test void boundedProviderQueryReadsOnlyLimitAndReportsWindowTruncation()throws Exception {
        for(int i=1;i<=1005;i++)PunishmentManager.get().history.put(i,new Punishment("KnownPlayer",UUID.replace("-",""),"Reason","Mod",PunishmentType.NOTE,i,-1,null,i));
        try(var service=service()){var result=service.query(ADMIN,"advancedban",Map.of("action","history","offset",975,"limit",25)).get();assertEquals(1001,PunishmentManager.get().lastLimit);assertEquals(true,result.get("truncated"));assertEquals(1000,result.get("matchedInWindow"));assertEquals(25,((List<?>)result.get("items")).size());assertEquals(false,result.get("hasMore"));}
    }
    @Test void cachedDirectoryNeverGrantsAdminWritesOrFabricatesUnknownIdentity()throws Exception {
        try(var service=service()){
            var players=service.query(ADMIN,"advancedban",Map.of("action","players")).get();assertEquals(UUID,first(players).get("uuid"));
            assertInstanceOf(SecurityException.class,assertThrows(ExecutionException.class,()->service.mutate(ADMIN,"advancedban",issue()).get()).getCause());
            var unknown=new HashMap<>(issue());unknown.put("player","UnknownPlayer");assertInstanceOf(IllegalArgumentException.class,assertThrows(ExecutionException.class,()->service.mutate(OWNER,"advancedban",unknown).get()).getCause());assertEquals(0,PunishmentManager.get().creates);
        }
    }
}
