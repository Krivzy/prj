package dev.mcc.game;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import static org.junit.jupiter.api.Assertions.*;

class WorldTerrainEngineTest {
    @TempDir Path folder;
    private static void location(byte[] bytes,int x,int z,int offset,int sectors){int i=(x+32*z)*4;bytes[i]=(byte)(offset>>16);bytes[i+1]=(byte)(offset>>8);bytes[i+2]=(byte)offset;bytes[i+3]=(byte)sectors;}
    @Test void anvilCoverageUsesExactLocationsAndIgnoresInvalidHeaderOffsets(){byte[] header=new byte[4096];location(header,0,0,2,1);location(header,31,31,300,4);location(header,4,7,1,1);location(header,3,9,4,0);byte[] bitmap=WorldTerrainEngine.presence(header);assertEquals(128,bitmap.length);assertEquals(1,bitmap[0]);assertEquals((byte)128,bitmap[127]);assertEquals(2,java.util.stream.IntStream.range(0,128).map(i->Integer.bitCount(bitmap[i]&255)).sum());assertThrows(IllegalArgumentException.class,()->WorldTerrainEngine.presence(new byte[1024]));}
    @Test void viewportBoundsLimitResourceRadiusWithoutLimitingExplorationToSpawn(){assertDoesNotThrow(()->WorldTerrainEngine.validateView(-120000,540000,3,4));assertDoesNotThrow(()->WorldTerrainEngine.validateView(1874990,-1874990,0,1));assertThrows(IllegalArgumentException.class,()->WorldTerrainEngine.validateView(Integer.MIN_VALUE,0,1,1));assertThrows(IllegalArgumentException.class,()->WorldTerrainEngine.validateView(0,0,4,1));assertThrows(IllegalArgumentException.class,()->WorldTerrainEngine.validateView(0,0,1,3));}
    @Test void modernDimensionIndexIncludesNegativeAndFarRegionsWithoutLoadingChunks()throws Exception{Path region=Files.createDirectories(folder.resolve("dimensions/minecraft/overworld/region"));byte[] a=new byte[4096],b=new byte[4096];location(a,31,0,2,1);location(b,1,2,2,1);Files.write(region.resolve("r.-1.0.mca"),a);Files.write(region.resolve("r.120.-70.mca"),b);Files.write(region.resolve("r.0.0.mca"),new byte[60]);Map<String,Object> index=WorldTerrainEngine.scanIndex(folder,"world","overworld");assertEquals(2,index.get("savedChunks"));assertEquals(Map.of("minX",-1,"maxX",3841,"minZ",-2238,"maxZ",0),index.get("bounds"));assertEquals(2,((List<?>)index.get("regions")).size());assertEquals(false,index.get("truncated"));}
    @Test void emptyAndLegacyNetherPathsHaveTruthfulSavedCoverage()throws Exception{assertNull(WorldTerrainEngine.scanIndex(folder,"empty","overworld").get("bounds"));Path region=Files.createDirectories(folder.resolve("DIM-1/region"));byte[] bytes=new byte[4096];location(bytes,4,6,2,1);Files.write(region.resolve("r.2.3.mca"),bytes);Map<String,Object> index=WorldTerrainEngine.scanIndex(folder,"nether","the_nether");assertEquals(1,index.get("savedChunks"));assertEquals(Map.of("minX",68,"maxX",68,"minZ",102,"maxZ",102),index.get("bounds"));}
    @Test void immediateSnapshotsRejectSameTickAndLowTpsWithoutRetainingChunks(){
        assertEquals(1,WorldTerrainEngine.MAX_LOADS,"Only one async/preflight reservation may run at once");
        assertEquals(0,WorldTerrainEngine.MAX_TICKETS,"Snapshot capture must not add an ENTITY_TICKING plugin ticket");
        assertTrue(WorldTerrainEngine.canSnapshot(120,119,20));
        assertFalse(WorldTerrainEngine.canSnapshot(120,120,20),"Two completions in the same server tick must not take two snapshots");
        assertFalse(WorldTerrainEngine.canSnapshot(121,120,17.99),"Low TPS must defer without an entity-ticking plugin ticket");
        assertTrue(WorldTerrainEngine.canSnapshot(121,120,18));
    }
    @Test void temporaryCapacityAndIoFailuresNeverMasqueradeAsUnsavedTerrain(){
        assertEquals("deferred",WorldTerrainEngine.retryStatus(new WorldTerrainEngine.CapacityException()));
        assertEquals("error",WorldTerrainEngine.retryStatus(new java.io.IOException("retry")));
        assertEquals("error",WorldTerrainEngine.retryStatus(new IllegalStateException("chunk unloaded")));
    }

