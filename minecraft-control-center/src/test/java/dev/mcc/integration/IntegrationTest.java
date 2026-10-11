package dev.mcc.integration;

import dev.mcc.core.Auth;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Contract fakes exercise actual adapters and lifecycle; no server/plugin startup. */
public class IntegrationTest {
    private static final Auth.Session OWNER=new Auth.Session("session","owner","Owner","csrf",Long.MAX_VALUE,false);
    private static final Auth.Session ADMIN=new Auth.Session("admin","admin","Admin","csrf",Long.MAX_VALUE,false);
    private static final Auth.Session VIEWER=new Auth.Session("viewer","viewer","Viewer","csrf",Long.MAX_VALUE,false);
    private static Map<String,Object> lookup(){return new HashMap<>(Map.of("world","test","x",0,"y",64,"z",0,"seconds",60,"radius",8,"limit",1));}
    private static IntegrationService.Binding binding(String id,Object api){return new IntegrationService.Binding(id,id,"test",api,IntegrationTest.class.getClassLoader(),api,"");}
    private static final class Runtime implements IntegrationService.Runtime {
        List<IntegrationService.Binding> items=new ArrayList<>();boolean enabled=true;
        public List<IntegrationService.Binding> discover(){return enabled?List.copyOf(items):List.of();}
        public boolean active(IntegrationService.Binding b){return enabled&&items.contains(b);}
        public Location location(String world,int x,int y,int z){return new Location(null,x,y,z);}
    }
    public interface Contract {String value();}
    private static final class PrivateImplementation implements Contract {public String value(){return "actual";}}
    @Test void publicInterfaceCanBeCalledWithoutOpeningPrivateImplementation()throws Exception {assertEquals("actual",PublicApi.call(new PrivateImplementation(),"value"));assertThrows(UnsupportedOperationException.class,()->PublicApi.call(new PrivateImplementation(),"hidden"));}
    public interface BaseStatic {static String builder(String key){return "base:"+key;}}
    public interface SpecializedStatic extends BaseStatic {static String builder(String key){return "specialized:"+key;}}
    @Test void staticSpecializedBuilderNeverResolvesTheBaseNodeBuilder()throws Exception {assertEquals("specialized:default",PublicApi.call(SpecializedStatic.class,"builder","default"));}
    @Test void absenceProducesNoSyntheticPluginSections()throws Exception {try(IntegrationService service=new IntegrationService(new Runtime(),(s,w)->true)){Map<String,Object> result=service.discover(ADMIN).get(2,TimeUnit.SECONDS);assertEquals(List.of(),result.get("items"));assertEquals("builtin",result.get("punishmentProvider"));}}
    @Test void unsupportedAdvancedBanStillOwnsPunishmentsAndCannotMutate()throws Exception {
        Runtime runtime=new Runtime();runtime.items.add(new IntegrationService.Binding("advancedban","AdvancedBan","unsupported",null,getClass().getClassLoader(),this,"Unsupported API"));
        try(IntegrationService service=new IntegrationService(runtime,(s,w)->true)){assertEquals("unavailable",service.punishmentProvider(OWNER).get(2,TimeUnit.SECONDS));assertInstanceOf(UnsupportedOperationException.class,assertThrows(ExecutionException.class,()->service.mutate(OWNER,"advancedban",Map.of("confirmed",true)).get(2,TimeUnit.SECONDS)).getCause());}
    }
    @Test void serviceEnforcesRolesEvenWhenCallerPredicateIsTooPermissive()throws Exception {
        try(IntegrationService service=new IntegrationService(new Runtime(),(s,w)->true)){assertInstanceOf(SecurityException.class,assertThrows(ExecutionException.class,()->service.discover(VIEWER).get()).getCause());assertInstanceOf(SecurityException.class,assertThrows(ExecutionException.class,()->service.mutate(ADMIN,"luckperms",Map.of("confirmed",true)).get()).getCause());}
    }
    @Test void writesRequireExplicitConfirmationBeforeAnyPluginOperation()throws Exception {Runtime runtime=new Runtime();try(IntegrationService service=new IntegrationService(runtime,(s,w)->true)){assertInstanceOf(IllegalArgumentException.class,assertThrows(ExecutionException.class,()->service.mutate(OWNER,"luckperms",Map.of()).get()).getCause());}}
    @Test void coreProtectActuallyUsesPagedLookupAndParsesReturnedRecords()throws Exception {
        CoreApi api=new CoreApi();Runtime runtime=new Runtime();runtime.items.add(binding("coreprotect",api));Map<String,Object> input=lookup();input.put("offset",50);input.put("player","Notch");input.put("action","placed");
        try(IntegrationService service=new IntegrationService(runtime,(s,w)->true)){Map<String,Object> result=service.query(ADMIN,"coreprotect",input).get(2,TimeUnit.SECONDS);assertEquals(2,api.count);assertEquals(50,api.offset);assertEquals(List.of("Notch"),api.users);assertEquals(List.of(1),api.actions);assertEquals(true,result.get("hasMore"));assertEquals(true,result.get("readOnly"));List<?> rows=(List<?>)result.get("items");assertEquals(1,rows.size());assertEquals(123000L,((Map<?,?>)rows.getFirst()).get("timestamp"));assertEquals("STONE",((Map<?,?>)rows.getFirst()).get("block"));}
    }
    @Test void coreProtectRejectsUnboundedRadiusAndTimeBeforeCallingProvider()throws Exception {
        CoreApi api=new CoreApi();Runtime runtime=new Runtime();Map<String,Object> input=lookup();input.put("radius",33);assertThrows(IllegalArgumentException.class,()->CoreProtectIntegration.lookup(binding("coreprotect",api),input,runtime));input.put("radius",1);input.put("seconds",86401);assertThrows(IllegalArgumentException.class,()->CoreProtectIntegration.lookup(binding("coreprotect",api),input,runtime));assertEquals(0,api.calls.get());
    }
    @Test void coreProtectCannotFallbackToUnboundedApi() {assertThrows(UnsupportedOperationException.class,()->CoreProtectIntegration.validate(new OldCoreApi()));}
    @Test void actorRevokedWhileProviderReadsCannotReceiveTheData()throws Exception {
        AtomicBoolean active=new AtomicBoolean(true);CoreApi api=new CoreApi();api.beforeReturn=()->active.set(false);Runtime runtime=new Runtime();runtime.items.add(binding("coreprotect",api));try(IntegrationService service=new IntegrationService(runtime,(s,w)->active.get())){assertInstanceOf(SecurityException.class,assertThrows(ExecutionException.class,()->service.query(ADMIN,"coreprotect",lookup()).get()).getCause());}
    }
    @Test void queuedCancellationNeverStartsAnExtraProviderLookup()throws Exception {
        BlockingCore api=new BlockingCore();Runtime runtime=new Runtime();runtime.items.add(binding("coreprotect",api));try(IntegrationService service=new IntegrationService(runtime,(s,w)->true)){var first=service.query(ADMIN,"coreprotect",lookup());var second=service.query(ADMIN,"coreprotect",lookup());assertTrue(api.started.await(2,TimeUnit.SECONDS));var queued=service.query(ADMIN,"coreprotect",lookup());assertTrue(queued.cancel(false));assertFalse(first.cancel(false));api.release.countDown();first.get(2,TimeUnit.SECONDS);second.get(2,TimeUnit.SECONDS);assertEquals(2,api.calls.get());}
    }
    @Test void workerAndQueueCapsRejectExcessAndCloseCancelsQueuedCalls()throws Exception {
        BlockingCore api=new BlockingCore();Runtime runtime=new Runtime();runtime.items.add(binding("coreprotect",api));IntegrationService service=new IntegrationService(runtime,(s,w)->true);List<CompletableFuture<?>> operations=new ArrayList<>();operations.add(service.query(ADMIN,"coreprotect",lookup()));operations.add(service.query(ADMIN,"coreprotect",lookup()));assertTrue(api.started.await(2,TimeUnit.SECONDS));for(int i=0;i<16;i++)operations.add(service.query(ADMIN,"coreprotect",lookup()));var overflow=service.query(ADMIN,"coreprotect",lookup());assertInstanceOf(IllegalStateException.class,assertThrows(ExecutionException.class,overflow::get).getCause());service.close();api.release.countDown();assertTrue(operations.subList(2,18).stream().allMatch(CompletableFuture::isCancelled));assertEquals(2,api.calls.get());assertInstanceOf(IllegalStateException.class,assertThrows(ExecutionException.class,()->service.discover(ADMIN).get()).getCause());
    }
    @Test void aDisabledPluginDisappearsAtTheNextDiscovery()throws Exception {Runtime runtime=new Runtime();runtime.items.add(binding("coreprotect",new CoreApi()));try(IntegrationService service=new IntegrationService(runtime,(s,w)->true)){assertEquals(1,((List<?>)service.discover(ADMIN).get().get("items")).size());runtime.enabled=false;assertEquals(List.of(),service.discover(ADMIN).get().get("items"));}}
    @Test void nonIntegralAndNonFiniteRequestNumbersAreRejected(){assertThrows(IllegalArgumentException.class,()->IntegrationValidation.number(Map.of("x",Double.NaN),"x",0,0,10));assertThrows(IllegalArgumentException.class,()->IntegrationValidation.number(Map.of("x",1.1),"x",0,0,10));assertThrows(IllegalArgumentException.class,()->IntegrationValidation.uuid("1-1-1-1-1"));}

