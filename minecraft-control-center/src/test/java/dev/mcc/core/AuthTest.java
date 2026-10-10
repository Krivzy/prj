package dev.mcc.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class AuthTest {
    @TempDir Path directory;

    @Test void hashesAreSaltedAndPasswordAndFormatTamperingFail() {
        String password = "Example-for-test-only-25!";
        String first = Auth.hash(password);
        String second = Auth.hash(password);
        assertNotEquals(first, second, "Argon2 salts must be unique");
        assertTrue(Auth.verify(password, first));
        assertFalse(Auth.verify("incorrect-password", first));
        assertFalse(Auth.verify(password, first.replace("v=19", "v=16")));
        assertFalse(Auth.verify(password, first.replace("m=65536", "m=1")));
        assertFalse(Auth.verify(password, "not-a-hash"));
        assertFalse(Auth.verify(password, "argon2id$v=19$m=65536,t=3,p=1$?$?"));
    }

    @Test void ownerSetupIsLocalSingleUseAndSessionLogoutRevokesAccess() throws Exception {
        try (Database db = new Database(directory.resolve("database.db"))) {
            Auth auth = new Auth(db, directory);
            String token = Files.readString(directory.resolve(".setup-token"));
            assertTrue(auth.setupRequired());
            assertThrows(SecurityException.class, () -> auth.setup(token, "test-owner", "A-long-random-test-pass!", false));
            assertThrows(SecurityException.class, () -> auth.setup("wrong-token", "test-owner", "A-long-random-test-pass!", true));
            Auth.Session first = auth.setup(token, "test-owner", "A-long-random-test-pass!", true);
            assertEquals("Owner", first.role());
            assertFalse(auth.setupRequired());
            assertFalse(Files.exists(directory.resolve(".setup-token")));
            assertThrows(SecurityException.class, () -> auth.setup(token, "second-owner", "A-long-random-test-pass!", true));
            assertSame(first, auth.session(first.id()));
            auth.logout(first);
            assertNull(auth.session(first.id()));
            assertThrows(SecurityException.class, () -> auth.login("test-owner", "wrong-password"));
            Auth.Session session = auth.login("test-owner", "A-long-random-test-pass!");
            assertNotEquals(first.id(), session.id());
            assertNotEquals(first.csrf(), session.csrf());
            assertNull(auth.session(null));
            assertNull(auth.session("unknown-session"));
        }
    }

    @Test void ownerPersistsAcrossRestartsAndKnownInvalidRolesAreRejected() throws Exception {
        Path file = directory.resolve("database.db");
        try (Database db = new Database(file)) {
            Auth auth = new Auth(db, directory);
            auth.setup(Files.readString(directory.resolve(".setup-token")), "owner-restore", "Persistent-test-only-pass!", true);
            auth.createUser("viewer", "Viewer-test-only-password!", "Viewer");
            assertThrows(IllegalArgumentException.class, () -> auth.createUser("invalid", "Long-test-only-password!", "Superuser"));
        }
        try (Database db = new Database(file)) {
            Auth restarted = new Auth(db, directory);
            assertFalse(restarted.setupRequired());
            assertEquals("Owner", restarted.login("owner-restore", "Persistent-test-only-pass!").role());
            assertEquals("Viewer", restarted.login("viewer", "Viewer-test-only-password!").role());
            assertEquals(2, db.users().get(5, TimeUnit.SECONDS).size());
        }
    }

    @Test void invalidCredentialsCannotCreateUsers() {
        assertThrows(IllegalArgumentException.class, () -> Auth.validateCredentials("../path", "Long-enough-test-password"));
        assertThrows(IllegalArgumentException.class, () -> Auth.validateCredentials("normal-user", "short"));
        assertThrows(IllegalArgumentException.class, () -> Auth.validateCredentials("x".repeat(33), "Long-enough-test-password"));
        assertThrows(IllegalArgumentException.class, () -> Auth.validateCredentials("normal-user", "x".repeat(129)));
        assertDoesNotThrow(() -> Auth.validateCredentials("safe_user-25", "Long-enough-test-password"));
    }

    @Test void legacyBootstrapOptionStillRequiresPrivateSetupAndAccountChangesRotateSessions() throws Exception {
        Path file=directory.resolve("database.db");
        String originalPassword="Original-test-only-password!";
        try(Database db=new Database(file)) {
            Auth auth=new Auth(db,directory,true,true);
            assertTrue(auth.setupRequired());assertEquals(0,db.userCount().get());
            String token=Files.readString(directory.resolve(".setup-token"));
            auth.setup(token,"test-owner",originalPassword,true);
            Auth.Session first=auth.login("test-owner",originalPassword),second=auth.login("test-owner",originalPassword);
            assertEquals("Owner",first.role());
            assertThrows(SecurityException.class,()->auth.changeAccount(first,"incorrect","renamed-owner","Changed-test-only-password!"));
            assertThrows(IllegalArgumentException.class,()->auth.changeAccount(first,originalPassword,"renamed-owner","short"));
            Auth.Session rotated=auth.changeAccount(first,originalPassword,"renamed-owner","Changed-test-only-password!");
            assertEquals("Owner",rotated.role());assertNotEquals(first.id(),rotated.id());assertNotEquals(first.csrf(),rotated.csrf());
            assertNull(auth.session(first.id()));assertNull(auth.session(second.id()));assertSame(rotated,auth.session(rotated.id()));
            assertThrows(SecurityException.class,()->auth.login("test-owner",originalPassword));
            assertThrows(SecurityException.class,()->auth.login("renamed-owner",originalPassword));
            assertThrows(SecurityException.class,()->auth.changeAccount(first,"Changed-test-only-password!","another-owner",null));
        }
        try(Database db=new Database(file)) {
            Auth restarted=new Auth(db,directory,true,true);
            assertFalse(restarted.setupRequired());assertEquals(1,db.userCount().get());assertNull(db.user("test-owner").get());
            assertEquals("Owner",restarted.login("renamed-owner","Changed-test-only-password!").role());
        }
    }

    @Test void freshDefaultConfigurationDoesNotCreateAnyBuiltInUser() throws Exception {
        try(Database db=new Database(directory.resolve("database.db"))) {
            Auth auth=new Auth(db,directory,false,true);
            assertTrue(auth.setupRequired());assertEquals(0,db.userCount().get());
            assertTrue(Files.exists(directory.resolve(".setup-token")));
        }
    }

    @Test void bootstrapPreservesExistingAccountsAndNeverRunsOnLan() throws Exception {
        try(Database db=new Database(directory.resolve("database.db"))) {
            Auth original=new Auth(db,directory);original.setup(Files.readString(directory.resolve(".setup-token")),"existing-owner","Existing-owner-test-password!",true);
            original.createUser("admin","Existing-admin-test-password!","Viewer");
            Auth enabled=new Auth(db,directory,true,true);
            assertEquals("Owner",enabled.login("existing-owner","Existing-owner-test-password!").role());
            assertEquals("Viewer",enabled.login("admin","Existing-admin-test-password!").role());
            assertThrows(SecurityException.class,()->enabled.login("admin","admin"));
            assertThrows(SecurityException.class,()->new Auth(db,directory,true,false));
            assertEquals(2,db.userCount().get());
        }
    }

    @Test void accountRenamePreservesPasswordRoleAndCollisionCannotModifyEitherAccount() throws Exception {
        try(Database db=new Database(directory.resolve("database.db"))) {
            Auth auth=new Auth(db,directory);auth.setup(Files.readString(directory.resolve(".setup-token")),"owner","Owner-test-password-only!",true);
            auth.createUser("viewer","Viewer-test-password-only!","Viewer");
            Auth.Session viewer=auth.login("viewer","Viewer-test-password-only!");
            assertThrows(IllegalArgumentException.class,()->auth.changeAccount(viewer,"Viewer-test-password-only!","owner",null));
            assertSame(viewer,auth.session(viewer.id()));
            Auth.Session renamed=auth.changeAccount(viewer,"Viewer-test-password-only!","viewer-renamed",null);
            assertEquals("Viewer",renamed.role());assertNull(auth.session(viewer.id()));
            assertEquals("Viewer",auth.login("viewer-renamed","Viewer-test-password-only!").role());
            assertEquals("Owner",auth.login("owner","Owner-test-password-only!").role());
        }
    }
}
