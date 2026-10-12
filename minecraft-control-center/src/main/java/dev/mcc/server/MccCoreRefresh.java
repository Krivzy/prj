package dev.mcc.server;

import dev.mcc.core.Json;
import dev.mcc.core.PrivateFiles;
import dev.mcc.coreboot.CoreBootstrap;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/** Rebind an already verified installed Core after an offline MCC plugin update. */
final class MccCoreRefresh {
    private MccCoreRefresh() {}
    enum Stage { BEFORE_REPLACE, AFTER_REPLACE, AFTER_SELECTION }
    @FunctionalInterface interface Check { void verify(Stage stage) throws Exception; }

    static Map<String,Object> runOffline(Path root,Path pluginData,String serverFile,Map<String,Object> plan)throws Exception {
        Path data=pluginData.resolve("mcc-core"),current=root.resolve(serverFile),plugin=root.resolve("plugins/MinecraftControlCenter.jar");
        MccCoreInstaller.checkPath(root,current,false);
        // A stale selection file does not authorize converting an independently installed Paper JAR.
        if(!coreCapsule(current))return Map.of("selection","keep","changed",false);
        validateInstalled(current,serverFile,plan);
        String previousPlugin=shaValue(plan.get("pluginSha256")),newPlugin=CoreBootstrap.sha(plugin);
        CoreBootstrap.requiredPlugin(root,newPlugin);
        if(newPlugin.equals(previousPlugin))return Map.of("selection","installed","changed",false);
        MccCoreInstaller.verifyOffline(root);
        Path runtime=pluginData.resolve("kernel-profiles/runtime.lock"),apply=data.resolve("apply.lock");
        MccCoreInstaller.checkPath(root,runtime,false);MccCoreInstaller.checkPath(root,apply,false);
        try(FileChannel runtimeChannel=FileChannel.open(runtime,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);FileLock runtimeLock=runtimeChannel.tryLock();
            FileChannel applyChannel=FileChannel.open(apply,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);FileLock applyLock=applyChannel.tryLock()) {
            if(runtimeLock==null||applyLock==null)throw new IOException("Core refresh requires an inactive MCC runtime");
            byte[] selection=selectionBytes(root,data.resolve("selection.json"));
            if(!Json.object(new String(selection,StandardCharsets.UTF_8)).equals(plan))throw new IOException("Core selection changed before refresh");
            validateInstalled(current,serverFile,plan);MccCoreInstaller.foundationResourceAvailable(plugin);
            Path foundation=data.resolve("engine-"+CoreBootstrap.VERSION+".jar");
            if(!Files.exists(foundation,LinkOption.NOFOLLOW_LINKS))MccCoreInstaller.extractFoundation(root,plugin,foundation,()->true);
            MccCoreInstaller.checkPath(root,foundation,false);MccCoreInstaller.verifyFoundation(foundation);
            Path backup=MccCoreInstaller.privateDirectory(root,data.resolve("backups").resolve(UUID.randomUUID().toString()));
            Path artifact=backup.resolve("MCC-Core.jar");
            MccCoreInstaller.buildDistribution(root,foundation,plugin,artifact,newPlugin);
            Check identity=stage->{
                MccCoreInstaller.verifyOffline(root);
                if(!CoreBootstrap.sha(plugin).equals(newPlugin))throw new IOException("MCC plugin changed during Core refresh");
            };
            return commitPrepared(root,current,data.resolve("selection.json"),backup,artifact,selection,plan,newPlugin,identity);
        }
    }
    static boolean coreCapsule(Path current)throws IOException {
        if(Files.size(current)>MccCoreInstaller.MAX_ARTIFACT)throw new IOException("Core artifact exceeds its budget");
        try(JarFile jar=new JarFile(current.toFile())) {
            var entry=jar.getJarEntry("META-INF/MANIFEST.MF");if(entry==null)return false;
            try(var input=jar.getInputStream(entry)) {
                byte[] bytes=input.readNBytes(65537);if(bytes.length>65536)throw new IOException("Kernel manifest exceeds budget");
                Manifest manifest=new Manifest(new ByteArrayInputStream(bytes));
                return "dev.mcc.coreboot.CoreBootstrap".equals(manifest.getMainAttributes().getValue("Main-Class"));
            }
        }
    }
    static void validateInstalled(Path current,String serverFile,Map<String,Object> plan)throws IOException {
        Object schema=plan.get("schema");
        if(!(schema instanceof Integer||schema instanceof Long)||((Number)schema).longValue()!=1||!"installed".equals(plan.get("state"))||
            !serverFile.equals(plan.get("serverFile"))||!CoreBootstrap.VERSION.equals(plan.get("coreVersion"))||
            !CoreBootstrap.ENGINE_SHA.equals(plan.get("baseSha256"))||!CoreBootstrap.PATCHSET_SHA.equals(plan.get("enginePatchset")))
            throw new IOException("Installed Core selection identity is unsupported");
        canonicalId(plan.get("id"));shaValue(plan.get("sourceSha256"));
        String previous=shaValue(plan.get("pluginSha256")),artifact=shaValue(plan.get("artifactSha256"));
        if(!CoreBootstrap.sha(current).equals(artifact))throw new IOException("Installed Core differs from its verified selection");
        MccCoreInstaller.validateDistribution(current,previous);
    }
    /** Transaction mechanics are separate so fault injection can verify rollback without starting a server. */
    static Map<String,Object> commitPrepared(Path root,Path current,Path selectionFile,Path backup,Path artifact,byte[] oldSelection,
                                           Map<String,Object> plan,String newPlugin,Check check)throws Exception {
        MccCoreInstaller.checkPath(root,current,false);MccCoreInstaller.checkPath(root,artifact,false);MccCoreInstaller.checkPath(root,backup,true);
        validateInstalled(current,current.getFileName().toString(),plan);
        MccCoreInstaller.validateDistribution(artifact,newPlugin);
        String previous=shaValue(plan.get("artifactSha256")),next=CoreBootstrap.sha(artifact);
        if(!Arrays.equals(selectionBytes(root,selectionFile),oldSelection)||!Json.object(new String(oldSelection,StandardCharsets.UTF_8)).equals(plan))
            throw new IOException("Core selection changed during preparation");
        Path priorCore=backup.resolve("previous-server.jar"),priorSelection=backup.resolve("previous-selection.json");
        MccCoreInstaller.copyNew(root,current,priorCore);writeNew(priorSelection,oldSelection);
        if(!CoreBootstrap.sha(priorCore).equals(previous)||!Arrays.equals(selectionBytes(root,priorSelection),oldSelection))
            throw new IOException("Core refresh backup failed verification");
        Map<String,Object> updated=new LinkedHashMap<>(plan);updated.put("artifactSha256",next);updated.put("pluginSha256",newPlugin);
        updated.put("refreshedAt",Instant.now().toString());updated.put("refreshBackupId",backup.getFileName().toString());
        byte[] after=Json.write(updated).getBytes(StandardCharsets.UTF_8);
        if(after.length>16384)throw new IOException("Refreshed Core selection exceeds budget");
        Map<String,Object> receipt=new LinkedHashMap<>();receipt.put("schema",1);receipt.put("kind","plugin-binding-refresh");
        receipt.put("serverFile",current.getFileName().toString());receipt.put("previousArtifactSha256",previous);receipt.put("artifactSha256",next);
        receipt.put("previousPluginSha256",plan.get("pluginSha256"));receipt.put("pluginSha256",newPlugin);receipt.put("worldDataChanged",false);
        receipt.put("createdAt",Instant.now().toString());MccCoreInstaller.atomic(root,backup.resolve("receipt.json"),Json.write(receipt).getBytes(StandardCharsets.UTF_8));
        Path replacement=current.resolveSibling("."+current.getFileName()+".mcc-refresh-"+UUID.randomUUID()+".tmp");
        boolean replaced=false;
        try {
            MccCoreInstaller.copyNew(root,artifact,replacement);
            if(!CoreBootstrap.sha(replacement).equals(next))throw new IOException("Prepared Core refresh copy differs");
            check.verify(Stage.BEFORE_REPLACE);
            if(!CoreBootstrap.sha(current).equals(previous)||!Arrays.equals(selectionBytes(root,selectionFile),oldSelection))
                throw new IOException("Installed Core or selection changed before replacement");
            Files.move(replacement,current,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);replaced=true;
            if(!CoreBootstrap.sha(current).equals(next))throw new IOException("Installed Core refresh failed verification");
            check.verify(Stage.AFTER_REPLACE);
            if(!Arrays.equals(selectionBytes(root,selectionFile),oldSelection))throw new IOException("Core selection changed before commit");
            MccCoreInstaller.atomic(root,selectionFile,after);
            if(!Arrays.equals(selectionBytes(root,selectionFile),after))throw new IOException("Core refresh selection failed verification");
            check.verify(Stage.AFTER_SELECTION);
            return Map.of("selection","installed","changed",true,"refreshed",true,"refreshBackupId",backup.getFileName().toString(),
                "core","MCC Core","coreVersion",CoreBootstrap.VERSION,"artifactSha256",next,"worldDataChanged",false);
        } catch(Exception failure) {
            // Cancellation must not interrupt the verified restoration after a completed replacement.
            boolean interrupted=Thread.interrupted()||failure instanceof InterruptedException;
            try {
                if(replaced)try {rollback(root,current,selectionFile,priorCore,oldSelection,after,previous,next);}
                catch(Exception rollbackFailure){failure.addSuppressed(rollbackFailure);}
            } finally {if(interrupted)Thread.currentThread().interrupt();}
            throw failure;
        } finally {Files.deleteIfExists(replacement);}
    }
    private static void rollback(Path root,Path current,Path selection,Path backup,byte[] before,byte[] after,String previous,String next)throws IOException {
        MccCoreInstaller.checkPath(root,current,false);MccCoreInstaller.checkPath(root,backup,false);
        byte[] selected=selectionBytes(root,selection);String installed=CoreBootstrap.sha(current);
        if(!Set.of(previous,next).contains(installed)||!CoreBootstrap.sha(backup).equals(previous)||
            !(Arrays.equals(selected,before)||Arrays.equals(selected,after)))throw new IOException("Core refresh rollback refused changed files; verified backup remains");
        Path temporary=current.resolveSibling("."+current.getFileName()+".mcc-restore-"+UUID.randomUUID()+".tmp");
        try {
            MccCoreInstaller.copyNew(root,backup,temporary);
            if(!CoreBootstrap.sha(temporary).equals(previous))throw new IOException("Core rollback copy differs");
            Files.move(temporary,current,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            if(!CoreBootstrap.sha(current).equals(previous))throw new IOException("Restored Core failed verification");
            MccCoreInstaller.atomic(root,selection,before);
            if(!Arrays.equals(selectionBytes(root,selection),before))throw new IOException("Restored Core selection failed verification");
        } finally {Files.deleteIfExists(temporary);}
    }
    private static byte[] selectionBytes(Path root,Path file)throws IOException {
        MccCoreInstaller.checkPath(root,file,false);
        try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes=input.readNBytes(16385);if(bytes.length>16384)throw new IOException("Core selection exceeds budget");return bytes;
        }
    }
    private static void writeNew(Path file,byte[] bytes)throws IOException {
        Files.write(file,bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);PrivateFiles.restrict(file,false);
        try(FileChannel channel=FileChannel.open(file,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){channel.force(true);}
    }
    private static String shaValue(Object value)throws IOException {
        if(!(value instanceof String sha)||!sha.matches("[a-f0-9]{64}"))throw new IOException("Invalid Core refresh SHA");return sha;
    }
    private static void canonicalId(Object value)throws IOException {
        try {if(!(value instanceof String id)||!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();}
        catch(IllegalArgumentException invalid){throw new IOException("Invalid installed Core plan id");}
    }
}
