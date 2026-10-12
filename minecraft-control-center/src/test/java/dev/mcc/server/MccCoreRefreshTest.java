package dev.mcc.server;

import dev.mcc.core.Json;
import dev.mcc.coreboot.CoreBootstrap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

class MccCoreRefreshTest {
    @TempDir Path root;
    private record Fixture(Path root,Path data,Path current,Path artifact,Path selection,Path backup,Map<String,Object> plan,byte[] before,String newPlugin) {}
    private static void entry(JarOutputStream out,String name,byte[] bytes)throws IOException {out.putNextEntry(new JarEntry(name));out.write(bytes);out.closeEntry();}
    private static Path plugin(Path root,String marker)throws Exception {
        Path result=root.resolve("plugins/MinecraftControlCenter.jar");Files.createDirectories(result.getParent());
        try(JarOutputStream out=new JarOutputStream(Files.newOutputStream(result))) {
            entry(out,"dev/mcc/MccPlugin.class",new byte[]{1});entry(out,"dev/mcc/server/MccCoreLink.class",new byte[]{2});
            for(String name:List.of("CoreBootstrap","CoreBridge"))try(InputStream input=CoreBootstrap.class.getResourceAsStream(name+".class")) {
                assertNotNull(input);entry(out,"dev/mcc/coreboot/"+name+".class",input.readAllBytes());
            }
            entry(out,"META-INF/mcc-core-plugin.properties","protocol=1\nmain=dev.mcc.MccPlugin\n".getBytes(StandardCharsets.UTF_8));
            entry(out,"META-INF/mcc-core-licenses/LICENSE.md","test fixture license".getBytes(StandardCharsets.UTF_8));
            entry(out,"META-INF/mcc-core-licenses/THIRD-PARTY.md","test fixture provenance".getBytes(StandardCharsets.UTF_8));
            entry(out,"test-version.txt",marker.getBytes(StandardCharsets.UTF_8));
        }
        return result;
    }
    private static Path paper(Path root)throws IOException {
        Manifest manifest=new Manifest();manifest.getMainAttributes().putValue("Manifest-Version","1.0");manifest.getMainAttributes().putValue("Main-Class","io.papermc.paperclip.Main");
        Path result=root.resolve("fixture-foundation.jar");
        try(JarOutputStream out=new JarOutputStream(Files.newOutputStream(result),manifest)) {
            entry(out,"META-INF/patches.list",new byte[]{1,2,3});
            String metadata="engine.name=MCC Core\nengine.version="+CoreBootstrap.VERSION+"\nupstream.name=Paper\nupstream.minecraft=26.3\nupstream.commit="+CoreBootstrap.SOURCE_COMMIT+
                "\npatchset.sha256="+CoreBootstrap.PATCHSET_SHA+"\ntick.owner=paper-main-thread\nfeatures="+CoreBootstrap.ENGINE_FEATURES+"\n";
            entry(out,"META-INF/mcc-engine.properties",metadata.getBytes(StandardCharsets.UTF_8));
            entry(out,"runtime/fixture.patch",new byte[]{4,5});
        }
        return result;
    }
    private Fixture fixture(Path base)throws Exception {
        Files.createDirectories(base);Path foundation=paper(base),plugin=plugin(base,"old");String previousPlugin=CoreBootstrap.sha(plugin);
        Path current=base.resolve("paper.jar");MccCoreInstaller.buildDistribution(base,foundation,plugin,current,previousPlugin);
        Path data=base.resolve("plugins/MinecraftControlCenter"),core=data.resolve("mcc-core");Files.createDirectories(core);
        Map<String,Object> plan=new LinkedHashMap<>();plan.put("schema",1);plan.put("id",UUID.randomUUID().toString());plan.put("state","installed");plan.put("serverFile","paper.jar");
        plan.put("sourceSha256",CoreBootstrap.sha(foundation));plan.put("artifactSha256",CoreBootstrap.sha(current));plan.put("pluginSha256",previousPlugin);
        plan.put("baseSha256",CoreBootstrap.ENGINE_SHA);plan.put("enginePatchset",CoreBootstrap.PATCHSET_SHA);plan.put("coreVersion",CoreBootstrap.VERSION);
        plan.put("backupId",plan.get("id"));plan.put("actor","owner-fixture");
        Path selection=core.resolve("selection.json");byte[] before=Json.write(plan).getBytes(StandardCharsets.UTF_8);Files.write(selection,before);
        plugin=plugin(base,"updated");String nextPlugin=CoreBootstrap.sha(plugin);Path backup=core.resolve("backups").resolve(UUID.randomUUID().toString());Files.createDirectories(backup);
        Path artifact=backup.resolve("MCC-Core.jar");MccCoreInstaller.buildDistribution(base,foundation,plugin,artifact,nextPlugin);
        return new Fixture(base,data,current,artifact,selection,backup,plan,before,nextPlugin);
    }
    private static Map<String,Object> commit(Fixture f,MccCoreRefresh.Check check)throws Exception {
        return MccCoreRefresh.commitPrepared(f.root,f.current,f.selection,f.backup,f.artifact,f.before,f.plan,f.newPlugin,check);
    }
    @Test void refreshedBindingHasVerifiedPairBackupsAndRetainsOriginalPaperRollbackIdentity()throws Exception {
        Fixture f=fixture(root);String previous=CoreBootstrap.sha(f.current);Map<String,Object> result=commit(f,stage->{});
        assertEquals(true,result.get("changed"));assertEquals(true,result.get("refreshed"));assertEquals(false,result.get("worldDataChanged"));
        Map<String,Object> after=MccCoreInstaller.readPlan(f.data.resolve("mcc-core"));
        assertEquals(f.newPlugin,after.get("pluginSha256"));assertEquals(CoreBootstrap.sha(f.current),after.get("artifactSha256"));
        assertEquals(f.plan.get("backupId"),after.get("backupId"));assertEquals(f.plan.get("sourceSha256"),after.get("sourceSha256"));
        assertEquals(previous,CoreBootstrap.sha(f.backup.resolve("previous-server.jar")));assertArrayEquals(f.before,Files.readAllBytes(f.backup.resolve("previous-selection.json")));
        MccCoreInstaller.validateDistribution(f.current,f.newPlugin);assertFalse(Files.exists(root.resolve("world")));
    }
    @Test void failuresAfterReplacementAndAfterSelectionRestoreBothVerifiedOriginalFiles()throws Exception {
        for(MccCoreRefresh.Stage failureAt:List.of(MccCoreRefresh.Stage.AFTER_REPLACE,MccCoreRefresh.Stage.AFTER_SELECTION)) {
            Fixture f=fixture(root.resolve(failureAt.name()));String before=CoreBootstrap.sha(f.current);
            IOException error=assertThrows(IOException.class,()->commit(f,stage->{if(stage==failureAt)throw new IOException("injected commit failure");}));
            assertEquals(0,error.getSuppressed().length);assertEquals(before,CoreBootstrap.sha(f.current));assertArrayEquals(f.before,Files.readAllBytes(f.selection));
            assertEquals(before,CoreBootstrap.sha(f.backup.resolve("previous-server.jar")));assertFalse(Files.exists(f.root.resolve("world")));
        }
    }
    @Test void rollbackNeverOverwritesAConcurrentUnrecognizedArtifact()throws Exception {
        Fixture f=fixture(root);byte[] unrelated="operator changed kernel".getBytes(StandardCharsets.UTF_8);
        IOException failure=assertThrows(IOException.class,()->commit(f,stage->{if(stage==MccCoreRefresh.Stage.AFTER_REPLACE){Files.write(f.current,unrelated);throw new IOException("concurrent change");}}));
        assertEquals(1,failure.getSuppressed().length);assertArrayEquals(unrelated,Files.readAllBytes(f.current));assertArrayEquals(f.before,Files.readAllBytes(f.selection));
        assertEquals(f.plan.get("artifactSha256"),CoreBootstrap.sha(f.backup.resolve("previous-server.jar")));
    }
    @Test void cancellationRestoresBothFilesBeforeRestoringTheThreadInterrupt()throws Exception {
        Fixture f=fixture(root);String previous=CoreBootstrap.sha(f.current);
        try {
            InterruptedException failure=assertThrows(InterruptedException.class,()->commit(f,stage->{
                if(stage==MccCoreRefresh.Stage.AFTER_SELECTION){Thread.currentThread().interrupt();throw new InterruptedException("cancel refresh");}
            }));
            assertEquals(0,failure.getSuppressed().length);assertTrue(Thread.currentThread().isInterrupted());
            Thread.interrupted();assertEquals(previous,CoreBootstrap.sha(f.current));assertArrayEquals(f.before,Files.readAllBytes(f.selection));
        } finally {Thread.interrupted();}
    }
    @Test void aPaperReplacementWithStaleInstalledSelectionIsNeverConvertedBackToCore()throws Exception {
        Fixture f=fixture(root);Files.copy(root.resolve("fixture-foundation.jar"),f.current,StandardCopyOption.REPLACE_EXISTING);String before=CoreBootstrap.sha(f.current);
        Map<String,Object> result=MccCoreInstaller.runOffline(root,f.data,"paper.jar");
        assertEquals("keep",result.get("selection"));assertEquals(false,result.get("changed"));assertEquals(before,CoreBootstrap.sha(f.current));assertArrayEquals(f.before,Files.readAllBytes(f.selection));
    }
    @Test void unsupportedSchemaPatchsetAndArtifactShaAreRefusedWithoutChangingFiles()throws Exception {
        Fixture f=fixture(root);String before=CoreBootstrap.sha(f.current);
        for(var changed:List.of(Map.entry("schema",1.5),Map.entry("schema",2),Map.entry("enginePatchset","0".repeat(64)),Map.entry("artifactSha256","0".repeat(64)))) {
            Map<String,Object> invalid=new LinkedHashMap<>(f.plan);invalid.put(changed.getKey(),changed.getValue());Files.writeString(f.selection,Json.write(invalid));
            assertThrows(IOException.class,()->MccCoreInstaller.runOffline(root,f.data,"paper.jar"));assertEquals(before,CoreBootstrap.sha(f.current));
            assertEquals(invalid,MccCoreInstaller.readPlan(f.data.resolve("mcc-core")));
        }
    }
    @Test void evenCorrectParentMetadataCannotRefreshWithoutTheHeldSupervisorLease()throws Exception {
        Fixture f=fixture(root);Path control=root.resolve(".mcc-launcher");Files.createDirectory(control);Files.writeString(control.resolve("daemon.lock"),"0");
        String run=UUID.randomUUID().toString();long parent=ProcessHandle.current().parent().orElseThrow().pid();Map<String,Object> status=new LinkedHashMap<>();
        status.put("protocolVersion",3);status.put("launcherKind","embedded-java");status.put("leaseKind","java-file-lock-byte-0");status.put("runId",run);status.put("currentRunId",run);
        status.put("state","prelaunch");status.put("profileHook","running");status.put("javaPid",null);status.put("daemonPid",parent);Files.writeString(control.resolve("status.json"),Json.write(status));
        String before=CoreBootstrap.sha(f.current);assertThrows(IOException.class,()->MccCoreInstaller.runOffline(root,f.data,"paper.jar"));
        assertEquals(before,CoreBootstrap.sha(f.current));assertArrayEquals(f.before,Files.readAllBytes(f.selection));
        status.put("daemonPid",(double)parent+0.5);Files.writeString(control.resolve("status.json"),Json.write(status));assertThrows(IOException.class,()->MccCoreInstaller.runOffline(root,f.data,"paper.jar"));
    }
    @Test void changedSelectionDuringPreparationPreventsAnyKernelReplacement()throws Exception {
        Fixture f=fixture(root);String before=CoreBootstrap.sha(f.current);byte[] independent="{\"state\":\"keep\"}".getBytes(StandardCharsets.UTF_8);Files.write(f.selection,independent);
        assertThrows(IOException.class,()->commit(f,stage->{}));assertEquals(before,CoreBootstrap.sha(f.current));assertArrayEquals(independent,Files.readAllBytes(f.selection));
    }
    @Test void refreshedMetadataCannotOutgrowItsBoundAndLeaveAnUnreadablePair()throws Exception {
        Fixture f=fixture(root);String previous=CoreBootstrap.sha(f.current);Map<String,Object> plan=new LinkedHashMap<>(f.plan);plan.put("extension","");
        int empty=Json.write(plan).getBytes(StandardCharsets.UTF_8).length;plan.put("extension","x".repeat(16370-empty));
        byte[] before=Json.write(plan).getBytes(StandardCharsets.UTF_8);assertEquals(16370,before.length);Files.write(f.selection,before);
        assertThrows(IOException.class,()->MccCoreRefresh.commitPrepared(f.root,f.current,f.selection,f.backup,f.artifact,before,plan,f.newPlugin,stage->{}));
        assertEquals(previous,CoreBootstrap.sha(f.current));assertArrayEquals(before,Files.readAllBytes(f.selection));
    }
}
