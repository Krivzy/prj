package dev.mcc.integration;

import dev.mcc.core.Auth;
import org.bukkit.Location;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class PunishmentProvidersTest {
    private static final Auth.Session OWNER=new Auth.Session("test-only","owner","Owner","csrf",Long.MAX_VALUE,false);
    private static final UUID PLAYER=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final ClassLoader LOADER=PunishmentProvidersTest.class.getClassLoader();
    private static final class Runtime implements IntegrationService.Runtime {
        final List<IntegrationService.Binding> items=new ArrayList<>();
        public List<IntegrationService.Binding> discover(){return List.copyOf(items);}
        public boolean active(IntegrationService.Binding binding){return items.contains(binding);}
        public Location location(String world,int x,int y,int z){throw new UnsupportedOperationException();}
        public boolean playerMatches(String name,UUID uuid){return name.equals("KnownPlayer")&&uuid.equals(PLAYER);}
    }
    @BeforeEach void reset(){net.hnt8.advancedban.manager.PunishmentManager.get().reset();}
    private static IntegrationService.Binding abz(){return IntegrationService.punishmentBinding("AdvancedBanZ","4.0.1",new Object(),LOADER);}
    private static Map<String,Object> player(){return Map.of("player","KnownPlayer","uuid",PLAYER.toString());}
    private static Map<String,Object> issue(){Map<String,Object> body=new HashMap<>(player());body.putAll(Map.of("action","issue","type","mute","seconds",60,"reason","Contract test","confirmed",true));return body;}

    @Test void enabledAdvancedBanZAtStartupOwnsNativeModeration(){
        PunishmentProviders.Presence presence=new PunishmentProviders.Presence();presence.changed("AdvancedBanZ",true);assertTrue(presence.getAsBoolean());presence.changed("AdvancedBanZ",false);assertFalse(presence.getAsBoolean());
    }
    @Test void disablingOneProviderNeverUnblocksNativeWhileAnotherIsEnabled(){
        PunishmentProviders.Presence presence=new PunishmentProviders.Presence();presence.changed("AdvancedBan",true);presence.changed("AdvancedBanZ",true);presence.changed("AdvancedBan",false);assertTrue(presence.getAsBoolean());presence.changed("LiteBans",true);presence.changed("AdvancedBanZ",false);assertTrue(presence.getAsBoolean());presence.changed("LiteBans",false);assertFalse(presence.getAsBoolean());
    }
    @Test void exactKnownProvidersBlockNativeButUnrelatedPluginsDoNot(){
        for(String name:List.of("AdvancedBan","AdvancedBanZ","LiteBans","BanManager","LibertyBans")){PunishmentProviders.Presence presence=new PunishmentProviders.Presence();presence.changed(name,true);assertTrue(presence.getAsBoolean(),name);presence.changed("LuckPerms",false);assertTrue(presence.getAsBoolean(),name);}
        PunishmentProviders.Presence presence=new PunishmentProviders.Presence();presence.changed("CustomBanName",true);assertFalse(presence.getAsBoolean());presence.close();presence.changed("AdvancedBan",false);assertTrue(presence.getAsBoolean());
    }
    @Test void forkDiscoveryUsesRelocatedNamespaceAndActualMetadataWithoutReadingRecords(){
        var binding=abz();assertEquals("advancedban",binding.id());assertEquals("AdvancedBanZ",binding.title());assertEquals("4.0.1",binding.version());assertEquals("",binding.unavailable());assertSame(net.hnt8.advancedban.manager.PunishmentManager.get(),binding.api());assertEquals(0,net.hnt8.advancedban.manager.PunishmentManager.get().reads);
        assertEquals("me.leoko.advancedban",PunishmentProviders.namespace("AdvancedBan"));assertEquals("net.hnt8.advancedban",PunishmentProviders.namespace("AdvancedBanZ"));assertThrows(UnsupportedOperationException.class,()->PunishmentProviders.namespace("SomeAdvancedBanFork"));
    }
    @Test void missingOrLinkageBrokenForkApiNeverFallsBackToNative()throws Exception {
        for(ClassLoader loader:List.of(new ClassLoader(null){},new ClassLoader(null){@Override protected Class<?> loadClass(String name,boolean resolve){throw new NoClassDefFoundError("isolated broken API");}})){
            Runtime runtime=new Runtime();var binding=IntegrationService.punishmentBinding("AdvancedBanZ","unsupported",this,loader);runtime.items.add(binding);assertFalse(binding.unavailable().isEmpty());
            try(IntegrationService service=new IntegrationService(runtime,(s,w)->true)){assertEquals("unavailable",service.punishmentProvider().get(2,TimeUnit.SECONDS));assertInstanceOf(UnsupportedOperationException.class,assertThrows(ExecutionException.class,()->service.query(OWNER,"advancedban",player()).get()).getCause());}
        }
    }
    @Test void originalAliasNeverBorrowsRelocatedForkClassesAndUnknownNamespacesAreRejected()throws Exception {
        Runtime runtime=new Runtime();var original=IntegrationService.punishmentBinding("AdvancedBan","2",this,LOADER);runtime.items.add(original);
        assertFalse(original.unavailable().isEmpty());assertNull(original.api());
        try(IntegrationService service=new IntegrationService(runtime,(s,w)->true)){assertEquals("unavailable",service.punishmentProvider().get());}
        assertThrows(UnsupportedOperationException.class,()->AdvancedBanIntegration.validateTypes(net.hnt8.advancedban.manager.PunishmentManager.class,LOADER,"unreviewed.fork"));
    }
    @Test void unsupportedMajorProvidersExposeEmptyCapabilitiesAndRejectReadsAndWrites()throws Exception {
        for(String name:List.of("LiteBans","BanManager","LibertyBans")){
            Runtime runtime=new Runtime();var binding=IntegrationService.punishmentBinding(name,"test",this,new ClassLoader(null){});runtime.items.add(binding);
            try(IntegrationService service=new IntegrationService(runtime,(s,w)->true)){
                Map<String,Object> discovery=service.discover(OWNER).get(2,TimeUnit.SECONDS);assertEquals("unavailable",discovery.get("punishmentProvider"));Map<?,?> item=(Map<?,?>)((List<?>)discovery.get("items")).getFirst();assertEquals(false,item.get("available"));assertEquals(List.of(),item.get("capabilities"));
                assertInstanceOf(UnsupportedOperationException.class,assertThrows(ExecutionException.class,()->service.query(OWNER,binding.id(),player()).get()).getCause());assertInstanceOf(UnsupportedOperationException.class,assertThrows(ExecutionException.class,()->service.mutate(OWNER,binding.id(),issue()).get()).getCause());
            }
        }
    }
    @Test void twoAliasesAreUnavailableWithoutDuplicateUiIdsOrArbitraryAdapterSelection()throws Exception {
        Runtime runtime=new Runtime();runtime.items.add(abz());runtime.items.add(new IntegrationService.Binding("advancedban","AdvancedBan","2",new Object(),LOADER,this,""));
        try(IntegrationService service=new IntegrationService(runtime,(s,w)->true)){
            Map<String,Object> discovery=service.discover(OWNER).get(2,TimeUnit.SECONDS);assertEquals("unavailable",discovery.get("punishmentProvider"));List<?> items=(List<?>)discovery.get("items");assertEquals(1,items.size());assertEquals("AdvancedBanZ / AdvancedBan",((Map<?,?>)items.getFirst()).get("title"));assertEquals(List.of(),((Map<?,?>)items.getFirst()).get("capabilities"));
            assertInstanceOf(UnsupportedOperationException.class,assertThrows(ExecutionException.class,()->service.query(OWNER,"advancedban",player()).get()).getCause());assertInstanceOf(UnsupportedOperationException.class,assertThrows(ExecutionException.class,()->service.mutate(OWNER,"advancedban",issue()).get()).getCause());assertEquals(0,net.hnt8.advancedban.manager.PunishmentManager.get().reads);assertEquals(0,net.hnt8.advancedban.manager.PunishmentManager.get().creates);
        }
    }
    @Test void supportedForkPlusOtherProviderRemainsUnavailableUntilOnlyOneRemains()throws Exception {
        Runtime runtime=new Runtime();runtime.items.add(abz());var other=IntegrationService.punishmentBinding("LibertyBans","test",this,LOADER);runtime.items.add(other);
        try(IntegrationService service=new IntegrationService(runtime,(s,w)->true)){assertEquals("unavailable",service.punishmentProvider().get());assertInstanceOf(UnsupportedOperationException.class,assertThrows(ExecutionException.class,()->service.mutate(OWNER,"advancedban",issue()).get()).getCause());runtime.items.remove(other);assertEquals("advancedban",service.punishmentProvider().get());}
    }
    @Test void relocatedAdapterQueriesIssuesAndRevokesOnlyItsOwnPublicContract()throws Exception {
        Runtime runtime=new Runtime();runtime.items.add(abz());
        try(IntegrationService service=new IntegrationService(runtime,(s,w)->true)){
            assertEquals("advancedban",service.punishmentProvider().get());Map<String,Object> saved=service.mutate(OWNER,"advancedban",issue()).get();Map<?,?> row=(Map<?,?>)saved.get("punishment");assertEquals("TEMP_MUTE",row.get("type"));assertEquals(1_060_000L,row.get("end"));assertEquals("MCC:owner",row.get("operator"));assertEquals(1,((List<?>)service.query(OWNER,"advancedban",player()).get().get("items")).size());
            Map<String,Object> revoke=new HashMap<>(player());revoke.putAll(Map.of("confirmed",true,"action","revoke","punishmentId",row.get("id")));assertEquals(true,service.mutate(OWNER,"advancedban",revoke).get().get("ok"));assertEquals(1,net.hnt8.advancedban.manager.PunishmentManager.get().creates);assertEquals(1,net.hnt8.advancedban.manager.PunishmentManager.get().deletes);
        }
    }
}