    public static class CoreApi {
        final AtomicInteger calls=new AtomicInteger();int count,offset;List<String> users;List<Integer> actions;Runnable beforeReturn=()->{};
        public boolean isEnabled(){return true;}public int APIVersion(){return 11;}
        public List<String[]> performPartialLookup(int seconds,List<String> users,List<String> exclude,List<Object> blocks,List<Object> excludeBlocks,List<Integer> actions,int radius,Location location,int offset,int count)throws InterruptedException {calls.incrementAndGet();this.users=users;this.actions=actions;this.offset=offset;this.count=count;beforeReturn.run();return List.of(new String[]{"record1"},new String[]{"record2"});}
        public Parsed parseResult(String[] record){return new Parsed();}
    }
    public static final class BlockingCore extends CoreApi {
        final CountDownLatch started=new CountDownLatch(2),release=new CountDownLatch(1);
        @Override public List<String[]> performPartialLookup(int seconds,List<String> users,List<String> exclude,List<Object> blocks,List<Object> excludeBlocks,List<Integer> actions,int radius,Location location,int offset,int count)throws InterruptedException {calls.incrementAndGet();started.countDown();release.await();return Collections.singletonList(new String[]{"record"});}
    }
    public static final class OldCoreApi {public boolean isEnabled(){return true;}public int APIVersion(){return 11;}public Object parseResult(String[] row){return row;}}
    public static final class Parsed {
        public String getPlayer(){return "Notch";}public String worldName(){return "test";}public int getX(){return 1;}public int getY(){return 64;}public int getZ(){return 2;}public String getType(){return "STONE";}public int getTimestamp(){return 123;}public String getActionString(){return "Placement";}public int getActionId(){return 1;}public boolean isRolledBack(){return false;}
    }

