package dev.mcc.launcher;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Bounded coordination with a reader's brief shared lease; callers revalidate before writing. */
public final class LauncherQueueWriter {
    private LauncherQueueWriter() {}
    public static Object identity(Path root, Path queue) throws IOException { return LauncherFiles.fileKey(root, queue); }
    public static FileLock acquire(FileChannel channel) throws IOException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        for (;;) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Launcher queue wait interrupted");
            if (System.nanoTime() - until >= 0) throw new BusyException();
            FileLock lock;
            try { lock = channel.tryLock(); }
            catch (OverlappingFileLockException readerBusy) { lock = null; }
            if (lock != null) return lock;
            long remaining = until - System.nanoTime();
            if (remaining <= 0) throw new BusyException();
            try { TimeUnit.NANOSECONDS.sleep(Math.min(TimeUnit.MILLISECONDS.toNanos(10), remaining)); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); throw new InterruptedIOException("Launcher queue wait interrupted");
            }
        }
    }
    public static final class BusyException extends IOException {
        private BusyException() { super("Launcher command queue remained busy"); }
    }
}
