package dev.mcc.core;

import java.nio.file.*;
import java.security.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Uploads are inert files outside plugins/. Administrators apply them only with Paper stopped. */
public final class PluginStage {
    private final Database db;private final Path folder;
    public PluginStage(Database db,Path data)throws IOException{this.db=db;folder=data.resolve("staged-plugins");Files.createDirectories(folder);PrivateFiles.restrict(folder,true);}
    public List<Map<String,Object>> list()throws Exception{return db.documents("staged-plugins").get();}
    public synchronized Map<String,Object> upload(String filename,InputStream input)throws Exception{
        if(!filename.matches("[A-Za-z0-9_.-]{1,100}\\.jar")||filename.contains(".."))throw new IllegalArgumentException("Use an ASCII .jar filename without path components");
        if(list().size()>=20)throw new IllegalStateException("Staged plugin limit reached");
        String id=UUID.randomUUID().toString();Path target=folder.resolve(id+"-"+filename);long size=0;
        try{
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(OutputStream out=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW)){byte[] buffer=new byte[32768];int n;while((n=input.read(buffer))!=-1){size+=n;if(size>16*1024*1024)throw new IllegalArgumentException("Maximum plugin upload size is 16 MiB");digest.update(buffer,0,n);out.write(buffer,0,n);}}
            PrivateFiles.restrict(target,false);boolean metadata=false;int count=0;
            try(ZipFile zip=new ZipFile(target.toFile())){
                Enumeration<? extends ZipEntry> entries=zip.entries();while(entries.hasMoreElements()){ZipEntry entry=entries.nextElement();if(++count>20000)throw new IllegalArgumentException("Too many JAR entries");String name=entry.getName();if(name.startsWith("/")||name.contains("\\")||Arrays.asList(name.split("/")).contains(".."))throw new IllegalArgumentException("Unsafe JAR entry path");}
                for(String name:List.of("plugin.yml","paper-plugin.yml")){ZipEntry entry=zip.getEntry(name);if(entry!=null){try(InputStream in=zip.getInputStream(entry)){byte[] yaml=in.readNBytes(16385);if(yaml.length>16384)throw new IllegalArgumentException("Plugin metadata too large");String text=new String(yaml,java.nio.charset.StandardCharsets.UTF_8);if(!text.matches("(?s).*\\bname\\s*:.*")||!text.matches("(?s).*\\bmain\\s*:.*"))throw new IllegalArgumentException("Invalid plugin metadata");metadata=true;}}}
            }
            if(!metadata)throw new IllegalArgumentException("JAR must contain plugin.yml or paper-plugin.yml");
            Map<String,Object> record=Map.of("id",id,"file",target.getFileName().toString(),"originalName",filename,"sha256",HexFormat.of().formatHex(digest.digest()),"size",size,"time",System.currentTimeMillis(),"status","staged","requiresRestart",true,"kind","install-or-update");
            db.document("staged-plugins",id,record).get();return record;
        }catch(Exception failure){Files.deleteIfExists(target);throw failure;}
    }
}
