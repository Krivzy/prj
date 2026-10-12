package dev.mcc.server;

import java.util.Map;

/** Protocol 3 advertises the Java byte-range lease; legacy Python protocol 2 stays explicit. */
final class LauncherStatus {
    private LauncherStatus() {}
    static boolean pidEquals(Object value, long expected) {
        return expected > 0 && (value instanceof Integer || value instanceof Long)
            && ((Number) value).longValue() == expected;
    }
    static boolean supported(Map<String, Object> status) {
        Object raw = status.get("protocolVersion");
        if (!(raw instanceof Integer || raw instanceof Long)) return false;
        long version = ((Number) raw).longValue();
        if (version == 2) return true;
        return version == 3 && "embedded-java".equals(status.get("launcherKind"))
            && "java-file-lock-byte-0".equals(status.get("leaseKind"))
            && status.get("runId") instanceof String run && run.equals(status.get("currentRunId"));
    }
}
