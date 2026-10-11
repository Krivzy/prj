package dev.mcc.game;

import java.util.*;
import java.util.function.LongSupplier;

/** Main-thread, bounded in-memory ledger. This is observation, not a cheating verdict. */
public final class OreLedger {
    public static final int WINDOW_SECONDS = 600;
    private static final OreKind[] ORES = OreKind.values();
    public record BlockKey(UUID world, int x, int y, int z) { public BlockKey { Objects.requireNonNull(world); } }
    public record Threshold(int yellow, int red) {
        public Threshold { if (yellow < 1 || red <= yellow || red > 1_000_000) throw new IllegalArgumentException("Invalid ore thresholds"); }
    }
    public record Limits(int maxEvents, int maxPlayers, int maxPlaced, long placedTtlMillis) {
        public Limits {
            if (maxEvents < 1 || maxEvents > 65_536 || maxPlayers < 1 || maxPlayers > 512 || maxPlaced < 1 || maxPlaced > 131_072 || placedTtlMillis < 1 || placedTtlMillis > 7 * 86_400_000L) throw new IllegalArgumentException("Invalid ore monitor limits");
        }
        public static Limits defaults() { return new Limits(32_768, 256, 65_536, 86_400_000L); }
    }
    private record Mined(UUID player, long generation, OreKind ore, long at) {}
    private record Placed(String material, long at) {}
    private static final class Player {
        final UUID id; final long generation; String name; String world; long lastMined; boolean online;
        Player(UUID id, long generation, String name, String world, long now) { this.id = id; this.generation = generation; this.name = name; this.world = world; this.lastMined = now; this.online = true; }
    }
    private final Limits limits;
    private final EnumMap<OreKind, Threshold> thresholds = new EnumMap<>(OreKind.class);
    private final LongSupplier clock;
    private final LinkedHashMap<Long, Mined> events = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, Player> players = new LinkedHashMap<>(16, .75f, true);
    private final LinkedHashMap<BlockKey, Placed> placed = new LinkedHashMap<>();
    private final long startedAt;
    private long lastNow, sequence, generation, eventsEvicted, playersEvicted, placedEvicted, placedExcluded, verificationSkipped, pendingDropped;

    public OreLedger(Limits limits, Map<OreKind, Threshold> thresholds, LongSupplier clock) {
        this.limits = Objects.requireNonNull(limits); this.clock = Objects.requireNonNull(clock);
        for (OreKind ore : OreKind.values()) this.thresholds.put(ore, thresholds.getOrDefault(ore, new Threshold(ore.yellow, ore.red)));
        this.startedAt = this.lastNow = clock.getAsLong();
    }
    private long now() { return lastNow = Math.max(lastNow, clock.getAsLong()); }
    public long observationTime() { return now(); }
    public boolean isTracked(BlockKey key) { return placed.containsKey(key); }
    public void placed(BlockKey key, String material) {
        placed(key, material, now());
    }
    public void placed(BlockKey key, String material, long occurredAt) {
        long now = now();
        placed.remove(key);
        if (OreKind.fromMaterial(material) == null) return;
        if (placed.size() >= limits.maxPlaced) { placed.remove(placed.firstEntry().getKey()); placedEvicted++; }
        placed.put(key, new Placed(material, Math.min(now, Math.max(startedAt, occurredAt))));
    }
    public void forgetPlaced(BlockKey key) { placed.remove(key); }
    public void online(UUID player, boolean online) { Player value = players.get(player); if (value != null) value.online = online; }
    public void droppedPending() { pendingDropped++; }
    public void skippedVerification() { verificationSkipped++; }

    /** Caller has verified a successful SURVIVAL break. One call is exactly one block. */
    public void mined(UUID playerId, String name, String world, BlockKey key, String material) {
        mined(playerId, name, world, key, material, now());
    }
    public void mined(UUID playerId, String name, String world, BlockKey key, String material, long occurredAt) {
        OreKind ore = OreKind.fromMaterial(material); if (ore == null) return;
        long now = now(), at = Math.min(now, Math.max(startedAt, occurredAt)); Placed source = placed.remove(key);
        if (source != null && source.material.equals(material) && at >= source.at && at - source.at < limits.placedTtlMillis) { placedExcluded++; return; }
        Player player = players.get(playerId);
        if (player == null) {
            if (players.size() >= limits.maxPlayers) { players.remove(players.firstEntry().getKey()); playersEvicted++; }
            player = new Player(playerId, ++generation, bounded(name, 64), bounded(world, 128), now); players.put(playerId, player);
        }
        player.name = bounded(name, 64); player.world = bounded(world, 128); player.lastMined = at;
        if (events.size() >= limits.maxEvents) { events.remove(events.firstEntry().getKey()); eventsEvicted++; }
        events.put(++sequence, new Mined(playerId, player.generation, ore, at));
    }

