package dev.mcc.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ServerPlatformTest {
    @Test void ownCoreBrandRequiresVerifiedRuntimeHandshake() {
        assertEquals("MCC Core",ServerPlatform.displayBrand("Paper",true));
        assertEquals("Paper",ServerPlatform.displayBrand("Paper",false));
        assertEquals("Purpur",ServerPlatform.displayBrand("Purpur",false));
        assertEquals("Unknown Bukkit server",ServerPlatform.displayBrand(null,false));
        assertEquals("Unknown Bukkit server",ServerPlatform.displayBrand(" ",false));
    }
    @Test void minecraftVersionUsesExactBukkitVersionRatherThanBrandOrPluginVersion() {
        assertEquals("26.3", ServerPlatform.parseMinecraftVersion("26.3-R0.1-SNAPSHOT"));
        assertEquals("1.21.11", ServerPlatform.parseMinecraftVersion("1.21.11-R0.1-SNAPSHOT"));
        assertEquals("unknown", ServerPlatform.parseMinecraftVersion("Paper server git-123"));
        assertEquals("unknown", ServerPlatform.parseMinecraftVersion(null));
    }
    @Test void missingOrInvalidNativeMetricsNeverBecomeFabricatedTwentyTps() {
        assertArrayEquals(new double[0], ServerPlatform.validTps(null));
        assertArrayEquals(new double[0], ServerPlatform.validTps(new double[]{Double.NaN, 20}));
        assertArrayEquals(new double[0], ServerPlatform.validTps(new double[]{-1}));
        assertArrayEquals(new double[]{20,18,16}, ServerPlatform.validTps(new double[]{20.1,18,16}));
    }
    @Test void fallbackMeasuresSchedulerRateAfterWarmupAndIncludesServerPauses() {
        var rate = new ServerPlatform.TickRate();
        for (long i = 0; i < 40; i++) rate.tick(i * 100_000_000L);
        assertEquals(0, rate.values().length, "Warmup is explicitly unavailable");
        for (long i = 40; i <= 100; i++) rate.tick(i * 100_000_000L);
        assertTrue(rate.values()[0] >= 10 && rate.values()[0] < 10.5, "Ten callbacks a second cannot be reported as twenty");
        rate.tick(80_000_000_000L); assertTrue(rate.values()[0] < 1, "A long scheduling pause is part of measured throughput");
    }
    @Test void fallbackSamplesAreImmutableCopiesAndNegativeNanoOriginIsSupported() {
        var rate = new ServerPlatform.TickRate(); long origin = -100_000_000_000L;
        for (long i = 0; i <= 120; i++) rate.tick(origin + i * 50_000_000L);
        double[] copy = rate.values(); assertEquals(3, copy.length); copy[0] = 0;
        assertEquals(20, rate.values()[0]); rate.tick(origin); assertEquals(20, rate.values()[0]);
    }
}
