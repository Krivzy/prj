package dev.mcc.server;

import dev.mcc.core.Json;
import java.nio.file.Path;
import java.util.*;

/** Invoke from an offline supervisor prelaunch hook, before the server JVM reads its configuration. */
public final class ProfileBootstrap {
    private ProfileBootstrap(){}
    public static void main(String[] args){
        try{Map<String,Object> result=run(args);System.out.println(Json.write(result));}
        catch(Exception e){System.err.println("Managed kernel prelaunch refused: "+safeMessage(e));System.exit(2);}
    }
    static Map<String,Object> run(String[] args)throws Exception {
        Map<String,String> values=new LinkedHashMap<>();boolean offline=false;
        for(int i=0;i<args.length;i++){String key=args[i];if(key.equals("--offline")){if(offline)throw new IllegalArgumentException("Duplicate offline marker");offline=true;continue;}
            if(!Set.of("--server-root","--plugin-data","--kernel","--minecraft-version").contains(key)||i+1>=args.length||values.putIfAbsent(key,args[++i])!=null)throw new IllegalArgumentException("Invalid prelaunch arguments");
        }
        if(!offline||values.size()!=4)throw new IllegalArgumentException("Prelaunch requires --offline and exact server-root, plugin-data, kernel, minecraft-version");
        Path root=Path.of(values.get("--server-root")),data=Path.of(values.get("--plugin-data"));
        Map<String,Object> maintenance=dev.mcc.core.PluginMaintenanceBootstrap.runOffline(root,data);
        Map<String,Object> result=new LinkedHashMap<>(KernelProfiles.reapplyOffline(root,data,values.get("--kernel"),values.get("--minecraft-version")));
        result.put("pluginMaintenance",maintenance);return result;
    }
    private static String safeMessage(Exception e){return e instanceof IllegalArgumentException?"invalid arguments or selected profile":e instanceof java.io.IOException?"configuration, identity, lease, or backup validation failed":"profile operation failed";}
}
