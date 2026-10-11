package dev.mcc.web;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class RealtimeWorkGateTest {
    @Test void stateCapturedBeforeDisableIsDiscardedWhenDisableOccursDuringActualJsonEncoding()throws Exception{
        CountDownLatch encoding=new CountDownLatch(1),resume=new CountDownLatch(1);
        Map<String,Object> delayed=new AbstractMap<>(){
            @Override public Set<Entry<String,Object>> entrySet(){encoding.countDown();try{assertTrue(resume.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}return Map.<String,Object>of("players",List.of("old-player")).entrySet();}
        };
        try(Realtime stream=new Realtime(null,topic->true)){
            CompletableFuture<String> old=CompletableFuture.supplyAsync(()->stream.encodeCurrent("state",delayed,0));
            assertTrue(encoding.await(5,TimeUnit.SECONDS));stream.modulesChanged();resume.countDown();
            assertNull(old.get(5,TimeUnit.SECONDS));assertEquals(0L,stream.stats().get("published"));
            assertNotNull(stream.encodeCurrent("state",Map.of("modules",Map.of("playerSnapshots",false)),1));
        }finally{resume.countDown();}
    }
    @Test void disabledOrUnobservedTopicsNeverQueueOrEncodeEvenUnderRepeatedSignals(){
        AtomicBoolean enabled=new AtomicBoolean(false);
        try(Realtime stream=new Realtime(null,topic->enabled.get())){
            Object mustNotSerialize=new Object(){public String toString(){throw new AssertionError("Disabled payload was encoded");}};
            for(int i=0;i<10000;i++)stream.publish("inventory",mustNotSerialize);
            enabled.set(true);for(int i=0;i<10000;i++)stream.publish("players",mustNotSerialize); // No subscribers: same zero-work gate.
            stream.modulesChanged();assertEquals(0L,stream.sequence());Map<String,Object> actual=stream.stats();assertEquals(0L,actual.get("published"));assertEquals(0L,actual.get("dropped"));assertEquals(0,actual.get("eventQueue"));assertFalse(stream.subscribers("players"));
        }
    }
}
