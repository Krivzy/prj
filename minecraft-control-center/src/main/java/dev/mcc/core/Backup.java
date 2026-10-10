package dev.mcc.core;

import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.*;
import java.io.*;

/** Online backup is deliberately scoped to MCC data; active-world restore is never offered. */
public final class Backup {
    private final Database db;private final Path folder,data;private final AtomicBoolean running=new AtomicBoolean();
    public Backup(Database db,Path data) throws IOException {this.db=db;this.data=data;folder=data.resolve("backups");Files.createDirectories(folder);PrivateFiles.restrict(folder,true);}
    public List<Map<String,Object>> list() throws Exception {return db.documents("backups").get();}
    public Map<String,Object> create() throws Exception {
        if(!running.compareAndSet(false,true))throw new IllegalStateException("Backup already running");
        String id=UUID.randomUUID().toString();Path sqlite=folder.resolve(id+".db"),zip=folder.resolve("mcc-"+id+".zip");
        try {
            db.vacuumInto(sqlite).get();
            try(ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(zip,StandardOpenOption.CREATE_NEW))){
                add(out,sqlite,"database.db");Path config=data.resolve("config.yml");if(Files.isRegularFile(config))add(out,config,"config.yml");
            }
            PrivateFiles.restrict(zip,false);String hash;
            // Reading each entry through ZipInputStream validates its decompressed CRC.
            try(ZipInputStream verify=new ZipInputStream(Files.newInputStream(zip))){byte[] buffer=new byte[65536];int entries=0;while(verify.getNextEntry()!=null){entries++;while(verify.read(buffer)!=-1){}verify.closeEntry();}if(entries<1)throw new IOException("Empty backup archive");}
            try(InputStream in=Files.newInputStream(zip)){MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))>=0)digest.update(buffer,0,n);hash=HexFormat.of().formatHex(digest.digest());}
            Map<String,Object> metadata=Map.of("id",id,"file",zip.getFileName().toString(),"size",Files.size(zip),"sha256",hash,"time",System.currentTimeMillis(),"status","verified","scope","MCC settings and database; no Minecraft worlds");
            db.document("backups",id,metadata).get();return metadata;
        }finally{Files.deleteIfExists(sqlite);running.set(false);}
    }
    private static void add(ZipOutputStream zip,Path file,String name)throws IOException{zip.putNextEntry(new ZipEntry(name));Files.copy(file,zip);zip.closeEntry();}
}
