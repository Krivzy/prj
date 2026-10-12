package dev.mcc.launcher;

import dev.mcc.core.Json;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class LauncherProtocolTest {
    private final String run = UUID.randomUUID().toString(), request = UUID.randomUUID().toString();
    private byte[] record(String command, String identity, String id) { return (Json.write(Map.of("command", command, "runId", identity, "requestId", id)) + "\n").getBytes(StandardCharsets.UTF_8); }
    @Test void onlyCanonicalStopAndRestartForCurrentRunAreAccepted() {
        assertEquals("stop", LauncherProtocol.decode(record("stop", run, request), run).command());
        assertEquals(request, LauncherProtocol.decode(record("restart", run, request), run).requestId());
        assertNull(LauncherProtocol.decode(record("op", run, request), run));
        assertNull(LauncherProtocol.decode(record("stop", UUID.randomUUID().toString(), request), run));
        assertNull(LauncherProtocol.decode(record("stop", run, "1-1-1-1-1"), run));
        assertNull(LauncherProtocol.decode(record("stop", run, "BBBD6A80-1038-405B-B588-7CB8133C840B"), run));
    }
    @Test void PartialOversizedDuplicateAndExtraKeysNeverBecomeCommands() {
        byte[] valid = record("stop", run, request);
        assertNull(LauncherProtocol.decode(java.util.Arrays.copyOf(valid, valid.length - 1), run));
        assertNull(LauncherProtocol.decode(new byte[4097], run));
        String duplicate = "{\"command\":\"stop\",\"command\":\"restart\",\"runId\":\"" + run + "\",\"requestId\":\"" + request + "\"}\n";
        assertNull(LauncherProtocol.decode(duplicate.getBytes(StandardCharsets.UTF_8), run));
        String extra = Json.write(Map.of("command", "stop", "runId", run, "requestId", request, "console", "arbitrary")) + "\n";
        assertNull(LauncherProtocol.decode(extra.getBytes(StandardCharsets.UTF_8), run));
    }
    @Test void malformedUtf8IsRejectedBeforeJsonParsing() {
        byte[] invalid = record("stop", run, request); invalid[1] = (byte) 0xff;
        assertNull(LauncherProtocol.decode(invalid, run));
    }
    @Test void additionalJsonValueOrTrailingPlaintextIsRejected() {
        String valid = new String(record("stop", run, request), StandardCharsets.UTF_8).trim();
        assertNull(LauncherProtocol.decode((valid + " {}\n").getBytes(StandardCharsets.UTF_8), run));
        assertNull(LauncherProtocol.decode((valid + " trailing\n").getBytes(StandardCharsets.UTF_8), run));
    }
}
