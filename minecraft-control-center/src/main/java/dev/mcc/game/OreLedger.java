package dev.mcc.game;

import java.util.*;
import java.util.function.LongSupplier;

/** Main-thread placement verification. Historical counts belong to the asynchronous store. */
public final class OreLedger {
    public static final int WINDOW_SECONDS = 3600;
    public record BlockKey(UUID world, int x, int y, int z) { public BlockKey { Objects.requireNonNull(world); } }
    public record Threshold(int yellow, int red) {
        public Threshold { if (yellow < 1 || red <= yellow || red > 1_000_000) throw new IllegalArgumentException("Invalid ore thresholds"); }
    }
    public record Limits(int maxEvents, int maxPlayers, int maxPlaced, long placedTtlMillis) {
        public Limits {
            if (maxEvents < 1 || maxEvents > 65_536 || maxPlayers < 1 || maxPlayers > 512 || maxPlaced < 1 || maxPlaced > 131_072 || placedTtlMillis < 1 || placedTtlMillis > 7 * 86_400_000L) throw new IllegalArgumentException("Invalid ore monitor limits");
        }
        public static Limits defaults() { return new Limits(2048, 256, 65_536, 86_400_000L); }
    }
    public record Observation(UUID player, String name, String world, OreKind ore, long at) {
        public Observation { Objects.requireNonNull(player); Objects.requireNonNull(ore); name = bounded(name, 64); world = bounded(world, 128); }
    }
    private record Placed(String material, long at) {}
    private final Limits limits;
    private final LongSupplier clock;
    private final LinkedHashMap<BlockKey, Placed> placed = new LinkedHashMap<>();
    private final long startedAt;
    private long lastNow, placedEvicted, placedExcluded, verificationSkipped, pendingDropped;

    public OreLedger(Limits limits, Map<OreKind, Threshold> thresholds, LongSupplier clock) {
        this.limits = Objects.requireNonNull(limits); this.clock = Objects.requireNonNull(clock);
        for (OreKind ore : OreKind.values()) Objects.requireNonNull(thresholds.getOrDefault(ore, new Threshold(ore.yellow, ore.red)));
        this.startedAt = this.lastNow = clock.getAsLong();
    }
    private long now() { return lastNow = Math.max(lastNow, clock.getAsLong()); }
    public long observationTime() { return now(); }
    public boolean isTracked(BlockKey key) { return placed.containsKey(key); }
    public void placed(BlockKey key, String material) { placed(key, material, now()); }
    public void placed(BlockKey key, String material, long occurredAt) {
        long now = now(); placed.remove(key);
        if (OreKind.fromMaterial(material) == null) return;
        if (placed.size() >= limits.maxPlaced) { placed.remove(placed.firstEntry().getKey()); placedEvicted++; }
        placed.put(key, new Placed(material, Math.min(now, Math.max(startedAt, occurredAt))));
    }
    public void forgetPlaced(BlockKey key) { placed.remove(key); }
    public void clearTransient() { placed.clear(); }
    public void droppedPending() { pendingDropped++; }
    public void skippedVerification() { verificationSkipped++; }

    /** Called only after a successful Survival break. Exactly one accepted call is one ore block. */
    public Observation mined(UUID playerId, String name, String world, BlockKey key, String material) { return mined(playerId, name, world, key, material, now()); }
    public Observation mined(UUID playerId, String name, String world, BlockKey key, String material, long occurredAt) {
        OreKind ore = OreKind.fromMaterial(material); if (ore == null) return null;
        long now = now(), at = Math.min(now, Math.max(startedAt, occurredAt)); Placed source = placed.remove(key);
        if (source != null && source.material.equals(material) && at >= source.at && at - source.at < limits.placedTtlMillis) { placedExcluded++; return null; }
        return new Observation(playerId, name, world, ore, at);
    }
    public Map<String, Object> coverage(int pending) {
        long now = now();
        // Insertion order permits incremental expiration; no full 65k scan every sample.
        for (int expired = 0; expired < 256 && !placed.isEmpty() && now - placed.firstEntry().getValue().at >= limits.placedTtlMillis; expired++) placed.remove(placed.firstEntry().getKey());
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("placedTracking", "session-only"); result.put("unknownOlderPlaced", true); result.put("placedTtlSeconds", limits.placedTtlMillis / 1000);
        result.put("placedTracked", placed.size()); result.put("placedExcluded", placedExcluded); result.put("placedEvicted", placedEvicted);
        result.put("eventsEvicted", 0L); result.put("playersEvicted", 0L); result.put("verificationSkipped", verificationSkipped); result.put("pendingDropped", pendingDropped); result.put("pending", pending);
        result.put("partial", placedEvicted + verificationSkipped + pendingDropped > 0);
        return Map.copyOf(result);
    }
    public static String severity(long[] counts, Map<OreKind, Threshold> thresholds) {
        String severity = "GREEN";
        for (OreKind ore : OreKind.values()) {
            Threshold threshold = thresholds.getOrDefault(ore, new Threshold(ore.yellow, ore.red)); long count = counts[ore.ordinal()];
            if (count >= threshold.red) return "RED"; if (count >= threshold.yellow) severity = "YELLOW";
        }
        return severity;
    }
    static String bounded(String text, int length) { if (text == null) return ""; return text.substring(0, Math.min(text.length(), length)); }
}
