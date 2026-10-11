package dev.mcc.game;

import dev.mcc.core.Database;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class PunishmentTest {
    @Test void externalProviderSuppressesEnforcementAndMutationWithoutRemovingHistoriesOrBans() throws Exception {
        Path file=directory.resolve("external.db");Time clock=new Time();Game game=new Game(file);long ban;
        try(Database db=new Database(file);PunishmentEngine engine=engine(db,game,clock)) {
            await(engine.start());ban=((Number)await(engine.create(body("ban",1),"owner")).get("id")).longValue();await(engine.create(body("mute",0),"owner"));
            engine.externalManaged(()->true);assertFalse(engine.muted(PLAYER));assertNull(engine.activeBan(PLAYER));
            assertThrows(ExecutionException.class,()->await(engine.create(body("warn",0),"owner")));assertThrows(ExecutionException.class,()->await(engine.revoke(ban,"owner")));
            clock.now+=2000;await(engine.pulse());assertTrue(game.removed.isEmpty());assertEquals("MCCPunishment:"+ban,game.bans.get(PLAYER));assertEquals(2,await(engine.list(PLAYER,20)).size());
        }
        Game restarted=new Game(file,game.bans);
        try(Database db=new Database(file);PunishmentEngine engine=engine(db,restarted,clock)) {
            engine.externalManaged(()->true);await(engine.start());assertTrue(restarted.applied.isEmpty());assertTrue(restarted.removed.isEmpty());assertEquals(2,await(engine.list(PLAYER,20)).size());
            engine.externalManaged(()->false);await(engine.pulse());assertNull(restarted.bans.get(PLAYER));assertTrue(engine.muted(PLAYER));
        }
    }
    @Test void providerActivationWhileDatabaseRequestIsQueuedCannotPublishNativePunishment() throws Exception {
        Path file=directory.resolve("queued-external.db");Time clock=new Time();Game game=new Game(file);var managed=new java.util.concurrent.atomic.AtomicBoolean();
        try(Database db=new Database(file);PunishmentEngine engine=engine(db,game,clock)) {
            engine.externalManaged(managed::get);await(engine.start());var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
            var blocker=db.submit(connection->{entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Test barrier timeout");return null;});
            assertTrue(entered.await(5,TimeUnit.SECONDS));var pending=engine.create(body("ban",0),"owner");managed.set(true);release.countDown();await(blocker);
            assertThrows(ExecutionException.class,()->await(pending));assertTrue(game.applied.isEmpty());assertTrue(game.bans.isEmpty());
            assertEquals("failed",await(engine.list(PLAYER,10)).getFirst().get("status"));
        }
    }
    @Test void providerFailureIsFailClosedAndDoesNotErasePreviouslyStoredPunishments() throws Exception {
        Path file=directory.resolve("broken-external.db");Time clock=new Time();Game game=new Game(file);
        try(Database db=new Database(file);PunishmentEngine engine=engine(db,game,clock)) {
            await(engine.start());await(engine.create(body("mute",0),"owner"));engine.externalManaged(()->{throw new IllegalStateException("Bridge closed");});
            assertFalse(engine.muted(PLAYER));assertThrows(ExecutionException.class,()->await(engine.create(body("kick",0),"owner")));await(engine.pulse());assertEquals(1,await(engine.list(PLAYER,10)).size());
        }
    }
    @Test void shutdownWhileDatabaseRequestIsQueuedCannotApplyNewNativeBan() throws Exception {
        Path file=directory.resolve("queued-shutdown.db");Time clock=new Time();Game game=new Game(file);
        try(Database db=new Database(file);PunishmentEngine engine=engine(db,game,clock)) {
            await(engine.start());var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
            var blocker=db.submit(connection->{entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Test barrier timeout");return null;});
            assertTrue(entered.await(5,TimeUnit.SECONDS));var pending=engine.create(body("ban",0),"owner");engine.close();release.countDown();await(blocker);
            assertThrows(ExecutionException.class,()->await(pending));assertTrue(game.applied.isEmpty());assertTrue(game.bans.isEmpty());
            assertEquals("failed",await(engine.list(PLAYER,10)).getFirst().get("status"));
        }
    }
    @TempDir Path directory;
    private static final UUID PLAYER=UUID.fromString("0cd877f0-a5ef-3457-9666-52728b2dc04c");
    private static Map<String,Object> body(String type,long seconds){return Map.of("uuid",PLAYER.toString(),"type",type,"seconds",seconds,"reason","Test reason","targetName","TestPlayer");}
    private static <T> T await(CompletableFuture<T> future)throws Exception{return future.get(10,TimeUnit.SECONDS);}
    static final class Time extends Clock {long now=1000000;@Override public ZoneId getZone(){return ZoneOffset.UTC;}@Override public Clock withZone(ZoneId zone){return this;}@Override public Instant instant(){return Instant.ofEpochMilli(now);}@Override public long millis(){return now;}}
    static final class Game implements PunishmentEngine.Enforcement {
        final Path file;final Map<UUID,String> bans;final List<Long> visible=new ArrayList<>(),applied=new ArrayList<>(),removed=new ArrayList<>();boolean failApply,failRemove;
        Game(Path file){this(file,new HashMap<>());}Game(Path file,Map<UUID,String> bans){this.file=file;this.bans=bans;}
        @Override public CompletableFuture<Void> initialize(PunishmentEngine engine){return CompletableFuture.completedFuture(null);}
        @Override public CompletableFuture<Map<String,Object>> apply(Database.Punishment row,boolean restore){
            try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+file.toAbsolutePath());PreparedStatement s=c.prepareStatement("SELECT status FROM punishments WHERE id=?")){s.setLong(1,row.id());try(ResultSet result=s.executeQuery()){if(result.next())visible.add(row.id());}}
            catch(Exception e){return CompletableFuture.failedFuture(e);}
            if(failApply)return CompletableFuture.failedFuture(new IllegalArgumentException("Injected unavailable player"));
            UUID id=UUID.fromString(row.uuid());if(row.type().equals("ban")){String current=bans.get(id),source="MCCPunishment:"+row.id();if(current!=null&&!current.equals(source)){if(restore)return CompletableFuture.completedFuture(Map.of("foreignBanPreserved",true));return CompletableFuture.failedFuture(new IllegalStateException("Foreign ban"));}bans.put(id,source);}
            applied.add(row.id());return CompletableFuture.completedFuture(Map.of("delivered",true));
        }
        @Override public CompletableFuture<Void> remove(Database.Punishment row){if(failRemove)return CompletableFuture.failedFuture(new IllegalStateException("Injected stopped game queue"));removed.add(row.id());if(row.type().equals("ban"))bans.remove(UUID.fromString(row.uuid()),"MCCPunishment:"+row.id());return CompletableFuture.completedFuture(null);}
    }
    private PunishmentEngine engine(Database db,Game game,Time clock){return new PunishmentEngine(db,game,(topic,value)->{},clock);}

    @Test void validatesCanonicalUuidDurationAndTypesBeforePersistence(){
        assertEquals(PLAYER,PunishmentEngine.request(body("mute",PunishmentEngine.MAX_SECONDS)).uuid());
        for(Object invalid:List.of(-1,1.5,Double.NaN,Double.POSITIVE_INFINITY,PunishmentEngine.MAX_SECONDS+1,"30")){Map<String,Object> request=new HashMap<>(body("mute",1));request.put("seconds",invalid);assertThrows(IllegalArgumentException.class,()->PunishmentEngine.request(request));}
        assertThrows(IllegalArgumentException.class,()->PunishmentEngine.uuid("1-1-1-1-1"));
        assertThrows(IllegalArgumentException.class,()->PunishmentEngine.request(body("warn",30)));
        Map<String,Object> invalid=new HashMap<>(body("ban",0));invalid.put("type","op");assertThrows(IllegalArgumentException.class,()->PunishmentEngine.request(invalid));
        invalid.put("type","ban");invalid.put("reason"," ");assertThrows(IllegalArgumentException.class,()->PunishmentEngine.request(invalid));
    }

    @Test void gameSeesCommittedRowAndTimedMuteExpiresEvenBeforeBackgroundPulse() throws Exception {
        Path file=directory.resolve("database.db");Time clock=new Time();Game game=new Game(file);
        try(Database db=new Database(file);PunishmentEngine engine=engine(db,game,clock)){
            await(engine.start());Map<String,Object> created=await(engine.create(body("mute",2),"website-owner"));long id=((Number)created.get("id")).longValue();
            assertEquals(List.of(id),game.visible);assertTrue(engine.muted(PLAYER));assertEquals(clock.now+2000,created.get("expiresAt"));assertEquals(false,created.get("syncPending"));
            assertThrows(ExecutionException.class,()->await(engine.create(body("mute",0),"website-owner")));
            clock.now+=2000;assertFalse(engine.muted(PLAYER));await(engine.pulse());Map<String,Object> expired=await(engine.list(PLAYER,20)).getFirst();assertEquals("expired",expired.get("status"));assertEquals(false,expired.get("syncPending"));assertTrue(game.removed.contains(id));
            assertEquals(2,await(db.auditHistory()).size());
        }
    }

    @Test void failureIsDurableAndForeignNativeBanIsPreservedOnCreateAndExpiry() throws Exception {
        Path file=directory.resolve("database.db");Time clock=new Time();Game game=new Game(file);game.bans.put(PLAYER,"AnotherPlugin");
        try(Database db=new Database(file);PunishmentEngine engine=engine(db,game,clock)){
            await(engine.start());assertThrows(ExecutionException.class,()->await(engine.create(body("ban",1),"owner")));assertNull(engine.activeBan(PLAYER));assertEquals("failed",await(engine.list(PLAYER,20)).getFirst().get("status"));await(engine.pulse());assertEquals("AnotherPlugin",game.bans.get(PLAYER));assertEquals(false,await(engine.list(PLAYER,20)).getFirst().get("syncPending"));
            game.bans.clear();Map<String,Object> created=await(engine.create(body("ban",1),"owner"));assertNotNull(engine.activeBan(PLAYER));game.bans.put(PLAYER,"AnotherPlugin");clock.now+=1000;await(engine.pulse());assertEquals("AnotherPlugin",game.bans.get(PLAYER));assertEquals("expired",await(engine.list(PLAYER,20)).getFirst().get("status"));
            assertTrue(((Number)created.get("id")).longValue()>0);
        }
    }

    @Test void committedRevocationRetriesNativeRemovalAfterRestart() throws Exception {
        Path file=directory.resolve("database.db");Time clock=new Time();Game original=new Game(file);long id;
        try(Database db=new Database(file);PunishmentEngine engine=engine(db,original,clock)){
            await(engine.start());id=((Number)await(engine.create(body("ban",0),"owner")).get("id")).longValue();original.failRemove=true;assertThrows(ExecutionException.class,()->await(engine.revoke(id,"owner")));
            Map<String,Object> row=await(engine.list(PLAYER,10)).getFirst();assertEquals("revoked",row.get("status"));assertEquals(true,row.get("syncPending"));assertNotNull(original.bans.get(PLAYER));
        }
        Game restarted=new Game(file,original.bans);
        try(Database db=new Database(file);PunishmentEngine engine=engine(db,restarted,clock)){
            await(engine.start());assertNull(restarted.bans.get(PLAYER));assertNull(engine.activeBan(PLAYER));assertEquals(List.of(id),restarted.removed);assertEquals(false,await(engine.list(PLAYER,10)).getFirst().get("syncPending"));
        }
    }

    @Test void activeMuteAndBanPersistAndPreparedReasonDoesNotBecomeSql() throws Exception {
        Path file=directory.resolve("database.db");Time clock=new Time();Game original=new Game(file);
        try(Database db=new Database(file);PunishmentEngine engine=engine(db,original,clock)){
            await(engine.start());Map<String,Object> sqlReason=new HashMap<>(body("mute",0));sqlReason.put("reason","x'); DROP TABLE users; --");await(engine.create(sqlReason,"owner"));await(engine.create(body("ban",30),"owner"));assertEquals(0,await(db.userCount()));
        }
        Game restarted=new Game(file,original.bans);
        try(Database db=new Database(file);PunishmentEngine engine=engine(db,restarted,clock)){
            await(engine.start());assertTrue(engine.muted(PLAYER));assertNotNull(engine.activeBan(PLAYER));assertEquals(2,await(engine.list(PLAYER,20)).size());assertEquals(2,restarted.applied.size());await(engine.revokeType(PLAYER,"mute","minecraft:operator"));assertFalse(engine.muted(PLAYER));
        }
    }

    @Test void gameSyncCompareAndSetCannotEraseConcurrentRevocation() throws Exception {
        try(Database db=new Database(directory.resolve("database.db"))){Database.Punishment created=await(db.createPunishment(PLAYER,"Player","ban","Reason","owner",1000,0));await(db.revokePunishment(created.id(),"owner",2000));Database.Punishment lateApply=await(db.punishmentSynced(created.id(),"active"));assertEquals("revoked",lateApply.status());assertTrue(lateApply.syncPending());}
    }

    @Test void issuerRateAndHistoryBoundsLimitWork() throws Exception {
        Path file=directory.resolve("database.db");Time clock=new Time();Game game=new Game(file);
        try(Database db=new Database(file);PunishmentEngine engine=engine(db,game,clock)){
            await(engine.start());for(int i=0;i<8;i++)await(engine.create(body("warn",0),"operator"));assertThrows(ExecutionException.class,()->await(engine.create(body("warn",0),"operator")));clock.now+=1000;await(engine.create(body("warn",0),"operator"));assertEquals(3,await(engine.list(null,3)).size());assertThrows(ExecutionException.class,()->await(engine.list(null,201)));assertEquals(9,await(engine.list(PLAYER,200)).size());
        }
    }

    @Test void legacyStartupMigrationExceedsNormalBurstButRetainsValidationAndExpiry() throws Exception {
        Path file=directory.resolve("database.db");Time clock=new Time();Game game=new Game(file);
        try(Database db=new Database(file);PunishmentEngine engine=engine(db,game,clock)){
            await(engine.start());for(int i=0;i<20;i++){UUID id=new UUID(0,i+1);Map<String,Object> imported=await(engine.importLegacyMute(id,"LegacyPlayer"+i,clock.now+20000));assertTrue(engine.muted(id));assertEquals(clock.now+20000,imported.get("expiresAt"));}
            assertEquals(20,await(engine.list(null,200)).size());assertEquals(true,await(engine.importLegacyMute(PLAYER,"ExpiredPlayer",clock.now-1)).get("skipped"));assertFalse(engine.muted(PLAYER));
            assertThrows(ExecutionException.class,()->await(engine.importLegacyMute(PLAYER,"InvalidDuration",clock.now+(PunishmentEngine.MAX_SECONDS+1)*1000)));
            for(int i=0;i<8;i++)await(engine.create(body("warn",0),"migration"));assertThrows(ExecutionException.class,()->await(engine.create(body("warn",0),"migration")));
        }
    }

    @Test void incompleteKickRecoveryMarksFailureWithoutBlockingSubsystemStartup() throws Exception {
        Path file=directory.resolve("database.db");Time clock=new Time();Game game=new Game(file);game.failApply=true;
        try(Database db=new Database(file)){
            await(db.createPunishment(PLAYER,"Player","kick","Reason","owner",clock.now,0));
            try(PunishmentEngine engine=engine(db,game,clock)){await(engine.start());Map<String,Object> row=await(engine.list(PLAYER,10)).getFirst();assertEquals("failed",row.get("status"));assertEquals(false,row.get("syncPending"));}
        }
    }
}
