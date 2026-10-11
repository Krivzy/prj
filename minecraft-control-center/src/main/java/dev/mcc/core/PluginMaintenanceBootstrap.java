package dev.mcc.core;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

/** The supervisor retains its offline lease around this prelaunch operation. */
public final class PluginMaintenanceBootstrap {
    private PluginMaintenanceBootstrap(){}
    public static Map<String,Object> runOffline(Path serverRoot,Path pluginData)throws Exception {
        Path data=pluginData.toAbsolutePath().normalize(),queue=data.resolve("plugin-quarantine");
        if(!Files.exists(queue,LinkOption.NOFOLLOW_LINKS))return Map.of("removed",0,"pending",0);
        try(FileChannel runtime=runtimeLease(data);FileLock guard=runtime.tryLock()){
            if(guard==null)throw new IOException("Plugin runtime is still active");
            return PluginLifecycle.applyPendingOffline(serverRoot,pluginData);
        }
    }
    /** Recovery only: cancels an intent without changing any plugin JAR or data, while both leases are held. */
    public static Map<String,Object> cancelOffline(Path serverRoot,Path pluginData,String id)throws Exception {
        Path root=serverRoot.toAbsolutePath().normalize(),data=pluginData.toAbsolutePath().normalize();
        if(!data.getParent().equals(root.resolve("plugins"))||!root.toRealPath().equals(root))throw new IOException("Invalid server scope");
        Path daemon=root.resolve(".mcc-launcher/daemon.lock");ordinary(daemon);ordinary(daemon.getParent());
        try(FileChannel supervisor=FileChannel.open(daemon,StandardOpenOption.WRITE);FileLock daemonGuard=supervisor.tryLock(0,1,false);FileChannel runtime=runtimeLease(data);FileLock runtimeGuard=runtime.tryLock()){
            if(daemonGuard==null||runtimeGuard==null)throw new IOException("Server or supervisor is still active");
            PluginLifecycle lifecycle=new PluginLifecycle(data,List::of,()->true);
            return lifecycle.cancelPending(id,()->true);
        }
    }
    private static FileChannel runtimeLease(Path data)throws IOException {
        Path folder=data.resolve("kernel-profiles"),lock=folder.resolve("runtime.lock");ordinary(data);ordinary(folder);ordinary(lock);
        if(!data.toRealPath().equals(data)||!folder.toRealPath().equals(folder))throw new IOException("Unsafe runtime lease path");
        return FileChannel.open(lock,StandardOpenOption.WRITE);
    }
    private static void ordinary(Path path)throws IOException {if(Files.isSymbolicLink(path)||(!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)&&!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)))throw new IOException("Unsafe offline lease path");}
    public static void main(String[] args){
        try{Map<String,String> values=new LinkedHashMap<>();boolean offline=false;for(int i=0;i<args.length;i++){if(args[i].equals("--offline")){if(offline)throw new IllegalArgumentException();offline=true;continue;}String key=args[i];if(!Set.of("--server-root","--plugin-data","--cancel-removal").contains(key)||i+1>=args.length||values.putIfAbsent(key,args[++i])!=null)throw new IllegalArgumentException();}if(!offline||values.size()!=3)throw new IllegalArgumentException();System.out.println(Json.write(cancelOffline(Path.of(values.get("--server-root")),Path.of(values.get("--plugin-data")),values.get("--cancel-removal"))));}
        catch(Exception error){System.err.println("Plugin maintenance refused: invalid scope, pending intent, or offline lease.");System.exit(2);}
    }
}
