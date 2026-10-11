package dev.mcc.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class UserManagementTest {
    @TempDir Path directory;
    private static final String OWNER_PASSWORD="Owner-test-only-password!";
    private Auth freshOwner(Database db)throws Exception{Auth auth=new Auth(db,directory,true,true);AuthFixture.owner(auth,"admin",OWNER_PASSWORD);return auth;}
    @Test void emptyLocalAndRemoteInstallHaveNoPublicDefaults()throws Exception{
        try(Database db=new Database(directory.resolve("database.db"))){
            Auth local=new Auth(db,directory,true,true),remote=new Auth(db,directory,true,false);
            assertTrue(local.setupRequired());assertTrue(remote.setupRequired());assertEquals(0,db.userCount().get());assertFalse(Files.exists(directory.resolve(".setup-token")));
            assertThrows(SecurityException.class,()->local.login("admin","admin"));assertFalse((boolean)local.status(null).get("defaultAdminAvailable"));
        }
    }
    @Test void existingStrongAdminIsNeverRenamedOrReset()throws Exception{
        Path file=directory.resolve("database.db");String before;
        try(Database db=new Database(file)){freshOwner(db);before=(String)db.user("admin").get().get("hash");db.document("auth-default-account","admin",Map.of("name","admin")).get();}
        try(Database db=new Database(file)){Auth restarted=new Auth(db,directory,true,true);assertFalse(restarted.setupRequired());assertEquals(before,db.user("admin").get().get("hash"));assertFalse(restarted.login("admin",OWNER_PASSWORD,false).defaultPassword());assertEquals(1,db.userCount().get());}
    }
    @Test void deletionRevokesAllSessionsAndPreservesAuditHistory()throws Exception{
        try(Database db=new Database(directory.resolve("database.db"))){
            Auth auth=freshOwner(db);Auth.Session owner=auth.login("admin",OWNER_PASSWORD);auth.createUser("viewer","Viewer-test-only-password!","Viewer");
            Auth.Session one=auth.login("viewer","Viewer-test-only-password!"),two=auth.login("viewer","Viewer-test-only-password!");db.audit("viewer","fixture.read","ok",Map.of()).get();
            assertThrows(SecurityException.class,()->auth.deleteUser(one,"admin"));assertThrows(IllegalArgumentException.class,()->auth.deleteUser(owner,"admin"));assertThrows(ExecutionException.class,()->db.deleteUser("admin").get());
            auth.deleteUser(owner,"viewer");assertNull(auth.session(one.id()));assertNull(auth.session(two.id()));assertThrows(SecurityException.class,()->auth.login("viewer","Viewer-test-only-password!"));
            assertTrue(db.auditHistory().get().stream().anyMatch(a->a.get("actor").equals("viewer")));assertThrows(IllegalArgumentException.class,()->auth.deleteUser(owner,"viewer"));assertEquals(1,db.userCount().get());
        }
    }
    @Test void concurrentFullAdministratorDeletionCannotRemoveBoth()throws Exception{
        try(Database db=new Database(directory.resolve("database.db"))){Auth auth=freshOwner(db);auth.createUser("second","Second-test-only-password!","Owner");CompletableFuture<Boolean> a=db.deleteUser("admin"),b=db.deleteUser("second");assertTrue(a.get());assertThrows(ExecutionException.class,b::get);assertEquals(List.of(Map.of("name","second","role","Owner")),db.users().get());}
    }
    @Test void deletedAdministratorCannotUseStaleSessionToCreateAnOwner()throws Exception{
        try(Database db=new Database(directory.resolve("database.db"))){Auth auth=freshOwner(db);Auth.Session a=auth.login("admin",OWNER_PASSWORD);auth.createUser(a,"second","Second-test-only-password!","Owner");Auth.Session stale=auth.login("second","Second-test-only-password!");auth.deleteUser(a,"second");assertNull(auth.session(stale.id()));assertThrows(SecurityException.class,()->auth.createUser(stale,"replacement","Replacement-test-only-password!","Owner"));assertNull(db.user("replacement").get());assertEquals(1,db.userCount().get());}
    }
    @Test void renamedLegacyAccountKeepsIdentityAndRequiresPasswordChange()throws Exception{
        Path file=directory.resolve("database.db");
        try(Database db=new Database(file)){db.bootstrapAdmin(Auth.hash("admin")).get();String hash=(String)db.user("admin").get().get("hash");db.changeUser("admin",hash,"local-owner",hash,false).get();}
        try(Database db=new Database(file)){Auth restarted=new Auth(db,directory,true,true);assertFalse(restarted.setupRequired());assertNull(db.user("admin").get());Auth.Session legacy=restarted.login("local-owner","admin",true);assertTrue(legacy.defaultPassword());assertEquals(true,restarted.status(legacy).get("mustChangePassword"));assertThrows(SecurityException.class,()->restarted.login("local-owner","admin",false));Auth.Session changed=restarted.changeAccount(legacy,"admin","local-owner","Strong-test-only-password!");assertFalse(changed.defaultPassword());assertEquals("Owner",restarted.login("local-owner","Strong-test-only-password!",false).role());}
    }
    @Test void deletingLegacyAdminPreservesSecondOwnerAndNeverRecreatesDefault()throws Exception{
        Path file=directory.resolve("database.db");
        try(Database db=new Database(file)){db.bootstrapAdmin(Auth.hash("admin")).get();db.createUser("second-owner",Auth.hash("Second-owner-test-only-password!"),"Owner",false).get();Auth auth=new Auth(db,directory,true,true);Auth.Session second=auth.login("second-owner","Second-owner-test-only-password!",false);auth.deleteUser(second,"admin");assertNull(db.user("admin").get());assertTrue(db.documents("auth-default-account").get().isEmpty());}
        try(Database db=new Database(file)){Auth restarted=new Auth(db,directory,true,true);assertFalse(restarted.setupRequired());assertEquals(List.of(Map.of("name","second-owner","role","Owner")),db.users().get());assertThrows(SecurityException.class,()->restarted.login("admin","admin",true));}
    }
}