    private static Map<String,Object> edit(LuckPermsIntegration api)throws Exception {Map<String,Object> view=api.query(Map.of("action","holder","target","default"));return new HashMap<>(Map.of("action","setNode","holderType","group","target","default","key","example.read","value",false,"expectedRevision",view.get("revision")));}
    private static LuckPermsIntegration lp(LpApi api)throws Exception{return new LuckPermsIntegration(binding("luckperms",api),Permission.class,Inheritance.class);}
    @Test void luckPermsSaveEditsOnlyExactGlobalNodePreservingTemporaryContextualEntries()throws Exception {
        LpApi provider=new LpApi();var global=new Permission("example.read",true,Map.of(),null);var context=new Permission("example.read",true,Map.of("world","nether"),null);var temporary=new Permission("example.read",true,Map.of(),Instant.now().plusSeconds(300));provider.groups.group.nodes.addAll(List.of(global,context,temporary));LuckPermsIntegration api=lp(provider);Map<String,Object> request=edit(api);api.mutate(request,()->{});assertEquals(1,provider.groups.saves);assertEquals(3,provider.groups.group.nodes.size());assertTrue(provider.groups.group.nodes.contains(context));assertTrue(provider.groups.group.nodes.contains(temporary));assertFalse(provider.groups.group.nodes.contains(global));assertTrue(provider.groups.group.nodes.stream().anyMatch(n->n.getKey().equals("example.read")&&!n.getValue()));
    }
    @Test void luckPermsConcurrentChangeRejectsStaleRevisionBeforeEditing()throws Exception {LpApi provider=new LpApi();LuckPermsIntegration api=lp(provider);Map<String,Object> request=edit(api);provider.groups.group.nodes.add(new Permission("changed.node",true,Map.of(),null));assertThrows(IllegalStateException.class,()->api.mutate(request,()->{}));assertEquals(0,provider.groups.saves);assertEquals(1,provider.groups.group.nodes.size());}
    @Test void luckPermsRechecksActorAfterLoadAndBeforeAnyMutation()throws Exception {LpApi provider=new LpApi();LuckPermsIntegration api=lp(provider);Map<String,Object> request=edit(api);assertThrows(SecurityException.class,()->api.mutate(request,()->{throw new SecurityException("revoked");}));assertEquals(0,provider.groups.saves);assertTrue(provider.groups.group.nodes.isEmpty());}
    @Test void luckPermsDirectAndIndirectInheritanceCyclesAreRejected()throws Exception {LpApi provider=new LpApi();LuckPermsIntegration api=lp(provider);Map<String,Object> request=edit(api);request.put("nodeType","inheritance");request.put("key","default");assertThrows(IllegalArgumentException.class,()->api.mutate(request,()->{}));request.put("key","parent");provider.groups.parent=new Holder();provider.groups.parent.nodes.add(new Inheritance("default",true,Map.of(),null));assertThrows(IllegalArgumentException.class,()->api.mutate(request,()->{}));assertEquals(0,provider.groups.saves);}
    @Test void luckPermsOversizedHolderIsReadOnlyRatherThanSavingHiddenNodes()throws Exception {LpApi provider=new LpApi();for(int i=0;i<501;i++)provider.groups.group.nodes.add(new Permission("node."+i,true,Map.of(),null));LuckPermsIntegration api=lp(provider);var view=api.query(Map.of("action","holder","target","default"));assertEquals(true,view.get("truncated"));assertEquals("",view.get("revision"));assertThrows(IllegalStateException.class,()->api.mutate(edit(api),()->{}));assertEquals(0,provider.groups.saves);}
    @Test void luckPermsMetadataCannotBeEditedThroughPermissionRoute()throws Exception {LpApi provider=new LpApi();LuckPermsIntegration api=lp(provider);var request=edit(api);request.put("key","prefix.10.owner");assertThrows(IllegalArgumentException.class,()->api.mutate(request,()->{}));assertEquals(0,provider.groups.saves);}
    @Test void luckPermsLoadedUsersAreExplicitlyLabeledNotAllUsers()throws Exception {var data=lp(new LpApi()).query(Map.of("action","users"));assertEquals("loaded-users",data.get("scope"));}

