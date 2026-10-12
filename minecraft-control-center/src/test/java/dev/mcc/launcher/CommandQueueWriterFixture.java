package dev.mcc.launcher;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;

/** A tiny file-only subprocess fixture: no server, console command, or shutdown operation. */
public final class CommandQueueWriterFixture {
    private CommandQueueWriterFixture() {}
    public static void main(String[] args) throws Exception {
        Path queue = Path.of(args[0]), ready = Path.of(args[1]), release = Path.of(args[2]);
        try (FileChannel channel = FileChannel.open(queue, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
             var guard = channel.lock()) {
            ByteBuffer data = StandardCharsets.UTF_8.encode("writer-record\n");
            while (data.hasRemaining()) channel.write(data);
            channel.force(true); Files.createFile(ready);
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!Files.exists(release) && System.nanoTime() - until < 0) Thread.sleep(20);
            if (!Files.exists(release)) throw new IllegalStateException("File-only fixture release timed out");
        }
    }
}