    /** A bounded scan, called every five seconds on main; all returned structures are immutable. */
    public Map<String, Object> snapshot(int pending) {
        long now = now(), cutoff = now - WINDOW_SECONDS * 1000L;
        Map<UUID, int[][]> totals = new HashMap<>();
        for (Iterator<Map.Entry<Long, Mined>> iterator = events.entrySet().iterator(); iterator.hasNext();) {
            Mined event = iterator.next().getValue();
            if (event.at <= cutoff) { iterator.remove(); continue; }
            Player player = players.get(event.player);
            if (player == null || player.generation != event.generation) { iterator.remove(); continue; }
            int[][] count = totals.computeIfAbsent(event.player, ignored -> new int[3][ORES.length]);
            count[2][event.ore.ordinal()]++;
            if (event.at > now - 300_000) count[1][event.ore.ordinal()]++;
            if (event.at > now - 60_000) count[0][event.ore.ordinal()]++;
        }
        // Bounded expiration also releases names and UUIDs once their ten-minute history is gone.
        players.entrySet().removeIf(entry -> entry.getValue().lastMined <= cutoff);
        placed.entrySet().removeIf(entry -> now - entry.getValue().at >= limits.placedTtlMillis);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Player player : players.values()) {
            int[][] count = totals.get(player.id); if (count == null) continue;
            LinkedHashMap<String, Object> windows = new LinkedHashMap<>();
            for (int i = 0; i < 3; i++) windows.put(String.valueOf(new int[]{60, 300, 600}[i]), counts(count[i]));
            String severity = "GREEN"; List<String> reasons = new ArrayList<>();
            for (OreKind ore : ORES) {
                int value = count[2][ore.ordinal()]; Threshold threshold = thresholds.get(ore);
                if (value >= threshold.yellow) { reasons.add(ore.id()); if (value >= threshold.red) severity = "RED"; else if (!severity.equals("RED")) severity = "YELLOW"; }
            }
            int total = Arrays.stream(count[2]).sum();
            rows.add(Map.of("id", player.id.toString(), "name", player.name, "world", player.world, "online", player.online, "lastMinedAt", player.lastMined, "counts", Map.copyOf(windows), "total", total, "severity", severity, "flaggedOres", List.copyOf(reasons)));
        }
        rows.sort(Comparator.<Map<String, Object>>comparingInt(row -> switch ((String) row.get("severity")) { case "RED" -> 2; case "YELLOW" -> 1; default -> 0; }).reversed().thenComparing(Comparator.comparingInt((Map<String, Object> row) -> (int) row.get("total")).reversed()).thenComparing(row -> (String) row.get("name")));
        List<Map<String, Object>> ores = Arrays.stream(OreKind.values()).map(ore -> Map.<String, Object>of("id", ore.id(), "material", ore.material, "yellow", thresholds.get(ore).yellow, "red", thresholds.get(ore).red)).toList();
        LinkedHashMap<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("placedTracking", "session-only"); coverage.put("unknownOlderPlaced", true); coverage.put("placedTtlSeconds", limits.placedTtlMillis / 1000);
        coverage.put("placedTracked", placed.size()); coverage.put("placedExcluded", placedExcluded); coverage.put("placedEvicted", placedEvicted);
        coverage.put("eventsEvicted", eventsEvicted); coverage.put("playersEvicted", playersEvicted); coverage.put("verificationSkipped", verificationSkipped); coverage.put("pendingDropped", pendingDropped); coverage.put("pending", pending);
        coverage.put("partial", placedEvicted + eventsEvicted + playersEvicted + verificationSkipped + pendingDropped > 0);
        return Map.of("generatedAt", now, "startedAt", startedAt, "observedSeconds", Math.min(WINDOW_SECONDS, Math.max(0, (now - startedAt) / 1000)), "windowSeconds", WINDOW_SECONDS, "windows", List.of(60, 300, 600), "ores", ores, "players", List.copyOf(rows), "coverage", Map.copyOf(coverage), "limits", Map.of("maxEvents", limits.maxEvents, "maxPlayers", limits.maxPlayers, "maxPlaced", limits.maxPlaced));
    }
    private static Map<String, Integer> counts(int[] count) { Map<String, Integer> result = new LinkedHashMap<>(); for (OreKind ore : ORES) result.put(ore.id(), count[ore.ordinal()]); return Collections.unmodifiableMap(result); }
    private static String bounded(String text, int length) { if (text == null) return ""; return text.substring(0, Math.min(text.length(), length)); }
}
