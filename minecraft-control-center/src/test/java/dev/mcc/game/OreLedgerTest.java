package dev.mcc.game;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class OreLedgerTest {
    private final AtomicLong clock=new AtomicLong(1_000_000);
    private final UUID world=UUID.randomUUID(),player=UUID.randomUUID();
    private OreLedger ledger(){return new OreLedger(OreLedger.Limits.defaults(),Map.of(),clock::get);}
    private OreLedger.BlockKey block(int x){return new OreLedger.BlockKey(world,x,-55,-31);}
    private OreLedger.Observation mine(OreLedger ledger,int x,String material){return ledger.mined(player,"Miner","World",block(x),material);}
    @Test void allElevenFamiliesAndDeepslateVariantsBecomeOneObservationPerBlock(){
        OreLedger ledger=ledger();int x=0,count=0;
        for(OreKind ore:OreKind.values()){
            assertEquals(ore,mine(ledger,x++,ore.material).ore());count++;
            if(!Set.of(OreKind.QUARTZ,OreKind.NETHER_GOLD,OreKind.ANCIENT_DEBRIS).contains(ore)){assertEquals(ore,mine(ledger,x++,"DEEPSLATE_"+ore.material).ore());count++;}
        }
        assertEquals(19,count);assertNull(mine(ledger,998,"DIAMOND_BLOCK"));assertNull(mine(ledger,999,"RAW_IRON_BLOCK"));assertNull(mine(ledger,997,"STONE"));
    }
    @Test void sameTickPlacementBreakExcludesExactlyThatWorldAndMaterial(){
        OreLedger ledger=ledger();ledger.placed(block(-18),"DEEPSLATE_DIAMOND_ORE");assertNull(mine(ledger,-18,"DEEPSLATE_DIAMOND_ORE"));
        assertEquals(1L,ledger.coverage(0).get("placedExcluded"));
        ledger.placed(block(-17),"DIAMOND_ORE");
        assertNotNull(ledger.mined(player,"Miner","Other",new OreLedger.BlockKey(UUID.randomUUID(),-17,-55,-31),"DIAMOND_ORE"));
        assertNotNull(mine(ledger,-17,"DEEPSLATE_DIAMOND_ORE"));
    }
    @Test void expiredAndEvictedPlacementsHaveHonestPartialCoverage(){
        OreLedger ledger=new OreLedger(new OreLedger.Limits(20,4,1,1000),Map.of(),clock::get);
        ledger.placed(block(1),"DIAMOND_ORE");ledger.placed(block(2),"DIAMOND_ORE");
        assertNotNull(mine(ledger,1,"DIAMOND_ORE"));assertEquals(1L,ledger.coverage(0).get("placedEvicted"));
        clock.addAndGet(1000);assertNotNull(mine(ledger,2,"DIAMOND_ORE"));
        assertEquals(true,ledger.coverage(0).get("partial"));assertEquals("session-only",ledger.coverage(0).get("placedTracking"));assertEquals(true,ledger.coverage(0).get("unknownOlderPlaced"));
    }
    @Test void successfulNonOreReplacementForgetsFormerOre(){
        OreLedger ledger=ledger();ledger.placed(block(2),"DIAMOND_ORE");ledger.placed(block(2),"STONE");
        assertNotNull(mine(ledger,2,"DIAMOND_ORE"));assertFalse(ledger.isTracked(block(2)));
    }
    @Test void repeatedPlacementRefreshesFifoRatherThanEvictingAnUnrelatedNewerBlock(){
        OreLedger ledger=new OreLedger(new OreLedger.Limits(20,4,2,1000),Map.of(),clock::get);
        ledger.placed(block(1),"DIAMOND_ORE");ledger.placed(block(2),"DIAMOND_ORE");ledger.placed(block(1),"DIAMOND_ORE");ledger.placed(block(3),"DIAMOND_ORE");
        assertFalse(ledger.isTracked(block(2)));assertTrue(ledger.isTracked(block(1)));assertNull(mine(ledger,1,"DIAMOND_ORE"));
    }
    @Test void observationRetainsOriginalOccurrenceTimeAndClampsClockRegression(){
        OreLedger ledger=ledger();long at=clock.get();clock.addAndGet(61_000);
        assertEquals(at,ledger.mined(player,"Miner","World",block(1),"DIAMOND_ORE",at).at());
        long observed=ledger.observationTime();clock.addAndGet(-100_000);assertEquals(observed,ledger.observationTime());
    }
    @Test void missedVerificationAndPendingDropsRemainExplicit(){
        OreLedger ledger=ledger();ledger.skippedVerification();ledger.droppedPending();
        Map<String,Object> coverage=ledger.coverage(2);assertEquals(1L,coverage.get("verificationSkipped"));assertEquals(1L,coverage.get("pendingDropped"));assertEquals(2,coverage.get("pending"));assertEquals(true,coverage.get("partial"));
        assertThrows(UnsupportedOperationException.class,()->coverage.put("partial",false));
    }
    @Test void disablingCanReleaseAllPlacedPositionsWithoutErasingLossMetadata(){
        OreLedger ledger=ledger();for(int i=0;i<500;i++)ledger.placed(block(i),"DIAMOND_ORE");ledger.skippedVerification();ledger.clearTransient();
        assertEquals(0,ledger.coverage(0).get("placedTracked"));assertFalse(ledger.isTracked(block(4)));assertEquals(1L,ledger.coverage(0).get("verificationSkipped"));
    }
    @Test void thresholdsUseAbsoluteFiveMinuteCountsWithoutExtrapolation(){
        long[] counts=new long[OreKind.values().length];Map<OreKind,OreLedger.Threshold> thresholds=Map.of(OreKind.DIAMOND,new OreLedger.Threshold(4,8));
        counts[OreKind.DIAMOND.ordinal()]=3;assertEquals("GREEN",OreLedger.severity(counts,thresholds));
        counts[OreKind.DIAMOND.ordinal()]=4;assertEquals("YELLOW",OreLedger.severity(counts,thresholds));
        counts[OreKind.DIAMOND.ordinal()]=8;assertEquals("RED",OreLedger.severity(counts,thresholds));
    }
    @Test void lowerDefaultsAreBelowPreviousThresholdsNormalizedToFiveMinutes(){
        int[] yellow={64,192,320,640,80,32,384,1024,640,384,24};int[] red={160,512,800,1536,200,96,1000,3072,1536,1024,64};
        for(OreKind ore:OreKind.values()){assertTrue(ore.yellow<yellow[ore.ordinal()]/2,ore.id());assertTrue(ore.red<red[ore.ordinal()]/2,ore.id());}
    }
    @Test void invalidThresholdsAndMemoryLimitsAreRejected(){
        assertThrows(IllegalArgumentException.class,()->new OreLedger.Threshold(4,4));assertThrows(IllegalArgumentException.class,()->new OreLedger.Threshold(0,5));
        assertThrows(IllegalArgumentException.class,()->new OreLedger.Limits(65_537,1,1,1000));
    }
}
