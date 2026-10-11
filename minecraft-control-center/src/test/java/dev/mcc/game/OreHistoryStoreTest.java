package dev.mcc.game;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class OreHistoryStoreTest {
    @TempDir Path directory;
    private final AtomicLong clock=new AtomicLong(10_000_000);
    private final UUID player=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private OreHistoryStore store(){return new OreHistoryStore(directory.resolve("history.sqlite"),Map.of(),clock::get);}
    private void mine(OreHistoryStore store,UUID id,String name,OreKind ore,int count,long at){for(int i=0;i<count;i++)assertTrue(store.offer(new OreLedger.Observation(id,name,"World",ore,at)));}
    private Map<String,Object> snapshot(OreHistoryStore store)throws Exception{return store.snapshot(clock.get()).get(5,TimeUnit.SECONDS);}
    private void flush(OreHistoryStore store)throws Exception{store.flush().get(5,TimeUnit.SECONDS);}
    private void close(OreHistoryStore store)throws Exception{store.closeAsync().get(5,TimeUnit.SECONDS);}
    @SuppressWarnings("unchecked") private List<Map<String,Object>> players(Map<String,Object> snapshot){return (List<Map<String,Object>>)snapshot.get("players");}
    @SuppressWarnings("unchecked") private List<Map<String,Object>> top(Map<String,Object> snapshot){return (List<Map<String,Object>>)snapshot.get("topPlayers");}
    @SuppressWarnings("unchecked") private long count(Map<String,Object> row,String window,String ore){return ((Map<String,Map<String,Long>>)row.get("counts")).get(window).get(ore);}
    @SuppressWarnings("unchecked") private Map<String,Object> coverage(Map<String,Object> snapshot){return (Map<String,Object>)snapshot.get("historyCoverage");}
    private void sql(String sql)throws Exception{try(Connection connection=DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("history.sqlite"));Statement statement=connection.createStatement()){statement.execute(sql);}}

    @Test void allTimeTotalsAndTopFiveSurviveCloseAndRestart()throws Exception{
        OreHistoryStore first=store();mine(first,player,"Miner",OreKind.DIAMOND,3,clock.get());flush(first);
        long began=(long)snapshot(first).get("trackingSince");close(first);clock.addAndGet(3_700_000);
        OreHistoryStore second=store();try{
            Map<String,Object> value=snapshot(second);assertTrue(players(value).isEmpty());assertEquals(3L,top(value).getFirst().get("total"));assertEquals(began,value.get("trackingSince"));
            Map<String,Object> page=second.history("",0,12).get(5,TimeUnit.SECONDS);
            assertEquals(1L,page.get("totalPlayers"));assertEquals(3L,count(players(page).getFirst(),"ALL_TIME","diamond"));assertEquals(0L,count(players(page).getFirst(),"300","diamond"));
        }finally{close(second);}
    }
    @Test void recentWindowsUseExclusiveSecondBoundaryAndAllTimeNeverExpires()throws Exception{
        OreHistoryStore store=store();try{
            mine(store,player,"Miner",OreKind.DIAMOND,1,clock.get()-3_600_000);mine(store,player,"Miner",OreKind.DIAMOND,2,clock.get()-1_800_000);mine(store,player,"Miner",OreKind.DIAMOND,3,clock.get()-300_000);mine(store,player,"Miner",OreKind.DIAMOND,4,clock.get());
            flush(store);Map<String,Object> row=players(snapshot(store)).getFirst();
            assertEquals(4L,count(row,"300","diamond"));assertEquals(7L,count(row,"1800","diamond"));assertEquals(9L,count(row,"3600","diamond"));assertEquals(10L,count(row,"ALL_TIME","diamond"));
            clock.addAndGet(3_600_000);assertTrue(players(snapshot(store)).isEmpty());assertEquals(10L,top(snapshot(store)).getFirst().get("total"));
        }finally{close(store);}
    }
    @Test void allElevenOreKindsContributeToTheGlobalTotalAndStableRanking()throws Exception{
        OreHistoryStore store=store();try{
            for(OreKind ore:OreKind.values())mine(store,player,"AllOres",ore,1,clock.get());
            for(int i=2;i<=7;i++)mine(store,new UUID(0,i),"Player"+i,OreKind.IRON,i,clock.get());
            flush(store);List<Map<String,Object>> leaders=top(snapshot(store));assertEquals(5,leaders.size());assertEquals("AllOres",leaders.getFirst().get("name"));assertEquals(11L,leaders.getFirst().get("total"));assertEquals("Player7",leaders.get(1).get("name"));
        }finally{close(store);}
    }
    @Test void serverPagedHistorySearchEscapesWildcardsAndDoesNotExecuteSql()throws Exception{
        OreHistoryStore store=store();try{
            mine(store,player,"100%_Miner",OreKind.COAL,2,clock.get());mine(store,new UUID(0,2),"Other",OreKind.COAL,1,clock.get());flush(store);
            Map<String,Object> match=store.history("%_",0,1).get(5,TimeUnit.SECONDS);assertEquals(1L,match.get("totalPlayers"));assertEquals("100%_Miner",players(match).getFirst().get("name"));
            assertTrue(players(store.history("' OR 1=1 --",0,12).get(5,TimeUnit.SECONDS)).isEmpty());
            assertEquals("Other",players(store.history("",1,1).get(5,TimeUnit.SECONDS)).getFirst().get("name"));
        }finally{close(store);}
    }
    @Test void severityAlwaysUsesFiveMinutesEvenWhenAllTimeAndHourAreLarge()throws Exception{
        OreHistoryStore store=store();try{
            mine(store,player,"Miner",OreKind.DIAMOND,100,clock.get()-301_000);mine(store,player,"Miner",OreKind.DIAMOND,1,clock.get());flush(store);
            Map<String,Object> row=players(store.history("",0,12).get(5,TimeUnit.SECONDS)).getFirst();assertEquals(101L,count(row,"ALL_TIME","diamond"));assertEquals(101L,count(row,"3600","diamond"));assertEquals("GREEN",row.get("severity"));
            mine(store,player,"Miner",OreKind.DIAMOND,23,clock.get());flush(store);assertEquals("YELLOW",players(snapshot(store)).getFirst().get("severity"));
        }finally{close(store);}
    }
    @Test void bufferCoalescesThousandsOfBlocksAndRejectsOnlyNewGroupsAtCapacity()throws Exception{
        OreHistoryStore store=new OreHistoryStore(directory.resolve("history.sqlite"),Map.of(),clock::get,new OreHistoryStore.Limits(1,100,10),CompletableFuture.completedFuture(null));try{
            mine(store,player,"Miner",OreKind.COAL,5000,clock.get());
            assertFalse(store.offer(new OreLedger.Observation(new UUID(0,2),"Other","World",OreKind.IRON,clock.get())));
            assertEquals(5000L,store.state().get("pendingBlocks"));assertEquals(1L,store.state().get("droppedBlocks"));
            flush(store);assertEquals(5000L,top(snapshot(store)).getFirst().get("total"));assertEquals(true,coverage(snapshot(store)).get("partial"));
        }finally{close(store);}
    }
    @Test void recentRowCapacityDoesNotReduceDurableAllTimeTotals()throws Exception{
        OreHistoryStore store=new OreHistoryStore(directory.resolve("history.sqlite"),Map.of(),clock::get,new OreHistoryStore.Limits(20,2,10),CompletableFuture.completedFuture(null));try{
            for(int i=0;i<5;i++)mine(store,player,"Miner",OreKind.COAL,1,clock.get()-i*1000);flush(store);
            Map<String,Object> value=snapshot(store);assertEquals(5L,top(value).getFirst().get("total"));assertEquals(2L,count(players(value).getFirst(),"3600","coal"));assertEquals(3L,coverage(value).get("recentRowsEvicted"));assertEquals(true,coverage(value).get("partial"));
        }finally{close(store);}
    }
    @Test void activePlayerDisplayCapDoesNotErasePlayersFromAllTimeSearch()throws Exception{
        OreHistoryStore store=new OreHistoryStore(directory.resolve("history.sqlite"),Map.of(),clock::get,new OreHistoryStore.Limits(20,100,1),CompletableFuture.completedFuture(null));try{
            mine(store,player,"One",OreKind.COAL,1,clock.get());mine(store,new UUID(0,2),"Two",OreKind.COAL,2,clock.get());flush(store);
            Map<String,Object> value=snapshot(store);assertEquals(1,players(value).size());assertEquals(true,coverage(value).get("recentPlayersTruncated"));assertEquals(2L,store.history("",0,12).get(5,TimeUnit.SECONDS).get("totalPlayers"));assertEquals(2,top(value).size());
        }finally{close(store);}
    }
    @Test void failedTransactionIsRetriedWithoutDoubleCountingEitherTable()throws Exception{
        OreHistoryStore store=store();try{
            snapshot(store);sql("CREATE TRIGGER block_recent BEFORE INSERT ON ore_recent BEGIN SELECT RAISE(ABORT,'test'); END");
            mine(store,player,"Miner",OreKind.DIAMOND,3,clock.get());assertThrows(ExecutionException.class,()->flush(store));snapshot(store);
            assertTrue(top(snapshot(store)).isEmpty());sql("DROP TRIGGER block_recent");flush(store);
            Map<String,Object> value=snapshot(store);assertEquals(3L,top(value).getFirst().get("total"));assertEquals(3L,count(players(value).getFirst(),"300","diamond"));flush(store);assertEquals(3L,top(snapshot(store)).getFirst().get("total"));
        }finally{close(store);}
    }
    @Test void closeFlushesPendingRowsAndIsIdempotent()throws Exception{
        OreHistoryStore first=store();mine(first,player,"Miner",OreKind.IRON,7,clock.get());CompletableFuture<Void> one=first.closeAsync();assertSame(one,first.closeAsync());one.get(5,TimeUnit.SECONDS);assertFalse(first.offer(new OreLedger.Observation(player,"Miner","World",OreKind.IRON,clock.get())));
        OreHistoryStore second=store();try{assertEquals(7L,top(snapshot(second)).getFirst().get("total"));assertEquals(false,coverage(snapshot(second)).get("possibleUnflushedLoss"));}finally{close(second);}
    }
    @Test void previousUnconfirmedShutdownIsExposedWithoutInventingMissingTotals()throws Exception{
        OreHistoryStore first=store();mine(first,player,"Miner",OreKind.IRON,2,clock.get());flush(first);close(first);sql("UPDATE ore_meta SET value='1' WHERE key='active'");
        OreHistoryStore second=store();try{Map<String,Object> value=snapshot(second);assertEquals(true,coverage(value).get("possibleUnflushedLoss"));assertEquals(true,coverage(value).get("partial"));assertEquals(2L,top(value).getFirst().get("total"));}finally{close(second);}
    }
    @Test void invalidPagingAndInvalidDatabasePathFailClosed()throws Exception{
        OreHistoryStore store=store();try{
            assertThrows(ExecutionException.class,()->store.history("",-1,12).get());assertThrows(ExecutionException.class,()->store.history("",0,51).get());assertThrows(ExecutionException.class,()->store.history("x".repeat(65),0,12).get());
        }finally{close(store);}
        Path invalid=directory.resolve("directory");Files.createDirectory(invalid);OreHistoryStore bad=new OreHistoryStore(invalid,Map.of(),clock::get);
        assertThrows(ExecutionException.class,()->snapshot(bad));assertEquals("error",bad.state().get("status"));assertThrows(ExecutionException.class,()->close(bad));
    }
    @Test void immutablePublishedRowsDoNotChangeAfterNewFlushes()throws Exception{
        OreHistoryStore store=store();try{
            mine(store,player,"Miner",OreKind.IRON,1,clock.get());flush(store);Map<String,Object> value=snapshot(store);Map<String,Object> row=players(value).getFirst();
            assertThrows(UnsupportedOperationException.class,()->value.put("players",List.of()));assertThrows(UnsupportedOperationException.class,()->players(value).clear());assertThrows(UnsupportedOperationException.class,()->row.put("name","Other"));
            mine(store,player,"Miner",OreKind.IRON,2,clock.get());flush(store);assertEquals(1L,count(row,"ALL_TIME","iron"));assertEquals(3L,top(snapshot(store)).getFirst().get("total"));
        }finally{close(store);}
    }
    @Test void reenableWaitsForOldStoreCloseBeforeOpeningTheSameDatabase()throws Exception{
        OreHistoryStore first=store();mine(first,player,"Miner",OreKind.IRON,3,clock.get());CompletableFuture<Void> previous=first.closeAsync();
        OreHistoryStore second=new OreHistoryStore(directory.resolve("history.sqlite"),Map.of(),clock::get,OreHistoryStore.Limits.defaults(),previous);
        try{assertEquals(3L,top(snapshot(second)).getFirst().get("total"));}finally{close(second);}
    }
    @Test void coverageLossesSurviveRestartAndRepeatedSamplesAreNotAddedTwice()throws Exception{
        OreHistoryStore first=store();first.noteCoverage(2,3,4);flush(first);first.noteCoverage(2,3,4);flush(first);close(first);
        OreHistoryStore second=store();try{Map<String,Object> loss=coverage(snapshot(second));assertEquals(2L,loss.get("verificationSkipped"));assertEquals(3L,loss.get("pendingDropped"));assertEquals(4L,loss.get("placedEvicted"));assertEquals(true,loss.get("partial"));second.noteCoverage(1,1,1);}finally{close(second);}
        OreHistoryStore third=store();try{Map<String,Object> loss=coverage(snapshot(third));assertEquals(3L,loss.get("verificationSkipped"));assertEquals(4L,loss.get("pendingDropped"));assertEquals(5L,loss.get("placedEvicted"));}finally{close(third);}
    }
}
