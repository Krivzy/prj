package dev.mcc.web;

import org.bukkit.configuration.ConfigurationSection;

/** A host-assigned port always takes precedence over the new-install default. */
public final class DashboardPort {
    public static final int DEFAULT = 8123;

    private DashboardPort() {}

    public static int configured(ConfigurationSection config) {
        return requireValid(config.getInt("web.port", DEFAULT));
    }

    public static int requireValid(int port) {
        if (port < 1024 || port > 65535) throw new IllegalArgumentException("web.port must be 1024–65535");
        return port;
    }
}
