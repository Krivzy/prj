package dev.mcc.server;

import dev.mcc.core.Json;
import dev.mcc.core.PrivateFiles;
import dev.mcc.coreboot.CoreBootstrap;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.jar.*;
import java.util.zip.ZipEntry;

/** Prepares the bundled, patched Paper-compatible engine; replacement is an offline prelaunch operation. */
public final class MccCoreInstaller {
    public static final long MAX_ARTIFACT=100_000_000L;
    private static final String BOOT_PREFIX="dev/mcc/coreboot/",CORE_VERSION=CoreBootstrap.VERSION;
    private final Path root,data,plugin;
    private final String serverFile;
    public MccCoreInstaller(Path serverRoot,Path pluginData,Path pluginJar)throws IOException {
        root=serverRoot.toRealPath();data=pluginData.toAbsolutePath().normalize().resolve("mcc-core");plugin=pluginJar.toAbsolutePath().normalize();
        if(!plugin.equals(root.resolve("plugins/MinecraftControlCenter.jar"))||!data.equals(root.resolve("plugins/MinecraftControlCenter/mcc-core")))throw new IOException("Invalid MCC Core server scope");
        checkPath(root,plugin,false);checkPath(root,data,true);serverFile=System.getProperty("mcc.launcher.server-jar","");
    }
    public synchronized Map<String,Object> status(){
        Map<String,Object> result=new LinkedHashMap<>(MccCoreLink.state());result.put("version",CORE_VERSION);result.put("upstream","Paper");result.put("minecraftVersion","26.3");result.put("upstreamBuild",169);
        result.put("method","verified-staging-and-offline-replacement");result.put("requiresRestart",true);result.put("preservesWorlds",true);result.put("performanceBenchmarkAvailable",false);
        String reason="";Map<String,Object> plan=Map.of();
        try {verifyRuntime(root,serverFile);foundationResourceAvailable(plugin);plan=readPlan(data);}
        catch(Exception failure){reason="Переход доступен для Paper/Purpur 26.3 под MCC launcher, если сборка панели содержит проверенное ядро MCC Core. Текущее ядро продолжает работать.";}
        String selection=String.valueOf(plan.getOrDefault("state",MccCoreLink.active()?"installed":"keep"));
        if("installed".equals(selection)&&!MccCoreLink.active())selection="keep";
        result.put("available",reason.isEmpty());result.put("message",reason);result.put("pendingRestart","pending".equals(plan.get("state")));result.put("selection",selection);
        result.put("backupId",plan.getOrDefault("backupId",""));return Collections.unmodifiableMap(result);
    }
    /** Caller runs on an IO executor and rechecks its authenticated Owner authority throughout. */
    public synchronized Map<String,Object> stage(String actor,BooleanSupplier authority)throws Exception {
        permit(authority);if(actor==null||actor.isBlank()||actor.length()>128)throw new IllegalArgumentException("Invalid MCC Core actor");
        Map<String,Object> runtime=verifyRuntime(root,serverFile);checkPath(root,plugin,false);String pluginSha=CoreBootstrap.sha(plugin);
        CoreBootstrap.requiredPlugin(root,pluginSha);
        Path current=root.resolve(serverFile);String currentSha=CoreBootstrap.sha(current);permit(authority);
        privateDirectory(root,data);
        try(FileChannel channel=FileChannel.open(data.resolve("apply.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);FileLock guard=channel.tryLock()){
            if(guard==null)throw new IOException("MCC Core preparation is busy");
            Map<String,Object> previous=readPlan(data);
            if("pending".equals(previous.get("state"))){
                if(pluginSha.equals(previous.get("pluginSha256"))&&currentSha.equals(previous.get("sourceSha256")))return status();
                throw new IllegalStateException("A different MCC Core transition is already pending");
            }
            Path base=data.resolve("engine-"+CORE_VERSION+".jar");
            if(!Files.exists(base,LinkOption.NOFOLLOW_LINKS))extractFoundation(root,plugin,base,authority);
            checkPath(root,base,false);verifyFoundation(base);
            String id=UUID.randomUUID().toString();Path directory=privateDirectory(root,data.resolve("staged").resolve(id));Path artifact=directory.resolve("MCC-Core.jar");
            buildDistribution(root,base,plugin,artifact,pluginSha);String stagedSha=CoreBootstrap.sha(artifact);permit(authority);
            if(!CoreBootstrap.sha(plugin).equals(pluginSha)||!CoreBootstrap.sha(current).equals(currentSha))throw new IllegalStateException("MCC or kernel changed during preparation; stage again");
            verifyRuntime(root,serverFile);permit(authority);
            Map<String,Object> plan=new LinkedHashMap<>();plan.put("schema",1);plan.put("id",id);plan.put("state","pending");plan.put("actor",actor);plan.put("createdAt",Instant.now().toString());
            plan.put("sourceRunId",runtime.get("runId"));plan.put("serverFile",serverFile);plan.put("sourceSha256",currentSha);plan.put("pluginSha256",pluginSha);plan.put("artifactSha256",stagedSha);plan.put("baseSha256",CoreBootstrap.ENGINE_SHA);
            plan.put("coreVersion",CORE_VERSION);plan.put("enginePatchset",CoreBootstrap.PATCHSET_SHA);
            plan.put("sourceKernel",runtime.get("kernelName"));
            atomic(root,data.resolve("selection.json"),Json.write(plan).getBytes(StandardCharsets.UTF_8));return status();
        }
    }
    /** The supervisor retains its daemon lease; this additionally proves the old server runtime has ended. */
    public static Map<String,Object> runOffline(Path serverRoot,Path pluginData,String serverFile)throws Exception {
        Path root=serverRoot.toRealPath(),data=pluginData.toAbsolutePath().normalize().resolve("mcc-core");
        if(!data.equals(root.resolve("plugins/MinecraftControlCenter/mcc-core")))throw new IOException("Invalid MCC Core offline scope");
        if(!Files.exists(data,LinkOption.NOFOLLOW_LINKS))return Map.of("selection","keep","changed",false);
        checkPath(root,data,true);Map<String,Object> plan=readPlan(data);
        if("installed".equals(plan.get("state"))){filename(serverFile);return MccCoreRefresh.runOffline(root,pluginData,serverFile,plan);}
        if(!"pending".equals(plan.get("state")))return Map.of("selection",plan.getOrDefault("state","keep"),"changed",false);
        filename(serverFile);if(!serverFile.equals(plan.get("serverFile")))throw new IOException("Pending MCC Core target does not match launcher target");
        verifyOffline(root);
        Path lease=pluginData.resolve("kernel-profiles/runtime.lock");checkPath(root,lease,false);
        try(FileChannel runtime=FileChannel.open(lease,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);FileLock runtimeGuard=runtime.tryLock();
            FileChannel apply=FileChannel.open(data.resolve("apply.lock"),StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);FileLock applyGuard=apply.tryLock()){
            if(runtimeGuard==null||applyGuard==null)throw new IOException("MCC Core or plugin runtime remains active");
            String id=canonicalId(plan.get("id")),source=shaValue(plan.get("sourceSha256")),expected=shaValue(plan.get("artifactSha256")),pluginSha=shaValue(plan.get("pluginSha256"));
            if(!CoreBootstrap.ENGINE_SHA.equals(plan.get("baseSha256"))||!CORE_VERSION.equals(plan.get("coreVersion"))||!CoreBootstrap.PATCHSET_SHA.equals(plan.get("enginePatchset")))throw new IOException("Unsupported pending MCC Core base");
            Path current=root.resolve(serverFile),artifact=data.resolve("staged").resolve(id).resolve("MCC-Core.jar"),plugin=root.resolve("plugins/MinecraftControlCenter.jar");
            checkPath(root,current,false);checkPath(root,artifact,false);checkPath(root,plugin,false);
            if(!CoreBootstrap.sha(artifact).equals(expected)||!CoreBootstrap.sha(plugin).equals(pluginSha))throw new IOException("MCC Core staged artifact or required MCC plugin changed");
            validateDistribution(artifact,pluginSha);
            String currentSha=CoreBootstrap.sha(current);Path backup=privateDirectory(root,data.resolve("backups").resolve(id)).resolve("previous-server.jar");
            if(!currentSha.equals(source)&&!currentSha.equals(expected))throw new IOException("The server JAR changed after MCC Core preparation");
            if(currentSha.equals(source)){
                if(!Files.exists(backup,LinkOption.NOFOLLOW_LINKS))copyNew(root,current,backup);
                checkPath(root,backup,false);if(!CoreBootstrap.sha(backup).equals(source))throw new IOException("Previous server JAR backup failed verification");
                Map<String,Object> receipt=Map.of("schema",1,"id",id,"serverFile",serverFile,"sourceSha256",source,"artifactSha256",expected,"pluginSha256",pluginSha,"worldDataChanged",false,"createdAt",Instant.now().toString());
                atomic(root,backup.getParent().resolve("receipt.json"),Json.write(receipt).getBytes(StandardCharsets.UTF_8));
                verifyOffline(root);if(!CoreBootstrap.sha(current).equals(source))throw new IOException("Kernel changed during offline backup");
                Path replacement=current.resolveSibling("."+serverFile+".mcc-core-"+id+".tmp");
                copyNew(root,artifact,replacement);if(!CoreBootstrap.sha(replacement).equals(expected))throw new IOException("MCC Core replacement failed verification");
                try{Files.move(replacement,current,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(replacement);}
                if(!CoreBootstrap.sha(current).equals(expected))throw new IOException("Installed MCC Core JAR failed verification");
            }else {checkPath(root,backup,false);if(!CoreBootstrap.sha(backup).equals(source))throw new IOException("Interrupted MCC Core install has no verified previous JAR");}
            KernelProfiles.adoptCoreIdentityOffline(root,pluginData,backup.getParent());
            Map<String,Object> installed=new LinkedHashMap<>(plan);installed.put("state","installed");installed.put("installedAt",Instant.now().toString());installed.put("backupId",id);
            atomic(root,data.resolve("selection.json"),Json.write(installed).getBytes(StandardCharsets.UTF_8));
            return Map.of("selection","installed","changed",true,"backupId",id,"core","MCC Core","coreVersion",CORE_VERSION,"upstream","Paper","artifactSha256",expected);
        }
    }
    static void buildDistribution(Path root,Path base,Path plugin,Path output,String pluginSha)throws Exception {
        checkPath(root,base,false);checkPath(root,plugin,false);if(Files.size(base)>MAX_ARTIFACT||Files.size(plugin)>MAX_ARTIFACT)throw new IOException("MCC Core source artifact exceeds budget");
        try(JarFile upstream=new JarFile(base.toFile());JarFile mcc=new JarFile(plugin.toFile())){
            Manifest original=upstream.getManifest();if(original==null||!"io.papermc.paperclip.Main".equals(original.getMainAttributes().getValue("Main-Class")))throw new IOException("Unsupported Paperclip main class");
            CoreBootstrap.engineMetadata(upstream);
            for(String notice:List.of("LICENSE.md","THIRD-PARTY.md"))if(mcc.getJarEntry("META-INF/mcc-core-licenses/"+notice)==null)throw new IOException("MCC Core distribution license notices are absent");
            Manifest manifest=new Manifest(original);manifest.getMainAttributes().putValue("Main-Class","dev.mcc.coreboot.CoreBootstrap");manifest.getMainAttributes().putValue("MCC-Core-Version",CORE_VERSION);manifest.getMainAttributes().putValue("MCC-Core-Protocol","1");
            int count=0,classes=0;Set<String> names=new HashSet<>();
            try(JarOutputStream out=new JarOutputStream(Files.newOutputStream(output,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE),manifest)){
                Enumeration<JarEntry> entries=upstream.entries();while(entries.hasMoreElements()){
                    JarEntry entry=entries.nextElement();String name=entry.getName();if(++count>20000||!names.add(name))throw new IOException("Duplicate or excessive upstream archive entries");
                    if(name.equals("META-INF/MANIFEST.MF"))continue;if(name.startsWith(BOOT_PREFIX)||(name.startsWith("META-INF/mcc-")&&!name.equals("META-INF/mcc-engine.properties"))||name.matches("META-INF/.*\\.(SF|RSA|DSA|EC)"))throw new IOException("Unsupported upstream archive signature or collision");
                    copyEntry(upstream,entry,out);
                }
                try(InputStream input=upstream.getInputStream(upstream.getJarEntry("META-INF/MANIFEST.MF"))){byte[] originalManifest=input.readNBytes(65537);if(originalManifest.length>65536)throw new IOException("Upstream manifest exceeds bound");add(out,"META-INF/mcc-upstream-manifest.mf",originalManifest);}
                int licenses=0;Set<String> notices=new HashSet<>();Enumeration<JarEntry> own=mcc.entries();while(own.hasMoreElements()){
                    JarEntry entry=own.nextElement();if(entry.getName().startsWith(BOOT_PREFIX)&&entry.getName().endsWith(".class")){if(++classes>32||entry.getSize()>262144)throw new IOException("MCC Core bootstrap exceeds bound");copyEntry(mcc,entry,out);}
                    else if(entry.getName().startsWith("META-INF/mcc-core-licenses/")&&!entry.isDirectory()){
                        if(++licenses>12||entry.getSize()>65536)throw new IOException("MCC Core license notice exceeds bound");notices.add(entry.getName());copyEntry(mcc,entry,out);
                    }
                }
                if(classes<2)throw new IOException("MCC Core bootstrap classes are absent");
                if(!notices.containsAll(Set.of("META-INF/mcc-core-licenses/LICENSE.md","META-INF/mcc-core-licenses/THIRD-PARTY.md")))throw new IOException("MCC Core distribution license notices are absent");
                String metadata="protocol=1\nversion="+CORE_VERSION+"\nupstream.sha256="+CoreBootstrap.PAPER_SHA+"\nupstream.version=26.3\nupstream.build=169\nfoundation.sha256="+CoreBootstrap.ENGINE_SHA+"\nengine.patchset="+CoreBootstrap.PATCHSET_SHA+"\nplugin.sha256="+pluginSha+"\n";
                add(out,"META-INF/mcc-core.properties",metadata.getBytes(StandardCharsets.ISO_8859_1));
            }
        }
        PrivateFiles.restrict(output,false);if(Files.size(output)>MAX_ARTIFACT)throw new IOException("MCC Core distribution exceeds budget");validateDistribution(output,pluginSha);
    }
    static void validateDistribution(Path artifact,String pluginSha)throws IOException {
        try(JarFile jar=new JarFile(artifact.toFile())){
            if(jar.getManifest()==null||!"dev.mcc.coreboot.CoreBootstrap".equals(jar.getManifest().getMainAttributes().getValue("Main-Class"))||
                jar.getJarEntry("dev/mcc/coreboot/CoreBootstrap.class")==null||jar.getJarEntry("dev/mcc/coreboot/CoreBridge.class")==null||jar.getJarEntry("META-INF/patches.list")==null||
                jar.getJarEntry("META-INF/mcc-core-licenses/LICENSE.md")==null||jar.getJarEntry("META-INF/mcc-core-licenses/THIRD-PARTY.md")==null)throw new IOException("Invalid MCC Core executable distribution");
            JarEntry entry=jar.getJarEntry("META-INF/mcc-core.properties");if(entry==null||entry.getSize()>4096)throw new IOException("Invalid MCC Core metadata");
            Properties p;try(InputStream in=jar.getInputStream(entry)){p=CoreBootstrap.properties(in);}if(!"1".equals(p.getProperty("protocol"))||!CORE_VERSION.equals(p.getProperty("version"))||!CoreBootstrap.PAPER_SHA.equals(p.getProperty("upstream.sha256"))||
                !CoreBootstrap.ENGINE_SHA.equals(p.getProperty("foundation.sha256"))||!CoreBootstrap.PATCHSET_SHA.equals(p.getProperty("engine.patchset"))||!pluginSha.equals(p.getProperty("plugin.sha256")))throw new IOException("MCC Core artifact identity mismatch");
            CoreBootstrap.engineMetadata(jar);
        }
    }
    /** Configuration target is still Paper even when the enclosing distribution is named MCC Core. */
    static String profileKernel(Path root,String file,String configured)throws IOException {
        filename(file);Path artifact=root.resolve(file);checkPath(root,artifact,false);
        try(JarFile jar=new JarFile(artifact.toFile())){
            Manifest manifest=jar.getManifest();if(manifest!=null&&"dev.mcc.coreboot.CoreBootstrap".equals(manifest.getMainAttributes().getValue("Main-Class"))){
                validateDistribution(artifact,CoreBootstrap.sha(root.resolve("plugins/MinecraftControlCenter.jar")));return "Paper";
            }
        }return configured;
    }
    private static void copyEntry(JarFile jar,JarEntry original,JarOutputStream out)throws IOException {
        if(original.getSize()>MAX_ARTIFACT)throw new IOException("Upstream archive entry exceeds budget");JarEntry copy=new JarEntry(original.getName());copy.setTime(0);out.putNextEntry(copy);
        if(!original.isDirectory())try(InputStream in=jar.getInputStream(original)){transfer(in,out,MAX_ARTIFACT);}out.closeEntry();
    }
    private static void add(JarOutputStream out,String name,byte[] content)throws IOException {JarEntry e=new JarEntry(name);e.setTime(0);out.putNextEntry(e);out.write(content);out.closeEntry();}
    private static void transfer(InputStream in,OutputStream out,long max)throws IOException {byte[] buffer=new byte[262144];long total=0;for(int count;(count=in.read(buffer))>=0;){if((total+=count)>max)throw new IOException("Artifact stream exceeds bound");if(count>0)out.write(buffer,0,count);}}
    static Map<String,Object> readPlan(Path data)throws IOException {Path file=data.resolve("selection.json");if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return Map.of();checkPath(data,file,false);byte[] bytes;try(InputStream in=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){bytes=in.readNBytes(16385);}if(bytes.length>16384)throw new IOException("MCC Core selection exceeds budget");try{return Json.object(new String(bytes,StandardCharsets.UTF_8));}catch(RuntimeException e){throw new IOException("Invalid MCC Core selection",e);}}
    private static Map<String,Object> verifyRuntime(Path root,String file)throws Exception {
        filename(file);checkPath(root,root.resolve(file),false);if(!root.resolve(".mcc-launcher").toString().equals(System.getProperty("mcc.launcher.control","")))throw new IOException("An owned MCC launcher is required");
        Path status=root.resolve(".mcc-launcher/status.json");checkPath(root,status,false);Map<String,Object> s=boundedJson(status);
        long parent=ProcessHandle.current().parent().map(ProcessHandle::pid).orElse(-1L);
        if(!LauncherStatus.supported(s)||!System.getProperty("mcc.launcher.run-id","").equals(s.get("runId"))||
            !LauncherStatus.pidEquals(s.get("javaPid"),ProcessHandle.current().pid())||!LauncherStatus.pidEquals(s.get("daemonPid"),parent)||
            !ProcessHandle.of(parent).map(ProcessHandle::isAlive).orElse(false)||!"running".equals(s.get("state"))||!Boolean.TRUE.equals(s.get("paperReady"))||!Boolean.TRUE.equals(s.get("dashboardReady")))throw new IOException("MCC launcher run identity is unavailable");
        if(!"26.3".equals(s.get("minecraftVersion"))||!Set.of("paper","purpur","mcccore").contains(String.valueOf(s.get("kernelName")).toLowerCase(Locale.ROOT)))throw new IOException("MCC Core currently supports Paper/Purpur 26.3 only");
        return s;
    }
    /** Availability checks metadata only; 56 MB of engine bytes are read exclusively after an explicit Owner choice. */
    static void foundationResourceAvailable(Path plugin)throws IOException {
        try(JarFile jar=new JarFile(plugin.toFile())){
            JarEntry resource=jar.getJarEntry(CoreBootstrap.ENGINE_RESOURCE);
            if(resource==null||resource.isDirectory()||resource.getSize()!=CoreBootstrap.ENGINE_BYTES)throw new IOException("Bundled MCC Core engine is absent or has an unexpected size");
        }
    }
    static void verifyFoundation(Path foundation)throws IOException {
        if(Files.size(foundation)!=CoreBootstrap.ENGINE_BYTES||!CoreBootstrap.sha(foundation).equals(CoreBootstrap.ENGINE_SHA))throw new IOException("MCC Core engine failed pinned size/SHA-256 verification");
        try(JarFile jar=new JarFile(foundation.toFile())){CoreBootstrap.engineMetadata(jar);}
    }
    static void extractFoundation(Path root,Path plugin,Path output,BooleanSupplier authority)throws Exception {
        permit(authority);checkPath(root,plugin,false);checkPath(root,output.getParent(),true);foundationResourceAvailable(plugin);
        Path temporary=output.resolveSibling(".engine-extract-"+UUID.randomUUID()+".tmp");
        try {
            Files.createFile(temporary);PrivateFiles.restrict(temporary,false);
            try(JarFile jar=new JarFile(plugin.toFile());InputStream in=jar.getInputStream(jar.getJarEntry(CoreBootstrap.ENGINE_RESOURCE));OutputStream out=Files.newOutputStream(temporary,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
                byte[] buffer=new byte[262144];long total=0;
                for(int count;(count=in.read(buffer))>=0;){permit(authority);if((total+=count)>CoreBootstrap.ENGINE_BYTES)throw new IOException("Bundled MCC Core engine exceeds its size budget");if(count>0)out.write(buffer,0,count);}
                if(total!=CoreBootstrap.ENGINE_BYTES)throw new IOException("Bundled MCC Core engine is incomplete");
            }
            verifyFoundation(temporary);permit(authority);checkPath(root,output.getParent(),true);
            if(Files.exists(output,LinkOption.NOFOLLOW_LINKS))throw new IOException("MCC Core engine cache changed during extraction");
            try(FileChannel c=FileChannel.open(temporary,StandardOpenOption.WRITE)){c.force(true);}Files.move(temporary,output,StandardCopyOption.ATOMIC_MOVE);
        }finally{Files.deleteIfExists(temporary);}
    }
    static void verifyOffline(Path root)throws Exception {
        Path status=root.resolve(".mcc-launcher/status.json");checkPath(root,status,false);Map<String,Object> s=boundedJson(status);long parent=ProcessHandle.current().parent().map(ProcessHandle::pid).orElse(-1L);
        if(!LauncherStatus.supported(s)||!"prelaunch".equals(s.get("state"))||!"running".equals(s.get("profileHook"))||
            s.get("javaPid")!=null||!LauncherStatus.pidEquals(s.get("daemonPid"),parent)||!ProcessHandle.of(parent).map(ProcessHandle::isAlive).orElse(false))throw new IOException("MCC Core installation requires the owned supervisor's offline prelaunch lease");
        Path daemonLock=root.resolve(".mcc-launcher/daemon.lock");checkPath(root,daemonLock,false);
        try(FileChannel c=FileChannel.open(daemonLock,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);FileLock guard=c.tryLock(0,1,false)){if(guard!=null)throw new IOException("The supervisor's retained daemon lease is absent");}
    }
    private static Map<String,Object> boundedJson(Path file)throws IOException {try(InputStream in=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){byte[] b=in.readNBytes(16385);if(b.length>16384)throw new IOException("Launcher metadata exceeds bound");return Json.object(new String(b,StandardCharsets.UTF_8));}}
    private static void filename(String file){if(file==null||!file.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,120}\\.jar"))throw new IllegalArgumentException("Safe MCC launcher server JAR filename is required");}
    private static String canonicalId(Object value)throws IOException {try{String s=String.valueOf(value);if(!UUID.fromString(s).toString().equals(s))throw new IllegalArgumentException();return s;}catch(IllegalArgumentException e){throw new IOException("Invalid MCC Core plan id",e);}}
    private static String shaValue(Object value)throws IOException {if(!(value instanceof String s)||!s.matches("[a-f0-9]{64}"))throw new IOException("Invalid MCC Core plan SHA");return s;}
    private static void permit(BooleanSupplier authority){if(!authority.getAsBoolean())throw new SecurityException("MCC Core Owner authority changed");}
    static void checkPath(Path root,Path path,boolean directory)throws IOException {
        Path base=root.toAbsolutePath().normalize(),p=path.toAbsolutePath().normalize();if(!p.startsWith(base))throw new IOException("MCC Core path escaped server scope");
        Path walk=base;for(Path part:base.relativize(p)){walk=walk.resolve(part);if(Files.isSymbolicLink(walk))throw new IOException("MCC Core symbolic links are forbidden");}
        if(Files.exists(p,LinkOption.NOFOLLOW_LINKS)&&(!p.toRealPath().equals(p)||(directory?!Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS):!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))))throw new IOException("Unsafe MCC Core file type");
        if(!directory&&!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))throw new IOException("Required MCC Core artifact is absent");
    }
    static Path privateDirectory(Path root,Path directory)throws IOException {checkPath(root,directory,true);Files.createDirectories(directory);PrivateFiles.restrict(directory,true);return directory;}
    static void copyNew(Path root,Path source,Path target)throws IOException {checkPath(root,source,false);checkPath(root,target.getParent(),true);if(Files.exists(target,LinkOption.NOFOLLOW_LINKS))throw new IOException("MCC Core copy target already exists");try(InputStream in=Files.newInputStream(source,LinkOption.NOFOLLOW_LINKS);OutputStream out=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){transfer(in,out,MAX_ARTIFACT);}PrivateFiles.restrict(target,false);try(FileChannel channel=FileChannel.open(target,StandardOpenOption.WRITE)){channel.force(true);}}
    static void atomic(Path root,Path target,byte[] bytes)throws IOException {checkPath(root,target.getParent(),true);if(Files.isSymbolicLink(target))throw new IOException("Unsafe MCC Core metadata target");Path temporary=target.resolveSibling("."+target.getFileName()+"."+UUID.randomUUID()+".tmp");try{Files.write(temporary,bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);PrivateFiles.restrict(temporary,false);try(FileChannel c=FileChannel.open(temporary,StandardOpenOption.WRITE)){c.force(true);}Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temporary);}}
    /** Reproducible packaging of local verified inputs only; does not replace a server, change a world or start a JVM. */
    public static void main(String[] args){
        try {
            Map<String,String> values=new LinkedHashMap<>();for(int i=0;i<args.length;i++){String key=args[i];if(!Set.of("--server-root","--upstream-jar","--output").contains(key)||i+1>=args.length||values.putIfAbsent(key,args[++i])!=null)throw new IllegalArgumentException("Invalid Core packaging arguments");}
            if(values.size()!=3)throw new IllegalArgumentException("Core packaging requires server-root, upstream-jar and output");
            Path root=Path.of(values.get("--server-root")).toRealPath(),base=root.resolve(values.get("--upstream-jar")).normalize(),output=root.resolve(values.get("--output")).normalize(),plugin=root.resolve("plugins/MinecraftControlCenter.jar");
            checkPath(root,base,false);checkPath(root,output.getParent(),true);if(!output.startsWith(root)||Files.exists(output,LinkOption.NOFOLLOW_LINKS)||!output.getFileName().toString().endsWith(".jar"))throw new IOException("Invalid new Core output path");
            verifyFoundation(base);String pluginSha=CoreBootstrap.sha(plugin);CoreBootstrap.requiredPlugin(root,pluginSha);
            buildDistribution(root,base,plugin,output,pluginSha);
            System.out.println(Json.write(Map.of("core","MCC Core","version",CORE_VERSION,"jarSha256",CoreBootstrap.sha(output),"pluginSha256",pluginSha,"upstreamSha256",CoreBootstrap.PAPER_SHA,"foundationSha256",CoreBootstrap.ENGINE_SHA,"bytes",Files.size(output),"serverStarted",false,"worldDataChanged",false)));
        }catch(Exception error){System.err.println("MCC Core packaging refused: invalid source identity, path, or archive.");System.exit(2);}
    }
}
