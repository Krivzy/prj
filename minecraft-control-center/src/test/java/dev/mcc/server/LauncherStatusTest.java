package dev.mcc.server;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LauncherStatusTest {
    private Map<String, Object> embedded() {
        return new HashMap<>(Map.of("protocolVersion", 3, "launcherKind", "embedded-java",
            "leaseKind", "java-file-lock-byte-0", "runId", "same", "currentRunId", "same"));
    }
    @Test void legacyAndOwnedJavaProtocolsAreSupported() {
        assertTrue(LauncherStatus.supported(Map.of("protocolVersion", 2)));
        assertTrue(LauncherStatus.supported(embedded()));
    }
    @Test void fractionalUnknownAndTextProtocolsAreRejected() {
        for (Object value : List.of(2.5, 3.0, 4, "3")) {
            var status = embedded(); status.put("protocolVersion", value);
            assertFalse(LauncherStatus.supported(status));
        }
    }
    @Test void javaProtocolNeedsExplicitLeaseAndCurrentRun() {
        for (String key : List.of("launcherKind", "leaseKind", "currentRunId")) {
            var status = embedded(); status.remove(key); assertFalse(LauncherStatus.supported(status));
            status.put(key, "foreign"); assertFalse(LauncherStatus.supported(status));
        }
    }
    @Test void processIdsMustBeExactPositiveIntegers() {
        assertTrue(LauncherStatus.pidEquals(42,42)); assertTrue(LauncherStatus.pidEquals(42L,42));
        for (Object value : List.of(42.5,42.0,"42",43,0,-1)) assertFalse(LauncherStatus.pidEquals(value,42));
        assertFalse(LauncherStatus.pidEquals(-1,-1));
    }
}
