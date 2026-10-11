package dev.mcc.core;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class PluginCatalogTest {
    @TempDir Path directory;
    private final Map<String,byte[]> responses=new ConcurrentHashMap<>();
    private final List<Map<String,Object>> snapshot=new CopyOnWriteArrayList<>();
    private Database db;private PluginCatalog catalog;private Path plugins,data;private final MutableClock clock=new MutableClock();
    @BeforeEach void setup()throws Exception {plugins=Files.createDirectories(directory.resolve("plugins"));data=Files.createDirectories(plugins.resolve("MCC"));db=new Database(data.resolve("test.db"));catalog=new PluginCatalog(db,data,"26.3",()->List.copyOf(snapshot),this::fetch,clock);}
    @AfterEach void cleanup(){catalog.close();db.close();}
    private byte[] fetch(URI uri,int limit)throws IOException {byte[] value=responses.get(uri.getHost().equals("cdn.modrinth.com")?uri.getPath():uri.getPath().replace("/v2/",""));if(value==null)throw new IOException("Mock source unavailable");if(value.length>limit)throw new IOException("Limit exceeded");return value;}
    private void json(String path,Object data){responses.put(path,Json.write(data).getBytes(StandardCharsets.UTF_8));}
    private Map<String,Object> project(String id,String title){Map<String,Object> p=Map.of("id",id,"project_id",id,"slug",title.toLowerCase(Locale.ROOT),"title",title,"description","Test plugin","downloads",12345,"author","publisher");json("project/"+id,p);return p;}
    private Map<String,Object> version(String project,String id,String name,String yaml,List<Map<String,Object>> dependencies)throws Exception {
        byte[] jar=jar(yaml);String path="/data/"+project+"/versions/"+id+"/"+name+".jar";responses.put(path,jar);
        Map<String,Object> version=new LinkedHashMap<>();version.put("id",id);version.put("project_id",project);version.put("version_number","1.0");version.put("game_versions",List.of("26.3"));version.put("loaders",List.of("paper"));version.put("version_type","release");version.put("status","listed");version.put("date_published","2026-10-10T12:00:00Z");version.put("dependencies",dependencies);version.put("files",List.of(Map.of("filename",name+".jar","size",jar.length,"primary",true,"hashes",Map.of("sha512",sha(jar,"SHA-512")),"url","https://cdn.modrinth.com"+path)));json("project/"+project+"/version",List.of(version));json("version/"+id,version);return version;
    }
    private static String yaml(String name){return "name: "+name+"\nversion: '1.0'\nmain: test.Main\napi-version: '26.3'\n";}
    private static byte[] jar(String descriptor)throws Exception {ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(bytes)){ZipEntry metadata=new ZipEntry("plugin.yml");metadata.setTime(0);zip.putNextEntry(metadata);zip.write(descriptor.getBytes(StandardCharsets.UTF_8));zip.closeEntry();ZipEntry main=new ZipEntry("test/Main.class");main.setTime(0);zip.putNextEntry(main);zip.write(new byte[]{1,2,3});zip.closeEntry();}return bytes.toByteArray();}
    private static String sha(byte[] bytes,String algorithm)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));}
    private Map<String,Object> plan(String id)throws Exception{return catalog.plan(id,"owner").get(5,TimeUnit.SECONDS);}
    private Map<String,Object> install(Map<String,Object> plan)throws Exception{return catalog.install(plan.get("planId").toString(),"owner").get(5,TimeUnit.SECONDS);}
    private long jarCount()throws IOException{try(var files=Files.list(plugins)){return files.filter(p->p.toString().endsWith(".jar")).count();}}
    @SuppressWarnings("unchecked") private List<Map<String,Object>> items(Map<String,Object> result){return (List<Map<String,Object>>)result.get("items");}

    @Test void searchRequiresExactGameAndServerLoaderOnTheSameStableRelease()throws Exception {
        Map<String,Object> p=project("Project","Example");Map<String,Object> old=version("Project","Old","Example",yaml("Example"),List.of());old.put("game_versions",List.of("1.21.11"));Map<String,Object> fabric=new LinkedHashMap<>(old);fabric.put("id","Fabric");fabric.put("game_versions",List.of("26.3"));fabric.put("loaders",List.of("fabric"));json("project/Project/version",List.of(old,fabric));json("search",Map.of("hits",List.of(p),"total_hits",1));
        Map<String,Object> result=catalog.search("","downloads",0,12,true,false).get(5,TimeUnit.SECONDS);assertEquals("unavailable",items(result).getFirst().get("compatibility"));assertFalse(items(result).getFirst().containsKey("version"));assertThrows(ExecutionException.class,()->plan("Project"));
    }
    @Test void betaIsNeverSilentlySelectedAndExactStableReleaseIsSelected()throws Exception {
        project("Project","Example");Map<String,Object> release=version("Project","Release","Example",yaml("Example"),List.of());Map<String,Object> beta=new LinkedHashMap<>(release);beta.put("version_type","beta");beta.put("date_published","2026-10-11T12:00:00Z");json("project/Project/version",List.of(beta,release));assertEquals("Release",items(plan("Project")).getFirst().get("versionId"));
    }
    @Test void requiredDependenciesAppearFirstAndAreActuallyInstalledWithoutLoadingCode()throws Exception {
        project("Dependency","Dependency");version("Dependency","DepV","Dependency",yaml("Dependency"),List.of());project("Project","Example");version("Project","Version","Example",yaml("Example")+"depend: [Dependency]\n",List.of(Map.of("project_id","Dependency","dependency_type","required")));
        Map<String,Object> plan=plan("Project");assertEquals(List.of("Dependency","Project"),items(plan).stream().map(i->i.get("projectId")).toList());Map<String,Object> result=install(plan);assertEquals("installed-pending-restart",result.get("status"));assertEquals(2,jarCount());assertEquals(2,db.documents("catalog-installs").get().size());assertEquals(2,catalog.installedCatalog().get().stream().filter(i->Boolean.TRUE.equals(i.get("requiresRestart"))).count());
    }
    @Test void alreadyInstalledExactDependencyIsReusedAndNeverOverwritten()throws Exception {
        project("Dependency","Dependency");version("Dependency","DepV","Dependency",yaml("Dependency"),List.of());Path existing=plugins.resolve("existing.jar");byte[] original=jar(yaml("Dependency"));Files.write(existing,original);
        project("Project","Example");version("Project","Version","Example",yaml("Example")+"depend: [Dependency]\n",List.of(Map.of("project_id","Dependency","dependency_type","required")));install(plan("Project"));assertEquals(2,jarCount());assertArrayEquals(original,Files.readAllBytes(existing));assertEquals(1,db.documents("catalog-installs").get().size());
    }
    @Test void mismatchedRequiredDependencyVersionBlocksTheWholeSet()throws Exception {
        project("Dependency","Dependency");version("Dependency","DepV","Dependency",yaml("Dependency"),List.of());Files.write(plugins.resolve("existing.jar"),jar(yaml("Dependency").replace("'1.0'","'0.9'")));
        project("Project","Example");version("Project","Version","Example",yaml("Example"),List.of(Map.of("project_id","Dependency","dependency_type","required")));assertThrows(ExecutionException.class,()->install(plan("Project")));assertEquals(1,jarCount());assertTrue(db.documents("catalog-installs").get().isEmpty());
    }
    @Test void reusedDependencyStillMustSatisfyItsJarDeclaredRequirements()throws Exception {
        project("Dependency","Dependency");String descriptor=yaml("Dependency")+"depend: [Missing]\n";version("Dependency","DepV","Dependency",descriptor,List.of());Files.write(plugins.resolve("existing.jar"),jar(descriptor));project("Project","Example");version("Project","Version","Example",yaml("Example")+"depend: [Dependency]\n",List.of(Map.of("project_id","Dependency","dependency_type","required")));
        assertThrows(ExecutionException.class,()->install(plan("Project")));assertEquals(1,jarCount());assertTrue(db.documents("catalog-installs").get().isEmpty());
    }
    @Test void sameVersionDifferentDependencyBytesAreNotAssumedCompatible()throws Exception {
        project("Dependency","Dependency");version("Dependency","DepV","Dependency",yaml("Dependency"),List.of());Files.write(plugins.resolve("existing.jar"),jar(yaml("Dependency")+"description: different build\n"));project("Project","Example");version("Project","Version","Example",yaml("Example"),List.of(Map.of("project_id","Dependency","dependency_type","required")));
        assertThrows(ExecutionException.class,()->install(plan("Project")));assertEquals(1,jarCount());assertTrue(db.documents("catalog-installs").get().isEmpty());
    }
    @Test void changedDownloadHashCannotPublishAnyPlugin()throws Exception {
        project("Project","Example");version("Project","Version","Example",yaml("Example"),List.of());byte[] original=responses.get("/data/Project/versions/Version/Example.jar");byte[] changed=original.clone();changed[changed.length-1]^=1;responses.put("/data/Project/versions/Version/Example.jar",changed);assertThrows(ExecutionException.class,()->install(plan("Project")));assertEquals(0,jarCount());assertTrue(db.documents("catalog-installs").get().isEmpty());
    }
    @Test void descriptorCannotReplaceMccEvenWithTrustedRegistryHash()throws Exception {
        project("Project","Example");version("Project","Version","Example",yaml("MinecraftControlCenter"),List.of());assertThrows(ExecutionException.class,()->install(plan("Project")));assertEquals(0,jarCount());
    }
    @Test void jarDependencyMissingFromRegistryBlocksEveryPublication()throws Exception {
        project("Project","Example");version("Project","Version","Example",yaml("Example")+"depend: [SecretDependency]\n",List.of());assertThrows(ExecutionException.class,()->install(plan("Project")));assertEquals(0,jarCount());
    }
    @Test void catalogPlanIsActorBoundSingleUseAndExpires()throws Exception {
        project("Project","Example");version("Project","Version","Example",yaml("Example"),List.of());Map<String,Object> plan=plan("Project");assertThrows(ExecutionException.class,()->catalog.install(plan.get("planId").toString(),"different-owner").get());install(plan);assertThrows(ExecutionException.class,()->install(plan));
        Map<String,Object> expired=plan("Project");clock.now+=300001;assertThrows(ExecutionException.class,()->install(expired));assertEquals(1,jarCount());
    }
    @Test void existingJarIdentityIsProtectedEvenWithAnotherFilename()throws Exception {
        project("Project","Example");version("Project","Version","Example",yaml("Example"),List.of());Path existing=plugins.resolve("something.JAR");byte[] bytes=jar(yaml("Example"));Files.write(existing,bytes);assertThrows(ExecutionException.class,()->install(plan("Project")));assertArrayEquals(bytes,Files.readAllBytes(existing));assertEquals(1,Files.list(plugins).filter(p->p.toString().toLowerCase(Locale.ROOT).endsWith(".jar")).count());
    }
    @Test void databaseFailureRollsBackOurPublishedFilesOnly()throws Exception {
        catalog.close();AtomicBoolean closeDatabase=new AtomicBoolean();catalog=new PluginCatalog(db,data,"26.3",()->List.copyOf(snapshot),(uri,limit)->{byte[] bytes=fetch(uri,limit);if(uri.getHost().equals("cdn.modrinth.com")&&closeDatabase.compareAndSet(false,true))db.close();return bytes;},clock);
        Path existing=plugins.resolve("untouched.jar");byte[] bytes=jar(yaml("Other"));Files.write(existing,bytes);project("Project","Example");version("Project","Version","Example",yaml("Example"),List.of());assertThrows(ExecutionException.class,()->install(plan("Project")));assertArrayEquals(bytes,Files.readAllBytes(existing));assertEquals(1,jarCount());
    }
    @Test void unsafeSourcesCannotBecomeArtifactRequests() {
        for(String url:List.of("http://cdn.modrinth.com/data/P/versions/V/plugin.jar","https://127.0.0.1/data/P/versions/V/plugin.jar","https://cdn.modrinth.com.evil/data/P/versions/V/plugin.jar","https://user@cdn.modrinth.com/data/P/versions/V/plugin.jar","https://cdn.modrinth.com:443/data/P/versions/V/plugin.jar","https://cdn.modrinth.com/data/P/versions/V/plugin.jar?redirect=http://127.0.0.1","https://cdn.modrinth.com/data/P/../versions/V/plugin.jar"))assertThrows(IllegalArgumentException.class,()->CatalogHttp.validate(URI.create(url)),url);
        assertDoesNotThrow(()->CatalogHttp.validate(URI.create("https://cdn.modrinth.com/data/Project/versions/Version/Example.jar")));
    }
    @Test void dangerousYamlTagsAliasesAndDuplicateIdentityAreRejected()throws Exception {
        for(String descriptor:List.of(yaml("Example")+"name: Replaced\n",yaml("Example")+"payload: !!java.net.URL [https://example.com]\n",yaml("Example")+"a: &a [one]\nb: *a\n")){Path file=directory.resolve(UUID.randomUUID()+".jar");Files.write(file,jar(descriptor));assertThrows(IOException.class,()->PluginJar.inspect(file,"26.3"));}
    }
    @Test void descriptorApiVersionIsOnlyAMinimumAndCannotProveExactCompatibility()throws Exception {
        Path file=directory.resolve("legacy.jar");Files.write(file,jar(yaml("Example").replace("26.3","1.13")));assertEquals("1.13",PluginJar.inspect(file,"26.3").apiVersion());Files.write(file,jar(yaml("Example").replace("26.3","26.4")));assertThrows(IOException.class,()->PluginJar.inspect(file,"26.3"));
    }
    @Test void disabledRuntimePluginIsNotReportedAsLoaded()throws Exception {
        db.document("catalog-installs","Project",Map.of("name","Example","version","1.0")).get();snapshot.add(Map.of("name","Example","version","1.0","enabled",false));Map<String,Object> item=catalog.installedCatalog().get().getFirst();assertEquals("disabled",item.get("status"));assertEquals(false,item.get("requiresRestart"));
    }
    @Test void closeCancelsQueuedAndRunningCatalogRequestsPromptly()throws Exception {
        catalog.close();CountDownLatch entered=new CountDownLatch(2);catalog=new PluginCatalog(db,data,"26.3",()->List.copyOf(snapshot),(uri,limit)->{entered.countDown();Thread.sleep(60_000);return "[]".getBytes(StandardCharsets.UTF_8);},clock);List<CompletableFuture<?>> futures=new ArrayList<>();for(int i=0;i<5;i++)futures.add(catalog.search("","downloads",0,12,true,false));assertTrue(entered.await(2,TimeUnit.SECONDS));long start=System.nanoTime();catalog.close();assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<2000);assertTrue(futures.stream().allMatch(CompletableFuture::isCompletedExceptionally));
    }
    @Test void revokingOwnerWhileDownloadRunsPreventsPublication()throws Exception {
        catalog.close();CountDownLatch downloading=new CountDownLatch(1),release=new CountDownLatch(1);AtomicBoolean owner=new AtomicBoolean(true);catalog=new PluginCatalog(db,data,"26.3",()->List.copyOf(snapshot),(uri,limit)->{if(uri.getHost().equals("cdn.modrinth.com")){downloading.countDown();if(!release.await(3,TimeUnit.SECONDS))throw new IOException("Test timed out");}return fetch(uri,limit);},clock);
        project("Project","Example");version("Project","Version","Example",yaml("Example"),List.of());Map<String,Object> plan=plan("Project");CompletableFuture<?> installing=catalog.install(plan.get("planId").toString(),"owner",owner::get);assertTrue(downloading.await(2,TimeUnit.SECONDS));owner.set(false);release.countDown();assertThrows(ExecutionException.class,()->installing.get(3,TimeUnit.SECONDS));assertEquals(0,jarCount());assertTrue(db.documents("catalog-installs").get().isEmpty());
    }
    @Test void revocationBeforeDatabaseCommitRollsBackPublishedJars()throws Exception {
        project("Project","Example");version("Project","Version","Example",yaml("Example"),List.of());Map<String,Object> plan=plan("Project");assertThrows(ExecutionException.class,()->catalog.install(plan.get("planId").toString(),"owner",()->{try{return jarCount()==0;}catch(IOException error){return false;}}).get());assertEquals(0,jarCount());assertTrue(db.documents("catalog-installs").get().isEmpty());
    }
    @Test void projectAndPinnedVersionResponsesCannotChangeTheirIdentity()throws Exception {
        project("Project","Example");Map<String,Object> version=version("Project","Version","Example",yaml("Example"),List.of());version.put("project_id","Other");json("project/Project/version",List.of(version));assertThrows(ExecutionException.class,()->plan("Project"));
        catalog.close();catalog=new PluginCatalog(db,data,"26.3",()->List.copyOf(snapshot),this::fetch,clock);project("Dependency","Dependency");Map<String,Object> dep=version("Dependency","Correct","Dependency",yaml("Dependency"),List.of());dep.put("id","Different");json("version/Correct",dep);project("Project","Example");version("Project","Version","Example",yaml("Example"),List.of(Map.of("project_id","Dependency","version_id","Correct","dependency_type","required")));assertThrows(ExecutionException.class,()->plan("Project"));assertEquals(0,jarCount());
    }
    @Test void callerCancellationInterruptsMetadataWorkAndFreesTheQueue()throws Exception {
        catalog.close();CountDownLatch entered=new CountDownLatch(1),interrupted=new CountDownLatch(1);catalog=new PluginCatalog(db,data,"26.3",()->List.copyOf(snapshot),(uri,limit)->{entered.countDown();try{Thread.sleep(60_000);}catch(InterruptedException error){interrupted.countDown();throw error;}return fetch(uri,limit);},clock);CompletableFuture<?> request=catalog.plan("Project","owner");assertTrue(entered.await(2,TimeUnit.SECONDS));request.cancel(true);assertTrue(interrupted.await(2,TimeUnit.SECONDS));assertTrue(request.isCancelled());
    }
    @Test void staleCatalogRemainsReadableButCannotCreateAnInstallPlan()throws Exception {
        Map<String,Object> project=project("Project","Example");version("Project","Version","Example",yaml("Example"),List.of());json("search",Map.of("hits",List.of(project),"total_hits",1));catalog.search("","downloads",0,12,true,false).get();plan("Project");clock.now+=300001;responses.clear();Map<String,Object> cached=catalog.search("","downloads",0,12,true,false).get();assertEquals(true,cached.get("stale"));assertEquals(true,items(cached).getFirst().get("stale"));assertThrows(ExecutionException.class,()->plan("Project"));assertEquals(0,jarCount());
    }
    @Test void metadataForAResultPageIsFetchedWithBoundedParallelism()throws Exception {
        List<Map<String,Object>> projects=new ArrayList<>();for(int i=0;i<4;i++){String id="Project"+i;projects.add(project(id,"Example"+i));version(id,"Version"+i,"Example"+i,yaml("Example"+i),List.of());}json("search",Map.of("hits",projects,"total_hits",4));catalog.close();CountDownLatch entered=new CountDownLatch(4),release=new CountDownLatch(1);AtomicInteger running=new AtomicInteger(),peak=new AtomicInteger();catalog=new PluginCatalog(db,data,"26.3",()->List.copyOf(snapshot),(uri,limit)->{if(uri.getPath().endsWith("/version")){int active=running.incrementAndGet();peak.accumulateAndGet(active,Math::max);entered.countDown();try{if(!release.await(3,TimeUnit.SECONDS))throw new IOException("Test timed out");return fetch(uri,limit);}finally{running.decrementAndGet();}}return fetch(uri,limit);},clock);CompletableFuture<Map<String,Object>> search=catalog.search("","downloads",0,12,true,false);assertTrue(entered.await(2,TimeUnit.SECONDS));release.countDown();assertEquals(4,items(search.get(3,TimeUnit.SECONDS)).size());assertEquals(4,peak.get());
    }
    @Test void transactionDirectoryLinkIsRejectedBeforeChangingItsTargetPermissions()throws Exception {
        Path isolated=Files.createDirectories(directory.resolve("linked-plugins"));Path ownData=Files.createDirectories(isolated.resolve("MCC"));Path outside=Files.createDirectories(directory.resolve("outside-target"));Path link=ownData.resolve("catalog-transactions");
        try{Files.createSymbolicLink(link,outside);}catch(IOException|UnsupportedOperationException error){
            if(!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows"))throw error;
            String command="New-Item -ItemType Junction -Path '"+link.toString().replace("'","''")+"' -Value '"+outside.toString().replace("'","''")+"' | Out-Null";
            Process process=new ProcessBuilder("powershell.exe","-NoProfile","-NonInteractive","-Command",command).redirectErrorStream(true).start();String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);assertEquals(0,process.waitFor(),output);
        }
        var acl=Files.getFileAttributeView(outside,java.nio.file.attribute.AclFileAttributeView.class);var posix=Files.getFileAttributeView(outside,java.nio.file.attribute.PosixFileAttributeView.class);Object before=acl!=null?acl.getAcl():posix.readAttributes().permissions();
        assertThrows(IOException.class,()->new PluginCatalog(db,ownData,"26.3",()->List.copyOf(snapshot),this::fetch,clock));assertEquals(before,acl!=null?acl.getAcl():posix.readAttributes().permissions());Files.delete(link);
    }
    private static final class MutableClock extends Clock {long now=Instant.parse("2026-10-10T12:00:00Z").toEpochMilli();@Override public ZoneId getZone(){return ZoneOffset.UTC;}@Override public Clock withZone(ZoneId zone){return this;}@Override public Instant instant(){return Instant.ofEpochMilli(now);}@Override public long millis(){return now;}}
}
