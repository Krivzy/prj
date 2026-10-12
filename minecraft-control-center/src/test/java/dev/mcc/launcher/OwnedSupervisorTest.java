package dev.mcc.launcher;

import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class OwnedSupervisorTest {
    @Test void interruptedCleanupStillWaitsForNaturalChildExitBeforeReturning() throws Exception {
        FakeProcess child = new FakeProcess(); AtomicBoolean finished = new AtomicBoolean(), restored = new AtomicBoolean();
        Thread cleanup = new Thread(() -> {
            Thread.currentThread().interrupt(); OwnedSupervisor.waitNaturally(child);
            restored.set(Thread.currentThread().isInterrupted()); finished.set(true);
        }); cleanup.start();
        try {
            assertTrue(child.waiting.await(1, TimeUnit.SECONDS)); cleanup.join(100);
            assertFalse(finished.get()); assertTrue(cleanup.isAlive());
        } finally { child.exited.countDown(); cleanup.join(2000); }
        assertFalse(cleanup.isAlive()); assertTrue(finished.get()); assertTrue(restored.get());
    }
    private static final class FakeProcess extends Process {
        final CountDownLatch waiting = new CountDownLatch(1), exited = new CountDownLatch(1);
        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() throws InterruptedException { waiting.countDown(); exited.await(); return 0; }
        @Override public int exitValue() { if (isAlive()) throw new IllegalThreadStateException(); return 0; }
        @Override public boolean isAlive() { return exited.getCount() != 0; }
        @Override public void destroy() { throw new AssertionError("Normal cleanup must never kill a child"); }
    }
}
