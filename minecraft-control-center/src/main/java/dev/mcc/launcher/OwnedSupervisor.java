package dev.mcc.launcher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** A separate, small JVM from this very plugin JAR owns the Minecraft child and its console pipe. */
final class OwnedSupervisor {
    private final LauncherOptions options;
    private final Map<String, Object> status = new LinkedHashMap<>();
    private final CrashPolicy crashes;
    private final CountDownLatch closed = new CountDownLatch(1);
    private Path executable, control, plugin;
    private String pluginSha;
    private LauncherFiles.Lease lease;
    private RollingLog log;
    private CommandQueue queue;
    private Process process;
    private Thread reader;
    private volatile boolean shutdown;
    private LauncherProtocol.Command pending;
    private long stopDue;
    private boolean stopSent, prepared;

    OwnedSupervisor(LauncherOptions options) {
        this.options = options; crashes = new CrashPolicy(options.maxCrashRestarts());
        status.put("protocolVersion", LauncherProtocol.VERSION); status.put("launcherKind", "embedded-java");
        status.put("leaseKind", "java-file-lock-byte-0"); status.put("ownership", "owned-child");
        status.put("daemonPid", ProcessHandle.current().pid());
        status.put("daemonStartedUtc", ProcessHandle.current().info().startInstant().orElseThrow().toString());
        status.put("javaPid", null); status.put("javaStartedUtc", null); status.put("runId", options.initialRunId());
        status.put("currentRunId", options.initialRunId()); status.put("state", "starting");
        status.put("paperReady", false); status.put("dashboardReady", false); status.put("startedUtc", Instant.now().toString());
        status.put("memoryMiB", options.memoryMiB()); status.put("kernelName", options.kernelName());
        status.put("minecraftVersion", options.minecraftVersion()); status.put("bootNumber", 0);
    }
    enum ExitAction { STOP, RESTART, RECOVER, FAIL }
    static ExitAction afterExit(int exitCode, String requestedCommand, boolean shutdown) {
        if (shutdown || "stop".equals(requestedCommand)) return ExitAction.STOP;
        if ("restart".equals(requestedCommand)) return exitCode == 0 ? ExitAction.RESTART : ExitAction.FAIL;
        return exitCode == 0 ? ExitAction.STOP : ExitAction.RECOVER;
    }
    private synchronized void save() throws IOException { LauncherFiles.atomicStatus(options.root(), control, status); }
    private void prepare() throws Exception {
        LauncherFiles.ordinary(options.root(), options.root(), true);
        executable = options.java().toRealPath();
        if (!Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Java executable is absent");
        LauncherFiles.ordinary(options.root(), options.root().resolve(options.serverJar()), false);
        Path eula = options.root().resolve("eula.txt"); LauncherFiles.ordinary(options.root(), eula, false);
        byte[] eulaBytes;
        try (var input = Files.newInputStream(eula, LinkOption.NOFOLLOW_LINKS)) { eulaBytes = input.readNBytes(65537); }
        if (!eulaAccepted(eulaBytes)) throw new IOException("Minecraft EULA has not been accepted");
        plugin = options.root().resolve("plugins").resolve(options.pluginJar()); LauncherFiles.ordinary(options.root(), plugin, false);
        Path ownJar = Path.of(LauncherMain.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        if (!ownJar.equals(plugin)) throw new IOException("Launch the canonical installed MinecraftControlCenter.jar itself");
        pluginSha = LauncherFiles.sha(plugin);
        control = LauncherFiles.control(options.root(), true);
        lease = new LauncherFiles.Lease(options.root(), control.resolve("daemon.lock"));
        LauncherFiles.previousProcessesOffline(options.root(), control);
        Path commands = control.resolve("commands.ndjson"); LauncherFiles.createPrivateFile(options.root(), commands);
        queue = new CommandQueue(options.root(), commands); log = new RollingLog(options.root(), control);
        prepared = true; save();
    }
    private boolean prestart() throws Exception {
        LauncherFiles.ordinary(options.root(), plugin, false);
        if (!LauncherFiles.sha(plugin).equals(pluginSha)) throw new IOException("The running launcher's plugin JAR changed; stop it before replacement");
        synchronized (this) { status.put("state", "prelaunch"); status.put("profileHook", "running"); save(); }
        Process hook = new ProcessBuilder(options.profileCommand(executable)).directory(options.root().toFile()).redirectErrorStream(true).start();
        Thread hookReader = outputReader(hook, null);
        try {
            while (hook.isAlive()) { pollCommands(); hook.waitFor(100, TimeUnit.MILLISECONDS); }
            int code = hook.waitFor(); closeOutput(hook, hookReader);
            synchronized (this) { status.put("profileHookExitCode", code); status.put("profileHook", code == 0 ? "passed" : "failed"); save(); }
            if (code != 0) throw new IOException("Managed profile prelaunch refused");
            return !shutdown && pending == null;
        } finally {
            // The offline hook must finish naturally too; no subprocess is forcefully killed.
            if (hook.isAlive()) waitNaturally(hook);
            if (hookReader.isAlive()) { hook.getInputStream().close(); joinOutput(hookReader); }
            hook.getOutputStream().close();
        }
    }
    private boolean boot(String run, String reason, String restartRequest) throws Exception {
        pending = null; stopDue = 0; stopSent = false;
        synchronized (this) {
            status.put("runId", run); status.put("currentRunId", run); status.put("javaPid", null); status.put("javaStartedUtc", null);
            status.put("state", "starting"); status.put("paperReady", false); status.put("dashboardReady", false);
            status.put("startedUtc", Instant.now().toString()); status.put("startupReason", reason);
            status.put("restartRequestId", restartRequest); status.put("requestId", restartRequest);
            status.put("requestedCommand", restartRequest == null ? null : "restart"); status.put("exitCode", null);
            status.put("restartRequestStatus", restartRequest == null ? null : "restarting");
            status.remove("stopReason"); status.remove("outputFailure");
            status.put("profileHookExitCode", null); status.put("bootNumber", ((Number) status.get("bootNumber")).intValue() + 1);
            if (!"crash-recovery".equals(reason)) status.put("recovery", null);
            if (!"requested-restart".equals(reason)) status.remove("restartFromRunId"); save();
        }
        if (shutdown || !prestart()) { terminalBeforeBoot(); return false; }
        pollCommands();
        if (shutdown || pending != null) { terminalBeforeBoot(); return false; }
        LauncherFiles.ordinary(options.root(), options.root().resolve(options.serverJar()), false);
        byte[] marker = ("\n--- MCC embedded launcher boot " + run + " at " + Instant.now() + " ---\n").getBytes(StandardCharsets.UTF_8);
        log.write(marker, 0, marker.length);
        process = new ProcessBuilder(options.serverCommand(executable, control, run, true)).directory(options.root().toFile()).redirectErrorStream(true).start();
        synchronized (this) {
            reader = outputReader(process, run);
            status.put("javaPid", process.pid()); status.put("javaStartedUtc", process.info().startInstant().orElseThrow().toString());
            status.put("state", "starting"); save();
        }
        return true;
    }
    private void terminalBeforeBoot() throws IOException {
        synchronized (this) { status.put("state", "stopped"); status.put("exitCode", 0); status.put("stoppedUtc", Instant.now().toString());
            status.put("stopReason", shutdown ? "launcher-shutdown" : "requested-stop-before-boot"); save(); }
    }
    private Thread outputReader(Process child, String run) {
        Thread thread = new Thread(() -> {
            try (var input = child.getInputStream()) {
                byte[] buffer = new byte[8192]; ByteArrayOutputStream line = new ByteArrayOutputStream(1024); boolean oversize = false;
                for (int count; (count = input.read(buffer)) >= 0;) {
                    if (count == 0) continue; log.write(buffer, 0, count);
                    if (run == null) continue;
                    for (int i = 0; i < count; i++) {
                        if (buffer[i] == '\n') {
                            if (!oversize) observe(new String(line.toByteArray(), StandardCharsets.UTF_8), run);
                            line.reset(); oversize = false;
                        } else if (!oversize) {
                            if (line.size() >= 16384) { line.reset(); oversize = true; } else line.write(buffer[i]);
                        }
                    }
                }
            } catch (Exception failure) {
                synchronized (OwnedSupervisor.this) { status.put("outputFailure", failure.getClass().getSimpleName()); status.put("stopReason", "output-or-status-failure"); }
                shutdown = true;
            }
        }, "MCC-embedded-launcher-output"); thread.setDaemon(true); thread.start(); return thread;
    }
    private synchronized void observe(String line, String run) throws IOException {
        if (!run.equals(status.get("runId"))) return;
        boolean changed = false;
        if (line.contains("Done (") && line.contains("For help") && !Boolean.TRUE.equals(status.get("paperReady"))) {
            status.put("paperReady", true); status.put("readyUtc", Instant.now().toString()); changed = true;
        }
        if (line.contains("REST API: Ready") && !Boolean.TRUE.equals(status.get("dashboardReady"))) { status.put("dashboardReady", true); changed = true; }
        if (changed) {
            if (Boolean.TRUE.equals(status.get("paperReady")) && Boolean.TRUE.equals(status.get("dashboardReady")) && pending == null) {
                status.put("state", "running"); if (status.get("restartRequestId") != null) status.put("restartRequestStatus", "completed");
            }
            save();
        }
    }
    private void pollCommands() throws Exception {
        java.util.List<byte[]> records = queue.read();
        for (byte[] raw : records) {
            String run; synchronized (this) { run = (String) status.get("runId"); }
            LauncherProtocol.Command command = LauncherProtocol.decode(raw, run);
            if (command != null) accept(command);
        }
    }
    private synchronized void accept(LauncherProtocol.Command command) throws IOException {
        if (pending != null) return;
        if ("restart".equals(command.command()) && (!"running".equals(status.get("state")) ||
            !Boolean.TRUE.equals(status.get("paperReady")) || !Boolean.TRUE.equals(status.get("dashboardReady")))) return;
        pending = command; stopDue = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        status.put("state", "restart".equals(command.command()) ? "restart-requested" : "stop-requested");
        status.put("requestId", command.requestId()); status.put("requestedCommand", command.command()); status.put("requestedUtc", Instant.now().toString());
        if ("restart".equals(command.command())) { status.put("restartRequestId", command.requestId()); status.put("restartRequestStatus", "stopping"); }
        save();
    }
    private void normalStop() {
        if (stopSent || process == null || !process.isAlive()) return;
        stopSent = true;
        synchronized (this) { status.put("state", "stopping"); if (shutdown) status.putIfAbsent("stopReason", "launcher-shutdown"); try { save(); } catch (IOException ignored) {} }
        try { process.getOutputStream().write("stop\n".getBytes(StandardCharsets.UTF_8)); process.getOutputStream().flush(); }
        catch (IOException ignored) { /* A closed console cannot justify killing a server; wait for its normal exit. */ }
    }
    private static void closeOutput(Process child, Thread thread) throws Exception {
        joinOutput(thread);
        if (thread.isAlive()) { child.getInputStream().close(); joinOutput(thread); throw new IOException("Owned child output did not close after exit"); }
        child.getOutputStream().close();
    }
    int run() {
        Thread hook = new Thread(() -> {
            shutdown = true;
            try { closed.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }, "MCC-embedded-launcher-normal-shutdown"); Runtime.getRuntime().addShutdownHook(hook);
        try {
            prepare(); if (!boot(options.initialRunId(), "initial", null)) return 0;
            for (;;) {
                while (process.isAlive()) {
                    pollCommands();
                    if (shutdown || pending != null && System.nanoTime() - stopDue >= 0) normalStop();
                    process.waitFor(100, TimeUnit.MILLISECONDS);
                }
                int code = process.waitFor(); closeOutput(process, reader);
                String previousRun, request;
                synchronized (this) {
                    previousRun = (String) status.get("runId"); request = pending == null ? null : pending.requestId();
                    status.put("exitCode", code); status.put("paperReady", false); status.put("dashboardReady", false);
                    status.put("state", code == 0 ? "stopped" : "failed"); status.put("stoppedUtc", Instant.now().toString());
                    if (pending != null && "restart".equals(pending.command()) && (code != 0 || shutdown)) status.put("restartRequestStatus", "failed"); save();
                }
                ExitAction action = afterExit(code, pending == null ? null : pending.command(), shutdown);
                if (action == ExitAction.STOP || action == ExitAction.FAIL) return code;
                if (action == ExitAction.RESTART) {
                    synchronized (this) { status.put("restartFromRunId", previousRun); }
                    if (!boot(UUID.randomUUID().toString(), "requested-restart", request)) return 0; continue;
                }
                Integer delay = crashes.delaySeconds(System.nanoTime());
                if (delay == null) { synchronized (this) { status.put("recoveryExhausted", true); save(); } return code; }
                synchronized (this) {
                    status.put("state", "recovering"); status.put("startupReason", "crash-recovery"); status.put("restartRequestId", null);
                    status.put("recovery", Map.of("kind", "unexpected-exit", "attempt", crashes.attempts(), "delaySeconds", delay, "previousRunId", previousRun, "exitCode", code)); save();
                }
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(delay);
                while (System.nanoTime() - until < 0 && !shutdown && pending == null) { pollCommands(); Thread.sleep(100); }
                if (shutdown || pending != null) {
                    synchronized (this) { status.put("state", "stopped"); status.put("recoveryCancelled", true); status.put("stoppedUtc", Instant.now().toString()); save(); } return 0;
                }
                if (!boot(UUID.randomUUID().toString(), "crash-recovery", null)) return 0;
            }
        } catch (Exception failure) {
            shutdown = true;
            if (process != null && process.isAlive()) { normalStop(); waitNaturally(process); }
            synchronized (this) {
                status.put("state", "failed"); status.put("paperReady", false); status.put("dashboardReady", false);
                status.put("error", failure.getClass().getSimpleName()); status.put("stoppedUtc", Instant.now().toString());
                if (status.get("restartRequestId") != null) status.put("restartRequestStatus", "failed");
                if (prepared) try { save(); } catch (IOException ignored) {}
            }
            System.err.println("MCC embedded launcher refused or failed: " + failure.getClass().getSimpleName()); return 1;
        } finally {
            if (process != null && process.isAlive()) { normalStop(); waitNaturally(process); }
            if (reader != null && reader.isAlive() && process != null) try { process.getInputStream().close(); joinOutput(reader); } catch (Exception ignored) {}
            if (log != null) try { log.close(); } catch (IOException ignored) {}
            if (queue != null) try { queue.close(); } catch (IOException ignored) {}
            if (lease != null) try { lease.close(); } catch (IOException ignored) {}
            closed.countDown();
            try { Runtime.getRuntime().removeShutdownHook(hook); } catch (IllegalStateException ignored) {}
        }
    }
    static void waitNaturally(Process child) {
        boolean interrupted = false;
        while (child.isAlive()) try { child.waitFor(); } catch (InterruptedException ignored) { interrupted = true; }
        if (interrupted) Thread.currentThread().interrupt();
    }
    private static void joinOutput(Thread thread) {
        boolean interrupted = false; long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (thread.isAlive()) {
            long remaining = until - System.nanoTime(); if (remaining <= 0) break;
            try { thread.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining))); }
            catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
    static boolean eulaAccepted(byte[] bytes) throws IOException {
        if (bytes.length > 65536) return false;
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        Properties properties = new Properties(); properties.load(new java.io.StringReader(text));
        return "true".equals(properties.getProperty("eula"));
    }
}
