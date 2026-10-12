package dev.mcc.launcher;

import dev.mcc.core.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class LauncherFilesTest {
    @TempDir Path root;
    @Test void statusIsPrivateAtomicAndSizeBounded() throws Exception {
        Path control = LauncherFiles.control(root, true); LauncherFiles.atomicStatus(root, control, Map.of("state", "stopped", "protocolVersion", 3));
        assertEquals("stopped", LauncherFiles.status(root, control).get("state"));
        assertThrows(java.io.IOException.class, () -> LauncherFiles.atomicStatus(root, control, Map.of("large", "x".repeat(17000))));
        Files.write(control.resolve("status.json"), new byte[16385]); assertThrows(java.io.IOException.class, () -> LauncherFiles.status(root, control));
    }
    @Test void livePriorDaemonOrChildBlocksAnotherLaunchEvenBeforeReadiness() throws Exception {
        Path control = LauncherFiles.control(root, true);
        for (String field : new String[] {"javaPid", "daemonPid"}) {
            Files.writeString(control.resolve("status.json"), Json.write(Map.of(field, ProcessHandle.current().pid(), "state", "prelaunch")));
            assertThrows(java.io.IOException.class, () -> LauncherFiles.previousProcessesOffline(root, control));
        }
    }
    @Test void escapedAndUnexpectedFileTypesAreRefused() throws Exception {
        Path control = LauncherFiles.control(root, true); Path directory = Files.createDirectory(control.resolve("status.json"));
        assertThrows(java.io.IOException.class, () -> LauncherFiles.ordinary(root, directory, false));
        assertThrows(java.io.IOException.class, () -> LauncherFiles.ordinary(root, root.resolve("../outside"), false));
        for (Object invalid : new Object[] {0, -1L, 3.5, "12"}) assertThrows(java.io.IOException.class, () -> LauncherFiles.pid(invalid));
    }
    @Test void boundedLogRotatesWithoutExceedingNewStorageBudget() throws Exception {
        Path control = LauncherFiles.control(root, true); byte[] chunk = new byte[262144];
        try (RollingLog log = new RollingLog(root, control)) { for (int i = 0; i < 68; i++) log.write(chunk, 0, chunk.length); }
        assertEquals(RollingLog.MAX_BYTES, Files.size(control.resolve("console.log.1")));
        assertEquals(1024 * 1024, Files.size(control.resolve("console.log")));
    }
    @Test void localControlRejectsForeignPidStartIdentityAndLegacyProtocol() throws Exception {
        Path control = LauncherFiles.control(root, true); Files.writeString(control.resolve("commands.ndjson"), "");
        Map<String, Object> fake = Map.of("protocolVersion", 3, "launcherKind", "embedded-java", "leaseKind", "java-file-lock-byte-0", "daemonPid", ProcessHandle.current().pid(),
            "daemonStartedUtc", "1970-01-01T00:00:00Z", "runId", "0190f92c-4092-4612-bc98-5962b552f80d", "currentRunId", "0190f92c-4092-4612-bc98-5962b552f80d", "state", "prelaunch");
        Files.writeString(control.resolve("status.json"), Json.write(fake));
        assertThrows(java.io.IOException.class, () -> LauncherClient.request(root, "stop", "bbbd6a80-1038-405b-b588-7cb8133c840b"));
        Files.writeString(control.resolve("status.json"), Json.write(Map.of("protocolVersion", 2)));
        assertThrows(java.io.IOException.class, () -> LauncherClient.request(root, "stop", "bbbd6a80-1038-405b-b588-7cb8133c840b"));
        assertEquals(0, Files.size(control.resolve("commands.ndjson")));
    }
    @Test void contradictoryEulaUsesFinalPropertiesValueLikeTheServer() throws Exception {
        assertFalse(OwnedSupervisor.eulaAccepted("eula=true\neula=false\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertTrue(OwnedSupervisor.eulaAccepted("eula=false\neula=true\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertFalse(OwnedSupervisor.eulaAccepted(new byte[65537]));
    }
    @Test void oversizedLegacyLogIsPreservedAndMigrationRefusesIt() throws Exception {
        Path control = LauncherFiles.control(root, true); Path log = control.resolve("console.log");
        Files.write(log, new byte[(int) RollingLog.MAX_BYTES + 1]);
        assertThrows(java.io.IOException.class, () -> new RollingLog(root, control));
        assertEquals(RollingLog.MAX_BYTES + 1, Files.size(log));
        assertFalse(Files.exists(control.resolve("console.log.1")));
    }
    @Test void retainedLeaseCannotBeRenamedOnWindowsAndReleasesNormally() throws Exception {
        Path control = LauncherFiles.control(root, true), file = control.resolve("daemon.lock");
        try (LauncherFiles.Lease lease = new LauncherFiles.Lease(root, file)) {
            if (LauncherFiles.windows()) {
                assertThrows(java.io.IOException.class, () -> Files.move(file, control.resolve("replaced-lock")));
                assertThrows(java.io.IOException.class, () -> Files.delete(file));
            }
        }
        Files.move(file, control.resolve("released-lock")); assertTrue(Files.exists(control.resolve("released-lock")));
    }
    @Test void localStatusDoesNotEchoArbitraryObjectsOrOtherPrivateFields() throws Exception {
        Path control = LauncherFiles.control(root, true);
        Map<String, Object> valid = Map.of("protocolVersion", 3, "state", "stopped", "runId", "0190f92c-4092-4612-bc98-5962b552f80d", "privateData", "synthetic-private-field");
        Files.writeString(control.resolve("status.json"), Json.write(valid));
        assertFalse(Json.write(LauncherClient.status(root)).contains("synthetic-private-field"));
        Files.writeString(control.resolve("status.json"), Json.write(Map.of("protocolVersion", 3, "state", Map.of("privateData", "synthetic-private-field"), "runId", "0190f92c-4092-4612-bc98-5962b552f80d")));
        assertThrows(java.io.IOException.class, () -> LauncherClient.status(root));
    }
}
