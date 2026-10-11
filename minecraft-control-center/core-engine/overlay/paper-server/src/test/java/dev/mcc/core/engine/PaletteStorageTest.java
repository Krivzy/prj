package dev.mcc.core.engine;

import java.util.Random;
import net.minecraft.util.SimpleBitStorage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PaletteStorageTest {
    @Test
    void everySupportedWidthPreservesPackedRepresentationAndMutations() {
        final int size = 4096;
        final Random random = new Random(721);
        for (int bits = 1; bits <= 32; bits++) {
            final int perLong = 64 / bits;
            final long mask = (1L << bits) - 1;
            final SimpleBitStorage storage = new SimpleBitStorage(bits, size);
            final long[] expected = new long[(size + perLong - 1) / perLong];
            final int[] values = new int[size];
            for (int index = 0; index < size; index++) {
                final int value = random.nextInt() & (int) mask;
                values[index] = value;
                storage.set(index, value);
                expected[index / perLong] |= ((long) value & mask) << ((index % perLong) * bits);
            }
            assertArrayEquals(expected, storage.getRaw(), "width " + bits);
            for (int index = 0; index < size; index++) assertEquals(values[index], storage.get(index));
            final int index = 2047;
            assertEquals(values[index], storage.getAndSet(index, 0));
            assertEquals(0, storage.get(index));
            final SimpleBitStorage copy = (SimpleBitStorage) storage.copy();
            storage.fill((int) mask);
            assertEquals(0, copy.get(index));
            for (int cursor = 0; cursor < size; cursor++) assertEquals((int) mask, storage.get(cursor));
            assertFalse(java.util.Arrays.stream(SimpleBitStorage.class.getDeclaredFields())
                .anyMatch(field -> field.getName().equals("divideMulUnsigned") || field.getName().equals("divideAddUnsigned")));
        }
    }
}
