package dev.mcc.launcher;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Incremental, finite-budget reads; incomplete records wait and oversized records are discarded. */
final class CommandQueue implements AutoCloseable {
    private final Path root, file;
    private final Object identity;
    private final FileChannel channel;
    private final ReadOperation operation;
    private long position;
    private boolean discarding;
    @FunctionalInterface interface ReadOperation { int read(FileChannel channel, ByteBuffer target, long offset) throws IOException; }
    CommandQueue(Path root, Path file) throws IOException { this(root, file, FileChannel::read); }
    CommandQueue(Path root, Path file, ReadOperation operation) throws IOException {
        this.operation = operation;
        this.root = root; this.file = file; identity = LauncherFiles.fileKey(root, file); position = Files.size(file);
        if (position > LauncherProtocol.MAX_QUEUE_BYTES) throw new IOException("Command history exceeds budget; archive it offline");
        channel = LauncherFiles.retainedChannel(file, StandardOpenOption.READ);
        if (!identity.equals(LauncherFiles.fileKey(root, file))) { channel.close(); throw new IOException("Launcher command file was replaced"); }
    }
    long position() { return position; }
    private void validate(long minimumPosition) throws IOException {
        if (!identity.equals(LauncherFiles.fileKey(root, file))) throw new IOException("Launcher command file was replaced");
        long before = Files.size(file), opened = channel.size(), after = Files.size(file);
        // NTFS can preserve creation time when replacing a filename (file tunnelling).
        // The retained handle must track the path's append-only size, even in that case.
        // An append between these reads is allowed; a detached old handle is refused.
        if (before < minimumPosition || after < before || after > LauncherProtocol.MAX_QUEUE_BYTES || opened < before || opened > after)
            throw new IOException("Launcher command file identity or size changed");
    }
    List<byte[]> read() throws IOException {
        validate(position);
        FileLock guard;
        try { guard = channel.tryLock(0, Long.MAX_VALUE, true); }
        catch (OverlappingFileLockException writerBusy) { return List.of(); }
        if (guard == null) return List.of();
        try (guard) { return readOwned(); }
    }
    private List<byte[]> readOwned() throws IOException {
        validate(position);
        long cursor = position; boolean discard = discarding;
        ArrayList<byte[]> records = new ArrayList<>();
        {
            if (!identity.equals(LauncherFiles.fileKey(root, file))) throw new IOException("Launcher command file was replaced");
            for (int batch = 0; batch < 64; batch++) {
                byte[] buffer = new byte[LauncherProtocol.MAX_COMMAND_BYTES + 1]; ByteBuffer data = ByteBuffer.wrap(buffer);
                int length = 0, end = -1;
                while (data.hasRemaining()) {
                    int count = operation.read(channel, data, cursor + length);
                    if (count <= 0) break;
                    for (int i = length; i < length + count; i++) if (buffer[i] == '\n') { end = i + 1; break; }
                    length += count;
                    if (end >= 0) break;
                }
                if (length == 0) break;
                if (discard) { cursor += end >= 0 ? end : length; if (end >= 0) discard = false; continue; }
                if (end > LauncherProtocol.MAX_COMMAND_BYTES || end < 0 && length > LauncherProtocol.MAX_COMMAND_BYTES) {
                    cursor += end >= 0 ? end : length; discard = end < 0; continue;
                }
                if (end < 0) break;
                cursor += end; records.add(Arrays.copyOf(buffer, end));
            }
        }
        validate(cursor);
        // Match the writers' exclusive whole-file lease. Windows locks deny unguarded reads
        // with generic IOException; shared ownership avoids that race without hiding I/O errors.
        // Retain it through the final validation and transactional cursor commit.
        position = cursor; discarding = discard;
        return List.copyOf(records);
    }
    @Override public void close() throws IOException { channel.close(); }
}
