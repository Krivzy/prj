package dev.mcc.game;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorldTerrainEngineTest {
    @TempDir Path folder;
    private static void location(byte[] bytes,int x,int z,int offset,int sectors){int i=(x+32*z)*4;bytes[i]=(byte)(offset>>16);bytes[i+1]=(byte)(offset>>8);bytes[i+2]=(byte)offset;bytes[i+3]=(byte)sectors;}
    @Test void anvilCoverageUsesExactLocationsAndIgnoresInvalidHeaderOffsets(){byte[] header=new byte[4096];location(header,0,0,2,1);location(header,31,31,300,4);location(header,4,7,1,1);location(header,3,9,4,0);byte[] bitmap=WorldTerrainEngine.presence(header);assertEquals(128,bitmap.length);assertEquals(1,bitmap[0]);assertEquals((byte)128,bitmap[127]);assertEquals(2,java.util.stream.IntStream.range(0,128).map(i->Integer.bitCount(bitmap[i]&255)).sum());assertThrows(IllegalArgumentException.class,()->WorldTerrainEngine.presence(new byte[1024]));}
    @Test void viewportBoundsLimitResourceRadiusWithoutLimitingExplorationToSpawn(){assertDoesNotThrow(()->WorldTerrainEngine.validateView(-120000,540000,3,4));assertDoesNotThrow(()->WorldTerrainEngine.validateView(1874990,-1874990,0,1));assertThrows(IllegalArgumentException.class,()->WorldTerrainEngine.validateView(Integer.MIN_VALUE,0,1,1));assertThrows(IllegalArgumentException.class,()->WorldTerrainEngine.validateView(0,0,4,1));assertThrows(IllegalArgumentException.class,()->WorldTerrainEngine.validateView(0,0,1,3));}
    @Test void modernDimensionIndexIncludesNegativeAndFarRegionsWithoutLoadingChunks()throws Exception{Path region=Files.createDirectories(folder.resolve("dimensions/minecraft/overworld/region"));byte[] a=new byte[4096],b=new byte[4096];location(a,31,0,2,1);location(b,1,2,2,1);Files.write(region.resolve("r.-1.0.mca"),a);Files.write(region.resolve("r.120.-70.mca"),b);Files.write(region.resolve("r.0.0.mca"),new byte[60]);Map<String,Object> index=WorldTerrainEngine.scanIndex(folder,"world","overworld");assertEquals(2,index.get("savedChunks"));assertEquals(Map.of("minX",-1,"maxX",3841,"minZ",-2238,"maxZ",0),index.get("bounds"));assertEquals(2,((List<?>)index.get("regions")).size());assertEquals(false,index.get("truncated"));}
    @Test void emptyAndLegacyNetherPathsHaveTruthfulSavedCoverage()throws Exception{assertNull(WorldTerrainEngine.scanIndex(folder,"empty","overworld").get("bounds"));Path region=Files.createDirectories(folder.resolve("DIM-1/region"));byte[] bytes=new byte[4096];location(bytes,4,6,2,1);Files.write(region.resolve("r.2.3.mca"),bytes);Map<String,Object> index=WorldTerrainEngine.scanIndex(folder,"nether","the_nether");assertEquals(1,index.get("savedChunks"));assertEquals(Map.of("minX",68,"maxX",68,"minZ",102,"maxZ",102),index.get("bounds"));}
    @Test void inflightLoadsReserveTicketCapacityButExistingLeasesRemainReadable(){
        for(int held=0;held<=WorldTerrainEngine.MAX_TICKETS;held++)for(int inflight=0;inflight<=WorldTerrainEngine.MAX_LOADS;inflight++){
            assertEquals(held+inflight<WorldTerrainEngine.MAX_TICKETS,WorldTerrainEngine.canAdmitLoad(false,held,inflight));
            assertTrue(WorldTerrainEngine.canAdmitLoad(true,held,inflight),"Already-held chunk must refresh even when editor leases fill all8 slots");
        }
        assertFalse(WorldTerrainEngine.canAdmitLoad(false,7,1));
        assertTrue(WorldTerrainEngine.canAdmitLoad(false,6,1));
    }
    @Test void temporaryCapacityAndIoFailuresNeverMasqueradeAsUnsavedTerrain(){
        assertEquals("deferred",WorldTerrainEngine.retryStatus(new WorldTerrainEngine.CapacityException()));
        assertEquals("error",WorldTerrainEngine.retryStatus(new java.io.IOException("retry")));
        assertEquals("error",WorldTerrainEngine.retryStatus(new IllegalStateException("chunk unloaded")));
    }
}