    public static final class LpApi {final Groups groups=new Groups();public Groups getGroupManager(){return groups;}public Users getUserManager(){return new Users();}}
    public static final class Groups {
        final Holder group=new Holder();Holder parent;int saves;
        public CompletableFuture<Void> loadAllGroups(){return CompletableFuture.completedFuture(null);}public Set<Holder> getLoadedGroups(){return Set.of(group);}public Holder getGroup(String name){return name.equals("default")?group:name.equals("parent")?parent:null;}public CompletableFuture<Void> saveGroup(Holder holder){saves++;return CompletableFuture.completedFuture(null);}
    }
    public static final class Users {public Set<Holder> getLoadedUsers(){return Set.of();}}
    public static final class Holder {final List<Permission> nodes=new ArrayList<>();public String getFriendlyName(){return "default";}public String getName(){return "default";}public Data data(){return new Data(nodes);}}
    public static final class Data {final List<Permission> nodes;Data(List<Permission> nodes){this.nodes=nodes;}public Collection<Permission> toCollection(){return List.copyOf(nodes);}public void remove(Permission node){nodes.remove(node);}public void add(Permission node){nodes.add(node);}}
    public static class Permission {
        final String key;final boolean value;final Map<String,String> contexts;final Instant expiry;
        Permission(String key,boolean value,Map<String,String> contexts,Instant expiry){this.key=key;this.value=value;this.contexts=contexts;this.expiry=expiry;}
        public static Builder builder(String key){return new Builder(key,false);}public String getKey(){return key;}public boolean getValue(){return value;}public Instant getExpiry(){return expiry;}public Contexts getContexts(){return new Contexts(contexts);}
    }
    public static final class Inheritance extends Permission {Inheritance(String key,boolean value,Map<String,String> contexts,Instant expiry){super("group."+key,value,contexts,expiry);}public static Builder builder(String key){return new Builder(key,true);}}
    public static final class Builder {final String key;final boolean inheritance;boolean value=true;Instant expiry;Map<String,String> contexts=new HashMap<>();Builder(String key,boolean inheritance){this.key=key;this.inheritance=inheritance;}public Builder value(boolean value){this.value=value;return this;}public Builder withContext(String key,String value){contexts.put(key,value);return this;}public Builder expiry(Instant expiry){this.expiry=expiry;return this;}public Permission build(){return inheritance?new Inheritance(key,value,Map.copyOf(contexts),expiry):new Permission(key,value,Map.copyOf(contexts),expiry);}}
    public static final class Contexts {final Map<String,String> contexts;Contexts(Map<String,String> contexts){this.contexts=contexts;}public Set<Context> toSet(){Set<Context> out=new HashSet<>();contexts.forEach((key,value)->out.add(new Context(key,value)));return out;}}
    public record Context(String key,String value){public String getKey(){return key;}public String getValue(){return value;}}
}
