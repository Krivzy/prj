package dev.mcc.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseTest {
    @TempDir Path directory;

    @Test void concurrentInitialOwnersCannotBypassFirstAccountConstraint() throws Exception {
        try (Database db = new Database(directory.resolve("database.db"))) {
            CompletableFuture<Void> first = db.createUser("first", "test-hash", "Owner", true);
            CompletableFuture<Void> second = db.createUser("second", "test-hash", "Owner", true);
            first.get(5, TimeUnit.SECONDS);
            assertThrows(ExecutionException.class, () -> second.get(5, TimeUnit.SECONDS));
            assertEquals(1, db.userCount().get(5, TimeUnit.SECONDS));
            assertNull(db.user("second").get(5, TimeUnit.SECONDS));
        }
    }

    @Test void preparedStatementsKeepUntrustedNamesAndDocumentsLiteral() throws Exception {
        try (Database db = new Database(directory.resolve("database.db"))) {
            String untrusted = "name' OR 1=1 --";
            db.createUser(untrusted, "hash", "Viewer", false).get(5, TimeUnit.SECONDS);
            assertNull(db.user("another' OR 1=1 --").get(5, TimeUnit.SECONDS));
            assertEquals(untrusted, db.user(untrusted).get(5, TimeUnit.SECONDS).get("name"));
            db.document("tests", "id' DROP TABLE users", Map.of("id", "id' DROP TABLE users", "value", "literal")).get(5, TimeUnit.SECONDS);
            assertEquals("literal", db.documents("tests").get(5, TimeUnit.SECONDS).getFirst().get("value"));
            assertEquals(1, db.userCount().get(5, TimeUnit.SECONDS));
        }
    }

    @Test void auditAndSnapshotsPersistAndQueueExecutesAwayFromCaller() throws Exception {
        Path file = directory.resolve("database.db");
        String caller = Thread.currentThread().getName();
        try (Database db = new Database(file)) {
            assertNotEquals(caller, db.submit(connection -> Thread.currentThread().getName()).get(5, TimeUnit.SECONDS));
            db.audit("tester", "world.time", "success", Map.of("value", 1200)).get(5, TimeUnit.SECONDS);
            db.metric(Map.of("tps", 20.0)).get(5, TimeUnit.SECONDS);
            db.vacuumInto(directory.resolve("snapshot.db")).get(5, TimeUnit.SECONDS);
            assertTrue(Files.size(directory.resolve("snapshot.db")) > 0);
        }
        try (Database db = new Database(file)) {
            assertEquals("world.time", db.auditHistory().get(5, TimeUnit.SECONDS).getFirst().get("action"));
            assertEquals(1, db.metrics(0).get(5, TimeUnit.SECONDS).size());
        }
    }
}
