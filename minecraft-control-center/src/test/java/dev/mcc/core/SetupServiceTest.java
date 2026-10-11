package dev.mcc.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class SetupServiceTest {
    @TempDir Path directory;
    private static final String PASSWORD="Synthetic-owner-password-2026!";
    private static final class TestClock extends Clock{long now=1_000_000;public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return Instant.ofEpochMilli(now);}public long millis(){return now;}}
    private static Map<String,Object> plan(SetupService service,SetupService.Grant grant,String profile,long expires){Map<String,Object> plan=Map.of("planId",UUID.randomUUID().toString(),"profile",profile,"expiresAt",expires);service.profilePlan(grant,plan);return plan;}
    private static Map<String,Object> finish(Map<String,Object> plan,String mode){return new LinkedHashMap<>(Map.of("password",PASSWORD,"profile",plan.get("profile"),"planId",plan.get("planId"),"confirmed",true,"licenseMode",mode,"licenseKey","","core","keep"));}
    @Test void genuineIdentityLinkIsBoundSingleUseAndCsrfProtected()throws Exception{
        try(Database db=new Database(directory.resolve("database.db"))){Auth auth=new Auth(db,directory,true,true);LicenseService license=new LicenseService(db,false);TestClock clock=new TestClock();UUID uuid=UUID.randomUUID();
            SetupService service=new SetupService(auth,db,license,(id,name)->id.equals(uuid)&&name.equals("RealPlayer"),clock);
            assertThrows(SecurityException.class,()->service.issue(uuid,"RealPlayer",false));String link=service.issue(uuid,"RealPlayer",true);assertEquals(43,link.length());assertFalse(link.contains("RealPlayer"));
            SetupService.Grant grant=service.claim(link);assertEquals(uuid,grant.player().uuid());assertEquals("RealPlayer",grant.player().name());assertEquals(clock.now+600_000,grant.expiresAt());assertFalse(grant.toString().contains(grant.id()));
            assertThrows(SecurityException.class,()->service.claim(link));assertThrows(SecurityException.class,()->service.require(grant.id(),"incorrect"));assertSame(grant,service.require(grant.id(),grant.csrf()));
            Map<String,Object> body=finish(plan(service,grant,"custom",clock.now+300_000),"trial");body.put("username","Attacker");assertThrows(IllegalArgumentException.class,()->service.complete(grant.id(),grant.csrf(),body));assertEquals(0,db.userCount().get());
        }
    }
    @Test void selectedPresetIsBoundToTheClaimAndCannotBeForgedOrChanged()throws Exception{
        try(Database db=new Database(directory.resolve("database.db"))){Auth auth=new Auth(db,directory);LicenseService license=new LicenseService(db,false);TestClock clock=new TestClock();SetupService service=new SetupService(auth,db,license,(id,name)->true,clock);SetupService.Grant a=service.claim(service.issue(UUID.randomUUID(),"FirstPlayer",true)),b=service.claim(service.issue(UUID.randomUUID(),"SecondPlayer",true));
            Map<String,Object> original=plan(service,a,"custom",clock.now+300_000),foreign=plan(service,b,"vanilla",clock.now+300_000);
            assertThrows(SecurityException.class,()->service.complete(a.id(),a.csrf(),finish(foreign,"trial")));Map<String,Object> changed=finish(original,"trial");changed.put("profile","optimized");assertThrows(SecurityException.class,()->service.complete(a.id(),a.csrf(),changed));assertEquals(0,db.userCount().get());
        }
    }
    @Test void ownerAndLicenseAndIdentityPersistAndNoDefaultCanReturn()throws Exception{
        Path file=directory.resolve("database.db");UUID identity=UUID.randomUUID();
        try(Database db=new Database(file)){Auth auth=new Auth(db,directory,true,true);LicenseService license=new LicenseService(db,false);TestClock clock=new TestClock();SetupService service=new SetupService(auth,db,license,(id,name)->true,clock);SetupService.Grant grant=service.claim(service.issue(identity,"ServerOwner",true));SetupService.Completion complete=service.complete(grant.id(),grant.csrf(),finish(plan(service,grant,"custom",clock.now+300_000),"temporary-full"));
            assertEquals("ServerOwner",complete.session().name());assertEquals("Owner",complete.session().role());assertFalse(auth.setupRequired());assertTrue(license.fullAccess());assertNull(service.grant(grant.id()));assertThrows(SecurityException.class,()->service.complete(grant.id(),grant.csrf(),Map.of()));assertFalse(complete.toString().contains(complete.session().id()));assertEquals(identity.toString(),db.document("auth-identity","ServerOwner").get().orElseThrow().get("uuid"));
        }
        try(Database db=new Database(file)){Auth restarted=new Auth(db,directory,true,true);assertFalse(restarted.setupRequired());assertNull(db.user("admin").get());assertEquals("ServerOwner",restarted.login("ServerOwner",PASSWORD,false).name());assertTrue(new LicenseService(db,true).fullAccess());assertEquals("custom",db.document("onboarding","state").get().orElseThrow().get("profile"));}
    }
    @Test void concurrentClaimsCannotCreateTwoOwners()throws Exception{
        try(Database db=new Database(directory.resolve("database.db"))){Auth auth=new Auth(db,directory);LicenseService license=new LicenseService(db,false);TestClock clock=new TestClock();SetupService service=new SetupService(auth,db,license,(id,name)->true,clock);SetupService.Grant a=service.claim(service.issue(UUID.randomUUID(),"FirstPlayer",true)),b=service.claim(service.issue(UUID.randomUUID(),"SecondPlayer",true));Map<String,Object> pa=finish(plan(service,a,"custom",clock.now+300_000),"trial"),pb=finish(plan(service,b,"custom",clock.now+300_000),"temporary-full");CountDownLatch start=new CountDownLatch(1);ExecutorService workers=Executors.newFixedThreadPool(2);
            try{Callable<Boolean> first=()->{start.await();try{service.complete(a.id(),a.csrf(),pa);return true;}catch(SecurityException|ExecutionException denied){return false;}},second=()->{start.await();try{service.complete(b.id(),b.csrf(),pb);return true;}catch(SecurityException|ExecutionException denied){return false;}};Future<Boolean> one=workers.submit(first),two=workers.submit(second);start.countDown();assertNotEquals(one.get(20,TimeUnit.SECONDS),two.get(20,TimeUnit.SECONDS));assertEquals(1,db.userCount().get());assertEquals(1,db.documents("auth-identity").get().size());}finally{workers.shutdownNow();}
        }
    }
    @Test void revokedPermissionAfterKdfCannotCreateOwner()throws Exception{
        try(Database db=new Database(directory.resolve("database.db"))){Auth auth=new Auth(db,directory);LicenseService license=new LicenseService(db,false);TestClock clock=new TestClock();AtomicInteger authorityChecks=new AtomicInteger();AtomicBoolean completePhase=new AtomicBoolean();SetupService service=new SetupService(auth,db,license,(id,name)->!completePhase.get()||authorityChecks.incrementAndGet()<2,clock);SetupService.Grant grant=service.claim(service.issue(UUID.randomUUID(),"RealPlayer",true));Map<String,Object> body=finish(plan(service,grant,"custom",clock.now+300_000),"temporary-full");completePhase.set(true);
            assertThrows(SecurityException.class,()->service.complete(grant.id(),grant.csrf(),body));assertEquals(0,db.userCount().get());assertTrue(auth.setupRequired());assertFalse(license.fullAccess());assertTrue(db.documents("onboarding").get().isEmpty());
        }
    }
    @Test void expiredInvitationAndGrantAndPlansNeverCreateOwner()throws Exception{
        try(Database db=new Database(directory.resolve("database.db"))){Auth auth=new Auth(db,directory);LicenseService license=new LicenseService(db,false);TestClock clock=new TestClock();SetupService service=new SetupService(auth,db,license,(id,name)->true,clock);String expired=service.issue(UUID.randomUUID(),"FirstPlayer",true);clock.now+=600_000;assertThrows(SecurityException.class,()->service.claim(expired));SetupService.Grant grant=service.claim(service.issue(UUID.randomUUID(),"SecondPlayer",true));Map<String,Object> body=finish(plan(service,grant,"custom",clock.now+100),"trial");clock.now+=100;assertThrows(SecurityException.class,()->service.complete(grant.id(),grant.csrf(),body));clock.now+=600_000;assertNull(service.grant(grant.id()));assertThrows(SecurityException.class,()->service.require(grant.id(),grant.csrf()));assertEquals(0,db.userCount().get());}
    }
    @Test void atomicTransactionRollsBackAllDocumentsWhenFinalAuthorityChanges()throws Exception{
        try(Database db=new Database(directory.resolve("database.db"))){Map<String,Object> prior=LicenseService.stored("trial","fixture",1);db.document("license","state",prior).get();AtomicInteger checks=new AtomicInteger();
            CompletableFuture<Void> write=db.completeSetup("BoundPlayer",Auth.hash(PASSWORD),UUID.randomUUID(),null,LicenseService.stored("temporary-full","fixture",2),Map.of("completed",true),()->checks.incrementAndGet()==1);
            assertThrows(ExecutionException.class,write::get);assertEquals(0,db.userCount().get());assertEquals(Json.object(Json.write(prior)),db.document("license","state").get().orElseThrow());assertTrue(db.documents("auth-identity").get().isEmpty());assertTrue(db.documents("onboarding").get().isEmpty());
        }
    }
    @Test void grantExpiryWhileKdfFinishesCannotCreateOwner()throws Exception{
        try(Database db=new Database(directory.resolve("database.db"))){Auth auth=new Auth(db,directory);LicenseService license=new LicenseService(db,false);TestClock clock=new TestClock();AtomicInteger checks=new AtomicInteger();AtomicBoolean phase=new AtomicBoolean();SetupService service=new SetupService(auth,db,license,(id,name)->{if(phase.get()&&checks.incrementAndGet()==2)clock.now+=SetupService.TTL_MILLIS;return true;},clock);SetupService.Grant grant=service.claim(service.issue(UUID.randomUUID(),"RealPlayer",true));Map<String,Object> body=finish(plan(service,grant,"custom",clock.now+300_000),"temporary-full");phase.set(true);assertThrows(SecurityException.class,()->service.complete(grant.id(),grant.csrf(),body));assertEquals(0,db.userCount().get());assertFalse(license.fullAccess());assertTrue(db.documents("onboarding").get().isEmpty());}
    }
    @Test void knownSoleLegacyDefaultIsReboundWithoutDeletingHistory()throws Exception{
        try(Database db=new Database(directory.resolve("database.db"))){db.bootstrapAdmin(Auth.hash("admin")).get();db.document("build-history","admin",Map.of("actor","admin","undo",List.of(Map.of("fixture",true)),"redo",List.of())).get();db.audit("admin","fixture.history","ok",Map.of()).get();Auth auth=new Auth(db,directory,true,true);assertTrue(auth.setupRequired());assertThrows(SecurityException.class,()->auth.login("admin","admin"));LicenseService license=new LicenseService(db,false);TestClock clock=new TestClock();SetupService service=new SetupService(auth,db,license,(id,name)->true,clock);SetupService.Grant grant=service.claim(service.issue(UUID.randomUUID(),"MinecraftOwner",true));service.complete(grant.id(),grant.csrf(),finish(plan(service,grant,"custom",clock.now+300_000),"trial"));
            assertNull(db.user("admin").get());assertEquals(1,db.userCount().get());assertEquals("Owner",auth.login("MinecraftOwner",PASSWORD,false).role());assertEquals("MinecraftOwner",db.document("build-history","MinecraftOwner").get().orElseThrow().get("actor"));assertTrue(db.document("build-history","admin").get().isEmpty());assertTrue(db.documents("auth-default-account").get().isEmpty());assertTrue(db.auditHistory().get().stream().anyMatch(row->row.get("actor").equals("admin")));
        }
    }
    @Test void legacyAccountChangedDuringWizardCannotBeOverwritten()throws Exception{
        try(Database db=new Database(directory.resolve("database.db"))){db.bootstrapAdmin(Auth.hash("admin")).get();Auth auth=new Auth(db,directory);LicenseService license=new LicenseService(db,false);TestClock clock=new TestClock();SetupService service=new SetupService(auth,db,license,(id,name)->true,clock);SetupService.Grant grant=service.claim(service.issue(UUID.randomUUID(),"MinecraftOwner",true));Map<String,Object> body=finish(plan(service,grant,"custom",clock.now+300_000),"trial");String before=(String)db.user("admin").get().get("hash"),after=Auth.hash("Replacement-long-strong-password!");db.changeUser("admin",before,"admin",after,true).get();assertThrows(ExecutionException.class,()->service.complete(grant.id(),grant.csrf(),body));assertEquals(after,db.user("admin").get().get("hash"));assertNull(db.user("MinecraftOwner").get());assertEquals(1,db.userCount().get());}
    }
    @Test void capabilityCapacityIsBoundedAndRestartOrClosureDropsEveryLink()throws Exception{
        try(Database db=new Database(directory.resolve("database.db"))){Auth auth=new Auth(db,directory);LicenseService license=new LicenseService(db,false);TestClock clock=new TestClock();SetupService service=new SetupService(auth,db,license,(id,name)->true,clock);String retained=null;for(int i=0;i<32;i++)retained=service.issue(UUID.randomUUID(),"Player"+i,true);assertThrows(IllegalStateException.class,()->service.issue(UUID.randomUUID(),"OverflowPlayer",true));String old=retained;service.close();assertThrows(SecurityException.class,()->service.claim(old));SetupService next=new SetupService(auth,db,license,(id,name)->true,clock);assertThrows(SecurityException.class,()->next.claim(old));assertFalse(Files.exists(directory.resolve(".setup-token")));}
    }
    @Test void authorityWaitNeverHoldsTheLockUsedByMinecraftJoinIssuance()throws Exception{
        try(Database db=new Database(directory.resolve("database.db"))){Auth auth=new Auth(db,directory);LicenseService license=new LicenseService(db,false);TestClock clock=new TestClock();AtomicReference<SetupService> reference=new AtomicReference<>();AtomicInteger sequence=new AtomicInteger();SetupService service=new SetupService(auth,db,license,(id,name)->{CompletableFuture.runAsync(()->reference.get().issue(UUID.randomUUID(),"JoinPlayer"+sequence.incrementAndGet(),true)).get(2,TimeUnit.SECONDS);return true;},clock);reference.set(service);SetupService.Grant grant=service.claim(service.issue(UUID.randomUUID(),"RealPlayer",true));assertSame(grant,service.require(grant.id(),grant.csrf()));}
    }
}
