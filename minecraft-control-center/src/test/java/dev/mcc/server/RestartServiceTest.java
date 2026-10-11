package dev.mcc.server;
import dev.mcc.core.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class RestartServiceTest {
    @TempDir Path root;
    String run=UUID.randomUUID().toString();
    RestartService service()throws Exception {
        Path c=Files.createDirectories(root.resolve(".mcc-launcher"));Files.writeString(c.resolve("commands.ndjson"),"");
        status(run,42,7,"running");return new RestartService(root,c,run,42,7,p->p==7);
    }
    void status(String id,long pid,long parent,String state)throws Exception {Files.writeString(root.resolve(".mcc-launcher/status.json"),Json.write(Map.of("protocolVersion",2,"runId",id,"javaPid",pid,"daemonPid",parent,"state",state,"paperReady",true,"dashboardReady",true)));}
    @Test void restartWrittenOnceAndDifferentRequestRejected()throws Exception {var s=service();String id=UUID.randomUUID().toString();assertEquals(true,s.status().get("available"));assertEquals(true,s.request(id,()->{}).get("accepted"));long size=Files.size(root.resolve(".mcc-launcher/commands.ndjson"));s.request(id,()->{});assertEquals(size,Files.size(root.resolve(".mcc-launcher/commands.ndjson")));assertThrows(IllegalStateException.class,()->s.request(UUID.randomUUID().toString(),()->{}));var line=Json.object(Files.readString(root.resolve(".mcc-launcher/commands.ndjson")));assertEquals("restart",line.get("command"));assertEquals(run,line.get("runId"));}
    @Test void foreignRunOrPidNeverReceivesCommand()throws Exception {var s=service();status(UUID.randomUUID().toString(),42,7,"running");assertThrows(IllegalStateException.class,()->s.request(UUID.randomUUID().toString(),()->{}));status(run,43,7,"running");assertThrows(IllegalStateException.class,()->s.request(UUID.randomUUID().toString(),()->{}));assertEquals(0,Files.size(root.resolve(".mcc-launcher/commands.ndjson")));}
    @Test void revokedAuthorityAndUnreadyLauncherReject()throws Exception {var s=service();assertThrows(SecurityException.class,()->s.request(UUID.randomUUID().toString(),()->{throw new SecurityException();}));status(run,42,7,"stopping");assertEquals(false,s.status().get("available"));assertThrows(IllegalStateException.class,()->s.request(UUID.randomUUID().toString(),()->{}));}
    @Test void ordinaryServerHasHonestDisabledCapability(){var s=new RestartService(root,null,"",42,7,p->true);assertEquals(false,s.status().get("available"));assertFalse(s.status().get("message").toString().isBlank());}
    @Test void queueBudgetCannotBeBypassed()throws Exception {var s=service();Files.write(root.resolve(".mcc-launcher/commands.ndjson"),new byte[1024*1024+1]);assertThrows(IllegalStateException.class,()->s.request(UUID.randomUUID().toString(),()->{}));}
    @Test void queueNearLimitCannotGrowPastBudget()throws Exception {var s=service();Path q=root.resolve(".mcc-launcher/commands.ndjson");Files.write(q,new byte[1024*1024-1]);assertThrows(IllegalStateException.class,()->s.request(UUID.randomUUID().toString(),()->{}));assertEquals(1024*1024-1,Files.size(q));}
    @Test void healthIdentifiesBootWithoutLauncherDetails()throws Exception {var s=service();assertEquals(Set.of("ready","bootId"),s.health().keySet());assertNotEquals(s.bootId(),service().bootId());}
}
