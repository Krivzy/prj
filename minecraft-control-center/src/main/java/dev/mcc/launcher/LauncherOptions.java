package dev.mcc.launcher;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Explicit, data-only configuration. ProcessBuilder receives these arguments without a shell. */
public record LauncherOptions(Path java, Path root, String serverJar, String pluginJar,
                              int memoryMiB, int minMemoryMiB, String kernelName, String minecraftVersion,
                              String initialRunId, List<String> jvmArguments, int maxCrashRestarts) {
    private static final Set<String> KEYS = Set.of("--java", "--server-root", "--server-jar", "--plugin-jar", "--memory-mib",
        "--min-memory-mib", "--run-id", "--kernel-name", "--minecraft-version", "--max-crash-restarts");
    public LauncherOptions {
        java = java.toAbsolutePath().normalize();
        root = root.toAbsolutePath().normalize();
        safeJar(serverJar); safeJar(pluginJar);
        if (!"MinecraftControlCenter.jar".equals(pluginJar)) throw new IllegalArgumentException("Use the canonical MinecraftControlCenter.jar filename");
        if (memoryMiB < 512 || memoryMiB > 32768 || minMemoryMiB < 64 || minMemoryMiB > memoryMiB)
            throw new IllegalArgumentException("Invalid Java heap range");
        if (!LauncherProtocol.canonicalId(initialRunId)) throw new IllegalArgumentException("Canonical run UUID required");
        token(kernelName); token(minecraftVersion);
        if (maxCrashRestarts < 0 || maxCrashRestarts > 3) throw new IllegalArgumentException("Invalid recovery limit");
        jvmArguments = List.copyOf(jvmArguments);
        if (jvmArguments.size() > 64) throw new IllegalArgumentException("Too many JVM arguments");
        for (String value : jvmArguments) {
            if (value == null || value.length() > 512 || !(value.startsWith("-X") || value.startsWith("-D")) ||
                value.chars().anyMatch(c -> c < 32 || c == 127) || value.startsWith("-Xms") || value.startsWith("-Xmx") ||
                value.startsWith("-Dmcc.launcher.")) throw new IllegalArgumentException("Unsupported or reserved JVM argument");
        }
    }
    static void safeJar(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,120}\\.jar"))
            throw new IllegalArgumentException("A server-scope JAR filename is required");
    }
    private static void token(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.-]{1,32}")) throw new IllegalArgumentException("Invalid kernel/version name");
    }
    public static LauncherOptions parse(String[] args) {
        Map<String, String> values = new HashMap<>(); List<String> jvm = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String key = args[i];
            if ("--jvm-arg".equals(key)) {
                if (++i >= args.length) throw new IllegalArgumentException("Missing JVM argument");
                jvm.add(args[i]); continue;
            }
            if (!KEYS.contains(key) || ++i >= args.length || values.putIfAbsent(key, args[i]) != null)
                throw new IllegalArgumentException("Unknown, duplicate, or incomplete launcher option");
        }
        String javaName = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows") ? "java.exe" : "java";
        return new LauncherOptions(Path.of(values.getOrDefault("--java", Path.of(System.getProperty("java.home"), "bin", javaName).toString())),
            Path.of(values.getOrDefault("--server-root", ".")), values.getOrDefault("--server-jar", "paper.jar"),
            values.getOrDefault("--plugin-jar", "MinecraftControlCenter.jar"), number(values, "--memory-mib", 1536),
            number(values, "--min-memory-mib", 512), values.getOrDefault("--kernel-name", "Paper"),
            values.getOrDefault("--minecraft-version", "26.3"), values.getOrDefault("--run-id", UUID.randomUUID().toString()),
            jvm, number(values, "--max-crash-restarts", 3));
    }
    private static int number(Map<String, String> values, String key, int fallback) {
        String value = values.get(key); return value == null ? fallback : Integer.parseInt(value);
    }
    public List<String> serverCommand(Path executable, Path control, String runId, boolean profileHookPassed) {
        ArrayList<String> command = new ArrayList<>(List.of(executable.toString(), "-Xms" + minMemoryMiB + "M", "-Xmx" + memoryMiB + "M", "-Dterminal.ansi=false"));
        command.addAll(jvmArguments);
        command.add("-Dmcc.launcher.control=" + control); command.add("-Dmcc.launcher.run-id=" + runId);
        command.add("-Dmcc.launcher.server-jar=" + serverJar); command.add("-Dmcc.launcher.protocol=3");
        if (profileHookPassed) command.add("-Dmcc.launcher.profile-hook=true");
        command.addAll(List.of("-jar", root.resolve(serverJar).toString(), "--nogui", "--nojline"));
        return List.copyOf(command);
    }
    public List<String> profileCommand(Path executable) {
        ArrayList<String> command = new ArrayList<>(List.of(executable.toString(), "-Xms32M", "-Xmx192M")); command.addAll(jvmArguments);
        command.addAll(List.of("-cp", root.resolve("plugins").resolve(pluginJar).toString(), "dev.mcc.server.ProfileBootstrap", "--offline",
            "--server-root", root.toString(), "--plugin-data", root.resolve("plugins/MinecraftControlCenter").toString(),
            "--kernel", kernelName, "--minecraft-version", minecraftVersion, "--server-jar", serverJar));
        return List.copyOf(command);
    }
}
