package dev.mcc.server;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Small public-API transaction, called wholly on the tick thread. */
final class LiveDistanceChanges {
    interface WorldAccess {
        String name();int view();int simulation();void view(int value);void simulation(int value);
    }
    private record Before(WorldAccess world,int view,int simulation) {}
    private LiveDistanceChanges(){}
    static List<Map<String,Object>> apply(List<WorldAccess> worlds,int view,int simulation,BooleanSupplier authority){
        if(view<2||view>32||simulation<2||simulation>32||worlds.size()>128)throw new IllegalArgumentException("Invalid public distance request");
        List<Before> changed=new ArrayList<>();List<Map<String,Object>> result=new ArrayList<>();
        try{
            for(WorldAccess world:worlds){permit(authority);int oldView=world.view(),oldSim=world.simulation();if(oldView<2||oldView>32||oldSim<2||oldSim>32)throw new IllegalStateException("Unsupported current world distances");
                changed.add(new Before(world,oldView,oldSim));world.view(view);permit(authority);world.simulation(simulation);permit(authority);
                if(world.view()!=view||world.simulation()!=simulation)throw new IllegalStateException("Public distance setting was not applied");
                result.add(KernelProfiles.map("world",world.name(),"beforeView",oldView,"beforeSimulation",oldSim,"viewDistance",view,"simulationDistance",simulation));
            }return List.copyOf(result);
        }catch(RuntimeException failure){
            boolean incomplete=false;Collections.reverse(changed);
            for(Before old:changed){try{
                // Do not overwrite a concurrent change made by another plugin/administrator.
                int nowSim=old.world.simulation();if(nowSim==simulation)old.world.simulation(old.simulation);else if(nowSim!=old.simulation)incomplete=true;
                int nowView=old.world.view();if(nowView==view)old.world.view(old.view);else if(nowView!=old.view)incomplete=true;
            }catch(RuntimeException rollback){incomplete=true;}}
            if(incomplete)throw new IllegalStateException("Public distance rollback found an external change or API failure",failure);throw failure;
        }
    }
    private static void permit(BooleanSupplier authority){if(!authority.getAsBoolean())throw new SecurityException("Active Owner authority required for live distances");}
}
