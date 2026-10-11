package dev.mcc.server;

import dev.mcc.core.Json;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.function.LongPredicate;

/** A normal stop/start request to an explicitly configured, owned launcher. No shell commands. */
public final class RestartService {
    private final Path root,control;
    private final String runId,bootId=UUID.randomUUID().toString();
    private final long pid,parent;
    private final LongPredicate alive;
    private String acceptedId;
    public RestartService(Path serverRoot){
        this(serverRoot,System.getProperty("mcc.launcher.control","").isBlank()?null:Path.of(System.getProperty("mcc.launcher.control")),
            System.getProperty("mcc.launcher.run-id",""),ProcessHandle.current().pid(),
            ProcessHandle.current().parent().map(ProcessHandle::pid).orElse(-1L),
            id->ProcessHandle.of(id).map(ProcessHandle::isAlive).orElse(false));
    }
    RestartService(Path serverRoot,Path control,String runId,long pid,long parent,LongPredicate alive){
        this.root=serverRoot.toAbsolutePath().normalize();this.control=control==null?null:control.toAbsolutePath().normalize();
        this.runId=runId;this.pid=pid;this.parent=parent;this.alive=alive;
    }
    public String bootId(){return bootId;}
    public Map<String,Object> health(){return Map.of("ready",true,"bootId",bootId);}
    public synchronized Map<String,Object> status(){
        String reason="";try{verifiedStatus();}catch(Exception e){reason="Для штатного перезапуска нужен подключённый MCC launcher v2. Обычная работа панели доступна.";}
        return Map.of("available",reason.isEmpty()&&acceptedId==null,"message",reason,"requested",acceptedId!=null,"bootId",bootId,"method","normal-stop-and-start","savesWorlds",true);
    }
    private void ordinary(Path p,boolean directory)throws Exception {
        if(Files.isSymbolicLink(p)||!p.toRealPath().startsWith(root.toRealPath())||
            (directory?!Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS):!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)))throw new IllegalStateException("Launcher identity is unavailable");
    }
    private Map<String,Object> verifiedStatus()throws Exception {
        if(control==null||!control.equals(root.resolve(".mcc-launcher"))||parent<=0||!alive.test(parent))throw new IllegalStateException("Launcher is not connected");
        if(!UUID.fromString(runId).toString().equals(runId))throw new IllegalStateException("Invalid launcher run identity");
        ordinary(control,true);Path file=control.resolve("status.json");ordinary(file,false);
        byte[] statusBytes;try(var stream=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){statusBytes=stream.readNBytes(16385);}
        if(statusBytes.length>16384)throw new IllegalStateException("Launcher status exceeds budget");
        Map<String,Object> s=Json.object(new String(statusBytes,java.nio.charset.StandardCharsets.UTF_8));
        if(!(s.get("protocolVersion") instanceof Number version)||version.intValue()!=2||
            !runId.equals(s.get("runId"))||!(s.get("javaPid") instanceof Number javaPid)||javaPid.longValue()!=pid||
            !(s.get("daemonPid") instanceof Number daemonPid)||daemonPid.longValue()!=parent||!"running".equals(s.get("state"))||
            !Boolean.TRUE.equals(s.get("paperReady"))||!Boolean.TRUE.equals(s.get("dashboardReady")))throw new IllegalStateException("Launcher run is not ready or belongs to another process");
        return s;
    }
    public synchronized Map<String,Object> request(String id,Runnable authority)throws Exception {
        if(!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException("Canonical restart request UUID is required");
        authority.run();
        if(acceptedId!=null){if(acceptedId.equals(id))return accepted();throw new IllegalStateException("Server restart is already requested");}
        verifiedStatus();Path queue=control.resolve("commands.ndjson");ordinary(queue,false);
        if(Files.size(queue)>1024*1024)throw new IllegalStateException("Launcher command queue exceeds budget; archive offline");
        byte[] bytes=(Json.write(Map.of("command","restart","runId",runId,"requestId",id))+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try(FileChannel channel=FileChannel.open(queue,StandardOpenOption.WRITE,StandardOpenOption.APPEND,LinkOption.NOFOLLOW_LINKS);
            var lock=channel.tryLock()){
            if(lock==null)throw new IllegalStateException("Launcher queue is busy");
            verifiedStatus();authority.run();if(channel.size()+bytes.length>1024*1024)throw new IllegalStateException("Launcher command queue exceeds budget");
            ByteBuffer data=ByteBuffer.wrap(bytes);while(data.hasRemaining())channel.write(data);channel.force(true);
        }
        acceptedId=id;return accepted();
    }
    private Map<String,Object> accepted(){return Map.of("accepted",true,"requestId",acceptedId,"bootId",bootId,"method","normal-stop-and-start");}
}
