package dev.mcc.web;

import dev.mcc.core.Json;
import java.nio.file.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public final class LogTail {
    private static final int INITIAL_BYTES=16384,READ_BYTES=65536,LINE_BYTES=32768;
    private static final int BATCHES_PER_PULSE=4,ROWS_PER_BATCH=16,BATCH_BYTES=24576,MESSAGE_JSON_BYTES=16384;
    private static final byte[] EMPTY=new byte[0];
    private static final Pattern SECRET=Pattern.compile("(?i)(password|passwd|token|secret|authorization)\\s*[=:]\\s*\\S+");
    private final Path file;
    private final Consumer<List<Map<String,Object>>> publish;
    private final ConcurrentLinkedDeque<Map<String,Object>> history=new ConcurrentLinkedDeque<>();
    private final ByteArrayOutputStream line=new ByteArrayOutputStream();
    private long offset=-1;
    private byte[] pending=EMPTY;
    private int cursor;
    private boolean clipped,skipFragment;
    private Map<String,Object> held;
    private record Line(byte[] bytes,boolean clipped){}

    public LogTail(Path file,Realtime realtime){this(file,batch->realtime.publish("console",batch));}
    LogTail(Path file,Consumer<List<Map<String,Object>>> publish){this.file=Objects.requireNonNull(file);this.publish=Objects.requireNonNull(publish);}
    public List<Map<String,Object>> history(){return new ArrayList<>(history);}

    /** Startup logs must not consume the entire WebSocket queue before its sender can drain. */
    public synchronized void pulse(){
        List<Map<String,Object>> batch=new ArrayList<>(ROWS_PER_BATCH);
        int batchBytes=2,sent=0;
        boolean read=false;
        try{
            while(sent<BATCHES_PER_PULSE){
                Map<String,Object> entry=held;
                if(entry!=null)held=null;
                else{
                    Line next=nextLine();
                    if(next==null){if(read||!readNext())break;read=true;continue;}
                    entry=entry(next);
                }
                int bytes=Json.write(entry).getBytes(StandardCharsets.UTF_8).length;
                if(batch.size()==ROWS_PER_BATCH||batchBytes+bytes+(batch.isEmpty()?0:1)>BATCH_BYTES){
                    emit(batch);sent++;batch=new ArrayList<>(ROWS_PER_BATCH);batchBytes=2;
                    if(sent==BATCHES_PER_PULSE){held=entry;break;}
                }
                batchBytes+=bytes+(batch.isEmpty()?0:1);batch.add(entry);
            }
        }catch(IOException ignored){}
        if(!batch.isEmpty()&&sent<BATCHES_PER_PULSE)emit(batch);
    }

    private void emit(List<Map<String,Object>> batch){
        List<Map<String,Object>> immutable=List.copyOf(batch);
        for(Map<String,Object> entry:immutable){history.add(entry);while(history.size()>500)history.pollFirst();}
        publish.accept(immutable);
    }

    /** Keep UTF-8 bytes, including an unfinished code point, until the physical newline arrives. */
    private Line nextLine(){
        while(cursor<pending.length){
            int end=cursor;while(end<pending.length&&pending[end]!='\n')end++;
            if(!skipFragment){
                int count=end-cursor,keep=Math.min(count,LINE_BYTES-line.size());
                line.write(pending,cursor,keep);if(keep<count)clipped=true;
            }
            cursor=end;
            if(cursor<pending.length){
                cursor++;
                if(skipFragment){skipFragment=false;continue;}
                Line result=new Line(line.toByteArray(),clipped);line.reset();clipped=false;return result;
            }
        }
        pending=EMPTY;cursor=0;return null;
    }

    private boolean readNext()throws IOException{
        if(!Files.isRegularFile(file))return false;
        try(RandomAccessFile in=new RandomAccessFile(file.toFile(),"r")){
            long size=in.length();
            if(offset<0)seekTail(in,Math.max(0,size-INITIAL_BYTES));
            else if(size<offset)seekTail(in,0);
            else if(size-offset>READ_BYTES)seekTail(in,size-READ_BYTES);
            if(size==offset)return false;
            in.seek(offset);byte[] bytes=new byte[(int)Math.min(size-offset,READ_BYTES)];
            in.readFully(bytes);offset=in.getFilePointer();pending=bytes;cursor=0;return true;
        }
    }

    /** A retained tail can begin inside a line; never display that fragment or join it to new data. */
    private void seekTail(RandomAccessFile in,long next)throws IOException{
        offset=next;line.reset();clipped=false;skipFragment=false;
        if(next>0){in.seek(next-1);skipFragment=in.read()!='\n';}
    }

    private Map<String,Object> entry(Line value){
        String text=new String(value.bytes(),StandardCharsets.UTF_8);
        if(text.endsWith("\r"))text=text.substring(0,text.length()-1);
        text=SECRET.matcher(text).replaceAll("$1=[redacted]");
        text=message(text,value.clipped());
        return Map.of("time",System.currentTimeMillis(),"level",text.contains("ERROR")?"ERROR":text.contains("WARN")?"WARN":"INFO","message",text);
    }

    /** Control-character escapes count towards the JSON byte budget too. */
    private static String message(String text,boolean clipped){
        String result=text;
        if(clipped||text.length()>4096)result=prefix(text,Math.min(4095,text.length()))+"…";
        if(Json.write(result).getBytes(StandardCharsets.UTF_8).length<=MESSAGE_JSON_BYTES)return result;
        int low=0,high=result.length(),best=0;
        while(low<=high){
            int middle=(low+high)>>>1;
            String candidate=prefix(result,middle)+"…";
            if(Json.write(candidate).getBytes(StandardCharsets.UTF_8).length<=MESSAGE_JSON_BYTES){best=middle;low=middle+1;}else high=middle-1;
        }
        return prefix(result,best)+"…";
    }

    private static String prefix(String text,int length){
        if(length>0&&length<text.length()&&Character.isHighSurrogate(text.charAt(length-1)))length--;
        return text.substring(0,length);
    }
}
