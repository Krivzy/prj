package dev.mcc.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class AccountHistoryTest {
    @TempDir Path directory;
    private static <T> T await(CompletableFuture<T> future)throws Exception{return future.get(5,TimeUnit.SECONDS);}
    private static Map<String,Object> history(String actor,boolean nonempty){
        Map<String,Object> edit=Map.of("id","58b688d4-8284-478e-a4c7-4b0e4253eacd","worldId","981f9cf8-892f-4b31-8f94-a48e83c6cce2","world","fixture","operation","place","at",1000,"changes",List.of(Map.of("x",4,"y",70,"z",5,"before","minecraft:air","after","minecraft:glass")));
        return Map.of("version",1,"actor",actor,"undo",List.of(),"redo",nonempty?List.of(edit):List.of());
    }

    @Test void renameTransfersJournalAndUserAtomicallyAndSurvivesReopen()throws Exception{
        Path file=directory.resolve("database.db");
        try(Database db=new Database(file)){
            await(db.createUser("old-owner","old-hash","Owner",false));await(db.document("build-history","old-owner",history("old-owner",true)));
            assertTrue(await(db.changeUser("old-owner","old-hash","new-owner","new-hash")));
            assertNull(await(db.user("old-owner")));assertEquals("new-hash",await(db.user("new-owner")).get("hash"));assertTrue(await(db.document("build-history","old-owner")).isEmpty());
            Map<String,Object> moved=await(db.document("build-history","new-owner")).orElseThrow();assertEquals("new-owner",moved.get("actor"));assertEquals(Json.write(history("new-owner",true)),Json.write(moved));
        }
        try(Database reopened=new Database(file)){assertEquals("Owner",await(reopened.user("new-owner")).get("role"));assertEquals("new-owner",await(reopened.document("build-history","new-owner")).orElseThrow().get("actor"));assertTrue(await(reopened.document("build-history","old-owner")).isEmpty());}
    }

    @Test void nonemptyDestinationRollsBackBothCredentialsAndBothJournals()throws Exception{
        try(Database db=new Database(directory.resolve("database.db"))){
            await(db.createUser("old-owner","old-hash","Owner",false));await(db.document("build-history","old-owner",history("old-owner",true)));await(db.document("build-history","unused-name",history("unused-name",true)));
            ExecutionException error=assertThrows(ExecutionException.class,()->await(db.changeUser("old-owner","old-hash","unused-name","new-hash")));assertInstanceOf(IllegalArgumentException.class,error.getCause());
            assertEquals("old-hash",await(db.user("old-owner")).get("hash"));assertNull(await(db.user("unused-name")));assertEquals("old-owner",await(db.document("build-history","old-owner")).orElseThrow().get("actor"));assertEquals("unused-name",await(db.document("build-history","unused-name")).orElseThrow().get("actor"));
            await(db.document("unrelated","probe",Map.of("ok",true)));assertTrue(await(db.document("unrelated","probe")).isPresent(),"Rollback restores autocommit for later queued work");
        }
    }

    @Test void emptyDestinationMayBeReplacedWithoutTouchingOtherNamespaces()throws Exception{
        try(Database db=new Database(directory.resolve("database.db"))){
            await(db.createUser("old-owner","old-hash","Owner",false));await(db.document("build-history","old-owner",history("old-owner",true)));await(db.document("build-history","new-owner",history("new-owner",false)));await(db.document("settings","old-owner",Map.of("preserve",true)));
            assertTrue(await(db.changeUser("old-owner","old-hash","new-owner","new-hash")));assertEquals(1,((List<?>)await(db.document("build-history","new-owner")).orElseThrow().get("redo")).size());assertTrue(await(db.document("build-history","old-owner")).isEmpty());assertEquals(Map.of("preserve",true),await(db.document("settings","old-owner")).orElseThrow());
        }
    }

    @Test void failedCredentialCasCannotMoveHistoryAndPasswordOnlyChangeKeepsHistory()throws Exception{
        try(Database db=new Database(directory.resolve("database.db"))){
            await(db.createUser("owner","old-hash","Owner",false));await(db.document("build-history","owner",history("owner",true)));
            assertFalse(await(db.changeUser("owner","wrong-hash","another-name","new-hash")));assertEquals("old-hash",await(db.user("owner")).get("hash"));assertTrue(await(db.document("build-history","another-name")).isEmpty());
            assertTrue(await(db.changeUser("owner","old-hash","owner","new-hash")));assertEquals("new-hash",await(db.user("owner")).get("hash"));assertEquals(Json.write(history("owner",true)),Json.write(await(db.document("build-history","owner")).orElseThrow()));
        }
    }

    @Test void sourceWithoutHistoryCannotInheritNonemptyDestination()throws Exception{
        try(Database db=new Database(directory.resolve("database.db"))){
            await(db.createUser("owner","old-hash","Owner",false));await(db.document("build-history","reused-name",history("reused-name",true)));
            assertThrows(ExecutionException.class,()->await(db.changeUser("owner","old-hash","reused-name","new-hash")));assertNotNull(await(db.user("owner")));assertNull(await(db.user("reused-name")));assertTrue(await(db.document("build-history","owner")).isEmpty());
        }
    }
}
