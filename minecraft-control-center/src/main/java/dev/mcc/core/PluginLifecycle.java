package dev.mcc.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Clock;
import java.util.*;
import java.util.function.*;

/** Inert removal requests. Only the offline prelaunch hook moves a JAR out of plugins/. */
public final class PluginLifecycle {
    private static final long JAR_LIMIT=64L*1024*1024,TTL=300_000;
    private static final int MAX_JARS=512,MAX_OPERATIONS=512;
    private final Path plugins,data,quarantine;
    private final Supplier<List<Map<String,Object>>> runtime;
    private final BooleanSupplier hook;
    private final Clock clock;
    private final Map<String,Plan> plans=new LinkedHashMap<>();
    private record Jar(Path path,PluginJar.Metadata metadata){}
    private record Plan(String id,String actor,String name,String file,String sha256,String inventory,long expires){}
    public static final class Conflict extends IllegalArgumentException {public Conflict(String message){super(message);}}

    public PluginLifecycle(Path data,Supplier<List<Map<String,Object>>> runtime,BooleanSupplier hook)throws IOException {this(data,runtime,hook,Clock.systemUTC());}
    PluginLifecycle(Path directory,Supplier<List<Map<String,Object>>> runtime,BooleanSupplier hook,Clock clock)throws IOException {
        this.data=directory.toAbsolutePath().normalize();this.plugins=this.data.getParent();this.runtime=runtime;this.hook=hook;this.clock=clock;
        ordinary(this.plugins,true);ordinary(this.data,true);quarantine=this.data.resolve("plugin-quarantine");
        if(!Files.exists(quarantine,LinkOption.NOFOLLOW_LINKS)){Files.createDirectory(quarantine);PrivateFiles.restrict(quarantine,true);}ordinary(quarantine,true);checkFolders();
    }
    public synchronized Map<String,Object> list()throws Exception {
        List<Jar> jars=inventory();List<Map<String,Object>> active=runtime.get();List<Map<String,Object>> operations=operations();List<Map<String,Object>> result=new ArrayList<>();
        for(Jar jar:jars){Map<String,Object> row=new LinkedHashMap<>(row(jar));List<String> blockers=dependents(jar,jars,active);Map<String,Object> pending=operations.stream().filter(o->o.get("file").equals(jar.path.getFileName().toString())&&o.get("status").equals("pending-removal")).findFirst().orElse(null);
            boolean protectedPlugin=protectedPlugin(jar.metadata);row.put("loaded",active.stream().anyMatch(p->same(jar.metadata.name(),p.get("name"))&&Boolean.TRUE.equals(p.get("enabled"))));row.put("blockedBy",blockers);row.put("protected",protectedPlugin);row.put("canRemove",hook.getAsBoolean()&&!protectedPlugin&&blockers.isEmpty()&&pending==null);row.put("status",pending==null?"present":"pending-removal");if(pending!=null)row.put("removalId",pending.get("id"));result.add(row);}
        return Map.of("prelaunchSupported",hook.getAsBoolean(),"plugins",result,"removals",operations.stream().map(this::publicOperation).toList(),"dataDirectoriesPreserved",true);
    }
    public synchronized Map<String,Object> plan(String name,String actor)throws Exception {
        if(!hook.getAsBoolean())throw new IllegalStateException("Safe removal requires the offline MCC prelaunch hook; no live unloading is supported");actor(actor);name(name);
        List<Jar> jars=inventory();Jar jar=select(jars,name);file(jar.path.getFileName().toString());guardRemoval(jar,jars,runtime.get());
        if(operations().stream().anyMatch(o->o.get("status").equals("pending-removal")&&o.get("file").equals(jar.path.getFileName().toString())))throw new Conflict("Removal is already queued");
        plans.values().removeIf(p->p.expires<clock.millis());if(plans.size()>=64)throw new IllegalStateException("Too many removal plans");
        Plan plan=new Plan(UUID.randomUUID().toString(),actor,jar.metadata.name(),jar.path.getFileName().toString(),sha(jar.path),fingerprint(jars,runtime.get()),clock.millis()+TTL);plans.put(plan.id,plan);
        Map<String,Object> out=new LinkedHashMap<>(row(jar));out.put("planId",plan.id);out.put("expiresAt",plan.expires);out.put("sha256",plan.sha256);out.put("requiresRestart",true);out.put("dataDirectoriesPreserved",true);return out;
    }
    public synchronized Map<String,Object> remove(String id,String actor,BooleanSupplier authority)throws Exception {
        authorized(authority);actor(actor);UUID.fromString(id);Plan plan=plans.get(id);if(plan==null||plan.expires<clock.millis())throw new Conflict("Removal plan expired; review it again");if(!plan.actor.equals(actor))throw new SecurityException("Removal plan belongs to another session");
        List<Jar> jars=inventory();Jar jar=select(jars,plan.name);guardRemoval(jar,jars,runtime.get());if(!jar.path.getFileName().toString().equals(plan.file)||!sha(jar.path).equals(plan.sha256)||!fingerprint(jars,runtime.get()).equals(plan.inventory))throw new Conflict("Plugin files or dependencies changed; review a new removal plan");
        plans.remove(id);Map<String,Object> operation=new LinkedHashMap<>();operation.put("schema",1);operation.put("id",UUID.randomUUID().toString());operation.put("name",plan.name);operation.put("version",jar.metadata.version());operation.put("main",jar.metadata.main());operation.put("file",plan.file);operation.put("sha256",plan.sha256);operation.put("size",Files.size(jar.path));operation.put("createdAt",clock.millis());operation.put("status","pending-removal");
        authorized(authority);if(operations().size()>=MAX_OPERATIONS)throw new IllegalStateException("Plugin quarantine is full; archive it offline before another removal");Path folder=quarantine.resolve(operation.get("id").toString());Files.createDirectory(folder);PrivateFiles.restrict(folder,true);
        try{authorized(authority);write(folder,operation);authorized(authority);}catch(Exception error){Files.deleteIfExists(folder.resolve("operation.json"));Files.deleteIfExists(folder);throw error;}
        return publicOperation(operation);
    }
    public synchronized Map<String,Object> restore(String id,String actor,BooleanSupplier authority)throws Exception {
        actor(actor);authorized(authority);Map<String,Object> operation=operation(id);String status=operation.get("status").toString();Path folder=folder(id);
        if(status.equals("pending-removal")){checkFolders();authorized(authority);operation.put("status","cancelled");write(folder,operation);return publicOperation(operation);}
        if(!status.equals("removed"))throw new Conflict("This removal cannot be restored");Path retained=folder.resolve("plugin.jar");ordinary(retained,false);if(!sha(retained).equals(operation.get("sha256")))throw new Conflict("Quarantine bytes changed; restoration refused");PluginJar.Metadata metadata=PluginJar.inspectExisting(retained);if(protectedPlugin(metadata)||!metadata.name().equals(operation.get("name"))||!metadata.main().equals(operation.get("main")))throw new IOException("Quarantine identity mismatch");
        List<Jar> jars=inventory();Set<String> names=identities(jars);for(String identity:identities(metadata))if(names.contains(identity)||runtime.get().stream().anyMatch(p->same(identity,p.get("name"))))throw new Conflict("Plugin identity already exists; no JAR will be overwritten");for(String required:metadata.required())if(!names.contains(required.toLowerCase(Locale.ROOT)))throw new Conflict("Required dependency is unavailable: "+required);
        Path target=plugins.resolve(file(operation.get("file")));if(Files.exists(target,LinkOption.NOFOLLOW_LINKS))throw new Conflict("Original plugin filename is occupied");authorized(authority);
        // Publish an independent copy: modifying a restored JAR must never mutate its retained backup.
        Path temporary=folder.resolve("restore-"+UUID.randomUUID()+".tmp");
        try{Files.copy(retained,temporary);PrivateFiles.restrict(temporary,false);if(!sha(temporary).equals(operation.get("sha256")))throw new IOException("Restoration copy mismatch");authorized(authority);Files.createLink(target,temporary);}finally{Files.deleteIfExists(temporary);}
        try{authorized(authority);operation.put("status","restored-pending-restart");write(folder,operation);}catch(Exception error){if(Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(target)&&sha(target).equals(operation.get("sha256")))Files.delete(target);throw error;}
        return publicOperation(operation);
    }
    public synchronized Map<String,Object> cancelPending(String id,BooleanSupplier authority)throws Exception {
        authorized(authority);checkFolders();Map<String,Object> operation=operation(id);if(!operation.get("status").equals("pending-removal"))throw new Conflict("Only a pending removal can be cancelled");operation.put("status","cancelled");authorized(authority);write(folder(id),operation);return publicOperation(operation);
    }
    /** Caller must hold the supervisor's external offline lease and invoke this BEFORE the core starts. */
    public static Map<String,Object> applyPendingOffline(Path serverRoot,Path pluginData)throws Exception {
        Path root=serverRoot.toAbsolutePath().normalize(),data=pluginData.toAbsolutePath().normalize();ordinary(root,true);ordinary(root.resolve("plugins"),true);if(!data.getParent().equals(root.resolve("plugins")))throw new IOException("Plugin data is outside server plugins directory");
        if(!Files.exists(data,LinkOption.NOFOLLOW_LINKS))return Map.of("removed",0,"pending",0);
        PluginLifecycle lifecycle=new PluginLifecycle(data,List::of,()->true);int removed=0;
        for(Map<String,Object> operation:lifecycle.operations())if(operation.get("status").equals("pending-removal")){
            Path folder=lifecycle.folder(operation.get("id").toString()),source=lifecycle.plugins.resolve(file(operation.get("file"))),retained=folder.resolve("plugin.jar");
            // Recover a process interruption AFTER atomic move but BEFORE publishing its metadata.
            if(!Files.exists(source,LinkOption.NOFOLLOW_LINKS)&&Files.exists(retained,LinkOption.NOFOLLOW_LINKS)){ordinary(retained,false);PluginJar.Metadata metadata=PluginJar.inspectExisting(retained);if(protectedPlugin(metadata)||!metadata.name().equals(operation.get("name"))||!metadata.main().equals(operation.get("main"))||!sha(retained).equals(operation.get("sha256")))throw new IOException("Interrupted removal backup mismatch");List<Jar> remaining=new ArrayList<>(lifecycle.inventory());Jar removedJar=new Jar(source,metadata);remaining.add(removedJar);guardRemoval(removedJar,remaining,List.of());operation.put("status","removed");operation.put("removedAt",System.currentTimeMillis());write(folder,operation);removed++;continue;}
            List<Jar> jars=lifecycle.inventory();Jar jar=select(jars,operation.get("name").toString());guardRemoval(jar,jars,List.of());if(!jar.path.equals(source)||!sha(source).equals(operation.get("sha256"))||!jar.metadata.main().equals(operation.get("main")))throw new Conflict("Queued removal changed; restore/cancel it before starting the server");if(Files.exists(retained,LinkOption.NOFOLLOW_LINKS))throw new IOException("Removal quarantine destination is occupied");
            Files.move(source,retained,StandardCopyOption.ATOMIC_MOVE);PrivateFiles.restrict(retained,false);operation.put("status","removed");operation.put("removedAt",System.currentTimeMillis());write(folder,operation);removed++;
        }
        return Map.of("removed",removed,"pending",0,"liveUnloading",false,"dataDirectoriesPreserved",true);
    }
    private List<Jar> inventory()throws Exception {checkFolders();List<Jar> jars=new ArrayList<>();try(DirectoryStream<Path> files=Files.newDirectoryStream(plugins)){for(Path path:files){if(!path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))continue;if(jars.size()>=MAX_JARS)throw new IOException("Plugin inventory exceeds its bound");ordinary(path,false);if(Files.size(path)>JAR_LIMIT)throw new IOException("Plugin JAR exceeds64 MiB");jars.add(new Jar(path,PluginJar.inspectExisting(path)));}}return jars;}
    private List<Map<String,Object>> operations()throws IOException {checkFolders();List<Map<String,Object>> out=new ArrayList<>();try(DirectoryStream<Path> files=Files.newDirectoryStream(quarantine)){for(Path path:files){if(out.size()>=MAX_OPERATIONS)throw new IOException("Plugin quarantine exceeds its bound");ordinary(path,true);UUID.fromString(path.getFileName().toString());out.add(operation(path.getFileName().toString()));}}return out;}
    private Map<String,Object> operation(String id)throws IOException {Path path=folder(id).resolve("operation.json");ordinary(path,false);if(Files.size(path)>32768)throw new IOException("Quarantine metadata exceeds its bound");Map<String,Object> value=Json.object(Files.readString(path,StandardCharsets.UTF_8));if(!Objects.equals(value.get("schema"),1)||!id.equals(value.get("id"))||!Objects.toString(value.get("sha256"),"").matches("[0-9a-f]{64}")||!Set.of("pending-removal","removed","cancelled","restored-pending-restart").contains(value.get("status")))throw new IOException("Invalid quarantine metadata");file(value.get("file"));name(Objects.toString(value.get("name"),""));return value;}
    private Path folder(String id)throws IOException {UUID.fromString(id);Path result=quarantine.resolve(id);ordinary(result,true);return result;}
    private static void write(Path folder,Map<String,Object> operation)throws IOException {Path temporary=folder.resolve("operation-"+UUID.randomUUID()+".tmp");try{Files.writeString(temporary,Json.write(operation),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);PrivateFiles.restrict(temporary,false);Files.move(temporary,folder.resolve("operation.json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temporary);}}
    private Map<String,Object> publicOperation(Map<String,Object> operation){Map<String,Object> result=new LinkedHashMap<>(operation);result.remove("main");result.put("removalId",result.remove("id"));boolean queued=operation.get("status").equals("pending-removal"),restored=operation.get("status").equals("restored-pending-restart");if(restored&&runtime.get().stream().anyMatch(p->same(operation.get("name").toString(),p.get("name"))&&operation.get("version").equals(p.get("version")))){result.put("status","restored");restored=false;}result.put("requiresRestart",queued||restored);result.put("canRestore",queued||operation.get("status").equals("removed"));result.put("dataDirectoriesPreserved",true);return result;}
    private static Map<String,Object> row(Jar jar)throws IOException {return Map.of("name",jar.metadata.name(),"version",jar.metadata.version(),"file",jar.path.getFileName().toString(),"size",Files.size(jar.path),"provides",jar.metadata.provides(),"required",jar.metadata.required());}
    private static Jar select(List<Jar> jars,String name){List<Jar> matches=jars.stream().filter(j->j.metadata.name().equalsIgnoreCase(name)).toList();if(matches.size()!=1)throw new Conflict("Plugin JAR is missing or its identity is ambiguous");return matches.getFirst();}
    private static void guardRemoval(Jar jar,List<Jar> jars,List<Map<String,Object>> runtime){if(protectedPlugin(jar.metadata))throw new SecurityException("MinecraftControlCenter cannot remove itself");List<String> dependents=dependents(jar,jars,runtime);if(!dependents.isEmpty())throw new Conflict("Required by installed plugins: "+String.join(", ",dependents));}
    private static List<String> dependents(Jar selected,List<Jar> jars,List<Map<String,Object>> runtime){Set<String> removed=identities(selected.metadata),remaining=new HashSet<>();for(Jar jar:jars)if(jar!=selected)remaining.addAll(identities(jar.metadata));Set<String> unavailable=new HashSet<>(removed);unavailable.removeAll(remaining);Set<String> dependents=new TreeSet<>(String.CASE_INSENSITIVE_ORDER);for(Jar jar:jars)if(jar!=selected&&jar.metadata.required().stream().anyMatch(d->unavailable.contains(d.toLowerCase(Locale.ROOT))))dependents.add(jar.metadata.name());for(Map<String,Object> plugin:runtime)if(!same(selected.metadata.name(),plugin.get("name"))&&plugin.get("required") instanceof List<?> required&&required.stream().anyMatch(d->unavailable.contains(d.toString().toLowerCase(Locale.ROOT))))dependents.add(plugin.get("name").toString());return List.copyOf(dependents);}
    private static String fingerprint(List<Jar> jars,List<Map<String,Object>> runtime)throws Exception {List<String> rows=new ArrayList<>();for(Jar jar:jars)rows.add(jar.path.getFileName()+":"+jar.metadata.name()+":"+jar.metadata.version()+":"+jar.metadata.required()+":"+jar.metadata.provides());for(Map<String,Object> plugin:runtime)rows.add("runtime:"+plugin.get("name")+":"+plugin.get("required"));Collections.sort(rows);return digest(String.join("\n",rows).getBytes(StandardCharsets.UTF_8));}
    private static boolean protectedPlugin(PluginJar.Metadata metadata){return metadata.main().startsWith("dev.mcc.")||identities(metadata).stream().anyMatch(n->n.equals("mcc")||n.equals("minecraftcontrolcenter"));}
    private static Set<String> identities(List<Jar> jars){Set<String> names=new HashSet<>();for(Jar jar:jars)names.addAll(identities(jar.metadata));return names;}
    private static Set<String> identities(PluginJar.Metadata metadata){Set<String> names=new HashSet<>();names.add(metadata.name().toLowerCase(Locale.ROOT));for(String name:metadata.provides())names.add(name.toLowerCase(Locale.ROOT));return names;}
    private static boolean same(String left,Object right){return left.equalsIgnoreCase(Objects.toString(right,""));}
    private void checkFolders()throws IOException {ordinary(plugins,true);ordinary(data,true);ordinary(quarantine,true);if(!plugins.toRealPath().equals(plugins)||!data.toRealPath().equals(data)||!quarantine.toRealPath().equals(quarantine))throw new IOException("Plugin folders resolve outside their expected paths");}
    private static void ordinary(Path path,boolean directory)throws IOException {if(Files.isSymbolicLink(path)||(directory?!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS):!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)))throw new IOException("Unsafe plugin path");}
    private static String file(Object value){String name=Objects.toString(value,"");if(!name.matches("[A-Za-z0-9_. -]{1,180}\\.[jJ][aA][rR]")||name.contains(".."))throw new IllegalArgumentException("Unsafe plugin filename");return name;}
    private static void name(String value){if(!value.matches("[A-Za-z0-9_.-]{1,128}"))throw new IllegalArgumentException("Invalid plugin name");}
    private static void actor(String actor){if(actor==null||actor.isBlank()||actor.length()>128)throw new IllegalArgumentException("Active actor is required");}
    private static void authorized(BooleanSupplier authority){if(Thread.currentThread().isInterrupted()||!authority.getAsBoolean())throw new SecurityException("Owner session is no longer active; plugin operation cancelled");}
    private static String sha(Path path)throws Exception {ordinary(path,false);MessageDigest digest=MessageDigest.getInstance("SHA-256");long bytes=0;try(InputStream in=Files.newInputStream(path)){byte[] buffer=new byte[32768];int read;while((read=in.read(buffer))!=-1){if((bytes+=read)>JAR_LIMIT)throw new IOException("Plugin JAR exceeds its bound");digest.update(buffer,0,read);}}return HexFormat.of().formatHex(digest.digest());}
    private static String digest(byte[] bytes)throws NoSuchAlgorithmException{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
}
