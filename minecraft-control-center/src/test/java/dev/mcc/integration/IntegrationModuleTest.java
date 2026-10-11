package dev.mcc.integration;

import dev.mcc.core.Auth;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class IntegrationModuleTest {
    private static final Auth.Session OWNER=new Auth.Session("session","test","Owner","csrf",Long.MAX_VALUE,false);
    @Test void disableCancelsQueuedWorkAndRejectsInFlightResultsWithoutStartingMoreProviderReads()throws Exception{
        var api=new IntegrationTest.BlockingCore();var discoveries=new AtomicInteger();var clears=new AtomicInteger();
        IntegrationService.Runtime runtime=new IntegrationService.Runtime(){
            public List<IntegrationService.Binding> discover(){discoveries.incrementAndGet();return List.of(new IntegrationService.Binding("coreprotect","CoreProtect","test",api,getClass().getClassLoader(),api,""));}
            public boolean active(IntegrationService.Binding b){return true;}public Location location(String w,int x,int y,int z){return new Location(null,x,y,z);}
            public void clearCaches(){clears.incrementAndGet();}
        };
        var body=Map.<String,Object>of("world","test","x",0,"y",64,"z",0,"seconds",60,"radius",8,"limit",1);
        try(var service=new IntegrationService(runtime,(s,w)->true)){
            var a=service.query(OWNER,"coreprotect",body);var b=service.query(OWNER,"coreprotect",body);assertTrue(api.started.await(2,TimeUnit.SECONDS));var queued=service.query(OWNER,"coreprotect",body);
            service.setEnabled(false);assertTrue(queued.isCancelled());assertEquals(1,clears.get());
            assertInstanceOf(IllegalStateException.class,assertThrows(ExecutionException.class,()->service.discover(OWNER).get()).getCause());
            assertEquals("unavailable",service.punishmentProvider(OWNER).get());assertEquals(2,discoveries.get());
            api.release.countDown();assertInstanceOf(IllegalStateException.class,assertThrows(ExecutionException.class,()->a.get(2,TimeUnit.SECONDS)).getCause());assertThrows(ExecutionException.class,()->b.get(2,TimeUnit.SECONDS));assertEquals(2,api.calls.get());
            service.setEnabled(true);assertEquals(1,((List<?>)service.discover(OWNER).get().get("items")).size());
        }finally{api.release.countDown();}
    }
    @Test void disabledOwnershipStillRequiresActiveAdministratorSession()throws Exception{
        var runtime=new IntegrationService.Runtime(){public List<IntegrationService.Binding> discover(){throw new AssertionError("OFF must not discover");}public boolean active(IntegrationService.Binding b){return false;}public Location location(String w,int x,int y,int z){return null;}};
        try(var service=new IntegrationService(runtime,(s,w)->false)){service.setEnabled(false);assertInstanceOf(SecurityException.class,assertThrows(ExecutionException.class,()->service.punishmentProvider(OWNER).get()).getCause());}
    }
    @Test void reenableCannotReviveAReadStartedBeforeDisable()throws Exception{
        var api=new IntegrationTest.BlockingCore();
        var runtime=new IntegrationService.Runtime(){public List<IntegrationService.Binding> discover(){return List.of(new IntegrationService.Binding("coreprotect","CoreProtect","test",api,getClass().getClassLoader(),api,""));}public boolean active(IntegrationService.Binding b){return true;}public Location location(String w,int x,int y,int z){return new Location(null,x,y,z);}};
        var body=Map.<String,Object>of("world","test","x",0,"y",64,"z",0,"seconds",60,"radius",8,"limit",1);
        try(var service=new IntegrationService(runtime,(s,w)->true)){
            var a=service.query(OWNER,"coreprotect",body);var b=service.query(OWNER,"coreprotect",body);assertTrue(api.started.await(2,TimeUnit.SECONDS));
            service.setEnabled(false);service.setEnabled(true);api.release.countDown();
            assertInstanceOf(IllegalStateException.class,assertThrows(ExecutionException.class,()->a.get(2,TimeUnit.SECONDS)).getCause());assertThrows(ExecutionException.class,()->b.get(2,TimeUnit.SECONDS));
            assertEquals(1,((List<?>)service.discover(OWNER).get().get("items")).size());
        }finally{api.release.countDown();}
    }
    @Test void reenableCannotReviveAWriteWaitingForProviderDiscovery()throws Exception{
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);var api=new IntegrationTest.LpApi();
        var runtime=new IntegrationService.Runtime(){public List<IntegrationService.Binding> discover()throws Exception{started.countDown();release.await();return List.of(new IntegrationService.Binding("luckperms","LuckPerms","test",api,getClass().getClassLoader(),api,""));}public boolean active(IntegrationService.Binding b){return true;}public Location location(String w,int x,int y,int z){return null;}};
        try(var service=new IntegrationService(runtime,(s,w)->true)){
            var write=service.mutate(OWNER,"luckperms",Map.of("confirmed",true,"action","createGroup","target","test"));assertTrue(started.await(2,TimeUnit.SECONDS));
            service.setEnabled(false);service.setEnabled(true);release.countDown();
            assertInstanceOf(IllegalStateException.class,assertThrows(ExecutionException.class,()->write.get(2,TimeUnit.SECONDS)).getCause());assertEquals(0,api.groups.saves);assertTrue(api.groups.group.nodes.isEmpty());
        }finally{release.countDown();}
    }
}
