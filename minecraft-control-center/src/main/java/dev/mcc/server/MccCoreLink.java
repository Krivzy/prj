package dev.mcc.server;

import dev.mcc.coreboot.CoreBootstrap;
import org.bukkit.plugin.java.JavaPlugin;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Optional public JDK bridge. A command-line branding property alone never impersonates MCC Core. */
public final class MccCoreLink {
    private MccCoreLink(){}
    private static Class<?> bridge(){try{return Class.forName("dev.mcc.coreboot.CoreBridge",false,ClassLoader.getSystemClassLoader());}catch(ClassNotFoundException|LinkageError e){return null;}}
    public static boolean active(){Class<?> bridge=bridge();if(bridge==null)return false;try{return Boolean.TRUE.equals(bridge.getMethod("active").invoke(null));}catch(ReflectiveOperationException|RuntimeException e){return false;}}
    @SuppressWarnings("unchecked") public static Map<String,Object> state(){
        Class<?> bridge=bridge();if(bridge==null||!active())return Map.of("active",false,"connected",false,"name",CoreBootstrap.NAME,"version",CoreBootstrap.VERSION);
        try {Object value=bridge.getMethod("state").invoke(null);return value instanceof Map<?,?> map?Map.copyOf((Map<String,Object>)map):Map.of("active",false,"connected",false);}
        catch(ReflectiveOperationException|RuntimeException e){return Map.of("active",false,"connected",false);}
    }
    /** Invoke only once the authenticated web server has actually bound successfully. */
    public static void ready(JavaPlugin plugin)throws Exception {ready(plugin,plugin::isEnabled);}
    public static void ready(JavaPlugin plugin,BooleanSupplier health)throws Exception {
        if(!active())return;if(!plugin.getClass().getName().equals("dev.mcc.MccPlugin")||!plugin.getName().equals("MinecraftControlCenter"))throw new IllegalArgumentException("The real MCC plugin is required");
        Path artifact=Path.of("").toAbsolutePath().normalize().resolve("plugins/MinecraftControlCenter.jar");
        bridge().getMethod("ready",String.class,String.class,String.class,BooleanSupplier.class).invoke(null,plugin.getName(),plugin.getClass().getName(),CoreBootstrap.sha(artifact),health);
    }
    public static void disabled(JavaPlugin plugin){
        if(!active()||!plugin.getClass().getName().equals("dev.mcc.MccPlugin"))return;
        try{bridge().getMethod("disconnected").invoke(null);}catch(ReflectiveOperationException|RuntimeException e){plugin.getLogger().severe("MCC Core normal shutdown request was unavailable");}
    }
}
