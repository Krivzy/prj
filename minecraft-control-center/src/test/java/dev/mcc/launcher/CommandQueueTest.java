package dev.mcc.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class CommandQueueTest {
    @TempDir Path root;
    private Path queue() throws Exception { Path control = Files.createDirectory(root.resolve(".mcc-launcher")); return Files.createFile(control.resolve("commands.ndjson")); }
    @Test void ignoresPriorDaemonHistoryAndWaitsForPartialRecord() throws Exception {
        Path file = queue(); Files.writeString(file, "prior\n"); try (CommandQueue reader = new CommandQueue(root, file)) {
        Files.writeString(file, "partial", StandardOpenOption.APPEND); assertTrue(reader.read().isEmpty()); assertEquals(6, reader.position());
        Files.writeString(file, " record\nnext\n", StandardOpenOption.APPEND); var records = reader.read();
        assertEquals(2, records.size()); assertEquals("partial record\n", new String(records.getFirst(), StandardCharsets.UTF_8));
        }
    }
    @Test void oversizedRecordCannotSmuggleValidTailAndNextLineIsRead() throws Exception {
        Path file = queue(); try (CommandQueue reader = new CommandQueue(root, file)) {
        byte[] oversized = new byte[12000]; Arrays.fill(oversized, (byte) 'x'); Files.write(file, oversized, StandardOpenOption.APPEND);
        assertTrue(reader.read().isEmpty()); Files.writeString(file, "fake-tail\nvalid\n", StandardOpenOption.APPEND);
        var records = reader.read(); assertEquals(1, records.size()); assertEquals("valid\n", new String(records.getFirst(), StandardCharsets.UTF_8));
        }
    }
    @Test void perPollBatchAndQueueSizeAreBounded() throws Exception {
        Path file = queue(); try (CommandQueue reader = new CommandQueue(root, file)) { Files.writeString(file, "a\n".repeat(100), StandardOpenOption.APPEND);
        assertEquals(64, reader.read().size()); assertEquals(36, reader.read().size());
        Files.write(file, new byte[1024 * 1024 + 1]); assertThrows(java.io.IOException.class, reader::read);
        }
    }
    @Test void replacementOrTruncationIsRefused() throws Exception {
        Path file = queue(); Files.writeString(file, "history\n"); try (CommandQueue reader = new CommandQueue(root, file)) {
        Files.writeString(file, ""); assertThrows(java.io.IOException.class, reader::read);
        if (LauncherFiles.windows()) {
            assertThrows(java.io.IOException.class, () -> Files.move(file, file.resolveSibling("old-queue")));
            assertThrows(java.io.IOException.class, () -> Files.delete(file));
        } else {
            Files.move(file, file.resolveSibling("old-queue")); Files.writeString(file, "replacement\n"); assertThrows(java.io.IOException.class, reader::read);
        }
        }
    }
    @Test void retainedQueueAllowsAppendAndProtectsWindowsFilenameUntilClose() throws Exception {
        Path file = queue(); try (CommandQueue reader = new CommandQueue(root, file)) {
            if (LauncherFiles.windows()) assertThrows(java.io.IOException.class, () -> Files.delete(file));
            Files.writeString(file, "append\n", StandardOpenOption.APPEND);
            assertEquals(1, reader.read().size());
        }
        Files.move(file, file.resolveSibling("archived-queue"));
        assertTrue(Files.exists(file.resolveSibling("archived-queue")));
    }
    @Test void transientWriterDenialAfterFirstRecordDoesNotAdvanceOrLoseCommands() throws Exception {
        Path file = queue(); java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        try (CommandQueue reader = new CommandQueue(root, file, (channel, target, offset) -> {
            if (calls.incrementAndGet() == 2) throw new java.nio.file.AccessDeniedException("synthetic queue writer lease");
            return channel.read(target, offset);
        })) {
            Files.writeString(file, "first\nsecond\n", StandardOpenOption.APPEND);
            assertThrows(java.nio.file.AccessDeniedException.class, reader::read); assertEquals(0, reader.position());
            var records = reader.read(); assertEquals(2, records.size());
            assertEquals("first\n", new String(records.getFirst(), StandardCharsets.UTF_8));
        }
    }
    @Test void nativeWriterInAnotherJvmDoesNotFailOrConsumeTheQueuedRecordWhileLocked() throws Exception {
        Path file = queue(), ready = root.resolve("writer-ready"), release = root.resolve("writer-release");
        Path java = Path.of(System.getProperty("java.home"), "bin", LauncherFiles.windows() ? "java.exe" : "java");
        String fixtureClasses = Path.of(CommandQueueWriterFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Process writer = null;
        try (CommandQueue reader = new CommandQueue(root, file)) {
            writer = new ProcessBuilder(java.toString(), "-Xms16M", "-Xmx32M", "-cp", fixtureClasses,
                CommandQueueWriterFixture.class.getName(), file.toString(), ready.toString(), release.toString())
                .redirectErrorStream(true).redirectOutput(root.resolve("writer-output.log").toFile()).start();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!Files.exists(ready) && writer.isAlive() && System.nanoTime() - until < 0) Thread.sleep(20);
            assertTrue(Files.exists(ready), "File-only writer did not acquire its exclusive lock");
            assertTrue(reader.read().isEmpty()); assertEquals(0, reader.position());
            Files.createFile(release); assertTrue(writer.waitFor(20, TimeUnit.SECONDS)); assertEquals(0, writer.exitValue());
            var records = reader.read(); assertEquals(1, records.size());
            assertEquals("writer-record\n", new String(records.getFirst(), StandardCharsets.UTF_8));
        } finally {
            if (!Files.exists(release)) Files.createFile(release);
            if (writer != null) { OwnedSupervisor.waitNaturally(writer); assertEquals(0, writer.exitValue()); }
        }
    }
    @Test void overlappingWriterInThisJvmIsBusyWithoutAdvancingOrReading() throws Exception {
        Path file = queue();
        try (CommandQueue reader = new CommandQueue(root, file);
             FileChannel writer = FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            try (var guard = writer.lock()) {
                writer.write(StandardCharsets.UTF_8.encode("same-jvm-record\n"));
                assertTrue(reader.read().isEmpty()); assertEquals(0, reader.position());
            }
            assertEquals(1, reader.read().size());
        }
    }
    @Test void busyWriterCannotHideInvalidQueueTruncation() throws Exception {
        Path file = queue(); Files.writeString(file, "history\n");
        try (CommandQueue reader = new CommandQueue(root, file);
             FileChannel writer = FileChannel.open(file, StandardOpenOption.WRITE);
             var guard = writer.lock()) {
            writer.truncate(0);
            assertThrows(java.io.IOException.class, reader::read); assertEquals(8, reader.position());
        }
    }
}
