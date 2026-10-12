package dev.mcc.server;
import dev.mcc.core.Json;
import dev.mcc.launcher.CommandQueueReaderFixture;
import java.nio.channels.FileChannel;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
    @Test void embeddedJavaRestartRequiresOwnedRunAndExplicitLease()throws Exception {
        var service=service();var status=new HashMap<String,Object>(Json.object(Files.readString(root.resolve(".mcc-launcher/status.json"))));
        status.put("protocolVersion",3);status.put("launcherKind","embedded-java");status.put("leaseKind","java-file-lock-byte-0");status.put("currentRunId",run);
        Files.writeString(root.resolve(".mcc-launcher/status.json"),Json.write(status));
        assertEquals(true,service.status().get("available"));assertEquals(true,service.request(UUID.randomUUID().toString(),()->{}).get("accepted"));
    }
    @Test void fractionalProtocolCannotMasqueradeAsLegacyLauncher()throws Exception {
        var service=service();var status=new HashMap<String,Object>(Json.object(Files.readString(root.resolve(".mcc-launcher/status.json"))));status.put("protocolVersion",2.5);
        Files.writeString(root.resolve(".mcc-launcher/status.json"),Json.write(status));assertEquals(false,service.status().get("available"));
        assertThrows(IllegalStateException.class,()->service.request(UUID.randomUUID().toString(),()->{}));assertEquals(0,Files.size(root.resolve(".mcc-launcher/commands.ndjson")));
    }
    @Test void externalReaderLeaseWaitsThenAcceptsExactlyOneDurableRestart() throws Exception {
        var service = service(); String id = UUID.randomUUID().toString();
        Path queue = root.resolve(".mcc-launcher/commands.ndjson"), ready = root.resolve("reader-ready"), release = root.resolve("reader-release");
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows");
        Path java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
        String fixtureClasses = Path.of(CommandQueueReaderFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Process reader = new ProcessBuilder(java.toString(), "-Xms16M", "-Xmx32M", "-cp", fixtureClasses,
            CommandQueueReaderFixture.class.getName(), queue.toString(), ready.toString(), release.toString())
            .redirectErrorStream(true).redirectOutput(root.resolve("reader-output.log").toFile()).start();
        ExecutorService worker = Executors.newSingleThreadExecutor(); Future<Map<String,Object>> accepted = null;
        try {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!Files.exists(ready) && reader.isAlive() && System.nanoTime() - until < 0) Thread.sleep(20);
            assertTrue(Files.exists(ready), "File-only reader did not acquire its shared lock");
            AtomicBoolean entered = new AtomicBoolean();
            accepted = worker.submit(() -> { entered.set(true); return service.request(id, () -> {}); });
            until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (!entered.get() && System.nanoTime() - until < 0) Thread.sleep(5);
            assertTrue(entered.get()); Thread.sleep(100);
            assertFalse(accepted.isDone(), "A transient shared reader lease must wait, not refuse the request");
            Files.createFile(release); assertTrue(reader.waitFor(20, TimeUnit.SECONDS)); assertEquals(0, reader.exitValue());
            assertEquals(true, accepted.get(5, TimeUnit.SECONDS).get("accepted"));
            String durable = Files.readString(queue); assertEquals(1, durable.lines().count());
            assertEquals(id, Json.object(durable).get("requestId")); assertEquals(run, Json.object(durable).get("runId"));
            service.request(id, () -> {}); assertEquals(durable, Files.readString(queue));
        } finally {
            if (!Files.exists(release)) Files.createFile(release);
            boolean interrupted = false;
            while (reader.isAlive()) try { reader.waitFor(); } catch (InterruptedException ignored) { interrupted = true; }
            if (interrupted) Thread.currentThread().interrupt();
            worker.shutdown(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, reader.exitValue());
        }
    }
    @FunctionalInterface interface WaitingMutation { void change(Path queue, AtomicBoolean authority) throws Exception; }
    private void rejectsChangedStateAfterWaiting(WaitingMutation mutation, Class<? extends Throwable> failureType) throws Exception {
        var service = service(); Path queue = root.resolve(".mcc-launcher/commands.ndjson"); Files.writeString(queue, "history\n");
        AtomicBoolean authority = new AtomicBoolean(true); AtomicReference<Thread> workerThread = new AtomicReference<>();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (FileChannel reader = FileChannel.open(queue, StandardOpenOption.READ);
             var guard = reader.lock(0, 1, true)) {
            Future<Map<String,Object>> request = worker.submit(() -> {
                workerThread.set(Thread.currentThread());
                return service.request(UUID.randomUUID().toString(), () -> { if (!authority.get()) throw new SecurityException("Revoked during wait"); });
            });
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while ((workerThread.get() == null || workerThread.get().getState() != Thread.State.TIMED_WAITING) && !request.isDone() && System.nanoTime() - until < 0) Thread.sleep(1);
            assertFalse(request.isDone()); assertNotNull(workerThread.get()); assertEquals(Thread.State.TIMED_WAITING, workerThread.get().getState());
            mutation.change(queue, authority); byte[] beforeRelease = Files.readAllBytes(queue);
            guard.release();
            var error = assertThrows(ExecutionException.class, () -> request.get(5, TimeUnit.SECONDS)); assertInstanceOf(failureType, error.getCause());
            assertArrayEquals(beforeRelease, Files.readAllBytes(queue));
        } finally { worker.shutdown(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS)); }
    }
    @Test void changedRunDuringQueueWaitCannotReceiveTheRestart() throws Exception {
        rejectsChangedStateAfterWaiting((queue, authority) -> status(UUID.randomUUID().toString(),42,7,"running"), IllegalStateException.class);
    }
    @Test void revokedAuthorityDuringQueueWaitCannotAppend() throws Exception {
        rejectsChangedStateAfterWaiting((queue, authority) -> authority.set(false), SecurityException.class);
    }
    @Test void increasedQueueBudgetDuringWaitCannotAppend() throws Exception {
        rejectsChangedStateAfterWaiting((queue, authority) -> Files.write(queue, new byte[1024*1024], StandardOpenOption.APPEND), IllegalStateException.class);
    }
    @Test void replacedQueueDuringWaitCannotReceiveTheRestart() throws Exception {
        rejectsChangedStateAfterWaiting((queue, authority) -> {
            Files.move(queue, queue.resolveSibling("old-commands.ndjson")); Files.writeString(queue,"external-new-file\n",StandardOpenOption.CREATE_NEW);
            if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows")) Files.setAttribute(queue,"basic:creationTime",FileTime.fromMillis(946684800000L));
        }, IllegalStateException.class);
        assertEquals("history\n",Files.readString(root.resolve(".mcc-launcher/old-commands.ndjson")));
    }
}
