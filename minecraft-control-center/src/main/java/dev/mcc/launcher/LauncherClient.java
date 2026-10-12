package dev.mcc.launcher;

import dev.mcc.core.Json;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Local OS-owner CLI client; the authenticated web API retains its independent authorization checks. */
public final class LauncherClient {
    private LauncherClient() {}
    public static Map<String, Object> status(Path serverRoot) throws IOException {
        Path root = serverRoot.toAbsolutePath().normalize(), control = LauncherFiles.control(root, false);
        Map<String, Object> source = LauncherFiles.status(root, control);
        Object protocol = source.get("protocolVersion");
        if (!(protocol instanceof Integer || protocol instanceof Long) || !Set.of(2L, 3L).contains(((Number) protocol).longValue()) ||
            !(source.get("state") instanceof String state) || !Set.of("starting", "prelaunch", "running", "restart-requested", "stop-requested", "stopping", "stopped", "failed", "recovering").contains(state) ||
            !(source.get("runId") instanceof String run) || !LauncherProtocol.canonicalId(run)) throw new IOException("Invalid launcher status summary");
        for (String field : Set.of("paperReady", "dashboardReady", "recoveryExhausted"))
            if (source.containsKey(field) && !(source.get(field) instanceof Boolean)) throw new IOException("Invalid launcher status summary");
        for (String field : Set.of("bootNumber", "exitCode")) {
            Object value = source.get(field);
            if (value != null && !(value instanceof Integer || value instanceof Long)) throw new IOException("Invalid launcher status summary");
        }
        if (source.containsKey("launcherKind") && !"embedded-java".equals(source.get("launcherKind"))) throw new IOException("Invalid launcher status summary");
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        for (String field : Set.of("protocolVersion", "state", "runId", "bootNumber", "paperReady", "dashboardReady", "exitCode", "recoveryExhausted", "launcherKind"))
            if (source.containsKey(field)) out.put(field, source.get(field));
        return out;
    }
    public static Map<String, Object> request(Path serverRoot, String command, String requestId) throws Exception {
        if (!Set.of("stop", "restart").contains(command) || !LauncherProtocol.canonicalId(requestId)) throw new IllegalArgumentException("Invalid local control request");
        Path root = serverRoot.toAbsolutePath().normalize(), control = LauncherFiles.control(root, false);
        Map<String, Object> initial = verify(root, control, command); String run = (String) initial.get("runId");
        Path queue = control.resolve("commands.ndjson"); LauncherFiles.ordinary(root, queue, false);
        Object queueIdentity = LauncherFiles.fileKey(root, queue);
        byte[] bytes = (Json.write(Map.of("command", command, "runId", run, "requestId", requestId)) + "\n").getBytes(StandardCharsets.UTF_8);
        if (Files.size(queue) + bytes.length > LauncherProtocol.MAX_QUEUE_BYTES) throw new IOException("Launcher command queue exceeds budget");
        try (FileChannel channel = FileChannel.open(queue, StandardOpenOption.WRITE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS);
             FileLock lock = LauncherQueueWriter.acquire(channel)) {
            Map<String, Object> current = verify(root, control, command);
            if (!run.equals(current.get("runId")) || !queueIdentity.equals(LauncherFiles.fileKey(root, queue)) ||
                channel.size() + bytes.length > LauncherProtocol.MAX_QUEUE_BYTES) throw new IOException("Launcher identity or command budget changed");
            ByteBuffer data = ByteBuffer.wrap(bytes); while (data.hasRemaining()) channel.write(data); channel.force(true);
        }
        return Map.of("accepted", true, "command", command, "runId", run, "requestId", requestId, "method", "normal-stop-and-start");
    }
    private static Map<String, Object> verify(Path root, Path control, String command) throws Exception {
        Map<String, Object> status = LauncherFiles.status(root, control);
        Object protocol = status.get("protocolVersion");
        if (!(protocol instanceof Integer || protocol instanceof Long) || ((Number) protocol).longValue() != LauncherProtocol.VERSION ||
            !"embedded-java".equals(status.get("launcherKind")) || !"java-file-lock-byte-0".equals(status.get("leaseKind")) ||
            !(status.get("runId") instanceof String run) || !LauncherProtocol.canonicalId(run) || !run.equals(status.get("currentRunId")))
            throw new IOException("Embedded launcher identity is unavailable");
        ProcessHandle daemon = ProcessHandle.of(LauncherFiles.pid(status.get("daemonPid"))).orElseThrow(() -> new IOException("Launcher is offline"));
        if (!daemon.isAlive() || !daemon.info().startInstant().map(instant -> instant.toString().equals(status.get("daemonStartedUtc"))).orElse(false))
            throw new IOException("Launcher process identity changed");
        String state = String.valueOf(status.get("state"));
        if (!Set.of("running", "starting", "prelaunch", "recovering").contains(state)) throw new IOException("Launcher has already stopped or accepted a request");
        if ("restart".equals(command) && (!"running".equals(state) || !Boolean.TRUE.equals(status.get("paperReady")) || !Boolean.TRUE.equals(status.get("dashboardReady"))))
            throw new IOException("Restart requires a ready managed server");
        if (status.get("javaPid") != null && !"recovering".equals(state)) {
            ProcessHandle child = ProcessHandle.of(LauncherFiles.pid(status.get("javaPid"))).orElseThrow(() -> new IOException("Managed server is offline"));
            if (!child.isAlive() || child.parent().map(ProcessHandle::pid).orElse(-1L) != daemon.pid() ||
                !child.info().startInstant().map(instant -> instant.toString().equals(status.get("javaStartedUtc"))).orElse(false))
                throw new IOException("Managed server process identity changed");
        }
        Path lease = control.resolve("daemon.lock"); LauncherFiles.ordinary(root, lease, false);
        try (FileChannel channel = FileChannel.open(lease, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS); FileLock guard = channel.tryLock(0, 1, false)) {
            if (guard != null) throw new IOException("The launcher lease is not retained");
        }
        return status;
    }
}
