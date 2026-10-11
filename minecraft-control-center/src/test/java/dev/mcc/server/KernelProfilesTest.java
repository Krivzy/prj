package dev.mcc.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class KernelProfilesTest {
    @TempDir Path root;
    private Path data;
    private FakeRuntime runtime;
    private KernelProfiles service()throws Exception {fixture();return new KernelProfiles(root,data,runtime,false);}
    private void fixture()throws Exception {
        data=root.resolve("plugins/MinecraftControlCenter");Files.createDirectories(data);
        write("server.properties","# keep this comment\r\nmotd=Keep me\r\nview-distance=12\r\nsimulation-distance=7\r\nlevel-seed=unchanged-test-value\r\n");
        write("spigot.yml","config-version: 13\nsettings:\n  unrelated: true\nworld-settings:\n  default:\n    view-distance: default\n    simulation-distance: default\n  custom-world:\n    view-distance: 16\n    unrelated: retained\n");
        write("config/paper-world-defaults.yml","_version: 31\nmisc:\n  redstone-implementation: VANILLA\n  unrelated: retained\n");
        Path world=root.resolve("world/dimensions/minecraft/overworld");Files.createDirectories(world);write("world/dimensions/minecraft/overworld/paper-world.yml","_version: 31\nmisc:\n  redstone-implementation: VANILLA\nother-plugin:\n  setting: retained\n");
        runtime=new FakeRuntime("Paper",List.of(new KernelProfiles.WorldState("world",world,12,7)));
    }
    private void write(String relative,String text)throws Exception {Path p=root.resolve(relative);Files.createDirectories(p.getParent());Files.writeString(p,text,StandardCharsets.UTF_8);}
    private String read(String path)throws Exception{return Files.readString(root.resolve(path));}
    private static <T>T done(CompletableFuture<T> value)throws Exception{return value.get(5,TimeUnit.SECONDS);}
    private static Throwable failed(CompletableFuture<?> value){return assertThrows(ExecutionException.class,()->value.get(5,TimeUnit.SECONDS)).getCause();}
    private Map<String,Object> plan(KernelProfiles manager,String id)throws Exception{return done(manager.plan("owner-id",id,()->true));}
    private Map<String,Object> apply(KernelProfiles manager,String id)throws Exception {Map<String,Object> plan=plan(manager,id);return done(manager.apply("owner-id",String.valueOf(plan.get("planId")),true,()->true));}
    @Test void customIsDefaultAndNeverRewritesKernelFilesEvenOnStartup()throws Exception {
        try(KernelProfiles manager=service()){
            Map<String,String> before=kernelHashes();Map<String,Object> status=done(manager.status());assertEquals("custom",status.get("profile"));assertFalse(Files.exists(data.resolve("kernel-profiles/profile.json")));
            assertEquals(true,apply(manager,"custom").get("saved"));done(manager.reconcileStartup());assertEquals(before,kernelHashes());assertEquals(0,runtime.liveCalls);
        }
    }
    @Test void managedApplyMergesDefaultsAndPerWorldOverridesPreservingUnrelatedSettings()throws Exception {
        try(KernelProfiles manager=service()){
            Map<String,Object> result=apply(manager,"optimized");assertEquals(true,result.get("pendingRestart"));assertEquals(1,runtime.liveCalls);
            String properties=read("server.properties");assertTrue(properties.contains("# keep this comment\r\n"));assertTrue(properties.contains("view-distance=8\r\nsimulation-distance=6\r\n"));assertTrue(properties.contains("level-seed=unchanged-test-value"));
            Map<String,Object> yaml=KernelConfigFiles.readYaml(KernelConfigFiles.Kind.SPIGOT,Files.readAllBytes(root.resolve("spigot.yml")));Map<?,?> worlds=(Map<?,?>)yaml.get("world-settings");assertEquals(8,((Map<?,?>)worlds.get("custom-world")).get("view-distance"));assertEquals("retained",((Map<?,?>)worlds.get("custom-world")).get("unrelated"));
            Map<String,Object> override=KernelConfigFiles.readYaml(KernelConfigFiles.Kind.PAPER,Files.readAllBytes(root.resolve("world/dimensions/minecraft/overworld/paper-world.yml")));assertEquals("ALTERNATE_CURRENT",((Map<?,?>)override.get("misc")).get("redstone-implementation"));assertNotNull(override.get("other-plugin"));
            Path backup=data.resolve("kernel-profiles/backups").resolve(String.valueOf(result.get("backupId")));assertTrue(Files.isRegularFile(backup.resolve("server.properties")));assertTrue(Files.readString(backup.resolve("server.properties")).contains("view-distance=12"));assertEquals(8,((Map<?,?>)((List<?>)result.get("worlds")).getFirst()).get("viewDistance"));
        }
    }
    @Test void customAfterManagedKeepsTheSelectedKernelBytes()throws Exception {
        try(KernelProfiles manager=service()){apply(manager,"optimized");Map<String,String> before=kernelHashes();apply(manager,"custom");assertEquals(before,kernelHashes());assertEquals(1,runtime.liveCalls);assertEquals(false,done(manager.status()).get("pendingRestart"));}
    }
    @Test void maximumOptimizationPreservesEveryDistanceAndNeverCallsLiveSetters()throws Exception {
        try(KernelProfiles manager=service()){
            byte[] properties=Files.readAllBytes(root.resolve("server.properties"));Map<String,Object> plan=plan(manager,"maximum-optimized");assertEquals(false,plan.get("liveDistances"));assertEquals(true,plan.get("preservesDistances"));
            for(Object file:(List<?>)plan.get("files")){assertNotEquals("server.properties",((Map<?,?>)file).get("path"));for(Object change:(List<?>)((Map<?,?>)file).get("changes"))assertFalse(String.valueOf(((Map<?,?>)change).get("key")).endsWith("distance"));}
            Map<String,Object> result=done(manager.apply("owner-id",(String)plan.get("planId"),true,()->true));assertEquals(true,result.get("pendingRestart"));assertArrayEquals(properties,Files.readAllBytes(root.resolve("server.properties")));assertEquals(0,runtime.liveCalls);
            Map<?,?> spigot=KernelConfigFiles.readYaml(KernelConfigFiles.Kind.SPIGOT,Files.readAllBytes(root.resolve("spigot.yml")));Map<?,?> worlds=(Map<?,?>)spigot.get("world-settings");assertEquals("default",((Map<?,?>)worlds.get("default")).get("view-distance"));assertEquals(16,((Map<?,?>)worlds.get("custom-world")).get("view-distance"));assertEquals(true,((Map<?,?>)worlds.get("default")).get("nerf-spawner-mobs"));assertEquals(2,((Map<?,?>)worlds.get("default")).get("max-entity-collisions"));
            for(String file:List.of("config/paper-world-defaults.yml","world/dimensions/minecraft/overworld/paper-world.yml")){Map<?,?> paper=KernelConfigFiles.readYaml(KernelConfigFiles.Kind.PAPER,Files.readAllBytes(root.resolve(file)));assertEquals(2,((Map<?,?>)paper.get("collisions")).get("max-entity-collisions"));}
            done(manager.reconcileStartup());assertEquals(0,runtime.liveCalls);assertArrayEquals(properties,Files.readAllBytes(root.resolve("server.properties")));assertEquals("maximum-optimized",result.get("profile"));
        }
    }
    @Test void vanillaThroughputPreservesSimulationFarmAndGameruleFilesAndKeepsVanillaRedstone()throws Exception {
        try(KernelProfiles manager=service()){
            byte[] properties=Files.readAllBytes(root.resolve("server.properties")),spigot=Files.readAllBytes(root.resolve("spigot.yml"));
            Map<String,Object> plan=plan(manager,"vanilla-throughput");assertEquals(true,plan.get("preservesDistances"));assertEquals(false,plan.get("liveDistances"));
            for(Object item:(List<?>)plan.get("files")){assertTrue(String.valueOf(((Map<?,?>)item).get("path")).endsWith(".yml"));for(Object c:(List<?>)((Map<?,?>)item).get("changes")){String key=String.valueOf(((Map<?,?>)c).get("key"));assertTrue(Set.of("misc.redstone-implementation","misc.update-pathfinding-on-block-update","chunks.max-auto-save-chunks-per-tick").contains(key),key);}}
            Map<String,Object> result=done(manager.apply("owner-id",(String)plan.get("planId"),true,()->true));
            assertArrayEquals(properties,Files.readAllBytes(root.resolve("server.properties")));assertArrayEquals(spigot,Files.readAllBytes(root.resolve("spigot.yml")));assertEquals(0,runtime.liveCalls);
            for(String file:List.of("config/paper-world-defaults.yml","world/dimensions/minecraft/overworld/paper-world.yml")){
                Map<?,?> paper=KernelConfigFiles.readYaml(KernelConfigFiles.Kind.PAPER,Files.readAllBytes(root.resolve(file)));
                assertEquals("VANILLA",((Map<?,?>)paper.get("misc")).get("redstone-implementation"));assertEquals(24,((Map<?,?>)paper.get("chunks")).get("max-auto-save-chunks-per-tick"));
            }
            assertEquals("vanilla-throughput",result.get("profile"));done(manager.reconcileStartup());assertArrayEquals(properties,Files.readAllBytes(root.resolve("server.properties")));assertArrayEquals(spigot,Files.readAllBytes(root.resolve("spigot.yml")));
        }
    }
    @Test void purpurToCoreManagedIdentityMigrationBacksUpExactMetadataAndKeepsTheChosenProfile()throws Exception {
        try(KernelProfiles manager=service()){apply(manager,"vanilla-throughput");}
        Path profile=data.resolve("kernel-profiles/profile.json"),backup=data.resolve("mcc-core/backups/test");Files.createDirectories(backup);
        String paper=Files.readString(profile),purpur=paper.replace("\"kernel\":\"Paper\"","\"kernel\":\"Purpur\"");assertNotEquals(paper,purpur);Files.writeString(profile,purpur);
        byte[] before=Files.readAllBytes(profile);Map<String,String> kernelBefore=kernelHashes();
        KernelProfiles.adoptCoreIdentityOffline(root,data,backup);
        assertArrayEquals(before,Files.readAllBytes(backup.resolve("previous-kernel-profile.json")));Map<String,Object> migrated=KernelProfiles.readState(profile);
        assertEquals("Paper",migrated.get("kernel"));assertEquals("Purpur",migrated.get("migratedFromKernel"));assertEquals("vanilla-throughput",migrated.get("profile"));assertEquals(kernelBefore,kernelHashes());
        byte[] after=Files.readAllBytes(profile);KernelProfiles.adoptCoreIdentityOffline(root,data,backup);assertArrayEquals(after,Files.readAllBytes(profile));
    }
    @Test void maximumOfflineStartupRestoresMechanicsDriftButRetainsNewManualDistances()throws Exception {
        try(KernelProfiles manager=service()){apply(manager,"maximum-optimized");}awaitLease();
        write("server.properties",read("server.properties").replace("view-distance=12","view-distance=24").replace("simulation-distance=7","simulation-distance=10"));
        write("spigot.yml",read("spigot.yml").replace("nerf-spawner-mobs: true","nerf-spawner-mobs: false").replace("view-distance: 16","view-distance: 20"));byte[] desired=Files.readAllBytes(root.resolve("server.properties"));
        Map<String,Object> outcome=KernelProfiles.reapplyOffline(root,data,"Paper","26.3");assertEquals(1L,outcome.get("changedFiles"));assertArrayEquals(desired,Files.readAllBytes(root.resolve("server.properties")));Map<?,?> defaults=(Map<?,?>)((Map<?,?>)KernelConfigFiles.readYaml(KernelConfigFiles.Kind.SPIGOT,Files.readAllBytes(root.resolve("spigot.yml"))).get("world-settings")).get("default");assertEquals(true,defaults.get("nerf-spawner-mobs"));assertTrue(read("spigot.yml").contains("view-distance: 20"));
    }
    @Test void leavingMaximumForAnotherManagedProfileRestoresAllMaximumExclusiveKeys()throws Exception {
        try(KernelProfiles manager=service()){
            for(String profile:List.of("vanilla","vanilla-performance","optimized")){
                apply(manager,"maximum-optimized");apply(manager,profile);
                Map<?,?> worlds=(Map<?,?>)KernelConfigFiles.readYaml(KernelConfigFiles.Kind.SPIGOT,Files.readAllBytes(root.resolve("spigot.yml"))).get("world-settings");
                for(Object value:worlds.values()){
                    Map<?,?> settings=(Map<?,?>)value;assertEquals(8,settings.get("max-entity-collisions"),profile);assertEquals(false,settings.get("nerf-spawner-mobs"),profile);assertEquals(true,settings.get("tick-inactive-villagers"),profile);
                    boolean optimized=profile.equals("optimized");assertEquals(optimized?6:8,settings.get("mob-spawn-range"),profile);assertEquals(optimized?2.5:0.5,((Map<?,?>)settings.get("merge-radius")).get("item"),profile);
                    assertEquals(optimized?32:0,((Map<?,?>)settings.get("entity-activation-range")).get("animals"),profile);
                }
                for(String file:List.of("config/paper-world-defaults.yml","world/dimensions/minecraft/overworld/paper-world.yml")){Map<?,?> paper=KernelConfigFiles.readYaml(KernelConfigFiles.Kind.PAPER,Files.readAllBytes(root.resolve(file)));assertEquals(8,((Map<?,?>)paper.get("collisions")).get("max-entity-collisions"),profile);}
            }
        }
    }
    @Test void spigotMaximumHonestlySkipsRedstoneWhileKeepingDistances()throws Exception {
        try(KernelProfiles manager=service()){runtime.kernel="Spigot";runtime.live=false;Map<String,Object> plan=plan(manager,"maximum-optimized");assertTrue(((List<?>)plan.get("tradeoffs")).stream().anyMatch(v->String.valueOf(v).contains("skipped")));assertEquals(1,((List<?>)plan.get("files")).size());apply(manager,"maximum-optimized");assertEquals(0,runtime.liveCalls);assertTrue(read("server.properties").contains("view-distance=12"));}
    }
    @Test void repeatingSavedProfileCannotClearUnappliedRestartSettings()throws Exception {
        try(KernelProfiles manager=service()){assertEquals(true,apply(manager,"optimized").get("pendingRestart"));Map<String,String> files=kernelHashes();assertEquals(true,apply(manager,"optimized").get("pendingRestart"));assertEquals(files,kernelHashes());assertEquals(false,done(manager.reconcileStartup()).get("pendingRestart"));}
    }
    @Test void sourceShaConflictRejectsBeforeAnyCommit()throws Exception {
        try(KernelProfiles manager=service()){Map<String,Object> p=plan(manager,"vanilla");Files.writeString(root.resolve("spigot.yml"),read("spigot.yml")+"# external change\n");Map<String,String> changed=kernelHashes();assertInstanceOf(KernelProfiles.ProfileConflictException.class,failed(manager.apply("owner-id",(String)p.get("planId"),true,()->true)));assertEquals(changed,kernelHashes());assertFalse(Files.exists(data.resolve("kernel-profiles/profile.json")));}
    }
    @Test void actorConfirmationAndAuthorityAreRequired()throws Exception {
        try(KernelProfiles manager=service()){Map<String,Object> p=plan(manager,"vanilla");String id=(String)p.get("planId");assertInstanceOf(SecurityException.class,failed(manager.plan("owner-id","vanilla",()->false)));assertInstanceOf(IllegalArgumentException.class,failed(manager.apply("other-id",id,true,()->true)));assertInstanceOf(IllegalArgumentException.class,failed(manager.apply("owner-id",id,false,()->true)));done(manager.apply("owner-id",id,true,()->true));assertInstanceOf(KernelProfiles.ProfileConflictException.class,failed(manager.apply("owner-id",id,true,()->true)));}
    }
    @Test void metadataChangedAfterInitialReviewIsNotClobberedByCommit()throws Exception {
        try(KernelProfiles manager=service()){Map<String,Object> p=plan(manager,"optimized");Map<String,String> before=kernelHashes();java.util.concurrent.atomic.AtomicInteger checks=new java.util.concurrent.atomic.AtomicInteger();Path metadata=data.resolve("kernel-profiles/profile.json");String external="{\"schema\":1,\"profile\":\"custom\",\"external\":\"operator\"}";
            BooleanSupplier owner=()->{if(checks.incrementAndGet()==2)try{Files.writeString(metadata,external);}catch(Exception e){throw new IllegalStateException(e);}return true;};assertInstanceOf(KernelProfiles.ProfileConflictException.class,failed(manager.apply("owner-id",(String)p.get("planId"),true,owner)));assertEquals(before,kernelHashes());assertEquals(external,Files.readString(metadata));
        }
    }
    @Test void authorityRevokedAfterAFileWriteRollsBackExactOriginalBytes()throws Exception {
        try(KernelProfiles manager=service()){Map<String,String> original=kernelHashes();Map<String,Object> p=plan(manager,"optimized");BooleanSupplier owner=()->{try{return !Files.readString(root.resolve("server.properties")).contains("view-distance=8");}catch(Exception e){return false;}};
            assertInstanceOf(SecurityException.class,failed(manager.apply("owner-id",(String)p.get("planId"),true,owner)));assertEquals(original,kernelHashes());assertFalse(Files.exists(data.resolve("kernel-profiles/profile.json")));assertEquals(0,runtime.liveCalls);
        }
    }
    @Test void shutdownKeepsOfflineLeaseUntilInterruptedWriterRollsBack()throws Exception {
        KernelProfiles manager=service();Map<String,String> before=kernelHashes();Map<String,Object> p=plan(manager,"optimized");CountDownLatch written=new CountDownLatch(1),release=new CountDownLatch(1);
        BooleanSupplier authority=()->{try{if(!Files.readString(root.resolve("server.properties")).contains("view-distance=8"))return true;}catch(Exception e){return false;}
            written.countDown();boolean interrupted=false;for(;;)try{release.await();break;}catch(InterruptedException e){interrupted=true;}if(interrupted)Thread.currentThread().interrupt();return false;
        };
        CompletableFuture<?> operation=manager.apply("owner-id",(String)p.get("planId"),true,authority);
        try{assertTrue(written.await(3,TimeUnit.SECONDS));manager.close();assertThrows(CancellationException.class,()->operation.get(3,TimeUnit.SECONDS));assertThrows(java.nio.channels.OverlappingFileLockException.class,()->{try(var c=java.nio.channels.FileChannel.open(data.resolve("kernel-profiles/runtime.lock"),StandardOpenOption.WRITE)){try(var lock=c.tryLock()){}}});}
        finally{release.countDown();manager.close();awaitLease();}
        assertEquals(before,kernelHashes());assertFalse(Files.exists(data.resolve("kernel-profiles/profile.json")));
    }
    @Test void plansExpireAndBoundedPlanQueueRejectsExcess()throws Exception {
        fixture();MutableClock clock=new MutableClock();try(KernelProfiles manager=new KernelProfiles(root,data,runtime,true,clock)){Map<String,Object> p=plan(manager,"custom");clock.now+=KernelProfiles.PLAN_TTL+1;assertInstanceOf(IllegalStateException.class,failed(manager.apply("owner-id",(String)p.get("planId"),true,()->true)));for(int i=0;i<8;i++)plan(manager,"custom");assertInstanceOf(IllegalStateException.class,failed(manager.plan("owner-id","custom",()->true)));}
    }
    @Test void unknownKernelAndUnknownSchemasFailClosedButCustomRemainsAvailable()throws Exception {
        try(KernelProfiles manager=service()){runtime.kernel="Folia";Map<?,?> caps=(Map<?,?>)done(manager.status()).get("capabilities");assertEquals(false,caps.get("managedFiles"));assertInstanceOf(java.io.IOException.class,failed(manager.plan("owner-id","optimized",()->true)));apply(manager,"custom");runtime.kernel="Paper";write("config/paper-world-defaults.yml","_version: 999\n");assertEquals(false,((Map<?,?>)done(manager.status()).get("capabilities")).get("managedFiles"));assertInstanceOf(java.io.IOException.class,failed(manager.plan("owner-id","vanilla",()->true)));}
    }
    @Test void spigotWithoutPaperFilesHasHonestRestartOnlyCapability()throws Exception {
        try(KernelProfiles manager=service()){runtime.kernel="Spigot";runtime.live=false;Files.delete(root.resolve("config/paper-world-defaults.yml"));Map<String,Object> result=apply(manager,"vanilla-performance");assertEquals(true,result.get("pendingRestart"));assertEquals(0,runtime.liveCalls);assertEquals(false,((Map<?,?>)result.get("capabilities")).get("liveDistances"));}
    }
    @Test void liveApiFailureRemainsVisibleWithDurablePendingRestartIntent()throws Exception {
        try(KernelProfiles manager=service()){runtime.failLive=true;Map<String,Object> result=apply(manager,"vanilla");assertEquals(true,result.get("saved"));assertEquals(true,result.get("pendingRestart"));assertFalse(String.valueOf(result.get("liveError")).isBlank());assertEquals("vanilla",result.get("profile"));}
    }
    @Test void fallbackStartupRestoresManagedDriftAndReportsRestart()throws Exception {
        try(KernelProfiles manager=service()){apply(manager,"optimized");write("server.properties",read("server.properties").replace("view-distance=8","view-distance=15"));Map<String,Object> result=done(manager.reconcileStartup());assertTrue(read("server.properties").contains("view-distance=8"));assertEquals(true,result.get("pendingRestart"));assertFalse(String.valueOf(result.get("startupMessage")).isBlank());runtime.kernel="Spigot";assertInstanceOf(java.io.IOException.class,failed(manager.reconcileStartup()));}
    }
    @Test void offlinePrelaunchReappliesDriftBeforeServerAndCustomDoesNothing()throws Exception {
        KernelProfiles manager=service();try{apply(manager,"optimized");assertThrows(Exception.class,()->KernelProfiles.reapplyOffline(root,data,"Paper","26.3"));}finally{manager.close();}awaitLease();
        write("server.properties",read("server.properties").replace("view-distance=8","view-distance=20"));Map<String,Object> result=KernelProfiles.reapplyOffline(root,data,"Paper","26.3");assertEquals(1L,result.get("changedFiles"));assertEquals(false,result.get("pendingRestart"));assertTrue(read("server.properties").contains("view-distance=8"));assertThrows(java.io.IOException.class,()->KernelProfiles.reapplyOffline(root,data,"Purpur","26.3"));
        try(KernelProfiles custom=new KernelProfiles(root,data,runtime,false)){apply(custom,"custom");}awaitLease();Map<String,String> before=kernelHashes();assertEquals("custom",ProfileBootstrap.run(new String[]{"--offline","--server-root",root.toString(),"--plugin-data",data.toString(),"--kernel","Paper","--minecraft-version","26.3"}).get("profile"));assertEquals(before,kernelHashes());assertThrows(IllegalArgumentException.class,()->ProfileBootstrap.run(new String[]{"--server-root",root.toString()}));
    }
    @Test void pathsOutsideServerAreRejectedBeforeCreatingProfileFiles()throws Exception {
        fixture();Path outside=root.resolveSibling(root.getFileName()+"-outside-profile");assertThrows(java.io.IOException.class,()->new KernelProfiles(root,outside,runtime,false));assertFalse(Files.exists(outside));assertThrows(java.io.IOException.class,()->KernelProfiles.resolve(root,"../outside.yml"));
    }
    private Map<String,String> kernelHashes()throws Exception {Map<String,String> out=new LinkedHashMap<>();for(String name:List.of("server.properties","spigot.yml","config/paper-world-defaults.yml","world/dimensions/minecraft/overworld/paper-world.yml"))if(Files.exists(root.resolve(name)))out.put(name,KernelProfiles.sha(Files.readAllBytes(root.resolve(name))));return out;}
    private void awaitLease()throws Exception {long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(System.nanoTime()<limit){try(var c=java.nio.channels.FileChannel.open(data.resolve("kernel-profiles/runtime.lock"),StandardOpenOption.WRITE)){try(var l=c.tryLock()){if(l!=null)return;}}catch(java.nio.channels.OverlappingFileLockException ignored){}Thread.sleep(10);}fail("Profile worker did not release its offline lease");}
    private static final class MutableClock extends Clock {long now=1_000_000;public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return Instant.ofEpochMilli(now);}}
    private static final class FakeRuntime implements KernelProfiles.RuntimeAccess {
        String kernel;boolean live=true,failLive;int liveCalls;List<KernelProfiles.WorldState> worlds;
        FakeRuntime(String kernel,List<KernelProfiles.WorldState> worlds){this.kernel=kernel;this.worlds=worlds;}
        public CompletableFuture<KernelProfiles.RuntimeState> snapshot(){return CompletableFuture.completedFuture(new KernelProfiles.RuntimeState(kernel,"26.3",live,worlds));}
        public CompletableFuture<List<Map<String,Object>>> applyDistances(int view,int simulation,BooleanSupplier owner){liveCalls++;if(!owner.getAsBoolean())return CompletableFuture.failedFuture(new SecurityException());if(failLive)return CompletableFuture.failedFuture(new IllegalStateException("test failure"));worlds=worlds.stream().map(w->new KernelProfiles.WorldState(w.name(),w.folder(),view,simulation)).toList();return CompletableFuture.completedFuture(List.of(Map.of("world","world","viewDistance",view,"simulationDistance",simulation)));}
    }
}
