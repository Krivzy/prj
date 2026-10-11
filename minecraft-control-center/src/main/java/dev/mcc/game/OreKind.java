package dev.mcc.game;

import java.util.Locale;

/** Counts ore blocks, never drops or item stacks. */
public enum OreKind {
    DIAMOND("DIAMOND_ORE", 24, 64), GOLD("GOLD_ORE", 72, 192),
    REDSTONE("REDSTONE_ORE", 128, 320), COAL("COAL_ORE", 256, 640),
    LAPIS("LAPIS_ORE", 32, 80), EMERALD("EMERALD_ORE", 12, 32),
    IRON("IRON_ORE", 160, 400), COPPER("COPPER_ORE", 384, 1024),
    QUARTZ("NETHER_QUARTZ_ORE", 256, 640), NETHER_GOLD("NETHER_GOLD_ORE", 160, 400),
    ANCIENT_DEBRIS("ANCIENT_DEBRIS", 8, 24);

    public final String material;
    public final int yellow;
    public final int red;
    OreKind(String material, int yellow, int red) { this.material = material; this.yellow = yellow; this.red = red; }
    public String id() { return name().toLowerCase(Locale.ROOT); }
    public static OreKind fromMaterial(String material) {
        if (material == null) return null;
        return switch (material) {
            case "DIAMOND_ORE", "DEEPSLATE_DIAMOND_ORE" -> DIAMOND;
            case "GOLD_ORE", "DEEPSLATE_GOLD_ORE" -> GOLD;
            case "REDSTONE_ORE", "DEEPSLATE_REDSTONE_ORE" -> REDSTONE;
            case "COAL_ORE", "DEEPSLATE_COAL_ORE" -> COAL;
            case "LAPIS_ORE", "DEEPSLATE_LAPIS_ORE" -> LAPIS;
            case "EMERALD_ORE", "DEEPSLATE_EMERALD_ORE" -> EMERALD;
            case "IRON_ORE", "DEEPSLATE_IRON_ORE" -> IRON;
            case "COPPER_ORE", "DEEPSLATE_COPPER_ORE" -> COPPER;
            case "NETHER_QUARTZ_ORE" -> QUARTZ;
            case "NETHER_GOLD_ORE" -> NETHER_GOLD;
            case "ANCIENT_DEBRIS" -> ANCIENT_DEBRIS;
            default -> null;
        };
    }
}
