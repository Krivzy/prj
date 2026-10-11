package dev.mcc.server;

import dev.mcc.core.MainThread;
import org.bukkit.Bukkit;
import org.bukkit.World;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/** Capability discovery is limited to public Bukkit API. No NMS, reload, or private reflection. */
public final class BukkitKernelRuntime implements KernelProfiles.RuntimeAccess {
    private final MainThread main;
    private final Method getView=publicMethod(World.class,"getViewDistance"),getSimulation=publicMethod(World.class,"getSimulationDistance");
    private final Method setView=publicMethod(World.class,"setViewDistance",int.class),setSimulation=publicMethod(World.class,"setSimulationDistance",int.class);
    public BukkitKernelRuntime(MainThread main){this.main=Objects.requireNonNull(main);}
    @Override public CompletableFuture<KernelProfiles.RuntimeState> snapshot(){return main.call(()->{
        assertMain();String kernel=Bukkit.getName();List<KernelProfiles.WorldState> worlds=new ArrayList<>();
        for(World world:Bukkit.getWorlds())worlds.add(new KernelProfiles.WorldState(world.getName(),world.getWorldFolder().toPath(),read(getView,world),read(getSimulation,world)));
        return new KernelProfiles.RuntimeState(kernel,minecraftVersion(),capable(kernel),worlds);
    });}
    @Override public CompletableFuture<List<Map<String,Object>>> applyDistances(int view,int simulation,BooleanSupplier authority){return main.call(()->{
        assertMain();if(!capable(Bukkit.getName()))throw new IllegalStateException("Kernel lacks supported public per-world distance setters");
        List<LiveDistanceChanges.WorldAccess> worlds=new ArrayList<>();for(World world:Bukkit.getWorlds())worlds.add(new LiveDistanceChanges.WorldAccess(){
            public String name(){return world.getName();}public int view(){return readRequired(getView,world);}public int simulation(){return readRequired(getSimulation,world);}
            public void view(int value){invoke(setView,world,value);}public void simulation(int value){invoke(setSimulation,world,value);}
        });return LiveDistanceChanges.apply(worlds,view,simulation,authority);
    });}
    private boolean capable(String kernel){return KernelProfiles.knownKernel(kernel)&&getView!=null&&getSimulation!=null&&setView!=null&&setSimulation!=null;}
    private static Method publicMethod(Class<?> type,String name,Class<?>...args){try{return type.getMethod(name,args);}catch(NoSuchMethodException|SecurityException e){return null;}}
    private static int read(Method method,World world){return method==null?0:readRequired(method,world);}
    private static int readRequired(Method method,World world){Object value=invoke(method,world);if(!(value instanceof Integer n))throw new IllegalStateException("Unsupported public distance return type");return n;}
    private static Object invoke(Method method,Object target,Object...args){try{if(method==null)throw new IllegalStateException("Public distance API unavailable");return method.invoke(target,args);}catch(ReflectiveOperationException e){throw new IllegalStateException("Public distance API rejected the operation",e);}}
    private static String minecraftVersion(){Method method=publicMethod(Bukkit.class,"getMinecraftVersion");if(method!=null)return String.valueOf(invoke(method,null));return Bukkit.getBukkitVersion().split("-",2)[0];}
    private static void assertMain(){if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Kernel runtime must run on the server tick thread");}
}