    private static BlockData block(Material material,String state){
        return (BlockData)Proxy.newProxyInstance(BlockData.class.getClassLoader(),new Class<?>[]{BlockData.class},(proxy,method,args)->switch(method.getName()){
            case "getMaterial"->material;
            case "getAsString"->state;
            default->throw new AssertionError("Unexpected block read: "+method.getName());
        });
    }

    @Test void surfaceReadsExactlyOneTopBlockPerColumnWithoutAnyVoxelPayload(){
        AtomicInteger heightsRead=new AtomicInteger(),blocksRead=new AtomicInteger();
        BlockData grass=block(Material.GRASS_BLOCK,"minecraft:grass_block[snowy=false]");
        BlockData water=block(Material.WATER,"minecraft:water[level=0]");
        ChunkSnapshot snapshot=(ChunkSnapshot)Proxy.newProxyInstance(ChunkSnapshot.class.getClassLoader(),new Class<?>[]{ChunkSnapshot.class},(proxy,method,args)->switch(method.getName()){
            case "getHighestBlockYAt"->{heightsRead.incrementAndGet();yield -64+((int)args[0]+16*(int)args[1])%100;}
            case "getBlockData"->{int x=(int)args[0],y=(int)args[1],z=(int)args[2];assertEquals(-64+(x+16*z)%100,y,"No block below the surface may be read");blocksRead.incrementAndGet();yield x%2==0?grass:water;}
            default->throw new AssertionError("Unexpected snapshot read: "+method.getName());
        });
        Map<String,Object> tile=WorldTerrainEngine.sample(snapshot,-120,42,-64,320,1234);
        assertEquals(256,heightsRead.get());assertEquals(256,blocksRead.get());
        assertEquals(-120,tile.get("cx"));assertEquals(42,tile.get("cz"));assertEquals(1,tile.get("step"));
        assertEquals(1234L,tile.get("sampledAt"));assertEquals("ready",tile.get("status"));
        assertFalse(tile.containsKey("voxels"));assertFalse(tile.containsKey("depth"));
        List<?> heights=(List<?>)tile.get("heights"),blocks=(List<?>)tile.get("blocks"),palette=(List<?>)tile.get("palette");
        assertEquals(256,heights.size());assertEquals(256,blocks.size());assertEquals(2,palette.size());
        assertEquals(-64,heights.getFirst());assertEquals(-49,heights.get(15));assertEquals(-48,heights.get(16));
        assertEquals(blocks.getFirst(),blocks.get(16));assertNotEquals(blocks.getFirst(),blocks.get(1));
        assertThrows(UnsupportedOperationException.class,()->heights.clear());assertThrows(UnsupportedOperationException.class,()->blocks.clear());
    }

    @Test void invalidHeightAndAirColumnsRemainTransparentWithoutOutOfWorldReads(){
        AtomicInteger blocksRead=new AtomicInteger();BlockData air=block(Material.AIR,"minecraft:air"),stone=block(Material.STONE,"minecraft:stone");
        ChunkSnapshot snapshot=(ChunkSnapshot)Proxy.newProxyInstance(ChunkSnapshot.class.getClassLoader(),new Class<?>[]{ChunkSnapshot.class},(proxy,method,args)->switch(method.getName()){
            case "getHighestBlockYAt"->{int i=(int)args[0]+16*(int)args[1];yield i==0?-65:i==1?320:64;}
            case "getBlockData"->{assertEquals(64,args[1]);int i=(int)args[0]+16*(int)args[2];blocksRead.incrementAndGet();yield i==2?air:stone;}
            default->throw new AssertionError("Unexpected snapshot read: "+method.getName());
        });
        Map<String,Object> tile=WorldTerrainEngine.sample(snapshot,0,0,-64,320,0);
        List<?> heights=(List<?>)tile.get("heights"),blocks=(List<?>)tile.get("blocks");
        for(int i=0;i<3;i++){assertNull(heights.get(i));assertNull(blocks.get(i));}
        assertEquals(64,heights.get(3));assertEquals(254,blocksRead.get());assertEquals(1,((List<?>)tile.get("palette")).size());
    }
}
