package dev.mcc.launcher;

import dev.mcc.core.Json;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Executable entry point of the very same JAR installed as a Bukkit plugin. */
public final class LauncherMain {
    private LauncherMain() {}
    public static void main(String[] args) { int code = execute(args); if (code != 0) System.exit(code); }
    public static int execute(String[] args) {
        if (args.length == 0 || args.length == 1 && Set.of("--help", "-h").contains(args[0])) { System.out.println(help()); return 0; }
        try {
            if (Set.of("--status", "--stop", "--restart").contains(args[0])) {
                Map<String, String> values = new HashMap<>();
                for (int i = 1; i < args.length; i++) {
                    String key = args[i];
                    if (!Set.of("--server-root", "--request-id").contains(key) || ++i >= args.length || values.putIfAbsent(key, args[i]) != null)
                        throw new IllegalArgumentException("Invalid local client options");
                }
                Path root = Path.of(values.getOrDefault("--server-root", "."));
                if ("--status".equals(args[0])) {
                    if (values.containsKey("--request-id")) throw new IllegalArgumentException("Status is read-only");
                    System.out.println(Json.write(LauncherClient.status(root)));
                } else System.out.println(Json.write(LauncherClient.request(root, args[0].substring(2), values.getOrDefault("--request-id", UUID.randomUUID().toString()))));
                return 0;
            }
            return new OwnedSupervisor(LauncherOptions.parse(args)).run();
        } catch (Exception error) {
            // Do not echo arguments, paths, log content, or configuration secrets.
            System.err.println("MCC embedded launcher refused: " + error.getClass().getSimpleName()); return 2;
        }
    }
    public static String help() {
        return """
            Minecraft Control Center — embedded Java launcher (protocol 3)
            One plugin JAR. Java only; no Python or external launcher script.

            Start from the server directory after accepting the Minecraft EULA:
              java -jar plugins/MinecraftControlCenter.jar --server-root . --server-jar paper.jar
            Local control:
              java -jar plugins/MinecraftControlCenter.jar --status --server-root .
              java -jar plugins/MinecraftControlCenter.jar --stop --server-root .
              java -jar plugins/MinecraftControlCenter.jar --restart --server-root .

            Start options:
              --java <executable>         Defaults to the current Java installation
              --memory-mib <512..32768>   Maximum heap, default 1536 MiB
              --min-memory-mib <MiB>      Initial heap, default 512 MiB
              --kernel-name <name>       Default Paper
              --minecraft-version <ver>  Default 26.3
              --jvm-arg <argument>        Repeat for supported -X/-D options
              --max-crash-restarts <0..3> Default 3; unexpected nonzero exit only
              --run-id <UUID>            Optional canonical initial identity

            Normal stop saves worlds and is terminal. Requested restart requires a ready
            managed server and a clean exit. No force-kill or arbitrary console command.
            Stop legacy v2 before switching to this launcher. Do not run both together.
            On managed hosting, the hosting service must allow this Java start command.
            """;
    }
}
