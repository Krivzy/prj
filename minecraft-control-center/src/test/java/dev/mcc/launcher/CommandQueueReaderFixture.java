package dev.mcc.launcher;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;

/** A file-only reader lock subprocess; never launches or controls a Minecraft server. */
public final class CommandQueueReaderFixture {
    private CommandQueueReaderFixture() {}
    public static void main(String[] args) throws Exception {
        Path queue = Path.of(args[0]), ready = Path.of(args[1]), release = Path.of(args[2]);
        try (FileChannel channel = FileChannel.open(queue, StandardOpenOption.READ);
             var guard = channel.lock(0, Long.MAX_VALUE, true)) {
            Files.createFile(ready);
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!Files.exists(release) && System.nanoTime() - until < 0) Thread.sleep(20);
            if (!Files.exists(release)) throw new IllegalStateException("File-only reader release timed out");
        }
    }
}
