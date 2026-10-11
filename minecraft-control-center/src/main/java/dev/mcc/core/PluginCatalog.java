package dev.mcc.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.function.BooleanSupplier;

/** Live Modrinth registry. Installation only publishes new, verified JARs for the next startup. */
public final class PluginCatalog implements AutoCloseable {
    @FunctionalInterface public interface Transport {byte[] get(URI uri,int maxBytes)throws Exception;}
    private static final Set<String> LOADERS=Set.of("paper","bukkit","spigot");
    private static final int JSON_LIMIT=8*1024*1024,JAR_LIMIT=64*1024*1024,MAX_PLAN_FILES=8;
    private static final long PLAN_TTL=5*60_000L,CACHE_TTL=5*60_000L,STALE_TTL=30*60_000L;
    private final Database db;private final Path data,plugins,transactions;private final String minecraft;
    private final Supplier<List<Map<String,Object>>> installed;private final Transport transport;private final Clock clock;private final PluginLifecycle lifecycle;
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(24),r->{Thread thread=new Thread(r,"MCC-PluginCatalog");thread.setDaemon(true);return thread;});
    private final ThreadPoolExecutor metadataWorker=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(48),r->{Thread thread=new Thread(r,"MCC-CatalogMetadata");thread.setDaemon(true);return thread;});
    private final Set<CompletableFuture<?>> pending=ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor(r->{Thread thread=new Thread(r,"MCC-CatalogDeadlines");thread.setDaemon(true);return thread;});
    private volatile boolean closed;
    private final Map<String,Cached> cache=new LinkedHashMap<>();private final Map<String,Plan> plans=new LinkedHashMap<>();private long cacheBytes;
    private record Cached(JsonNode value,long time,int bytes) {}
    private record Response(JsonNode value,boolean stale) {}
    private record FileSpec(String projectId,String versionId,String title,String version,String filename,long size,String sha512,URI uri,boolean dependency,List<JsonNode> dependencies) {
        Map<String,Object> json(){return Map.of("projectId",projectId,"versionId",versionId,"title",title,"version",version,"filename",filename,"size",size,"dependency",dependency,"sha512",sha512);}
    }
    private record Plan(String id,String actor,String projectId,String title,long expires,List<FileSpec> files,List<String> warnings) {}
    private record Download(FileSpec spec,Path path,PluginJar.Metadata metadata,String sha256) {}
    private record Featured(String id,String title,String url,String description) {}
    /** Curated upstream project IDs and verified descriptor names, never fuzzy title/slug guesses. */
    private static final Map<String,Set<String>> MANUAL_IDENTITIES=Map.of("Vebnzrzj",Set.of("luckperms"),"P1OZGk5p",Set.of("viaversion"),"fALzjamp",Set.of("chunky"));
    private record PresentPlugin(String name,String version,List<String> provides,Boolean enabled){boolean matches(String identity){return name.equalsIgnoreCase(identity)||provides.stream().anyMatch(p->p.equalsIgnoreCase(identity));}}
    private static final List<Featured> FEATURED=List.of(
        new Featured("Vebnzrzj","LuckPerms","https://luckperms.net/","Permissions and groups"),
        new Featured("hXiIvTyT","EssentialsX","https://essentialsx.net/","Commands, homes and economy"),
        new Featured("1u6JkXh5","WorldEdit","https://worldedit.enginehub.org/","World editing tools"),
        new Featured("DKY9btbd","WorldGuard","https://worldguard.enginehub.org/","Region protection"),
        new Featured("","Vault","https://github.com/MilkBowl/Vault","Economy and permissions bridge"),
        new Featured("lKEzGugV","PlaceholderAPI","https://wiki.placeholderapi.com/","Placeholders for other plugins"),
        new Featured("P1OZGk5p","ViaVersion","https://viaversion.com/","Cross-version connections"),
        new Featured("","ProtocolLib","https://github.com/dmulloy2/ProtocolLib","Network packet API"),
        new Featured("Lu3KuzdV","CoreProtect","https://coreprotect.net/","Block history and rollback"),
        new Featured("3wmN97b8","Multiverse-Core","https://mvplugins.org/","Multiple worlds"),
        new Featured("O4o4mKaq","GriefPrevention","https://www.spigotmc.org/resources/griefprevention.1884/","Player land claims"),
        new Featured("fALzjamp","Chunky","https://modrinth.com/plugin/chunky","Chunk generation tools"),
        new Featured("l6YH9Als","spark","https://spark.lucko.me/","Performance profiler; Paper may already include it"),
        new Featured("NpvuJQoq","ViaBackwards","https://viaversion.com/","Older clients support"),
        new Featured("TsLS8Py5","SkinsRestorer","https://skinsrestorer.net/","Player skins"),
        new Featured("gG7VFbG0","TAB","https://github.com/NEZNAMY/TAB","Player list and nametags"),
        new Featured("HYKaKraK","PacketEvents","https://github.com/retrooper/packetevents","Packet processing API"),
        new Featured("9eGKb6K1","Simple Voice Chat","https://modrinth.com/plugin/simple-voice-chat","Proximity voice chat"),
        new Featured("1bZhdhsH","Plasmo Voice","https://modrinth.com/plugin/plasmo-voice","Voice chat and addons"),
        new Featured("OhduvhIc","VeinMiner","https://modrinth.com/plugin/veinminer","Connected block mining")
    );
    public PluginCatalog(Database db,Path dataDirectory,String minecraftVersion,Supplier<List<Map<String,Object>>> installedSnapshot)throws IOException {
        this(db,dataDirectory,minecraftVersion,installedSnapshot,new CatalogHttp(),Clock.systemUTC());
    }
    PluginCatalog(Database db,Path dataDirectory,String minecraftVersion,Supplier<List<Map<String,Object>>> installedSnapshot,Transport transport,Clock clock)throws IOException {
        if(!minecraftVersion.matches("[0-9]+(\\.[0-9]+){1,2}"))throw new IllegalArgumentException("Exact Minecraft version is required");
        this.db=db;this.minecraft=minecraftVersion;this.installed=installedSnapshot;this.transport=transport;this.clock=clock;
        data=dataDirectory.toAbsolutePath().normalize();if(Files.isSymbolicLink(data)||Files.isSymbolicLink(data.getParent()))throw new IOException("Catalog folders cannot be symbolic links");
        plugins=data.getParent().toRealPath();if(!Files.isDirectory(plugins))throw new IOException("Plugins directory is missing");
        transactions=data.resolve("catalog-transactions");if(Files.isSymbolicLink(transactions)||(Files.exists(transactions,LinkOption.NOFOLLOW_LINKS)&&!Files.isDirectory(transactions,LinkOption.NOFOLLOW_LINKS)))throw new IOException("Catalog transaction path is unsafe");Files.createDirectories(transactions);
        if(!transactions.toRealPath().equals(data.toRealPath().resolve("catalog-transactions")))throw new IOException("Catalog transaction directory resolves outside MCC data");PrivateFiles.restrict(transactions,true);
        lifecycle=new PluginLifecycle(data,installed,()->Boolean.getBoolean("mcc.launcher.profile-hook"),clock);
    }
    public CompletableFuture<Map<String,Object>> search(String query,String sort,int offset,int limit,boolean compatible,boolean featured) {
        return submit(()->searchNow(query,sort,offset,limit,compatible,featured),17);
    }
    public CompletableFuture<Map<String,Object>> plan(String projectId,String actor){return submit(()->planNow(projectId,actor),15);}
    public CompletableFuture<Map<String,Object>> install(String planId,String actor){return install(planId,actor,()->true);}
    public CompletableFuture<Map<String,Object>> install(String planId,String actor,BooleanSupplier authority){Objects.requireNonNull(authority);return submit(()->installNow(planId,actor,authority));}
    public CompletableFuture<Map<String,Object>> managedPlugins(){return submit(()->{synchronized(this){return lifecycle.list();}});}
    public CompletableFuture<Map<String,Object>> removalPlan(String name,String actor){return submit(()->{synchronized(this){return lifecycle.plan(name,actor);}});}
    public CompletableFuture<Map<String,Object>> remove(String planId,String actor,BooleanSupplier authority){Objects.requireNonNull(authority);return submit(()->{synchronized(this){return lifecycle.remove(planId,actor,()->!closed&&authority.getAsBoolean());}});}
    public CompletableFuture<Map<String,Object>> restore(String removalId,String actor,BooleanSupplier authority){Objects.requireNonNull(authority);return submit(()->{synchronized(this){return lifecycle.restore(removalId,actor,()->!closed&&authority.getAsBoolean());}});}
    public CompletableFuture<List<Map<String,Object>>> installedCatalog(){return submit(()->{
        List<Map<String,Object>> active=installed.get();List<Map<String,Object>> result=new ArrayList<>();
        for(Map<String,Object> record:db.documents("catalog-installs").get()){Map<String,Object> row=new LinkedHashMap<>(record);String name=Objects.toString(record.get("name"),"");String version=Objects.toString(record.get("version"),"");Optional<Map<String,Object>> match=active.stream().filter(p->name.equalsIgnoreCase(Objects.toString(p.get("name"),""))&&version.equals(Objects.toString(p.get("version"),""))).findFirst();boolean loaded=match.isPresent()&&Boolean.TRUE.equals(match.get().get("enabled"));boolean present=match.isPresent()||findExistingJar(name.toLowerCase(Locale.ROOT))!=null;row.put("status",loaded?"loaded":match.isPresent()?"disabled":present?"installed-pending-restart":"missing");row.put("requiresRestart",match.isEmpty()&&present);result.add(row);}return result;
    });}
    private Map<String,Object> searchNow(String query,String sort,int offset,int limit,boolean compatible,boolean featured)throws Exception {
        if(query==null||query.length()>120||query.chars().anyMatch(Character::isISOControl)||offset<0||offset>1000000||limit<1||limit>24||!Set.of("downloads","follows","updated","newest","relevance").contains(sort))throw new IllegalArgumentException("Invalid catalog search");
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);List<Map<String,Object>> items=new ArrayList<>();boolean stale=false;int total;
        List<Callable<Map<String,Object>>> jobs=new ArrayList<>();List<Map<String,Object>> fallbacks=new ArrayList<>();
        if(featured) {
            List<Featured> selected=FEATURED.stream().filter(f->(f.title()+" "+f.description()).toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))).toList();total=selected.size();
            for(Featured entry:selected.stream().skip(offset).limit(limit).toList()) {
                Map<String,Object> fallback=unavailable(entry.title(),entry.url(),entry.description());fallback.put("projectId",entry.id());if(!entry.id().isEmpty()){fallback.put("compatibility","unknown");fallback.put("reason","Source is temporarily unavailable; installation is disabled");}
                fallbacks.add(fallback);jobs.add(()->{if(entry.id().isEmpty())return fallback;Response project=json("project/"+entry.id());if(System.nanoTime()>deadline)throw new IOException("Metadata deadline reached");Response versions=versions(entry.id());Map<String,Object> item=projectCard(project.value(),versions.value());item.put("featured",true);item.put("stale",project.stale()||versions.stale());return item;});
            }
        }else {
            List<List<String>> facets=new ArrayList<>();facets.add(List.of("categories:paper","categories:bukkit","categories:spigot"));if(compatible)facets.add(List.of("versions:"+minecraft));
            Response found=json("search?query="+encode(query)+"&index="+sort+"&offset="+offset+"&limit="+limit+"&facets="+encode(Json.write(facets)));stale=found.stale();total=found.value().path("total_hits").asInt();
            for(JsonNode hit:found.value().path("hits")) {
                String id=identifier(hit.path("project_id").asText());
                Map<String,Object> fallback=baseCard(hit);fallback.put("compatibility","unknown");fallback.put("reason","Source is temporarily unavailable; installation is disabled");fallbacks.add(fallback);jobs.add(()->{Response versions=versions(id);Map<String,Object> item=projectCard(hit,versions.value());item.put("stale",found.stale()||versions.stale());return item;});
            }
        }
        List<Future<Map<String,Object>>> futures=new ArrayList<>();for(Callable<Map<String,Object>> job:jobs)try{futures.add(metadataWorker.submit(()->{if(System.nanoTime()>deadline)throw new IOException("Metadata deadline reached");return job.call();}));}catch(RejectedExecutionException error){futures.add(CompletableFuture.failedFuture(error));}
        try{for(int i=0;i<futures.size();i++){Map<String,Object> item;try{long remaining=Math.max(1,deadline-System.nanoTime());item=futures.get(i).get(remaining,TimeUnit.NANOSECONDS);}catch(ExecutionException|TimeoutException error){item=fallbacks.get(i);futures.get(i).cancel(true);}items.add(item);stale|=Boolean.TRUE.equals(item.get("stale"));}}finally{for(Future<?> future:futures)if(!future.isDone())future.cancel(true);}
        annotateInstalled(items);
        Map<String,Object> result=new LinkedHashMap<>();result.put("minecraftVersion",minecraft);result.put("platform","paper");result.put("source","modrinth");result.put("total",total);result.put("offset",offset);result.put("limit",limit);result.put("items",items);result.put("stale",stale);result.put("featured",featured);result.put("compatibilityBasis","publisher-metadata");return result;
    }
    private void annotateInstalled(List<Map<String,Object>> cards)throws Exception {
        Map<String,Map<String,Object>> records=new HashMap<>();for(Map<String,Object> record:db.documents("catalog-installs").get())records.put(record.get("projectId").toString(),record);
        List<PresentPlugin> present=presentPlugins();
        for(Map<String,Object> card:cards){Map<String,Object> record=records.get(card.get("projectId"));Set<String> identities=record==null?MANUAL_IDENTITIES.getOrDefault(card.get("projectId"),Set.of()):Set.of(record.get("name").toString().toLowerCase(Locale.ROOT));Optional<PresentPlugin> match=present.stream().filter(p->identities.stream().anyMatch(p::matches)).findFirst();if(match.isEmpty())continue;PresentPlugin plugin=match.get();card.put("installation",Map.of("name",plugin.name(),"version",plugin.version(),"status",plugin.enabled()==null?"installed-pending-restart":plugin.enabled()?"loaded":"disabled","requiresRestart",plugin.enabled()==null,"source",record==null?"manual-identity":"catalog-record"));}
    }
    private Map<String,Object> unavailable(String title,String url,String description){Map<String,Object> card=new LinkedHashMap<>();card.put("projectId","");card.put("title",title);card.put("description",description);card.put("url",url);card.put("downloads",0);card.put("featured",true);card.put("compatibility","unavailable");card.put("reason","No verified exact-version artifact in this catalog");return card;}
    private Map<String,Object> baseCard(JsonNode project) {
        String id=project.path("project_id").asText(project.path("id").asText());Map<String,Object> card=new LinkedHashMap<>();card.put("projectId",id);card.put("slug",project.path("slug").asText());card.put("title",project.path("title").asText());card.put("description",truncate(project.path("description").asText(),500));card.put("author",project.path("author").asText());card.put("downloads",project.path("downloads").asLong());card.put("follows",project.path("follows").asLong());card.put("url","https://modrinth.com/project/"+identifier(id));card.put("categories",strings(project.path("display_categories").isArray()?project.path("display_categories"):project.path("categories")));return card;
    }
    private Map<String,Object> projectCard(JsonNode project,JsonNode versions) {
        Map<String,Object> card=baseCard(project);Optional<JsonNode> choice=select(versions);
        if(choice.isEmpty()){card.put("compatibility","unavailable");card.put("reason","No stable Paper/Bukkit/Spigot release declares Minecraft "+minecraft);return card;}
        JsonNode version=choice.get();try {FileSpec spec=file(version,card.get("title").toString(),false);card.put("compatibility","metadata-confirmed");card.put("version",Map.of("id",spec.versionId(),"number",spec.version(),"size",spec.size(),"published",version.path("date_published").asText(),"loaders",strings(version.path("loaders")),"type","release"));card.put("reason","Publisher declares this exact server version; JAR checked during installation");}catch(IllegalArgumentException error){card.put("compatibility","unavailable");card.put("reason","Release has no supported verified JAR artifact");}return card;
    }
    private Response versions(String id)throws Exception{return json("project/"+identifier(id)+"/version?loaders="+encode(Json.write(LOADERS))+"&game_versions="+encode(Json.write(List.of(minecraft)))+"&include_changelog=false");}
    private Optional<JsonNode> select(JsonNode versions) {
        List<JsonNode> valid=new ArrayList<>();if(!versions.isArray())throw new IllegalArgumentException("Invalid version response");for(JsonNode version:versions)if(exact(version))valid.add(version);
        return valid.stream().sorted(Comparator.comparing((JsonNode v)->v.path("date_published").asText()).reversed()).filter(v->{try{file(v,"",false);return true;}catch(IllegalArgumentException error){return false;}}).findFirst();
    }
    private boolean exact(JsonNode version){return version.path("version_type").asText().equals("release")&&Set.of("listed","archived").contains(version.path("status").asText())&&strings(version.path("game_versions")).contains(minecraft)&&strings(version.path("loaders")).stream().anyMatch(LOADERS::contains);}
    private FileSpec file(JsonNode version,String title,boolean dependency) {
        if(!exact(version))throw new IllegalArgumentException("Release is not an exact stable Paper-compatible version");String project=identifier(version.path("project_id").asText()),id=identifier(version.path("id").asText());
        List<JsonNode> candidates=new ArrayList<>();for(JsonNode f:version.path("files"))if(f.path("filename").asText().endsWith(".jar")&&(f.path("file_type").isNull()||f.path("file_type").isMissingNode())&&f.path("size").asLong()>0&&f.path("size").asLong()<=JAR_LIMIT)candidates.add(f);
        candidates.sort(Comparator.comparing((JsonNode f)->!f.path("primary").asBoolean()));if(candidates.isEmpty())throw new IllegalArgumentException("No supported JAR");JsonNode f=candidates.get(0);String sha=f.path("hashes").path("sha512").asText().toLowerCase(Locale.ROOT);if(!sha.matches("[0-9a-f]{128}"))throw new IllegalArgumentException("Missing SHA-512");
        URI uri=URI.create(f.path("url").asText());CatalogHttp.validate(uri);if(!uri.getHost().equals("cdn.modrinth.com")||!uri.getRawPath().startsWith("/data/"+project+"/versions/"+id+"/"))throw new IllegalArgumentException("Artifact identity does not match release");
        String filename=f.path("filename").asText();if(filename.length()>180||filename.contains("/")||filename.contains("\\")||filename.contains(".."))throw new IllegalArgumentException("Unsafe artifact name");List<JsonNode> dependencies=new ArrayList<>();for(JsonNode d:version.path("dependencies"))dependencies.add(d.deepCopy());
        return new FileSpec(project,id,title,version.path("version_number").asText(),filename,f.path("size").asLong(),sha,uri,dependency,List.copyOf(dependencies));
    }
    private Map<String,Object> planNow(String projectId,String actor)throws Exception {
        identifier(projectId);if(actor==null||actor.isBlank()||actor.length()>128)throw new IllegalArgumentException("Actor is required");
        Response project=json("project/"+projectId),versions=versions(projectId);if(project.stale()||versions.stale())throw new IOException("Fresh source metadata is required for installation");
        if(!projectId.equals(project.value().path("id").asText()))throw new IllegalArgumentException("Catalog project identity mismatch");
        JsonNode chosen=select(versions.value()).orElseThrow(()->new IllegalArgumentException("No stable release declares exact Minecraft "+minecraft+" and Paper compatibility"));
        if(!projectId.equals(chosen.path("project_id").asText()))throw new IllegalArgumentException("Catalog release belongs to another project");
        LinkedHashMap<String,FileSpec> files=new LinkedHashMap<>();List<String> warnings=new ArrayList<>();resolve(chosen,project.value().path("title").asText(),false,files,new HashSet<>(),warnings);
        long size=files.values().stream().mapToLong(FileSpec::size).sum();if(size>128L*1024*1024)throw new IllegalArgumentException("Dependency plan exceeds128 MiB");
        Plan plan=new Plan(UUID.randomUUID().toString(),actor,projectId,project.value().path("title").asText(),clock.millis()+PLAN_TTL,List.copyOf(files.values()),List.copyOf(warnings));
        synchronized(plans){plans.values().removeIf(p->p.expires()<clock.millis());if(plans.size()>=64)throw new IllegalStateException("Too many installation plans");plans.put(plan.id(),plan);}
        return Map.of("planId",plan.id(),"minecraftVersion",minecraft,"platform","paper","projectId",projectId,"title",plan.title(),"expiresAt",plan.expires(),"totalSize",size,"requiresRestart",true,"items",plan.files().stream().map(FileSpec::json).toList(),"warnings",warnings);
    }
    private void resolve(JsonNode version,String title,boolean dependency,LinkedHashMap<String,FileSpec> files,Set<String> visiting,List<String> warnings)throws Exception {
        cancelled();
        FileSpec spec=file(version,title,dependency);FileSpec existing=files.get(spec.projectId());if(existing!=null){if(!existing.versionId().equals(spec.versionId()))throw new IllegalArgumentException("Dependency version conflict");return;}if(!visiting.add(spec.projectId()))throw new IllegalArgumentException("Dependency cycle");if(visiting.size()+files.size()>MAX_PLAN_FILES)throw new IllegalArgumentException("Too many required dependencies");
        for(JsonNode d:spec.dependencies()) {
            String type=d.path("dependency_type").asText();if(type.equals("optional")){warnings.add("Optional dependency omitted: "+d.path("project_id").asText(d.path("file_name").asText("external")));continue;}if(type.equals("embedded"))continue;
            if(type.equals("incompatible"))throw new IllegalArgumentException("Release declares an incompatible dependency; install it manually after review");if(!type.equals("required"))throw new IllegalArgumentException("Unknown dependency requirement");
            String depId=d.path("project_id").asText();if(depId.isEmpty()||depId.equals("null"))throw new IllegalArgumentException("Required external dependency cannot be verified");identifier(depId);
            Response project=json("project/"+depId);JsonNode choice;String versionId=d.path("version_id").asText();
            if(!depId.equals(project.value().path("id").asText()))throw new IllegalArgumentException("Dependency project identity mismatch");
            if(!versionId.isEmpty()&&!versionId.equals("null")){Response pinned=json("version/"+identifier(versionId));if(pinned.stale())throw new IOException("Fresh dependency metadata is required");choice=pinned.value();if(!versionId.equals(choice.path("id").asText())||!depId.equals(choice.path("project_id").asText()))throw new IllegalArgumentException("Pinned dependency identity mismatch");}
            else {Response found=versions(depId);if(found.stale())throw new IOException("Fresh dependency metadata is required");choice=select(found.value()).orElseThrow(()->new IllegalArgumentException("Required dependency has no exact stable compatible release: "+project.value().path("title").asText()));}
            if(project.stale())throw new IOException("Fresh dependency metadata is required");if(!depId.equals(choice.path("project_id").asText()))throw new IllegalArgumentException("Dependency release belongs to another project");resolve(choice,project.value().path("title").asText(),true,files,visiting,warnings);
        }
        visiting.remove(spec.projectId());files.put(spec.projectId(),spec);
    }
    private synchronized Map<String,Object> installNow(String planId,String actor,BooleanSupplier authority)throws Exception {
        authorized(authority);
        UUID.fromString(planId);Plan plan;synchronized(plans){plan=plans.get(planId);if(plan==null||plan.expires()<clock.millis())throw new IllegalArgumentException("Installation plan expired; review a new plan");if(!plan.actor().equals(actor))throw new SecurityException("Installation plan belongs to another account");plans.remove(planId);}
        checkFolders();Path transaction=transactions.resolve(plan.id());Files.createDirectory(transaction);PrivateFiles.restrict(transaction,true);List<Download> downloaded=new ArrayList<>();List<Map<String,Object>> reused=new ArrayList<>();List<PluginJar.Metadata> verifiedMetadata=new ArrayList<>();List<Path> committed=new ArrayList<>();
        try {
            Map<String,String> existing=existingVersions();Set<String> occupied=new HashSet<>(existing.keySet());
            for(FileSpec spec:plan.files()) {
                authorized(authority);
                Path target=transaction.resolve("catalog-"+spec.projectId()+"-"+spec.versionId()+".jar");byte[] bytes=transport.get(spec.uri(),JAR_LIMIT);if(bytes.length!=spec.size()||!constantHash(bytes,"SHA-512",spec.sha512()))throw new IOException("Downloaded JAR does not match published size/SHA-512");
                Files.write(target,bytes,StandardOpenOption.CREATE_NEW);PrivateFiles.restrict(target,false);String sha=hash(bytes,"SHA-256");bytes=null;
                PluginJar.Metadata metadata=PluginJar.inspect(target,minecraft);String identity=metadata.name().toLowerCase(Locale.ROOT);
                verifiedMetadata.add(metadata);
                if(metadata.version().equals(existing.get(identity))){Path existingJar=findExistingJar(identity);if(existingJar==null||!fileHash(existingJar,"SHA-512").equals(spec.sha512()))throw new IllegalArgumentException("Installed plugin has different bytes from the verified release: "+metadata.name());if(!spec.dependency()){Map<String,Object> record=installRecord(spec,metadata,existingJar,sha);boolean active=installed.get().stream().anyMatch(p->metadata.name().equalsIgnoreCase(Objects.toString(p.get("name"),""))&&metadata.version().equals(p.get("version")));record.put("status",active?"loaded":"installed-pending-restart");record.put("requiresRestart",!active);reused.add(record);}Files.delete(target);continue;}
                if(!occupied.add(identity))throw new IllegalArgumentException("Plugin is already installed or pending: "+metadata.name());for(String provide:metadata.provides())if(!occupied.add(provide.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("Plugin capability conflicts with an installed plugin: "+provide);
                downloaded.add(new Download(spec,target,metadata,sha));
            }
            for(PluginJar.Metadata metadata:verifiedMetadata)for(String dependency:metadata.required())if(!occupied.contains(dependency.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("JAR declares an unresolved required dependency: "+dependency);
            // Nothing enters plugins/ until the entire dependency set has downloaded and validated.
            authorized(authority);checkFolders();Set<String> recheck=existingVersions().keySet();for(Download download:downloaded){List<String> identities=new ArrayList<>(download.metadata().provides());identities.add(download.metadata().name());for(String name:identities)if(recheck.contains(name.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("Plugin appeared during installation; review a new plan");}
            List<Map<String,Object>> result=new ArrayList<>(reused);for(Download download:downloaded) {
                authorized(authority);
                Path destination=plugins.resolve(download.path().getFileName());if(Files.exists(destination,LinkOption.NOFOLLOW_LINKS))throw new IOException("Plugin target already exists");
                // A same-filesystem hard link is an atomic CREATE_NEW publication: it cannot replace an existing JAR.
                Files.createLink(destination,download.path());committed.add(destination);Files.delete(download.path());
                Map<String,Object> record=installRecord(download.spec(),download.metadata(),destination,download.sha256());
                result.add(record);
            }
            for(Map<String,Object> record:reused){authorized(authority);Path existingJar=plugins.resolve(record.get("file").toString());if(Files.isSymbolicLink(existingJar)||!fileHash(existingJar,"SHA-256").equals(record.get("sha256")))throw new IllegalArgumentException("Reused plugin changed during installation; review a new plan");}
            // The complete install history commits in one database transaction; a failure rolls back only our new files.
            authorized(authority);db.submit(connection->{authorized(authority);connection.setAutoCommit(false);try(PreparedStatement statement=connection.prepareStatement("INSERT INTO documents(namespace,id,json) VALUES('catalog-installs',?,?) ON CONFLICT(namespace,id) DO UPDATE SET json=excluded.json")){for(Map<String,Object> record:result){statement.setString(1,record.get("projectId").toString());statement.setString(2,Json.write(record));statement.addBatch();}statement.executeBatch();authorized(authority);connection.commit();return null;}catch(Exception error){connection.rollback();throw error;}finally{connection.setAutoCommit(true);}}).join();
            boolean restart=result.stream().anyMatch(r->Boolean.TRUE.equals(r.get("requiresRestart")));return Map.of("status",downloaded.isEmpty()?"already-installed":"installed-pending-restart","requiresRestart",restart,"installed",result,"message",downloaded.isEmpty()?"The verified release is already installed; existing files were preserved.":"JAR files installed. Restart the server to activate them.");
        }catch(Exception error) {
            for(Path path:committed)try{Download own=downloaded.stream().filter(d->d.path().getFileName().equals(path.getFileName())).findFirst().orElseThrow();if(!Files.isSymbolicLink(path)&&hash(Files.readAllBytes(path),"SHA-256").equals(own.sha256()))Files.deleteIfExists(path);else throw new IOException("Rollback target changed externally");}catch(Exception rollback){error.addSuppressed(rollback);}throw error;
        }finally {
            try(DirectoryStream<Path> files=Files.newDirectoryStream(transaction)){for(Path file:files)Files.deleteIfExists(file);}Files.deleteIfExists(transaction);
        }
    }
    private Map<String,Object> installRecord(FileSpec spec,PluginJar.Metadata metadata,Path file,String sha){Map<String,Object> record=new LinkedHashMap<>();record.put("name",metadata.name());record.put("version",metadata.version());record.put("file",file.getFileName().toString());record.put("projectId",spec.projectId());record.put("versionId",spec.versionId());record.put("sha256",sha);record.put("sha512",spec.sha512());record.put("size",spec.size());record.put("time",clock.millis());record.put("status","installed-pending-restart");record.put("requiresRestart",true);record.put("source","modrinth");return record;}
    private Map<String,String> existingVersions()throws Exception {
        Map<String,String> names=new HashMap<>();for(PresentPlugin plugin:presentPlugins()){names.put(plugin.name().toLowerCase(Locale.ROOT),plugin.version());for(String provides:plugin.provides())names.put(provides.toLowerCase(Locale.ROOT),plugin.version());}return names;
    }
    private List<PresentPlugin> presentPlugins()throws IOException {
        checkFolders();List<PresentPlugin> present=new ArrayList<>();for(Map<String,Object> plugin:installed.get()){List<String> provides=plugin.get("provides") instanceof List<?> values?values.stream().map(Object::toString).toList():List.of();present.add(new PresentPlugin(Objects.toString(plugin.get("name"),""),Objects.toString(plugin.get("version"),""),provides,Boolean.TRUE.equals(plugin.get("enabled"))));}
        int count=0;try(DirectoryStream<Path> jars=Files.newDirectoryStream(plugins)){for(Path jar:jars){if(!jar.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))continue;if(++count>512)throw new IOException("Plugin inventory exceeds its bound");PluginJar.Metadata metadata=PluginJar.inspectExisting(jar);present.add(new PresentPlugin(metadata.name(),metadata.version(),metadata.provides(),null));}}return present;
    }
    private void cancelled(){if(closed||Thread.currentThread().isInterrupted())throw new CancellationException("Catalog installation cancelled");}
    private void authorized(BooleanSupplier authority){cancelled();if(!authority.getAsBoolean())throw new SecurityException("Owner session is no longer active; installation cancelled");}
    private Path findExistingJar(String identity)throws IOException {Path found=null;int count=0;try(DirectoryStream<Path> jars=Files.newDirectoryStream(plugins)){for(Path jar:jars){if(!jar.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))continue;if(++count>512)throw new IOException("Plugin inventory exceeds its bound");if(PluginJar.inspectExisting(jar).name().equalsIgnoreCase(identity)){if(found!=null)throw new IOException("Ambiguous plugin JAR identity");found=jar;}}}return found;}
    private static String fileHash(Path file,String algorithm)throws Exception {long limit=PluginJar.existingLimit(PluginJar.inspectExisting(file));MessageDigest digest=MessageDigest.getInstance(algorithm);long bytes=0;try(InputStream in=Files.newInputStream(file)){byte[] buffer=new byte[32768];int n;while((n=in.read(buffer))!=-1){if((bytes+=n)>limit)throw new IOException("Existing JAR exceeds its limit");digest.update(buffer,0,n);}}return HexFormat.of().formatHex(digest.digest());}
    private void checkFolders()throws IOException {if(Files.isSymbolicLink(data)||Files.isSymbolicLink(plugins)||Files.isSymbolicLink(transactions)||!plugins.equals(plugins.toRealPath())||!transactions.toRealPath().startsWith(data.toRealPath()))throw new IOException("Catalog directory changed or is unsafe");}
    private Response json(String path)throws Exception {
        cancelled();
        URI uri=URI.create("https://api.modrinth.com/v2/"+path);CatalogHttp.validate(uri);Cached previous;synchronized(cache){previous=cache.get(path);if(previous!=null&&clock.millis()-previous.time()<CACHE_TTL)return new Response(previous.value(),false);}
        try {byte[] bytes=transport.get(uri,JSON_LIMIT);JsonNode value=Json.MAPPER.readTree(bytes);if(value==null||(!value.isObject()&&!value.isArray()))throw new IOException("Invalid plugin source response");synchronized(cache){Cached replaced=cache.remove(path);if(replaced!=null)cacheBytes-=replaced.bytes();while(!cache.isEmpty()&&(cache.size()>=512||cacheBytes+bytes.length>16*1024*1024)){Cached evicted=cache.remove(cache.keySet().iterator().next());cacheBytes-=evicted.bytes();}cache.put(path,new Cached(value,clock.millis(),bytes.length));cacheBytes+=bytes.length;}return new Response(value,false);}catch(Exception error){if(previous!=null&&clock.millis()-previous.time()<STALE_TTL)return new Response(previous.value(),true);if(error instanceof IOException io)throw io;throw new IOException("Plugin source is unavailable",error);}
    }
    private <T> CompletableFuture<T> submit(Callable<T> task){return submit(task,0);}
    private <T> CompletableFuture<T> submit(Callable<T> task,int timeoutSeconds){CompletableFuture<T> result=new CompletableFuture<>();pending.add(result);result.whenComplete((value,error)->pending.remove(result));try{if(closed)throw new RejectedExecutionException();Future<?> job=worker.submit(()->{try{if(closed||result.isCancelled())throw new CancellationException("Catalog closed");result.complete(task.call());}catch(Exception error){result.completeExceptionally(error);}});result.whenComplete((value,error)->{if(result.isCancelled())job.cancel(true);});if(result.isCancelled())job.cancel(true);if(timeoutSeconds>0){ScheduledFuture<?> timer=deadlines.schedule(()->{if(result.completeExceptionally(new IllegalStateException("Plugin metadata request timed out; retry shortly")))job.cancel(true);},timeoutSeconds,TimeUnit.SECONDS);result.whenComplete((value,error)->timer.cancel(false));}}catch(RejectedExecutionException error){result.completeExceptionally(new IllegalStateException("Plugin catalog queue is full or closed"));}return result;}
    private static List<String> strings(JsonNode value){List<String> result=new ArrayList<>();if(value.isArray())for(JsonNode item:value)if(item.isTextual())result.add(item.asText());return result;}
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    private static String identifier(String value){if(!value.matches("[A-Za-z0-9]{1,64}"))throw new IllegalArgumentException("Invalid catalog project/version identifier");return value;}
    private static String truncate(String value,int max){return value.length()<=max?value:value.substring(0,max);}
    private static String hash(byte[] bytes,String algorithm)throws NoSuchAlgorithmException{return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));}
    private static boolean constantHash(byte[] bytes,String algorithm,String expected)throws NoSuchAlgorithmException{return MessageDigest.isEqual(HexFormat.of().parseHex(expected),MessageDigest.getInstance(algorithm).digest(bytes));}
    @Override public void close(){closed=true;deadlines.shutdownNow();metadataWorker.shutdownNow();worker.shutdownNow();if(transport instanceof AutoCloseable closeable)try{closeable.close();}catch(Exception ignored){}for(CompletableFuture<?> future:pending)future.completeExceptionally(new CancellationException("Plugin catalog closed"));try{worker.awaitTermination(1500,TimeUnit.MILLISECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();}}
}
