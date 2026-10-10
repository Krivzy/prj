package dev.mcc.web;

import java.nio.file.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

public final class LogTail {
    private final Path file;private final Realtime realtime;private long offset=-1;private String partial="";
    private final ConcurrentLinkedDeque<Map<String,Object>> history=new ConcurrentLinkedDeque<>();
    public LogTail(Path file,Realtime realtime){this.file=file;this.realtime=realtime;}
    public List<Map<String,Object>> history(){return new ArrayList<>(history);}
    public void pulse(){
        try{if(!Files.isRegularFile(file))return;try(RandomAccessFile in=new RandomAccessFile(file.toFile(),"r")){
            long size=in.length();if(offset<0)offset=Math.max(0,size-16384);if(size<offset){offset=0;partial="";}if(size-offset>65536){offset=size-65536;partial="";}
            in.seek(offset);byte[] buffer=new byte[(int)Math.min(size-offset,65536)];in.readFully(buffer);offset=in.getFilePointer();
            String text=partial+new String(buffer,StandardCharsets.UTF_8);String[] lines=text.split("\n",-1);partial=lines[lines.length-1];if(partial.length()>8192)partial="";
            for(int i=0;i<lines.length-1;i++){
                String line=lines[i].replaceAll("(?i)(password|passwd|token|secret|authorization)\\s*[=:]\\s*\\S+","$1=[redacted]");
                if(line.length()>4096)line=line.substring(0,4096);
                Map<String,Object> entry=Map.of("time",System.currentTimeMillis(),"level",line.contains("ERROR")?"ERROR":line.contains("WARN")?"WARN":"INFO","message",line);
                history.add(entry);while(history.size()>500)history.pollFirst();realtime.publish("console",entry);
            }
        }}catch(IOException ignored){}
    }
}
