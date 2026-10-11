package dev.mcc.server;

import dev.mcc.coreboot.CoreBootstrap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

class MccCoreInstallerTest {
    @TempDir Path root;
    private static void entry(JarOutputStream out,String path,byte[] bytes)throws IOException {out.putNextEntry(new JarEntry(path));out.write(bytes);out.closeEntry();}
    private Path plugin(boolean contract)throws Exception {
        Path result=root.resolve("plugins/MinecraftControlCenter.jar");Files.createDirectories(result.getParent());
        try(JarOutputStream out=new JarOutputStream(Files.newOutputStream(result))){
            entry(out,"dev/mcc/MccPlugin.class",new byte[]{1,2});entry(out,"dev/mcc/server/MccCoreLink.class",new byte[]{3,4});
            for(String name:List.of("CoreBootstrap","CoreBridge"))try(InputStream in=CoreBootstrap.class.getResourceAsStream(name+".class")){assertNotNull(in);entry(out,"dev/mcc/coreboot/"+name+".class",in.readAllBytes());}
            if(contract)entry(out,"META-INF/mcc-core-plugin.properties","protocol=1\nmain=dev.mcc.MccPlugin\n".getBytes(StandardCharsets.UTF_8));
            entry(out,"META-INF/mcc-core-licenses/LICENSE.md","GPL-3.0 notice test fixture".getBytes(StandardCharsets.UTF_8));
            entry(out,"META-INF/mcc-core-licenses/THIRD-PARTY.md","Upstream provenance test fixture".getBytes(StandardCharsets.UTF_8));
        }return result;
    }
    private Path upstream()throws Exception {return upstream(engineMetadata());}
    private static String engineMetadata(){return "engine.name=MCC Core\nengine.version="+CoreBootstrap.VERSION+"\nupstream.name=Paper\nupstream.minecraft=26.3\nupstream.commit="+CoreBootstrap.SOURCE_COMMIT+"\npatchset.sha256="+CoreBootstrap.PATCHSET_SHA+"\ntick.owner=paper-main-thread\nfeatures="+CoreBootstrap.ENGINE_FEATURES+"\n";}
    private Path upstream(String engine)throws Exception {
        Manifest manifest=new Manifest();manifest.getMainAttributes().putValue("Manifest-Version","1.0");manifest.getMainAttributes().putValue("Main-Class","io.papermc.paperclip.Main");manifest.getMainAttributes().putValue("Enable-Native-Access","ALL-UNNAMED");
        Path result=root.resolve("core.jar");try(JarOutputStream out=new JarOutputStream(Files.newOutputStream(result),manifest)){
            entry(out,"META-INF/patches.list",new byte[]{9,8,7});entry(out,"META-INF/license/paperclip-LICENSE.txt","MIT unchanged notice".getBytes(StandardCharsets.UTF_8));entry(out,"runtime/payload.patch",new byte[]{20,30,40});
            if(engine!=null)entry(out,"META-INF/mcc-engine.properties",engine.getBytes(StandardCharsets.UTF_8));
        }return result;
    }
    @Test void missingRequiredMccIsRefusedBeforeAWorldOrCoreFileIsCreated()throws Exception {
        assertThrows(IOException.class,()->CoreBootstrap.requiredPlugin(root,"a".repeat(64)));
        assertFalse(Files.exists(root.resolve("world")));assertFalse(Files.exists(root.resolve("core.jar")));
    }
    @Test void artifactMismatchAndMissingContractCannotPretendToBeTheRequiredPanel()throws Exception {
        Path plugin=plugin(false);String exact=CoreBootstrap.sha(plugin);
        assertThrows(IOException.class,()->CoreBootstrap.requiredPlugin(root,"0".repeat(64)));
        assertThrows(IOException.class,()->CoreBootstrap.requiredPlugin(root,exact));
    }
    @Test void runnableDistributionRetainsEveryUpstreamPayloadAndExactOriginalNoticeAndManifest()throws Exception {
        Path upstream=upstream(),plugin=plugin(true),output=root.resolve("MCC-Core.jar");String pluginSha=CoreBootstrap.sha(plugin),before=CoreBootstrap.sha(upstream);
        MccCoreInstaller.buildDistribution(root,upstream,plugin,output,pluginSha);
        assertEquals(before,CoreBootstrap.sha(upstream));
        try(JarFile original=new JarFile(upstream.toFile());JarFile core=new JarFile(output.toFile())){
            Enumeration<JarEntry> entries=original.entries();while(entries.hasMoreElements()){
                JarEntry old=entries.nextElement();String path=old.getName().equals("META-INF/MANIFEST.MF")?"META-INF/mcc-upstream-manifest.mf":old.getName();
                try(InputStream left=original.getInputStream(old);InputStream right=core.getInputStream(core.getJarEntry(path))){assertArrayEquals(left.readAllBytes(),right.readAllBytes(),path);}
            }
            assertEquals("dev.mcc.coreboot.CoreBootstrap",core.getManifest().getMainAttributes().getValue("Main-Class"));assertEquals("ALL-UNNAMED",core.getManifest().getMainAttributes().getValue("Enable-Native-Access"));
            assertNotNull(core.getEntry("dev/mcc/coreboot/CoreBootstrap.class"));assertNotNull(core.getEntry("dev/mcc/coreboot/CoreBridge.class"));
            assertNull(core.getEntry("dev/mcc/MccPlugin.class"));assertNull(core.getEntry("web/index.html"));assertNull(core.getEntry("database.db"));
        }
        MccCoreInstaller.validateDistribution(output,pluginSha);assertThrows(IOException.class,()->MccCoreInstaller.validateDistribution(output,"f".repeat(64)));
    }
    @Test void aCoreSourceCollisionIsRejectedAndNeverOverwritesAnExistingKernel()throws Exception {
        Path plugin=plugin(true),upstream=upstream(),output=root.resolve("MCC-Core.jar");Files.writeString(output,"existing independent artifact");
        assertThrows(FileAlreadyExistsException.class,()->MccCoreInstaller.buildDistribution(root,upstream,plugin,output,CoreBootstrap.sha(plugin)));
        assertEquals("existing independent artifact",Files.readString(output));
    }
    @Test void plainPaperCannotBePresentedAsThePatchedCore()throws Exception {
        Path upstream=upstream(null),plugin=plugin(true),output=root.resolve("MCC-Core.jar");
        assertThrows(IOException.class,()->MccCoreInstaller.buildDistribution(root,upstream,plugin,output,CoreBootstrap.sha(plugin)));
        assertFalse(Files.exists(output));assertFalse(Files.exists(root.resolve("world")));
    }
    @Test void changedSourcePatchsetOrThreadContractIsRejectedBeforePackaging()throws Exception {
        Path plugin=plugin(true);String exact=CoreBootstrap.sha(plugin);
        for(String changed:List.of(engineMetadata().replace(CoreBootstrap.SOURCE_COMMIT,"0".repeat(40)),engineMetadata().replace(CoreBootstrap.PATCHSET_SHA,"0".repeat(64)),engineMetadata().replace("paper-main-thread","parallel-world-ticks"))){
            Path upstream=upstream(changed),output=root.resolve("MCC-Core.jar");
            assertThrows(IOException.class,()->MccCoreInstaller.buildDistribution(root,upstream,plugin,output,exact));assertFalse(Files.exists(output));
        }
    }
    @Test void originalPaperBytesAndMissingResourceAreNotAnAcceptableFoundation()throws Exception {
        Path base=upstream(),plugin=plugin(true);
        assertThrows(IOException.class,()->MccCoreInstaller.verifyFoundation(base));
        assertThrows(IOException.class,()->MccCoreInstaller.foundationResourceAvailable(plugin));
    }
    @Test void unauthenticatedOrUnownedStagingCannotWriteAnyPlanOrServerJar()throws Exception {
        Path upstream=upstream(),plugin=plugin(true),data=root.resolve("plugins/MinecraftControlCenter");Files.createDirectories(data);String before=CoreBootstrap.sha(upstream);
        MccCoreInstaller service=new MccCoreInstaller(root,data,plugin);
        assertEquals(false,service.status().get("available"));assertThrows(SecurityException.class,()->service.stage("owner",()->false));
        assertThrows(Exception.class,()->service.stage("owner",()->true));assertEquals(before,CoreBootstrap.sha(upstream));assertFalse(Files.exists(data.resolve("mcc-core/selection.json")));
    }
    @Test void pendingInstallationRefusesWithoutActualOfflineSupervisorLeaseAndPreservesTheKernel()throws Exception {
        Path kernel=upstream(),data=root.resolve("plugins/MinecraftControlCenter");Files.createDirectories(data.resolve("mcc-core"));
        Files.writeString(data.resolve("mcc-core/selection.json"),"{\"state\":\"pending\",\"serverFile\":\"core.jar\"}");String before=CoreBootstrap.sha(kernel);
        assertThrows(Exception.class,()->MccCoreInstaller.runOffline(root,data,"core.jar"));assertEquals(before,CoreBootstrap.sha(kernel));assertFalse(Files.exists(root.resolve("world")));
    }
    @Test void boundedPlanReaderRejectsOversizedInput()throws Exception {Path data=root.resolve("data");Files.createDirectories(data);Files.writeString(data.resolve("selection.json")," ".repeat(16385));assertThrows(IOException.class,()->MccCoreInstaller.readPlan(data));}
}
