package dev.mcc.web;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DashboardPortTest {
    private static YamlConfiguration config(String yaml) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return config;
    }

    private static YamlConfiguration packagedDefaults() throws Exception {
        try (var input = DashboardPortTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(input);
            return YamlConfiguration.loadConfiguration(new InputStreamReader(input, StandardCharsets.UTF_8));
        }
    }

    @Test void newInstallationUsesDynmapPortWithoutOpeningExternalAccess() throws Exception {
        var defaults = packagedDefaults();
        assertEquals(8123, DashboardPort.configured(defaults));
        assertEquals("127.0.0.1", defaults.getString("web.host"));
        assertFalse(defaults.getBoolean("web.allow-lan"));
        assertEquals("", defaults.getString("web.public-origin"));
    }

    @Test void missingPortUsesNewDefaultWithoutWritingExistingConfiguration() throws Exception {
        var existing = config("web:\n  host: 127.0.0.1\nperformance:\n  profile: eco\n");
        String before = existing.saveToString();
        assertEquals(8123, DashboardPort.configured(existing));
        assertFalse(existing.contains("web.port"));
        assertEquals(before, existing.saveToString());
    }

    @Test void legacyPortSurvivesNewDefaultsAndRemainsUnmodified() throws Exception {
        var existing = config("web:\n  host: 127.0.0.1\n  port: 8080\nperformance:\n  profile: eco\n");
        existing.setDefaults(packagedDefaults());
        String before = existing.saveToString();
        assertEquals(8080, DashboardPort.configured(existing));
        assertEquals(before, existing.saveToString());
        assertEquals("eco", existing.getString("performance.profile"));
    }

    @Test void explicitDynmapFixtureAndHostAssignedPortsTakePrecedence() throws Exception {
        for (int port : List.of(8123, 18095, 7229, 1024, 65535)) {
            var existing = config("web:\n  port: " + port + "\n");
            existing.setDefaults(packagedDefaults());
            assertEquals(port, DashboardPort.configured(existing));
            assertEquals(port, existing.getInt("web.port"));
        }
    }

    @Test void invalidConfiguredPortsFailBeforeStartingAListener() throws Exception {
        for (int port : List.of(-1, 0, 1023, 65536)) {
            var existing = config("web:\n  port: " + port + "\n");
            assertThrows(IllegalArgumentException.class, () -> DashboardPort.configured(existing));
        }
    }
}
