package dev.mcc.game;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BuildEngineTest {
    private static Map<String,Object> xyz(int x,int y,int z){return Map.of("x",x,"y",y,"z",z);}
    private static Map<String,Object> region(Map<String,Object> a,Map<String,Object> b){return Map.of("min",a,"max",b);}
    @Test void reversedSelectionHasInclusiveBoundsAndHardVolumeLimit() {
        List<BuildEngine.Point> fill=BuildEngine.geometry("fill",region(xyz(15,15,15),xyz(0,0,0)));
        assertEquals(4096,fill.size());assertEquals(4096,new HashSet<>(fill).size());
        assertTrue(fill.contains(new BuildEngine.Point(0,0,0)));assertTrue(fill.contains(new BuildEngine.Point(15,15,15)));
        assertThrows(IllegalArgumentException.class,()->BuildEngine.geometry("fill",region(xyz(0,0,0),xyz(16,15,15))));
        assertThrows(IllegalArgumentException.class,()->BuildEngine.geometry("box",region(xyz(0,0,0),xyz(100,100,100))));
    }
    @Test void lineFollowsEndpointsWithoutDuplicatesAndBoxOmitsInterior() {
        List<BuildEngine.Point> line=BuildEngine.geometry("line",region(xyz(3,1,1),xyz(0,0,0)));
        assertEquals(new BuildEngine.Point(3,1,1),line.getFirst());assertEquals(new BuildEngine.Point(0,0,0),line.getLast());
        assertEquals(line.size(),new HashSet<>(line).size());
        List<BuildEngine.Point> box=BuildEngine.geometry("box",region(xyz(0,0,0),xyz(2,2,2)));
        assertEquals(26,box.size());assertFalse(box.contains(new BuildEngine.Point(1,1,1)));
        assertEquals(1,BuildEngine.geometry("sphere",region(xyz(0,0,0),xyz(0,0,0))).size());
    }
    @Test void malformedFractionalAndUnboundedCoordinatesAreRejected() {
        assertThrows(IllegalArgumentException.class,()->BuildEngine.point(Map.of("x",1.9,"y",64,"z",0)));
        assertThrows(IllegalArgumentException.class,()->BuildEngine.point(Map.of("x",Double.NaN,"y",64,"z",0)));
        assertThrows(IllegalArgumentException.class,()->BuildEngine.point(Map.of("x",1,"z",0)));
        assertThrows(IllegalArgumentException.class,()->BuildEngine.geometry("place",region(xyz(0,0,0),xyz(1,0,0))));
    }
    @Test void pasteRotationKeepsNonSquareClipboardWithinRotatedBounds() {
        assertEquals(new BuildEngine.Point(2,1,0),BuildEngine.rotate(new BuildEngine.Point(0,1,0),2,3,90));
        assertEquals(new BuildEngine.Point(0,1,1),BuildEngine.rotate(new BuildEngine.Point(1,1,2),2,3,90));
        assertEquals(new BuildEngine.Point(1,1,2),BuildEngine.rotate(new BuildEngine.Point(0,1,0),2,3,180));
        assertEquals(new BuildEngine.Point(0,1,1),BuildEngine.rotate(new BuildEngine.Point(0,1,0),2,3,270));
        assertThrows(IllegalArgumentException.class,()->BuildEngine.rotation(Map.of("rotation",45)));
        assertThrows(IllegalArgumentException.class,()->BuildEngine.rotation(Map.of("rotation",90.1)));
    }
    @Test void undoAndCrashRecoveryPreserveForeignEditsAndSkipAlreadyRestoredCells() {
        String expected="minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]";
        String original="minecraft:air";
        assertEquals(BuildEngine.Decision.WRITE,BuildEngine.reconcile(expected,original,expected));
        assertEquals(BuildEngine.Decision.SKIP,BuildEngine.reconcile(expected,original,original));
        assertThrows(IllegalStateException.class,()->BuildEngine.reconcile(expected,original,expected.replace("north","east")));
        assertThrows(IllegalStateException.class,()->BuildEngine.reconcile(expected,original,"minecraft:oak_stairs"));
        assertThrows(IllegalStateException.class,()->BuildEngine.reconcile(expected,original,"minecraft:diamond_block"));
        // Redo uses the inverse expectation, and cannot overwrite a subsequent player build either.
        assertEquals(BuildEngine.Decision.WRITE,BuildEngine.reconcile(original,expected,original));
        assertThrows(IllegalStateException.class,()->BuildEngine.reconcile(original,expected,"minecraft:stone"));
    }
    @Test void accountMigrationReservesBothNamesAndRejectsOverlappingChanges() {
        BuildEngine.AccountChangeGates gates=new BuildEngine.AccountChangeGates();gates.begin("owner","renamed");
        assertThrows(IllegalStateException.class,()->gates.checkAvailable("owner"));
        assertThrows(IllegalStateException.class,()->gates.checkAvailable("renamed"));
        assertThrows(IllegalStateException.class,()->gates.begin("owner","third"));
        assertThrows(IllegalStateException.class,()->gates.begin("third","renamed"));
        // A rejected overlapping change must not reserve the third account.
        assertDoesNotThrow(()->gates.checkAvailable("third"));
        gates.end("owner","renamed");assertDoesNotThrow(()->gates.checkAvailable("owner"));assertDoesNotThrow(()->gates.checkAvailable("renamed"));
    }
    @Test void mismatchedCompletionCannotReleaseAnotherAccountChange() {
        BuildEngine.AccountChangeGates gates=new BuildEngine.AccountChangeGates();gates.begin("a","b");gates.begin("c","d");
        assertThrows(IllegalStateException.class,()->gates.end("a","d"));
        assertThrows(IllegalStateException.class,()->gates.end("b","a"));
        for(String actor:List.of("a","b","c","d"))assertThrows(IllegalStateException.class,()->gates.checkAvailable(actor));
        gates.end("a","b");assertDoesNotThrow(()->gates.checkAvailable("a"));assertThrows(IllegalStateException.class,()->gates.checkAvailable("c"));
        gates.end("c","d");assertThrows(IllegalStateException.class,()->gates.end("c","d"));
    }
    @Test void unchangedNameStillHasExclusiveAccountGate() {
        BuildEngine.AccountChangeGates gates=new BuildEngine.AccountChangeGates();gates.begin("owner","owner");
        assertThrows(IllegalStateException.class,()->gates.begin("other","owner"));
        assertThrows(IllegalStateException.class,()->gates.checkAvailable("owner"));
        gates.end("owner","owner");assertDoesNotThrow(()->gates.checkAvailable("owner"));
    }
    @Test void historyEvictionSkipsRunningAndQueuedActorsWithoutBlockingOnOldest() {
        List<String> actors=List.of("running","queued","idle","new");
        assertEquals("idle",BuildEngine.historyEvictionCandidate(actors,Set.of("queued"),"running"));
        assertEquals("running",BuildEngine.historyEvictionCandidate(actors,Set.of("queued"),"new"));
        assertNull(BuildEngine.historyEvictionCandidate(List.of("running","queued"),Set.of("queued"),"running"));
    }
}
