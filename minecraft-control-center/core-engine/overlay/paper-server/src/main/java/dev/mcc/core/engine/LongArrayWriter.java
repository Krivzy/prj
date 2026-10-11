package dev.mcc.core.engine;

import io.netty.buffer.ByteBuf;
import java.nio.ByteBuffer;

/** Encodes the same long sequence as ByteBuf.writeLong, with a contiguous bulk path. */
public final class LongArrayWriter {
    private static final int BULK_THRESHOLD = 64;

    private LongArrayWriter() {
    }

    public static void writeFixed(final ByteBuf output, final long[] values) {
        final int count = values.length;
        if (count < BULK_THRESHOLD || count > Integer.MAX_VALUE / Long.BYTES || output.isReadOnly()) {
            writeScalar(output, values);
            return;
        }
        final int bytes = count * Long.BYTES;
        // Preserve the scalar writer's partial-write/failure behavior for a
        // fixed-capacity buffer. A composite NIO view may be a detached copy.
        if (output.maxWritableBytes() < bytes || output.nioBufferCount() != 1) {
            writeScalar(output, values);
            return;
        }
        output.ensureWritable(bytes);
        if (output.nioBufferCount() != 1) {
            // Growing a formerly contiguous composite buffer can add a
            // component. Its merged nioBuffer would then be a copy.
            writeScalar(output, values);
            return;
        }
        final int start = output.writerIndex();
        final ByteBuffer contiguous = output.nioBuffer(start, bytes).order(output.order());
        contiguous.asLongBuffer().put(values);
        output.writerIndex(start + bytes);
    }

    private static void writeScalar(final ByteBuf output, final long[] values) {
        for (final long value : values) {
            output.writeLong(value);
        }
    }
}
