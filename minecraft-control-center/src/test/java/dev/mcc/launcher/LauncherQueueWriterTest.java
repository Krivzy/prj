package dev.mcc.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.InterruptedIOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class LauncherQueueWriterTest {
    @TempDir Path root;
    private Path queue() throws Exception { return Files.createFile(root.resolve("commands.ndjson")); }
    @Test void genuineIoFailureIsNotRetriedOrReportedAsBusy() throws Exception {
        FileChannel channel = FileChannel.open(queue(), StandardOpenOption.WRITE); channel.close();
        long before = System.nanoTime(); assertThrows(ClosedChannelException.class, () -> LauncherQueueWriter.acquire(channel));
        assertTrue(System.nanoTime() - before < TimeUnit.SECONDS.toNanos(1));
    }
    @Test void retainedConflictingLeaseTimesOutWithinTheBoundedBudget() throws Exception {
        Path file = queue();
        try (FileChannel reader = FileChannel.open(file, StandardOpenOption.READ);
             var guard = reader.lock(0, Long.MAX_VALUE, true);
             FileChannel writer = FileChannel.open(file, StandardOpenOption.WRITE)) {
            long before = System.nanoTime(); assertThrows(LauncherQueueWriter.BusyException.class, () -> LauncherQueueWriter.acquire(writer));
            long elapsed = System.nanoTime() - before;
            assertTrue(elapsed >= TimeUnit.MILLISECONDS.toNanos(1800)); assertTrue(elapsed < TimeUnit.SECONDS.toNanos(5));
            assertEquals(0, Files.size(file));
        }
    }
    @Test void interruptionDuringWaitRemainsSetAndNeverWrites() throws Exception {
        Path file = queue(); AtomicReference<Throwable> failure = new AtomicReference<>(); AtomicBoolean interrupted = new AtomicBoolean();
        try (FileChannel reader = FileChannel.open(file, StandardOpenOption.READ);
             var guard = reader.lock(0, Long.MAX_VALUE, true);
             FileChannel writer = FileChannel.open(file, StandardOpenOption.WRITE)) {
            Thread waiter = new Thread(() -> {
                try (var lock = LauncherQueueWriter.acquire(writer)) { failure.set(new AssertionError("Conflicting lease unexpectedly acquired")); }
                catch (Throwable error) { failure.set(error); interrupted.set(Thread.currentThread().isInterrupted()); }
            });
            waiter.start(); long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (waiter.getState() != Thread.State.TIMED_WAITING && waiter.isAlive() && System.nanoTime() - until < 0) Thread.sleep(1);
            assertEquals(Thread.State.TIMED_WAITING, waiter.getState()); waiter.interrupt(); waiter.join(3000);
            assertFalse(waiter.isAlive()); assertInstanceOf(InterruptedIOException.class, failure.get()); assertTrue(interrupted.get()); assertEquals(0, Files.size(file));
        }
    }
    @Test void alreadyInterruptedCallerDoesNotAcquireTheQueue() throws Exception {
        try (FileChannel writer = FileChannel.open(queue(), StandardOpenOption.WRITE)) {
            Thread.currentThread().interrupt();
            try { assertThrows(InterruptedIOException.class, () -> LauncherQueueWriter.acquire(writer)); assertTrue(Thread.currentThread().isInterrupted()); }
            finally { Thread.interrupted(); }
        }
    }
}
