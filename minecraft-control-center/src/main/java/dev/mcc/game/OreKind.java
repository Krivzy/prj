package dev.mcc.game;

import java.util.Locale;

/** Counts ore blocks, never drops or item stacks. */
public enum OreKind {
    DIAMOND("DIAMOND_ORE", 64, 160), GOLD("GOLD_ORE", 192, 512),
    REDSTONE("REDSTONE_ORE", 320, 800), COAL("COAL_ORE", 640, 1536),
    LAPIS("LAPIS_ORE", 80, 200), EMERALD("EMERALD_ORE", 32, 96),
    IRON("IRON_ORE", 384, 1000), COPPER("COPPER_ORE", 1024, 3072),
    QUARTZ("NETHER_QUARTZ_ORE", 640, 1536), NETHER_GOLD("NETHER_GOLD_ORE", 384, 1024),
    ANCIENT_DEBRIS("ANCIENT_DEBRIS", 24, 64);

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
