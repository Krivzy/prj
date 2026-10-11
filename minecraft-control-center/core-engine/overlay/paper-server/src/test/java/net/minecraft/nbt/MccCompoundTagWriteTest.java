package net.minecraft.nbt;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class MccCompoundTagWriteTest {
    @Test
    void fastutilHashAndOrderedMapsProduceOriginalWriterBytes() throws Exception {
        compare(new it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<>(8, 0.8f));
        compare(new HashMap<>());
        compare(new LinkedHashMap<>());
        compare(new java.util.TreeMap<>());
    }

    @Test
    void emptyCompoundPreservesTerminator() throws Exception {
        assertArrayEquals(new byte[]{0}, encode(new CompoundTag(), false));
    }

    private static void compare(Map<String, Tag> entries) throws Exception {
        final CompoundTag nested = new CompoundTag();
        nested.putString("unicode", "Тест \uD83D\uDC8E \u0000");
        nested.putLong("minimum", Long.MIN_VALUE);
        nested.putLongArray("heightmap", new long[]{0, -1, Long.MAX_VALUE, Long.MIN_VALUE});
        entries.put("nested", nested);
        entries.put("short", ShortTag.valueOf((short) -32768));
        entries.put("byte", ByteTag.valueOf((byte) 127));
        entries.put("float", FloatTag.valueOf(-0.0F));
        entries.put("double", DoubleTag.valueOf(Double.NaN));
        entries.put("utf-ключ", StringTag.valueOf("value"));
        for (int index = 0; index < 300; index++) entries.put("index_" + index, IntTag.valueOf(index));
        final CompoundTag compound = new CompoundTag(entries);
        final byte[] expected = encode(compound, true);
        final byte[] actual = encode(compound, false);
        assertArrayEquals(expected, actual);
        assertEquals(compound, CompoundTag.TYPE.load(new java.io.DataInputStream(new java.io.ByteArrayInputStream(actual)), NbtAccounter.unlimitedHeap()));
    }

    private static byte[] encode(CompoundTag tag, boolean original) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            if (original) writeOriginal(tag, output); else tag.write(output);
        }
        return bytes.toByteArray();
    }

    private static void writeOriginal(CompoundTag compound, DataOutputStream output) throws IOException {
        for (String key : compound.keySet()) {
            final Tag tag = compound.get(key);
            output.writeByte(tag.getId());
            if (tag.getId() != 0) {
                output.writeUTF(key);
                if (tag instanceof CompoundTag nested) writeOriginal(nested, output); else tag.write(output);
            }
        }
        output.writeByte(0);
    }
}
