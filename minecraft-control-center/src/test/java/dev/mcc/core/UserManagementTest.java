package dev.mcc.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class UserManagementTest {
    @TempDir Path directory;
    @Test void emptyLocalInstallCreatesOnlyAdminAndRejectsRemoteDefaultLogin()throws Exception {
        try(Database db=new Database(directory.resolve("database.db"))){
            Auth auth=new Auth(db,directory,true,true);
            assertFalse(auth.setupRequired());assertFalse(Files.exists(directory.resolve(".setup-token")));
            assertEquals(List.of(Map.of("name","admin","role","Owner")),db.users().get());
            Auth.Session admin=auth.login("admin","admin",true);
            assertTrue(admin.defaultPassword());assertEquals(true,auth.status(admin).get("defaultPassword"));
            assertThrows(SecurityException.class,()->auth.login("admin","admin",false));
            assertThrows(SecurityException.class,()->auth.login("owner","admin"));
            Auth.Session renamed=auth.changeAccount(admin,"admin","administrator",null);
            assertTrue(renamed.defaultPassword());assertThrows(SecurityException.class,()->auth.login("administrator","admin",false));
            Auth.Session changed=auth.changeAccount(renamed,"admin","administrator","Strong-test-only-password!");
            assertFalse(changed.defaultPassword());assertNull(auth.session(admin.id()));
            assertEquals("Owner",auth.login("administrator","Strong-test-only-password!",false).role());
        }
        try(Database db=new Database(directory.resolve("database.db"))){
            Auth auth=new Auth(db,directory,true,true);assertEquals(1,db.userCount().get());
            assertNull(db.user("admin").get());assertFalse(auth.login("administrator","Strong-test-only-password!",false).defaultPassword());
        }
    }
    @Test void remoteEmptyInstallUsesPrivateSetupAndExistingUsersAreNeverReset()throws Exception {
        try(Database db=new Database(directory.resolve("database.db"))){
            Auth auth=new Auth(db,directory,true,false);assertTrue(auth.setupRequired());assertEquals(0,db.userCount().get());
            auth.setup(Files.readString(directory.resolve(".setup-token")),"existing","Existing-test-only-password!",true);
            Auth restarted=new Auth(db,directory,true,true);
            assertEquals(1,db.userCount().get());assertNull(db.user("admin").get());
            assertEquals("Owner",restarted.login("existing","Existing-test-only-password!",false).role());
        }
    }
    @Test void deletionRevokesAllSessionsAndPreservesAuditHistory()throws Exception {
        try(Database db=new Database(directory.resolve("database.db"))){
            Auth auth=new Auth(db,directory,true,true);Auth.Session owner=auth.login("admin","admin");
            auth.createUser("viewer","Viewer-test-only-password!","Viewer");
            Auth.Session one=auth.login("viewer","Viewer-test-only-password!"),two=auth.login("viewer","Viewer-test-only-password!");
            db.audit("viewer","fixture.read","ok",Map.of()).get();
            assertThrows(SecurityException.class,()->auth.deleteUser(one,"admin"));
            assertThrows(IllegalArgumentException.class,()->auth.deleteUser(owner,"admin"));
            assertThrows(ExecutionException.class,()->db.deleteUser("admin").get());
            auth.deleteUser(owner,"viewer");assertNull(auth.session(one.id()));assertNull(auth.session(two.id()));
            assertThrows(SecurityException.class,()->auth.login("viewer","Viewer-test-only-password!"));
            assertTrue(db.auditHistory().get().stream().anyMatch(a->a.get("actor").equals("viewer")));
            assertThrows(IllegalArgumentException.class,()->auth.deleteUser(owner,"viewer"));
            assertEquals(1,db.userCount().get());
        }
    }
    @Test void concurrentFullAdministratorDeletionCannotRemoveBoth()throws Exception {
        try(Database db=new Database(directory.resolve("database.db"))){
            Auth auth=new Auth(db,directory,true,true);auth.createUser("second","Second-test-only-password!","Owner");
            CompletableFuture<Boolean> a=db.deleteUser("admin"),b=db.deleteUser("second");
            assertTrue(a.get());assertThrows(ExecutionException.class,b::get);
            assertEquals(List.of(Map.of("name","second","role","Owner")),db.users().get());
        }
    }
    @Test void deletedAdministratorCannotUseStaleSessionToCreateAnOwner()throws Exception {
        try(Database db=new Database(directory.resolve("database.db"))){
            Auth auth=new Auth(db,directory,true,true);Auth.Session a=auth.login("admin","admin");
            auth.createUser(a,"second","Second-test-only-password!","Owner");Auth.Session stale=auth.login("second","Second-test-only-password!");
            auth.deleteUser(a,"second");assertNull(auth.session(stale.id()));
            assertThrows(SecurityException.class,()->auth.createUser(stale,"replacement","Replacement-test-only-password!","Owner"));
            assertNull(db.user("replacement").get());assertEquals(1,db.userCount().get());
        }
    }
    @Test void renamedDefaultAccountRetainsRemoteRestrictionAcrossRestart()throws Exception {
        Path file=directory.resolve("database.db");
        try(Database db=new Database(file)){
            Auth auth=new Auth(db,directory,true,true);
            Auth.Session original=auth.login("admin","admin",true);
            Auth.Session renamed=auth.changeAccount(original,"admin","local-owner",null);
            assertTrue(renamed.defaultPassword());assertNull(auth.session(original.id()));
            assertNull(db.user("admin").get());assertEquals(1,db.userCount().get());
        }
        try(Database db=new Database(file)){
            Auth restarted=new Auth(db,directory,true,true);
            assertFalse(restarted.setupRequired());assertNull(db.user("admin").get());
            assertEquals(false,restarted.status(null).get("defaultAdminAvailable"));
            assertTrue(restarted.login("local-owner","admin",true).defaultPassword());
            assertThrows(SecurityException.class,()->restarted.login("local-owner","admin",false));
            assertEquals(List.of(Map.of("name","local-owner")),db.documents("auth-default-account").get());
        }
    }
    @Test void deletingBootstrapAdminPreservesMarkerAndSecondOwnerAcrossRestart()throws Exception {
        Path file=directory.resolve("database.db");
        try(Database db=new Database(file)){
            Auth auth=new Auth(db,directory,true,true);Auth.Session bootstrap=auth.login("admin","admin",true);
            auth.createUser(bootstrap,"second-owner","Second-owner-test-only-password!","Owner");
            Auth.Session second=auth.login("second-owner","Second-owner-test-only-password!",false);
            auth.deleteUser(second,"admin");assertNull(auth.session(bootstrap.id()));
            assertNull(db.user("admin").get());assertTrue(db.documents("auth-default-account").get().isEmpty());
            assertTrue(db.documents("migrations").get().stream().anyMatch(row->Boolean.TRUE.equals(row.get("created"))));
        }
        try(Database db=new Database(file)){
            Auth restarted=new Auth(db,directory,true,true);
            assertFalse(restarted.setupRequired());assertFalse(Files.exists(directory.resolve(".setup-token")));
            assertEquals(List.of(Map.of("name","second-owner","role","Owner")),db.users().get());
            assertEquals(false,restarted.status(null).get("defaultAdminAvailable"));
            assertFalse(restarted.login("second-owner","Second-owner-test-only-password!",false).defaultPassword());
            assertThrows(SecurityException.class,()->restarted.login("admin","admin",true));
            assertTrue(db.documents("auth-default-account").get().isEmpty());
        }
    }
}
