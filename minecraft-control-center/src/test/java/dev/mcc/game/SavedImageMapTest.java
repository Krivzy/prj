package dev.mcc.game;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class SavedImageMapTest {
    @TempDir Path folder;
    private SavedImageMap.WorldInfo info(Path world,UUID id){return new SavedImageMap.WorldInfo("world",id,world,"overworld",-64,320,Map.of("x",0,"y",64,"z",0));}
    private static long number(SavedImageMap map,String key){return ((Number)map.manifest("world").get(key)).longValue();}
    private static void await(BooleanSupplier condition)throws Exception{long deadline=System.currentTimeMillis()+5000;while(System.currentTimeMillis()<deadline&&!condition.getAsBoolean())Thread.sleep(25);assertTrue(condition.getAsBoolean(),"Autonomous scan must complete within its bounded test fixture");}
    @Test void viewerCannotEnqueueTerrainWorkWhenBackgroundPaused()throws Exception{
        Path world=folder.resolve("world"),cache=folder.resolve("cache");Path region=SavedSurfaceReaderTest.world(world,0,0,SavedSurfaceReaderTest.record("full",0,0,4,true,2),1);byte[] original=Files.readAllBytes(region);
        try(SavedImageMap map=new SavedImageMap(cache)){map.register(info(world,UUID.randomUUID()));map.paused(true);map.enabled(true);for(int i=0;i<100;i++){assertNull(map.tile("world",0,0,0));assertEquals(0,((Number)map.manifest("world").get("renderedChunks")).intValue());}Thread.sleep(200);assertEquals(0,((Number)map.manifest("world").get("pendingTiles")).intValue());assertArrayEquals(original,Files.readAllBytes(region));}
    }
    @Test void autonomousSavedFullRenderPersistsPngAndReadsAcrossRestart()throws Exception{
        Path world=folder.resolve("world"),cache=folder.resolve("cache");UUID id=UUID.randomUUID();Path region=SavedSurfaceReaderTest.world(world,0,0,SavedSurfaceReaderTest.record("full",0,0,4,true,2),1);byte[] original=Files.readAllBytes(region);SavedImageMap.Tile tile=null;
        try(SavedImageMap map=new SavedImageMap(cache)){map.register(info(world,id));map.enabled(true);long deadline=System.currentTimeMillis()+12000;while(System.currentTimeMillis()<deadline){tile=map.tile("world",0,0,-1);if(tile!=null)break;Thread.sleep(50);}assertNotNull(tile,"Background render must produce a stored PNG without any viewer demand job");var image=ImageIO.read(new ByteArrayInputStream(tile.png()));assertEquals(256,image.getWidth());assertEquals(256,image.getHeight());assertTrue(tile.etag().matches("\"[a-f0-9]{64}\""));assertEquals(1,((Number)map.manifest("world").get("renderedChunks")).intValue());assertEquals(true,map.manifest("world").get("backgroundOnly"));assertArrayEquals(original,Files.readAllBytes(region));}
        try(SavedImageMap restored=new SavedImageMap(cache)){restored.register(info(world,id));restored.paused(true);restored.enabled(true);SavedImageMap.Tile persisted=restored.tile("world",0,0,-1);assertNotNull(persisted);assertArrayEquals(tile.png(),persisted.png());assertEquals(tile.etag(),persisted.etag());Thread.sleep(250);assertNotNull(restored.manifest("world").get("bounds"));assertEquals("paused",restored.manifest("world").get("status"));assertEquals(1,((Number)restored.manifest("world").get("renderedChunks")).intValue());assertEquals(0,((Number)restored.manifest("world").get("maxLevel")).intValue());}
    }
    @Test void farTilesAndPyramidParametersDoNotLimitSavedAreaToSpawn(){assertDoesNotThrow(()->SavedImageMap.validateTile(0,-480000,480000));assertDoesNotThrow(()->SavedImageMap.validateTile(20,-1,1));assertThrows(IllegalArgumentException.class,()->SavedImageMap.validateTile(21,0,0));assertThrows(IllegalArgumentException.class,()->SavedImageMap.validateTile(0,Integer.MIN_VALUE,0));}
    @Test void persistedProtoIsNotMappedOrModified()throws Exception{
        Path world=folder.resolve("proto");Path region=SavedSurfaceReaderTest.world(world,0,0,SavedSurfaceReaderTest.record("minecraft:noise",0,0,4,true,2),1);byte[] original=Files.readAllBytes(region);
        try(SavedImageMap map=new SavedImageMap(folder.resolve("proto-cache"))){map.register(info(world,UUID.randomUUID()));map.enabled(true);long deadline=System.currentTimeMillis()+3000;while(System.currentTimeMillis()<deadline&&!"empty".equals(map.manifest("world").get("status")))Thread.sleep(50);assertEquals("empty",map.manifest("world").get("status"));assertEquals(0,((Number)map.manifest("world").get("renderedChunks")).intValue());assertNull(map.tile("world",0,0,-1));assertArrayEquals(original,Files.readAllBytes(region));}
    }
    @Test void overviewLevelUsesWholeSavedExtentRatherThanFixedMaximum(){assertEquals(0,SavedImageMap.overviewLevel(-32,-80,64,64));assertEquals(1,SavedImageMap.overviewLevel(-256,-80,256,64));assertEquals(20,SavedImageMap.overviewLevel(-120000000,-60000000,120000000,60000000));}
    @Test void failedSavedRecordsAreReportedAsErrorRatherThanAnEmptyWorld()throws Exception{
        Path world=folder.resolve("unsupported");byte[] bad=SavedSurfaceReaderTest.record("full",0,0,4,true,2);bad[0]=4;Path region=SavedSurfaceReaderTest.world(world,0,0,bad,1);byte[] original=Files.readAllBytes(region);
        try(SavedImageMap map=new SavedImageMap(folder.resolve("bad-cache"))){map.register(info(world,UUID.randomUUID()));map.enabled(true);await(()->number(map,"scannedAt")>0);assertEquals("error",map.manifest("world").get("status"));assertEquals(1,number(map,"scanFailures"));assertEquals(0,number(map,"renderedChunks"));assertFalse(((String)map.manifest("world").get("message")).isEmpty());assertArrayEquals(original,Files.readAllBytes(region));}
    }
    @Test void unchangedProtoSkipsNormalDecodeButChangedStampPromotesItToSavedFull()throws Exception{
        Path world=folder.resolve("promote");SavedSurfaceReaderTest.world(world,0,0,SavedSurfaceReaderTest.record("minecraft:noise",0,0,4,true,2),1);AtomicLong time=new AtomicLong(System.currentTimeMillis());
        try(SavedImageMap map=new SavedImageMap(folder.resolve("promote-cache"),time::get)){map.register(info(world,UUID.randomUUID()));map.enabled(true);await(()->number(map,"scannedAt")==time.get());assertEquals(1,number(map,"decodedRecords"));assertEquals(1,number(map,"nonFullRecords"));time.addAndGet(61000);await(()->number(map,"scannedAt")==time.get());assertEquals(1,number(map,"decodedRecords"),"Unchanged proto records must not be decompressed every normal pass");SavedSurfaceReaderTest.world(world,0,0,SavedSurfaceReaderTest.modernPalette(true),2);time.addAndGet(61000);await(()->number(map,"renderedChunks")==1);assertEquals(2,number(map,"decodedRecords"));assertEquals(0,number(map,"nonFullRecords"));}
    }
    @Test void forcedRescanDetectsProtoToFullWithIdenticalHeaderStamp()throws Exception{
        Path world=folder.resolve("force");SavedSurfaceReaderTest.world(world,0,0,SavedSurfaceReaderTest.record("minecraft:noise",0,0,4,true,2),1);AtomicLong time=new AtomicLong(System.currentTimeMillis());
        try(SavedImageMap map=new SavedImageMap(folder.resolve("force-cache"),time::get)){map.register(info(world,UUID.randomUUID()));map.enabled(true);await(()->number(map,"scannedAt")==time.get());SavedSurfaceReaderTest.world(world,0,0,SavedSurfaceReaderTest.modernPalette(true),1);time.addAndGet(61000);await(()->number(map,"scannedAt")==time.get());assertEquals(1,number(map,"decodedRecords"));assertEquals(0,number(map,"renderedChunks"));time.addAndGet(300000);await(()->number(map,"renderedChunks")==1);assertEquals(2,number(map,"decodedRecords"));assertEquals(0,number(map,"nonFullRecords"));}
    }
}
