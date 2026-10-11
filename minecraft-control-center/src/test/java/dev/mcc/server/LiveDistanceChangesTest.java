package dev.mcc.server;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class LiveDistanceChangesTest {
    @Test void secondWorldFailureRestoresFirstAndPartialSecond(){W first=new W("a"),second=new W("b");second.failSimulation=true;assertThrows(IllegalStateException.class,()->LiveDistanceChanges.apply(List.of(first,second),8,6,()->true));assertEquals(12,first.view);assertEquals(7,first.simulation);assertEquals(12,second.view);assertEquals(7,second.simulation);}
    @Test void logoutBetweenSettersRestoresChangedView(){W world=new W("a");AtomicBoolean active=new AtomicBoolean(true);world.afterView=()->active.set(false);assertThrows(SecurityException.class,()->LiveDistanceChanges.apply(List.of(world),8,6,active::get));assertEquals(12,world.view);assertEquals(7,world.simulation);}
    @Test void externalChangeDuringFailureIsNotOverwritten(){W first=new W("a"),second=new W("b");second.afterView=()->first.view=20;second.failSimulation=true;IllegalStateException error=assertThrows(IllegalStateException.class,()->LiveDistanceChanges.apply(List.of(first,second),8,6,()->true));assertTrue(error.getMessage().contains("rollback"));assertEquals(20,first.view);assertEquals(7,first.simulation);}
    @Test void validPublicRequestProducesActualBeforeAfterAndChecksBounds(){W world=new W("a");var result=LiveDistanceChanges.apply(List.of(world),8,6,()->true);assertEquals(12,result.getFirst().get("beforeView"));assertEquals(8,world.view);assertEquals(6,world.simulation);assertThrows(IllegalArgumentException.class,()->LiveDistanceChanges.apply(List.of(world),1,6,()->true));}
    private static class W implements LiveDistanceChanges.WorldAccess {String name;int view=12,simulation=7;boolean failSimulation;Runnable afterView=()->{};W(String name){this.name=name;}public String name(){return name;}public int view(){return view;}public int simulation(){return simulation;}public void view(int n){view=n;afterView.run();}public void simulation(int n){if(failSimulation&&n==6)throw new IllegalStateException("API rejected simulation");simulation=n;}}
}
