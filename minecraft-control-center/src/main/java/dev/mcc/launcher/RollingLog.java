package dev.mcc.launcher;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** The launcher captures child output privately; new log storage is bounded to two 8 MiB files. */
final class RollingLog implements AutoCloseable {
    static final long MAX_BYTES = 8L * 1024 * 1024;
    private final Path root, file, previous;
    private OutputStream stream;
    private long size;
    private boolean closed;
    RollingLog(Path root, Path control) throws IOException {
        this.root = root; file = control.resolve("console.log"); previous = control.resolve("console.log.1");
        LauncherFiles.createPrivateFile(root, file); size = Files.size(file);
        if (size > MAX_BYTES) throw new IOException("Legacy launcher log exceeds budget; archive it offline before migration");
        if (Files.exists(previous, LinkOption.NOFOLLOW_LINKS)) {
            LauncherFiles.ordinary(root, previous, false);
            if (Files.size(previous) > MAX_BYTES) throw new IOException("Legacy rotated log exceeds budget; archive it offline before migration");
        }
        stream = Files.newOutputStream(file, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS);
    }
    synchronized void write(byte[] bytes, int offset, int length) throws IOException {
        if (closed) throw new IOException("Launcher log is closed");
        while (length > 0) {
            if (size >= MAX_BYTES) rotate();
            int part = (int) Math.min(length, MAX_BYTES - size);
            stream.write(bytes, offset, part); stream.flush(); size += part; offset += part; length -= part;
        }
    }
    private void rotate() throws IOException {
        stream.close(); LauncherFiles.ordinary(root, file, false);
        if (Files.exists(previous, LinkOption.NOFOLLOW_LINKS)) LauncherFiles.ordinary(root, previous, false);
        Files.move(file, previous, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        LauncherFiles.createPrivateFile(root, file);
        stream = Files.newOutputStream(file, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS); size = 0;
    }
    @Override public synchronized void close() throws IOException { closed = true; if (stream != null) stream.close(); }
}
