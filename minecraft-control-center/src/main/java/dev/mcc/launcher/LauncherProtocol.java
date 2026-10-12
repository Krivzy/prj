package dev.mcc.launcher;

import dev.mcc.core.Json;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Bounded local control protocol. It accepts no arbitrary console commands. */
public final class LauncherProtocol {
    public static final int VERSION = 3;
    public static final int MAX_COMMAND_BYTES = 4096;
    public static final long MAX_QUEUE_BYTES = 1024 * 1024;
    private LauncherProtocol() {}

    public record Command(String command, String runId, String requestId) {}

    public static boolean canonicalId(String value) {
        try { return value != null && value.length() == 36 && UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException ignored) { return false; }
    }

    public static Command decode(byte[] bytes, String currentRunId) {
        if (bytes.length == 0 || bytes.length > MAX_COMMAND_BYTES || bytes[bytes.length - 1] != '\n') return null;
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            Map<String, Object> data = Json.object(text);
            if (!data.keySet().equals(Set.of("command", "runId", "requestId")) ||
                !(data.get("command") instanceof String command) || !Set.of("stop", "restart").contains(command) ||
                !(data.get("runId") instanceof String runId) || !canonicalId(runId) || !runId.equals(currentRunId) ||
                !(data.get("requestId") instanceof String requestId) || !canonicalId(requestId)) return null;
            return new Command(command, runId, requestId);
        } catch (IllegalArgumentException | CharacterCodingException ignored) { return null; }
    }
}
