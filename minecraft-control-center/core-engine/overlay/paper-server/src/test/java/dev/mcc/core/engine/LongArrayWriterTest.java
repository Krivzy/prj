package dev.mcc.core.engine;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import java.nio.ByteOrder;
import java.util.Random;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LongArrayWriterTest {
    @Test
    void heapDirectPooledSwappedAndSlicedMatchScalar() {
        for (ByteOrder order : new ByteOrder[]{ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN}) {
            compare(() -> Unpooled.buffer(8).order(order));
            compare(() -> Unpooled.directBuffer(8).order(order));
            compare(() -> PooledByteBufAllocator.DEFAULT.buffer(8).order(order));
            compare(() -> PooledByteBufAllocator.DEFAULT.directBuffer(8).order(order));
            compare(() -> Unpooled.wrappedBuffer(new byte[16_448], 17, 16_416).clear().order(order));
            compare(() -> Unpooled.directBuffer(16_448).slice(17, 16_416).clear().order(order));
        }
    }

    @Test
    void compositeBuffersNeverEncodeIntoDetachedMergedView() {
        compare(() -> Unpooled.compositeBuffer().addComponents(false,
            Unpooled.buffer(2048).writerIndex(2048), Unpooled.buffer(14_336).writerIndex(14_336)).clear());
        compare(() -> Unpooled.compositeBuffer().addComponent(false,
            Unpooled.buffer(16).writerIndex(16)).clear());
    }

    @Test
    void readOnlyAndFixedCapacityPreservePartialFailure() {
        compareFailures(() -> Unpooled.buffer(2048, 2048).asReadOnly());
        compareFailures(() -> Unpooled.buffer(69, 69));
        compareFailures(() -> Unpooled.directBuffer(69, 69));
        compareFailures(() -> Unpooled.wrappedBuffer(new byte[69]).clear());
    }

    @Test
    void emptyReleasedAndNullInputsMatchScalarContract() {
        LongArrayWriter.writeFixed(null, new long[0]);
        assertThrows(NullPointerException.class, () -> LongArrayWriter.writeFixed(Unpooled.EMPTY_BUFFER, null));
        ByteBuf released = Unpooled.buffer(1);
        released.release();
        LongArrayWriter.writeFixed(released, new long[0]);
    }

    private static void compare(Supplier<ByteBuf> factory) {
        for (int size : new int[]{0, 1, 16, 63, 64, 65, 256, 2048}) {
            final long[] values = values(size);
            final ByteBuf expected = factory.get();
            final ByteBuf actual = factory.get();
            try {
                expected.writeByte(0xA7);
                actual.writeByte(0xA7);
                for (long value : values) expected.writeLong(value);
                LongArrayWriter.writeFixed(actual, values);
                assertEquals(expected.writerIndex(), actual.writerIndex());
                assertEquals(expected, actual);
            } finally {
                expected.release();
                actual.release();
            }
        }
    }

    private static void compareFailures(Supplier<ByteBuf> factory) {
        final long[] values = values(256);
        final ByteBuf expected = factory.get();
        final ByteBuf actual = factory.get();
        try {
            final Throwable expectedFailure = assertThrows(Throwable.class, () -> {
                for (long value : values) expected.writeLong(value);
            });
            final Throwable actualFailure = assertThrows(Throwable.class, () -> LongArrayWriter.writeFixed(actual, values));
            assertEquals(expectedFailure.getClass(), actualFailure.getClass());
            assertEquals(expected.writerIndex(), actual.writerIndex());
            assertEquals(expected, actual);
        } finally {
            expected.release();
            actual.release();
        }
    }

    private static long[] values(int size) {
        final Random random = new Random(417L + size);
        final long[] values = new long[size];
        for (int i = 0; i < size; i++) values[i] = random.nextLong();
        if (size > 3) {
            values[0] = Long.MIN_VALUE;
            values[1] = Long.MAX_VALUE;
            values[2] = 0;
            values[3] = -1;
        }
        return values;
    }
}
