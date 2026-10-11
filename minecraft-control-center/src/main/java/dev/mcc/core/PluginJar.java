package dev.mcc.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Reads metadata without defining classes or executing any code from a downloaded JAR. */
public final class PluginJar {
    private PluginJar() {}
    public record Metadata(String name,String version,String main,List<String> required,List<String> provides,String apiVersion) {}
    public static Metadata inspect(Path file,String minecraftVersion)throws IOException {
        return inspect(file,minecraftVersion,true);
    }
    static Metadata inspectExisting(Path file)throws IOException{return inspect(file,"999.999",false);}
    private static Metadata inspect(Path file,String minecraftVersion,boolean download)throws IOException {
        try(ZipFile zip=new ZipFile(file.toFile())) {
            Set<String> names=new HashSet<>();long total=0;int entries=0;
            Enumeration<? extends ZipEntry> all=zip.entries();
            while(all.hasMoreElements()) {
                ZipEntry entry=all.nextElement();String name=entry.getName();
                if(++entries>25000||!names.add(name)||name.startsWith("/")||name.contains("\\")||name.indexOf('\0')>=0||Arrays.asList(name.split("/")).contains(".."))throw new IOException("Unsafe or duplicate JAR entries");
                if(entry.getSize()<0||entry.getSize()>64L*1024*1024||(total+=entry.getSize())>256L*1024*1024)throw new IOException("JAR expansion limit exceeded");
                if(entry.getSize()>1024*1024&&entry.getCompressedSize()>0&&entry.getSize()/entry.getCompressedSize()>200)throw new IOException("Suspicious JAR compression ratio");
            }
            ZipEntry descriptor=zip.getEntry("paper-plugin.yml");boolean paper=descriptor!=null;
            if(descriptor==null)descriptor=zip.getEntry("plugin.yml");
            if(descriptor==null||descriptor.isDirectory())throw new IOException("JAR has no Paper/Bukkit plugin descriptor");
            Map<?,?> yaml;
            try(InputStream in=zip.getInputStream(descriptor)) {
                byte[] bytes=in.readNBytes(65537);if(bytes.length>65536)throw new IOException("Plugin descriptor is too large");
                LoaderOptions limits=new LoaderOptions();limits.setAllowDuplicateKeys(false);limits.setMaxAliasesForCollections(0);limits.setNestingDepthLimit(12);limits.setCodePointLimit(65536);
                Object data=new Yaml(new SafeConstructor(limits)).load(new String(bytes,StandardCharsets.UTF_8));
                if(!(data instanceof Map<?,?> map))throw new IOException("Invalid plugin descriptor");yaml=map;
            }catch(RuntimeException error){throw new IOException("Invalid or complex plugin YAML",error);}
            String name=scalar(yaml,"name",128),version=scalar(yaml,"version",128),main=scalar(yaml,"main",256);
            if(!name.matches("[A-Za-z0-9_.-]{1,128}")||!main.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+")||zip.getEntry(main.replace('.','/')+".class")==null)throw new IOException("Invalid plugin identity or missing main class");
            if(download&&(name.equalsIgnoreCase("MinecraftControlCenter")||name.equalsIgnoreCase("MCC")||main.startsWith("dev.mcc.")))throw new IOException("The catalog cannot replace MCC");
            String api=Objects.toString(yaml.get("api-version"),"");
            if(!api.isEmpty()&&(!api.matches("[0-9]+(\\.[0-9]+){1,2}")||compareVersions(api,minecraftVersion)>0))throw new IOException("Plugin requires a newer server API");
            List<String> required=new ArrayList<>(strings(yaml.get("depend")));
            if(paper&&yaml.get("dependencies") instanceof Map<?,?> dependencies)for(Object section:List.of("bootstrap","server")) {
                if(dependencies.get(section) instanceof Map<?,?> map)for(var dep:map.entrySet()) {
                    if(!(dep.getKey() instanceof String dependency)||!(dep.getValue() instanceof Map<?,?> values))throw new IOException("Invalid Paper dependency metadata");
                    if(!Boolean.FALSE.equals(values.get("required")))required.add(dependency);
                }
            }
            return new Metadata(name,version,main,List.copyOf(new LinkedHashSet<>(required)),strings(yaml.get("provides")),api);
        }
    }
    private static String scalar(Map<?,?> map,String key,int max)throws IOException {
        Object value=map.get(key);if(!(value instanceof String||value instanceof Number)||value.toString().isBlank()||value.toString().length()>max||value.toString().chars().anyMatch(Character::isISOControl))throw new IOException("Invalid plugin "+key);return value.toString();
    }
    private static List<String> strings(Object value)throws IOException {
        if(value==null)return List.of();if(!(value instanceof List<?> list)||list.size()>32)throw new IOException("Invalid plugin dependency list");List<String> result=new ArrayList<>();
        for(Object item:list){if(!(item instanceof String s)||!s.matches("[A-Za-z0-9_.-]{1,128}"))throw new IOException("Invalid plugin dependency name");result.add(s);}return List.copyOf(result);
    }
    static int compareVersions(String left,String right) {
        String[] a=left.split("\\."),b=right.split("\\.");for(int i=0;i<Math.max(a.length,b.length);i++){int av=i<a.length?Integer.parseInt(a[i]):0,bv=i<b.length?Integer.parseInt(b[i]):0;if(av!=bv)return Integer.compare(av,bv);}return 0;
    }
}
