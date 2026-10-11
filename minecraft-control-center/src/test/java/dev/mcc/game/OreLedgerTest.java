package dev.mcc.game;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class OreLedgerTest {
    private final AtomicLong clock = new AtomicLong(1_000_000);
    private final UUID world = UUID.randomUUID(), player = UUID.randomUUID();
    private OreLedger ledger() { return new OreLedger(OreLedger.Limits.defaults(), Map.of(), clock::get); }
    private OreLedger.BlockKey block(int x) { return new OreLedger.BlockKey(world, x, -55, -31); }
    private void mine(OreLedger ledger, int x, String material) { ledger.mined(player, "Miner", "World", block(x), material); }
    @SuppressWarnings("unchecked") private List<Map<String, Object>> rows(OreLedger ledger) { return (List<Map<String, Object>>) ledger.snapshot(0).get("players"); }
    @SuppressWarnings("unchecked") private int count(Map<String, Object> row, int window, String ore) { return ((Map<String, Map<String, Integer>>) row.get("counts")).get(String.valueOf(window)).get(ore); }
    @SuppressWarnings("unchecked") private Map<String, Object> coverage(OreLedger ledger) { return (Map<String, Object>) ledger.snapshot(0).get("coverage"); }

    @Test void countsAllOreFamiliesAndDeepslateVariantsOncePerBlock() {
        OreLedger ledger = ledger(); int x = 0;
        for (OreKind ore : OreKind.values()) {
            mine(ledger, x++, ore.material);
            if (!Set.of(OreKind.QUARTZ, OreKind.NETHER_GOLD, OreKind.ANCIENT_DEBRIS).contains(ore)) mine(ledger, x++, "DEEPSLATE_" + ore.material);
        }
        Map<String, Object> row = rows(ledger).getFirst();
        assertEquals(19, row.get("total"));
        assertEquals(2, count(row, 600, "diamond")); assertEquals(1, count(row, 600, "ancient_debris"));
        mine(ledger, 999, "DIAMOND_BLOCK"); mine(ledger, 998, "RAW_IRON_BLOCK"); mine(ledger, 997, "STONE");
        assertEquals(19, rows(ledger).getFirst().get("total"), "Items/storage blocks do not inflate mined ore counts");
    }
    @Test void exactRollingWindowsExpireBoundaryWithoutKeepingPlayerIdentity() {
        OreLedger ledger = ledger(); mine(ledger, 1, "DIAMOND_ORE"); clock.addAndGet(60_000);
        mine(ledger, 2, "DEEPSLATE_DIAMOND_ORE");
        assertEquals(1, count(rows(ledger).getFirst(), 60, "diamond")); assertEquals(2, count(rows(ledger).getFirst(), 300, "diamond"));
        clock.addAndGet(240_000); assertEquals(0, count(rows(ledger).getFirst(), 60, "diamond")); assertEquals(1, count(rows(ledger).getFirst(), 300, "diamond"));
        clock.addAndGet(300_000); assertEquals(1, count(rows(ledger).getFirst(), 600, "diamond"));
        clock.addAndGet(60_000); assertTrue(rows(ledger).isEmpty());
    }
    @Test void sameTickPlacedBreakIsExcludedAndWorldOrMaterialDoesNotCrossMatch() {
        OreLedger ledger = ledger(); ledger.placed(block(-18), "DEEPSLATE_DIAMOND_ORE"); mine(ledger, -18, "DEEPSLATE_DIAMOND_ORE");
        assertTrue(rows(ledger).isEmpty()); assertEquals(1L, coverage(ledger).get("placedExcluded"));
        ledger.placed(block(-17), "DIAMOND_ORE");
        ledger.mined(player, "Miner", "Other", new OreLedger.BlockKey(UUID.randomUUID(), -17, -55, -31), "DIAMOND_ORE");
        mine(ledger, -17, "DEEPSLATE_DIAMOND_ORE");
        assertEquals(2, rows(ledger).getFirst().get("total"));
    }
    @Test void placementTtlAndCapacityLossAreHonestUnknownCoverage() {
        OreLedger ledger = new OreLedger(new OreLedger.Limits(20, 4, 1, 1000), Map.of(), clock::get);
        ledger.placed(block(1), "DIAMOND_ORE"); ledger.placed(block(2), "DIAMOND_ORE");
        mine(ledger, 1, "DIAMOND_ORE"); assertEquals(1L, coverage(ledger).get("placedEvicted"));
        clock.addAndGet(1000); mine(ledger, 2, "DIAMOND_ORE");
        assertEquals(2, rows(ledger).getFirst().get("total")); assertEquals(true, coverage(ledger).get("partial"));
        assertEquals("session-only", coverage(ledger).get("placedTracking")); assertEquals(true, coverage(ledger).get("unknownOlderPlaced"));
    }
    @Test void cancelledPlacementCanBeIgnoredAndSuccessfulReplacementForgetsPriorOre() {
        OreLedger ledger = ledger(); // The listener never calls placed for a cancelled event.
        mine(ledger, 1, "DIAMOND_ORE"); ledger.placed(block(2), "DIAMOND_ORE"); ledger.placed(block(2), "STONE");
        mine(ledger, 2, "DIAMOND_ORE"); assertEquals(2, rows(ledger).getFirst().get("total"));
    }
    @Test void thresholdsUseWholeTenMinuteCountsWithoutExtrapolatingOneVein() {
        OreLedger ledger = new OreLedger(OreLedger.Limits.defaults(), Map.of(OreKind.DIAMOND, new OreLedger.Threshold(4, 8)), clock::get);
        for (int x = 0; x < 3; x++) mine(ledger, x, "DIAMOND_ORE"); assertEquals("GREEN", rows(ledger).getFirst().get("severity"));
        mine(ledger, 4, "DIAMOND_ORE"); assertEquals("YELLOW", rows(ledger).getFirst().get("severity"));
        for (int x = 5; x < 9; x++) mine(ledger, x, "DIAMOND_ORE"); assertEquals("RED", rows(ledger).getFirst().get("severity"));
        clock.addAndGet(60_000); assertEquals(0, count(rows(ledger).getFirst(), 60, "diamond")); assertEquals("RED", rows(ledger).getFirst().get("severity"));
        clock.addAndGet(540_000); assertTrue(rows(ledger).isEmpty());
    }
    @Test void boundedEventsAndPlayersDoNotResurrectEvictedPlayersOldHistory() {
        OreLedger ledger = new OreLedger(new OreLedger.Limits(2, 1, 2, 1000), Map.of(), clock::get);
        mine(ledger, 1, "DIAMOND_ORE"); UUID other = UUID.randomUUID(); ledger.mined(other, "Other", "World", block(2), "IRON_ORE");
        mine(ledger, 3, "DIAMOND_ORE");
        assertEquals(1, rows(ledger).getFirst().get("total")); assertEquals(2L, coverage(ledger).get("playersEvicted"));
        mine(ledger, 4, "DIAMOND_ORE"); mine(ledger, 5, "DIAMOND_ORE"); assertEquals(2, rows(ledger).getFirst().get("total")); assertTrue((long) coverage(ledger).get("eventsEvicted") > 0);
    }
    @Test void publishedSnapshotsRemainDeeplyImmutableAndDoNotChangeWithNewEvents() {
        OreLedger ledger = ledger(); mine(ledger, 1, "DIAMOND_ORE"); Map<String, Object> published = ledger.snapshot(0);
        assertThrows(UnsupportedOperationException.class, () -> published.put("players", List.of()));
        @SuppressWarnings("unchecked") List<Map<String, Object>> players = (List<Map<String, Object>>) published.get("players");
        assertThrows(UnsupportedOperationException.class, () -> players.clear()); assertThrows(UnsupportedOperationException.class, () -> players.getFirst().put("name", "other"));
        mine(ledger, 2, "DIAMOND_ORE"); assertEquals(1, players.getFirst().get("total"));
    }
    @Test void deferredVerificationPreservesOriginalOccurrenceTimeAndClockDoesNotGoBackwards() {
        OreLedger ledger = ledger(); long at = clock.get(); clock.addAndGet(61_000);
        ledger.mined(player, "Miner", "World", block(1), "DIAMOND_ORE", at);
        assertEquals(0, count(rows(ledger).getFirst(), 60, "diamond")); assertEquals(1, count(rows(ledger).getFirst(), 600, "diamond"));
        long observed = (long) ledger.snapshot(0).get("generatedAt"); clock.addAndGet(-100_000); assertEquals(observed, ledger.snapshot(0).get("generatedAt"));
    }
    @Test void invalidThresholdsAndMemoryLimitsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new OreLedger.Threshold(4, 4)); assertThrows(IllegalArgumentException.class, () -> new OreLedger.Threshold(0, 5));
        assertThrows(IllegalArgumentException.class, () -> new OreLedger.Limits(65_537, 1, 1, 1000));
    }
}
