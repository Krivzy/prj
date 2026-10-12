package dev.mcc.launcher;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class LauncherOptionsTest {
    @Test void commandPreservesLiteralArgumentsAndManagedIdentityWithoutShell() {
        LauncherOptions options = LauncherOptions.parse(new String[] {"--server-root", "qa server", "--server-jar", "purpur.jar", "--jvm-arg", "-Dqa.literal=a b;$()", "--jvm-arg", "-XX:+UseG1GC"});
        String run = UUID.randomUUID().toString(); Path executable = Path.of("qa java", "java").toAbsolutePath();
        List<String> command = options.serverCommand(executable, options.root().resolve(".mcc-launcher"), run, true);
        assertEquals(executable.toString(), command.getFirst()); assertTrue(command.contains("-Dqa.literal=a b;$()"));
        assertEquals(1, command.stream().filter(s -> s.startsWith("-Xmx")).count());
        assertTrue(command.contains("-Dmcc.launcher.run-id=" + run)); assertTrue(command.contains("-Dmcc.launcher.profile-hook=true"));
        assertEquals(List.of("--nogui", "--nojline"), command.subList(command.size() - 2, command.size()));
        assertFalse(command.stream().anyMatch(s -> s.equals("cmd.exe") || s.equals("sh") || s.equals("powershell.exe")));
    }
    @Test void reservedOverridesAgentsControlCharactersAndEscapingJarsAreRejected() {
        for (String argument : List.of("-Xmx4G", "-Xms4G", "-Dmcc.launcher.run-id=fake", "-javaagent:fake", "-Dqa=\nsecret", "--class-path"))
            assertThrows(IllegalArgumentException.class, () -> LauncherOptions.parse(new String[] {"--jvm-arg", argument}));
        for (String jar : List.of("../paper.jar", "/paper.jar", "folder/paper.jar", "paper.jar;other", "paper.exe"))
            assertThrows(IllegalArgumentException.class, () -> LauncherOptions.parse(new String[] {"--server-jar", jar}));
        assertThrows(IllegalArgumentException.class, () -> LauncherOptions.parse(new String[] {"--plugin-jar", "Other.jar"}));
    }
    @Test void heapRecoveryAndDuplicateOptionsFailClosed() {
        for (String[] args : List.of(new String[] {"--memory-mib", "511"}, new String[] {"--memory-mib", "32769"},
            new String[] {"--min-memory-mib", "2048", "--memory-mib", "1536"}, new String[] {"--max-crash-restarts", "4"},
            new String[] {"--server-jar", "one.jar", "--server-jar", "two.jar"}, new String[] {"--unknown", "value"}, new String[] {"--java"}))
            assertThrows(IllegalArgumentException.class, () -> LauncherOptions.parse(args));
    }
    @Test void prelaunchIsSeparateSameJarJavaCommandWithExactOfflineScope() {
        LauncherOptions options = LauncherOptions.parse(new String[] {"--server-root", "qa-server"});
        List<String> command = options.profileCommand(Path.of("java"));
        assertEquals("dev.mcc.server.ProfileBootstrap", command.get(command.indexOf("-cp") + 2));
        assertEquals(options.root().resolve("plugins/MinecraftControlCenter.jar").toString(), command.get(command.indexOf("-cp") + 1));
        assertTrue(command.contains("--offline")); assertTrue(command.contains(options.root().toString()));
        assertFalse(command.contains("-jar"));
    }
    @Test void helpAndNoArgumentsDoNotLaunchServer() {
        assertEquals(0, LauncherMain.execute(new String[0])); assertEquals(0, LauncherMain.execute(new String[] {"--help"}));
        assertTrue(LauncherMain.help().contains("no Python"));
    }
}
