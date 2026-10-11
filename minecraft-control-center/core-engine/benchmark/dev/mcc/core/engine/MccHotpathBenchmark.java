package dev.mcc.core.engine;

import com.sun.management.ThreadMXBean;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import net.minecraft.nbt.*;

/** A bounded standalone microbenchmark. No server, worlds, timing assertions or throughput claims. */
public final class MccHotpathBenchmark {
    private static volatile long blackhole;
    private static final int WARMUPS = 4, ROUNDS = 8;
    private interface Op { long run() throws Exception; }
    private record Sample(String mode, int round, int operations, long elapsedNanos, long allocatedBytes, long checksum) {}
    private record Workload(String name, int operations, Op original, Op optimized, AutoCloseable resources) {}

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) throw new IllegalArgumentException("Supply a new JSON result path");
        final Path output = Path.of(arguments[0]);
        if (Files.exists(output)) throw new IOException("Refusing to overwrite a benchmark result");
        final ThreadMXBean thread = (ThreadMXBean)ManagementFactory.getThreadMXBean();
        if (!thread.isThreadAllocatedMemorySupported()) throw new IllegalStateException("Thread allocation counters unavailable");
        thread.setThreadAllocatedMemoryEnabled(true);
        final List<String> results = new ArrayList<>();
        final List<Workload> workloads = List.of(scratch(4096), scratch(16384), longs(256, false), longs(2048, false), longs(2048, true), nbt(32), nbt(256));
        try {
            for (Workload work : workloads) {
                // Equal outputs are checked before measurements. Full long/NBT
                // byte equivalence is also checked at workload construction.
                if (work.original().run() != work.optimized().run()) throw new AssertionError("Preflight checksum mismatch: " + work.name());
                for (int warmup = 0; warmup < WARMUPS; warmup++) {
                    if ((warmup & 1) == 0) { measure(work.original(), "original", warmup, work.operations(), thread); measure(work.optimized(), "optimized", warmup, work.operations(), thread); }
                    else { measure(work.optimized(), "optimized", warmup, work.operations(), thread); measure(work.original(), "original", warmup, work.operations(), thread); }
                }
                final List<Sample> samples = new ArrayList<>();
                for (int round = 0; round < ROUNDS; round++) {
                    final Sample original, optimized;
                    if ((round & 1) == 0) { original = measure(work.original(), "original", round, work.operations(), thread); optimized = measure(work.optimized(), "optimized", round, work.operations(), thread); }
                    else { optimized = measure(work.optimized(), "optimized", round, work.operations(), thread); original = measure(work.original(), "original", round, work.operations(), thread); }
                    if (original.checksum() != optimized.checksum()) throw new AssertionError("Measured checksum mismatch: " + work.name());
                    samples.add(original); samples.add(optimized);
                }
                results.add("{\"workload\":\"" + work.name() + "\",\"samples\":[" + String.join(",", samples.stream().map(MccHotpathBenchmark::json).toList()) + "]}");
            }
        } finally {
            for (Workload work : workloads) work.resources().close();
            OreBitSetPool.clearCurrentWorker();
        }
        final String json = "{\"scope\":\"isolated-hotpaths-only\",\"java\":\"" + System.getProperty("java.version") + "\",\"warmupsPerMode\":" + WARMUPS + ",\"counterbalancedRounds\":" + ROUNDS + ",\"threadAllocationCounter\":true,\"checksumsEqual\":true,\"serverStarted\":false,\"serverSuperiorityClaimed\":false,\"results\":[" + String.join(",", results) + "]}\n";
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, json, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        System.out.println("MCC hotpaths: 7 workloads, 8 counterbalanced rounds; checksums equal; server not started.");
    }

    private static Sample measure(Op operation, String mode, int round, int operations, ThreadMXBean thread) throws Exception {
        final long id = Thread.currentThread().threadId(), beforeBytes = thread.getThreadAllocatedBytes(id), beforeTime = System.nanoTime();
        long checksum = 0;
        for (int i = 0; i < operations; i++) checksum = checksum * 31 + operation.run();
        final long elapsed = System.nanoTime() - beforeTime, allocated = thread.getThreadAllocatedBytes(id) - beforeBytes;
        blackhole ^= checksum;
        return new Sample(mode, round, operations, elapsed, allocated, checksum);
    }

    private static String json(Sample s) { return "{\"mode\":\"" + s.mode() + "\",\"round\":" + s.round() + ",\"operations\":" + s.operations() + ",\"elapsedNanos\":" + s.elapsedNanos() + ",\"allocatedBytes\":" + s.allocatedBytes() + ",\"checksum\":\"" + Long.toUnsignedString(s.checksum()) + "\"}"; }

    private static long touch(BitSet bits, int size) {
        for (int i = 0; i < 64; i++) bits.set((i * 179) & (size - 1));
        return bits.length() ^ (bits.get(179) ? 1L << 20 : 0L) ^ bits.cardinality();
    }

    private static Workload scratch(int bits) {
        final Op original = () -> touch(new BitSet(bits), bits);
        final Op optimized = () -> { final BitSet scratch = OreBitSetPool.acquire(bits); try { return touch(scratch, bits); } finally { OreBitSetPool.release(scratch); } };
        return new Workload("ore-scratch-" + bits + "-bits", 20000, original, optimized, () -> {});
    }

    @SuppressWarnings("deprecation")
    private static Workload longs(int size, boolean direct) throws Exception {
        final long[] values = new long[size]; final Random random = new Random(6381247891023L + size);
        for (int i = 0; i < size; i++) values[i] = random.nextLong();
        final ByteBuf original = direct ? Unpooled.directBuffer(size * 8, size * 8) : Unpooled.buffer(size * 8, size * 8);
        final ByteBuf optimized = direct ? Unpooled.directBuffer(size * 8, size * 8) : Unpooled.buffer(size * 8, size * 8);
        final Op scalar = () -> { original.clear(); for (long value : values) original.writeLong(value); return consume(original); };
        final Op bulk = () -> { optimized.clear(); LongArrayWriter.writeFixed(optimized, values); return consume(optimized); };
        scalar.run(); bulk.run(); if (!original.equals(optimized)) throw new AssertionError("Full long bytes differ");
        return new Workload("long-encoding-" + size + "-" + (direct ? "direct" : "heap"), 20000, scalar, bulk, () -> { original.release(); optimized.release(); });
    }

    private static long consume(ByteBuf bytes) { return bytes.getLong(0) ^ bytes.getLong(bytes.writerIndex() / 2) ^ bytes.getLong(bytes.writerIndex() - 8) ^ bytes.writerIndex(); }

    private static Workload nbt(int entries) throws Exception {
        final CompoundTag compound = new CompoundTag();
        for (int i = 0; i < entries; i++) { compound.putInt("value_" + i, i * 179); compound.putString("name_" + i, "payload_" + i); }
        final ByteArrayOutputStream before = new ByteArrayOutputStream(32768), after = new ByteArrayOutputStream(32768);
        final DataOutputStream original = new DataOutputStream(before), optimized = new DataOutputStream(after);
        final Op lookup = () -> { before.reset(); writeOriginal(compound, original); return before.size(); };
        final Op entriesWriter = () -> { after.reset(); compound.write(optimized); return after.size(); };
        lookup.run(); entriesWriter.run();
        if (!Arrays.equals(before.toByteArray(), after.toByteArray())) throw new AssertionError("Full NBT bytes differ");
        return new Workload("nbt-" + entries + "-int-string-pairs", 2000, lookup, entriesWriter, () -> { original.close(); optimized.close(); });
    }

    private static void writeOriginal(CompoundTag compound, DataOutputStream output) throws IOException {
        for (String key : compound.keySet()) {
            final Tag tag = compound.get(key); output.writeByte(tag.getId());
            if (tag.getId() != 0) { output.writeUTF(key); if (tag instanceof CompoundTag nested) writeOriginal(nested, output); else tag.write(output); }
        }
        output.writeByte(0);
    }
}
