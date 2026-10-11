package dev.mcc.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LicenseServiceTest {
    @TempDir Path directory;
    @Test void trialBlocksEveryPaidRouteIncludingUnmappedUploadsAndActions()throws Exception{
        try(Database db=new Database(directory.resolve("database.db"))){LicenseService license=new LicenseService(db,false);assertFalse(license.fullAccess());for(String route:List.of("/api/players","/api/worlds","/api/plugins/stage","/api/plugins/staged","/api/plugins/manage","/api/plugins/catalog/install","/api/ore-monitor","/api/punishments","/api/punishments/provider","/api/integrations/luckperms/query","/api/entities","/api/map/images/tile","/api/automations","/api/events","/api/metrics","/api/audit","/api/server/unknown-paid-feature"))assertThrows(SecurityException.class,()->license.requireRoute(route),route);
            for(String route:List.of("/api/state","/api/console","/api/chat","/api/settings","/api/license","/api/server/profiles","/api/server/profiles/apply","/api/server/core","/api/server/core/stage","/api/server/restart","/api/auth/account","/api/users","/api/backups"))assertDoesNotThrow(()->license.requireRoute(route),route);
            for(String action:List.of("world.time","player.give","inventory.set","player.ban","mob.spawn","event.start","unknown.operation"))assertThrows(SecurityException.class,()->license.requireAction(action),action);for(String action:List.of("console.command","chat.broadcast","server.settings","event.stop"))assertDoesNotThrow(()->license.requireAction(action));
            for(String key:ModulePolicy.KEYS)assertEquals(Set.of("console","chat","metrics").contains(key),license.moduleAllowed(key),key);assertFalse(license.topicAllowed("world"));assertFalse(license.topicAllowed("punishments"));assertTrue(license.topicAllowed("state"));assertEquals(false,license.status().get("keyActivationAvailable"));
        }
    }
    @Test void temporaryModePersistsButCannotBeActivatedByArbitraryKey()throws Exception{
        Path file=directory.resolve("database.db");try(Database db=new Database(file)){LicenseService license=new LicenseService(db,false);assertThrows(IllegalArgumentException.class,()->license.change("licensed",""));assertThrows(IllegalArgumentException.class,()->license.change("temporary-full","fake-key"));assertFalse(license.fullAccess());license.change("temporary-full","");assertTrue(license.fullAccess());for(String key:ModulePolicy.KEYS)assertTrue(license.moduleAllowed(key));assertDoesNotThrow(()->license.requireRoute("/api/plugins/stage"));}
        try(Database db=new Database(file)){LicenseService restored=new LicenseService(db,false);assertTrue(restored.fullAccess());restored.change("trial","");assertFalse(restored.fullAccess());}
    }
    @Test void existingInstallKeepsAccessAndCorruptedEntitlementsFailClosed()throws Exception{
        try(Database db=new Database(directory.resolve("database.db"))){LicenseService migrated=new LicenseService(db,true);assertTrue(migrated.fullAccess());assertEquals("existing-installation",migrated.status().get("source"));db.document("license","state",Map.of("mode","licensed")).get();assertThrows(IllegalArgumentException.class,()->new LicenseService(db,true));}
    }
}
