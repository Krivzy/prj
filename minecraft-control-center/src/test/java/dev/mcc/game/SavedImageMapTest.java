package dev.mcc.game;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class SavedImageMapTest {
    @TempDir Path folder;
    private SavedImageMap.WorldInfo info(Path world,UUID id){return new SavedImageMap.WorldInfo("world",id,world,"overworld",-64,320,Map.of("x",0,"y",64,"z",0));}
    private static long number(SavedImageMap map,String key){return ((Number)map.manifest("world").get(key)).longValue();}
    private static void await(BooleanSupplier condition)throws Exception{long deadline=System.currentTimeMillis()+5000;while(System.currentTimeMillis()<deadline&&!condition.getAsBoolean())Thread.sleep(25);assertTrue(condition.getAsBoolean(),"Autonomous scan must complete within its bounded test fixture");}
    private static void awaitRecovery(BooleanSupplier condition)throws Exception{long deadline=System.currentTimeMillis()+20000;while(System.currentTimeMillis()<deadline&&!condition.getAsBoolean())Thread.sleep(25);assertTrue(condition.getAsBoolean(),"Durable image recovery must complete in the isolated saved fixture");}
    private static void awaitRecovery(BooleanSupplier condition,String phase,SavedImageMap map)throws Exception{
        // One permitted duty-control delay is already 5s; complete scan/pyramid drainage needs additional quanta.
        long deadline=System.currentTimeMillis()+20000;while(System.currentTimeMillis()<deadline&&!condition.getAsBoolean())Thread.sleep(25);
        assertTrue(condition.getAsBoolean(),()->{Map<String,Object> state=map.manifest("world");return phase+": status="+state.get("status")+", pendingTiles="+state.get("pendingTiles")+", scannedAt="+state.get("scannedAt")+", failures="+state.get("failures");});
    }
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
    @Test void warmForcedPassDoesNotRewriteIdenticalSurfaceOrPng()throws Exception{
        Path world=folder.resolve("warm"),cache=folder.resolve("warm-cache");UUID id=UUID.randomUUID();AtomicLong time=new AtomicLong(System.currentTimeMillis());
        SavedSurfaceReaderTest.world(world,0,0,SavedSurfaceReaderTest.record("full",0,0,4,true,2),1);
        try(SavedImageMap map=new SavedImageMap(cache,time::get)){
            map.register(info(world,id));map.enabled(true);await(()->"ready".equals(map.manifest("world").get("status")));
            Path surface=cache.resolve(id+"/surfaces/0_0/0_0.bin"),png=cache.resolve(id+"/tiles/0/0_-1.png");
            byte[] before=Files.readAllBytes(surface);SavedImageMap.Tile tile=map.tile("world",0,0,-1);long revision=number(map,"revision");FileTime sentinel=FileTime.fromMillis(12000);
            Files.setLastModifiedTime(surface,sentinel);Files.setLastModifiedTime(png,sentinel);time.addAndGet(300001);
            await(()->number(map,"scannedAt")==time.get()&&"ready".equals(map.manifest("world").get("status")));
            assertEquals(2,number(map,"decodedRecords"),"A forced pass must still validate persisted contents");assertArrayEquals(before,Files.readAllBytes(surface));assertEquals(sentinel,Files.getLastModifiedTime(surface),"Identical forced surface must not be rewritten");
            assertEquals(sentinel,Files.getLastModifiedTime(png));assertEquals(tile.etag(),map.tile("world",0,0,-1).etag());assertEquals(revision,number(map,"revision"));assertEquals(0,number(map,"pendingTiles"));
        }
    }
    @Test void changedHeaderWithSameImageOnlyRestampsSurfaceMetadata()throws Exception{
        Path world=folder.resolve("restamp"),cache=folder.resolve("restamp-cache");UUID id=UUID.randomUUID();AtomicLong time=new AtomicLong(System.currentTimeMillis());byte[] record=SavedSurfaceReaderTest.record("full",0,0,4,true,2);
        SavedSurfaceReaderTest.world(world,0,0,record,1);
        try(SavedImageMap map=new SavedImageMap(cache,time::get)){
            map.register(info(world,id));map.enabled(true);await(()->"ready".equals(map.manifest("world").get("status")));
            Path surface=cache.resolve(id+"/surfaces/0_0/0_0.bin"),png=cache.resolve(id+"/tiles/0/0_-1.png");byte[] before=Files.readAllBytes(surface);String etag=map.tile("world",0,0,-1).etag();long revision=number(map,"revision");FileTime sentinel=FileTime.fromMillis(12000);Files.setLastModifiedTime(png,sentinel);
            SavedSurfaceReaderTest.world(world,0,0,record,2);time.addAndGet(61000);await(()->number(map,"scannedAt")==time.get()&&"ready".equals(map.manifest("world").get("status")));
            byte[] after=Files.readAllBytes(surface);assertFalse(Arrays.equals(before,after),"Changed header stamp must persist for the next restart");assertArrayEquals(Arrays.copyOfRange(before,20,before.length),Arrays.copyOfRange(after,20,after.length),"Only cache stamp metadata changes");assertEquals(sentinel,Files.getLastModifiedTime(png));assertEquals(etag,map.tile("world",0,0,-1).etag());assertEquals(revision,number(map,"revision"));assertEquals(0,number(map,"pendingTiles"));
        }
    }
    @Test void forcedPassWithChangedHeightAndSameHeaderStillRedrawsImage()throws Exception{
        Path world=folder.resolve("height-change"),cache=folder.resolve("height-cache");AtomicLong time=new AtomicLong(System.currentTimeMillis());SavedSurfaceReaderTest.world(world,0,0,SavedSurfaceReaderTest.record("full",0,0,4,true,2),1);
        try(SavedImageMap map=new SavedImageMap(cache,time::get)){
            map.register(info(world,UUID.randomUUID()));map.enabled(true);await(()->"ready".equals(map.manifest("world").get("status")));String before=map.tile("world",0,0,-1).etag();long revision=number(map,"revision");
            SavedSurfaceReaderTest.world(world,0,0,SavedSurfaceReaderTest.record("full",0,0,5,true,2),1);time.addAndGet(300001);await(()->number(map,"scannedAt")==time.get()&&"ready".equals(map.manifest("world").get("status")));
            assertNotEquals(before,map.tile("world",0,0,-1).etag());assertTrue(number(map,"revision")>revision);assertEquals(1,number(map,"renderedChunks"));assertEquals(0,number(map,"pendingTiles"));
        }
    }
    @Test void removedSavedChunkClearsOldImageAfterCoalescedScan()throws Exception{
        Path world=folder.resolve("remove"),cache=folder.resolve("remove-cache");AtomicLong time=new AtomicLong(System.currentTimeMillis());Path region=SavedSurfaceReaderTest.world(world,0,0,SavedSurfaceReaderTest.record("full",0,0,4,true,2),1);
        try(SavedImageMap map=new SavedImageMap(cache,time::get)){
            map.register(info(world,UUID.randomUUID()));map.enabled(true);
            awaitRecovery(()->"ready".equals(map.manifest("world").get("status")),"Initial saved image generation",map);
            String before=map.tile("world",0,0,-1).etag();Files.delete(region);time.addAndGet(61000);
            awaitRecovery(()->number(map,"scannedAt")==time.get()&&"empty".equals(map.manifest("world").get("status")),"Removed chunk scan and pyramid drainage",map);
            assertEquals(0,number(map,"renderedChunks"));assertEquals(0,number(map,"pendingTiles"));assertNotEquals(before,map.tile("world",0,0,-1).etag());assertNull(map.manifest("world").get("bounds"));
        }
    }
    @Test void imageTilesAreCoalescedUntilSavedRecordScanFinishes()throws Exception{
        Path world=folder.resolve("coalesce"),cache=folder.resolve("coalesce-cache");UUID id=UUID.randomUUID();
        // Independent regions keep the scan open across several bounded worker quanta.
        for(int i=0;i<16;i++)SavedSurfaceReaderTest.world(world,i*32,0,SavedSurfaceReaderTest.record("full",i*32,0,4,true,2),1);
        try(SavedImageMap map=new SavedImageMap(cache)){
            map.register(info(world,id));map.enabled(true);await(()->number(map,"scannedRecords")>=2&&number(map,"scannedAt")==0);assertTrue(number(map,"pendingTiles")>0);
            try(var files=Files.walk(cache.resolve(id+"/tiles"))){assertFalse(files.anyMatch(p->p.getFileName().toString().endsWith(".png")),"Shared PNGs must not be baked after each saved FULL record while a bounded scan is still active");}
        }
    }
    @Test void interruptedScanRestoresPersistedSurfacesWithMissingImages()throws Exception{
        Path world=folder.resolve("interrupted-new"),cache=folder.resolve("interrupted-new-cache");UUID id=UUID.randomUUID();
        for(int i=0;i<16;i++)SavedSurfaceReaderTest.world(world,i*32,0,SavedSurfaceReaderTest.record("full",i*32,0,4,true,2),1);
        try(SavedImageMap map=new SavedImageMap(cache)){map.register(info(world,id));map.enabled(true);await(()->number(map,"scannedRecords")>=2&&number(map,"scannedAt")==0);assertNull(map.tile("world",0,0,-1));}
        assertTrue(Files.exists(cache.resolve(id+"/images-dirty")),"Interrupted persisted surfaces require a durable recovery marker");
        try(SavedImageMap map=new SavedImageMap(cache)){map.register(info(world,id));map.enabled(true);awaitRecovery(()->"ready".equals(map.manifest("world").get("status")));assertEquals(16,number(map,"renderedChunks"));assertNotNull(map.tile("world",0,0,-1));assertNotNull(map.tile("world",1,0,-1));assertEquals(0,number(map,"pendingTiles"));}
        assertFalse(Files.exists(cache.resolve(id+"/images-dirty")));assertEquals("ready",dev.mcc.core.Json.object(Files.readString(cache.resolve(id+"/manifest.json"))).get("status"));
    }
    @Test void interruptedHeightUpdateCannotKeepStaleBaseAndParentPng()throws Exception{
        Path world=folder.resolve("interrupted-change"),cache=folder.resolve("interrupted-change-cache");UUID id=UUID.randomUUID();AtomicLong time=new AtomicLong(System.currentTimeMillis());SavedSurfaceReaderTest.world(world,0,0,SavedSurfaceReaderTest.record("full",0,0,4,true,2),1);String baseBefore,parentBefore;
        try(SavedImageMap map=new SavedImageMap(cache,time::get)){
            map.register(info(world,id));map.enabled(true);await(()->"ready".equals(map.manifest("world").get("status")));baseBefore=map.tile("world",0,0,-1).etag();parentBefore=map.tile("world",1,0,-1).etag();long decoded=number(map,"decodedRecords");
            SavedSurfaceReaderTest.world(world,0,0,SavedSurfaceReaderTest.record("full",0,0,5,true,2),1);time.addAndGet(300001);await(()->number(map,"decodedRecords")>decoded&&number(map,"scannedAt")<time.get());assertEquals(baseBefore,map.tile("world",0,0,-1).etag());
        }
        try(SavedImageMap map=new SavedImageMap(cache,time::get)){map.register(info(world,id));map.enabled(true);awaitRecovery(()->"ready".equals(map.manifest("world").get("status")));assertNotEquals(baseBefore,map.tile("world",0,0,-1).etag());assertNotEquals(parentBefore,map.tile("world",1,0,-1).etag());}
        assertFalse(Files.exists(cache.resolve(id+"/images-dirty")));
    }
    @Test void recoveryPropagatesIdenticalBasePngToMissingParent()throws Exception{
        Path world=folder.resolve("interrupted-parent"),cache=folder.resolve("interrupted-parent-cache");UUID id=UUID.randomUUID();SavedSurfaceReaderTest.world(world,0,0,SavedSurfaceReaderTest.record("full",0,0,4,true,2),1);String baseBefore,parentBefore;
        try(SavedImageMap map=new SavedImageMap(cache)){map.register(info(world,id));map.enabled(true);await(()->"ready".equals(map.manifest("world").get("status")));baseBefore=map.tile("world",0,0,-1).etag();parentBefore=map.tile("world",1,0,-1).etag();}
        Files.write(cache.resolve(id+"/images-dirty"),new byte[]{1});Files.delete(cache.resolve(id+"/tiles/1/0_-1.png"));
        try(SavedImageMap map=new SavedImageMap(cache)){map.register(info(world,id));map.enabled(true);awaitRecovery(()->"ready".equals(map.manifest("world").get("status")));assertEquals(baseBefore,map.tile("world",0,0,-1).etag());assertEquals(parentBefore,map.tile("world",1,0,-1).etag());}
    }
    @Test void interruptedFullToProtoRemovalClearsUnreferencedOldTiles()throws Exception{
        Path world=folder.resolve("interrupted-remove"),cache=folder.resolve("interrupted-remove-cache");UUID id=UUID.randomUUID();AtomicLong time=new AtomicLong(System.currentTimeMillis());SavedSurfaceReaderTest.world(world,0,0,SavedSurfaceReaderTest.record("full",0,0,4,true,2),1);String baseBefore;
        try(SavedImageMap map=new SavedImageMap(cache,time::get)){
            map.register(info(world,id));map.enabled(true);await(()->"ready".equals(map.manifest("world").get("status")));baseBefore=map.tile("world",0,0,-1).etag();SavedSurfaceReaderTest.world(world,0,0,SavedSurfaceReaderTest.record("noise",0,0,4,true,2),1);time.addAndGet(300001);await(()->number(map,"renderedChunks")==0&&number(map,"scannedAt")<time.get());assertEquals(baseBefore,map.tile("world",0,0,-1).etag());
        }
        assertFalse(Files.exists(cache.resolve(id+"/surfaces/0_0/0_0.bin")));
        try(SavedImageMap map=new SavedImageMap(cache,time::get)){map.register(info(world,id));map.enabled(true);awaitRecovery(()->"empty".equals(map.manifest("world").get("status")));assertNotEquals(baseBefore,map.tile("world",0,0,-1).etag());assertEquals(0,number(map,"renderedChunks"));assertEquals(0,number(map,"pendingTiles"));}
    }
    @Test void legacyReadyManifestWithSameCountCannotTrustStaleImageGeneration()throws Exception{
        Path world=folder.resolve("legacy-ready"),cache=folder.resolve("legacy-ready-cache");UUID id=UUID.randomUUID();AtomicLong time=new AtomicLong(System.currentTimeMillis());SavedSurfaceReaderTest.world(world,0,0,SavedSurfaceReaderTest.record("full",0,0,4,true,2),1);String baseBefore;Map<String,Object> legacy;
        try(SavedImageMap map=new SavedImageMap(cache,time::get)){
            map.register(info(world,id));map.enabled(true);await(()->"ready".equals(map.manifest("world").get("status")));baseBefore=map.tile("world",0,0,-1).etag();legacy=dev.mcc.core.Json.object(Files.readString(cache.resolve(id+"/manifest.json")));legacy.remove("imageCoherenceVersion");assertEquals("ready",legacy.get("status"));
            long decoded=number(map,"decodedRecords");SavedSurfaceReaderTest.world(world,0,0,SavedSurfaceReaderTest.record("full",0,0,5,true,2),1);time.addAndGet(300001);await(()->number(map,"decodedRecords")>decoded&&number(map,"scannedAt")<time.get());assertEquals(baseBefore,map.tile("world",0,0,-1).etag());
        }
        // Previous versions had no journal and could leave an older ready manifest after .bin changed.
        Files.delete(cache.resolve(id+"/images-dirty"));Files.writeString(cache.resolve(id+"/manifest.json"),dev.mcc.core.Json.write(legacy));
        try(SavedImageMap map=new SavedImageMap(cache,time::get)){map.register(info(world,id));map.enabled(true);awaitRecovery(()->"ready".equals(map.manifest("world").get("status")));assertNotEquals(baseBefore,map.tile("world",0,0,-1).etag());assertEquals(1,number(map,"imageCoherenceVersion"));}
    }
}
