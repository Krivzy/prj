package dev.mcc.coreboot;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Shared only through the system loader: Paper isolates its library loader from this boot layer. */
public final class CoreBridge {
    private static volatile Path root,plugin;
    private static volatile String expected;
    private static volatile BooleanSupplier healthy;
    private static volatile ClassLoader runtime;
    private static volatile long initializedAt;
    private static final AtomicBoolean stopping=new AtomicBoolean();
    private static volatile ScheduledExecutorService monitor;
    private CoreBridge(){}
    static synchronized void initialize(Path serverRoot,Path required,String sha){
        if(root!=null)throw new IllegalStateException("MCC Core boot was already initialized");root=serverRoot;plugin=required;expected=sha;initializedAt=System.nanoTime();
    }
    static void runtimeLoader(ClassLoader loader){runtime=loader;}
    public static boolean active(){return root!=null;}
    public static synchronized void ready(String name,String main,String artifactSha,BooleanSupplier health)throws Exception {
        if(root==null||stopping.get()||!"MinecraftControlCenter".equals(name)||!"dev.mcc.MccPlugin".equals(main)||
            !Objects.equals(expected,artifactSha)||!CoreBootstrap.sha(plugin).equals(expected))throw new IllegalStateException("MCC Core/MCC panel linkage was refused");
        if(healthy!=null)throw new IllegalStateException("MCC Core panel linkage was already established");
        healthy=Objects.requireNonNull(health);if(!healthy.getAsBoolean()){healthy=null;throw new IllegalStateException("MCC panel is not ready");}
        if(runtime==null){ClassLoader pluginLoader=health.getClass().getClassLoader();if(pluginLoader!=null)runtime=pluginLoader.getParent();}
        System.out.println("[MCC Core] Minecraft Control Center panel handshake ready.");
    }
    public static Map<String,Object> state(){
        boolean connected=false;BooleanSupplier health=healthy;try{connected=health!=null&&health.getAsBoolean();}catch(RuntimeException ignored){}
        Map<String,Object> state=new LinkedHashMap<>();state.put("active",active());state.put("name",CoreBootstrap.NAME);state.put("version",CoreBootstrap.VERSION);state.put("protocol",1);
        state.put("connected",connected);state.put("upstream","Paper");state.put("minecraftVersion","26.3");state.put("upstreamBuild",169);state.put("stopping",stopping.get());
        state.put("engineSource",CoreBootstrap.SOURCE_COMMIT);state.put("enginePatchset",CoreBootstrap.PATCHSET_SHA);state.put("engineFeatures",CoreBootstrap.ENGINE_FEATURES);
        return Collections.unmodifiableMap(state);
    }
    public static void disconnected(){if(active())shutdown("The required MCC panel was disabled; stopping normally and saving worlds.");}
    static synchronized void watch(){
        if(monitor!=null)return;monitor=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"MCC-Core-Link");t.setDaemon(true);return t;});
        monitor.scheduleWithFixedDelay(()->{
            if(stopping.get())return;BooleanSupplier health=healthy;
            if(health==null){if(System.nanoTime()-initializedAt>TimeUnit.SECONDS.toNanos(180))shutdown("MCC panel did not become ready; stopping normally and saving worlds.");}
            else try {if(!health.getAsBoolean())shutdown("MCC panel is unavailable; stopping normally and saving worlds.");}catch(RuntimeException e){shutdown("MCC panel health check failed; stopping normally and saving worlds.");}
        },2,2,TimeUnit.SECONDS);
    }
    private static void shutdown(String reason){
        ClassLoader loader=runtime;if(loader==null)return;
        try {
            Class<?> bukkit=Class.forName("org.bukkit.Bukkit",false,loader);Object server=bukkit.getMethod("getServer").invoke(null);
            if(server==null)return;if(!stopping.compareAndSet(false,true))return;
            System.err.println("[MCC Core] "+reason);
            bukkit.getMethod("shutdown").invoke(null);
            ScheduledExecutorService worker=monitor;if(worker!=null)worker.shutdown();
        }catch(ReflectiveOperationException|LinkageError e){
            // Do not System.exit/kill a running world when the public shutdown path is unavailable.
            stopping.set(false);System.err.println("[MCC Core] Cannot request public normal shutdown yet; retrying.");
        }
    }
}
