package dev.mcc.core.engine;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.BlockReplacement;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.MccOriginalOreFeature;
import net.minecraft.world.level.levelgen.feature.OreFeature;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockMatchTest;
import org.bukkit.support.environment.VanillaFeature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Tests the actual full placement algorithm against the verified unchanged upstream source. */
@VanillaFeature
class OreFeatureEquivalenceTest {
    private record Write(BlockPos position, BlockState before, BlockState after) {}
    private record Section(int x, int y, int z) {}
    private record Outcome(boolean placed, List<Write> writes, Map<BlockPos, BlockState> blocks, List<Long> nextRandom) {}

    @AfterEach
    void cleanup() { OreBitSetPool.clearCurrentWorker(); }

    private static Feature feature(boolean patched, int size, float exposure) {
        final List<BlockReplacement> targets = List.of(
            new BlockReplacement(new BlockMatchTest(Blocks.STONE), Blocks.DIAMOND_ORE.defaultBlockState()),
            new BlockReplacement(new BlockMatchTest(Blocks.DEEPSLATE), Blocks.DEEPSLATE_LAPIS_ORE.defaultBlockState()));
        return patched ? new OreFeature(targets, size, exposure) : new MccOriginalOreFeature(targets, size, exposure);
    }

    private static final class Grid {
        private final Map<BlockPos, BlockState> blocks = new HashMap<>();
        private final List<Write> writes = new ArrayList<>();
        private final Map<Section, LevelChunkSection> sections = new HashMap<>();
        private final Map<Long, ChunkAccess> chunks = new HashMap<>();
        private Runnable firstWrite;
        private final WorldGenLevel level = mock(WorldGenLevel.class, withSettings().stubOnly());

        private Grid() {
            when(level.getHeight(any(Heightmap.Types.class), anyInt(), anyInt())).thenReturn(32);
            when(level.getSectionIndex(anyInt())).thenAnswer(call -> ((int)call.getArgument(0) >> 4) + 2);
            when(level.getSectionsCount()).thenReturn(4);
            when(level.isOutsideBuildHeight(anyInt())).thenAnswer(call -> { int y = call.getArgument(0); return y < -32 || y >= 32; });
            when(level.ensureCanWrite(any(BlockPos.class))).thenAnswer(call -> {
                if (firstWrite != null) { final Runnable nested = firstWrite; firstWrite = null; nested.run(); }
                final BlockPos position = call.getArgument(0);
                return Math.abs(position.getX()) < 32 && Math.abs(position.getZ()) < 32 && ((position.getX() + position.getZ()) & 7) != 0;
            });
            when(level.getChunk(anyInt(), anyInt())).thenAnswer(call -> chunk(call.getArgument(0), call.getArgument(1)));
        }

        private BlockState block(BlockPos position) {
            final BlockState changed = blocks.get(position);
            if (changed != null) return changed;
            if (((position.getX() * 31 + position.getY() * 17 + position.getZ() * 7) & 31) == 0) return Blocks.AIR.defaultBlockState();
            if (((position.getX() + position.getZ()) & 15) == 0) return Blocks.GRANITE.defaultBlockState();
            return position.getY() < 0 ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState();
        }

        private ChunkAccess chunk(int x, int z) {
            final long key = ((long)x << 32) | Integer.toUnsignedLong(z);
            return chunks.computeIfAbsent(key, unused -> {
                final ChunkAccess chunk = mock(ChunkAccess.class, withSettings().stubOnly());
                when(chunk.getSection(anyInt())).thenAnswer(call -> section(new Section(x, (int)call.getArgument(0) - 2, z)));
                return chunk;
            });
        }

        private LevelChunkSection section(Section position) {
            return sections.computeIfAbsent(position, unused -> {
                final LevelChunkSection section = mock(LevelChunkSection.class, withSettings().stubOnly());
                when(section.getBlockState(anyInt(), anyInt(), anyInt())).thenAnswer(call -> block(new BlockPos(
                    (position.x() << 4) + (int)call.getArgument(0), (position.y() << 4) + (int)call.getArgument(1), (position.z() << 4) + (int)call.getArgument(2))));
                when(section.setBlockState(anyInt(), anyInt(), anyInt(), any(BlockState.class), anyBoolean())).thenAnswer(call -> {
                    final BlockPos target = new BlockPos((position.x() << 4) + (int)call.getArgument(0),
                        (position.y() << 4) + (int)call.getArgument(1), (position.z() << 4) + (int)call.getArgument(2));
                    final BlockState before = block(target), after = call.getArgument(3);
                    writes.add(new Write(target, before, after)); blocks.put(target, after); return before;
                });
                return section;
            });
        }

        private Outcome run(boolean patched, int size, float exposure, long seed, BlockPos origin) {
            final RandomSource random = RandomSource.create(seed);
            final boolean placed = feature(patched, size, exposure).place(level, null, random, origin);
            final List<Long> next = new ArrayList<>(); for (int i = 0; i < 8; i++) next.add(random.nextLong());
            return new Outcome(placed, List.copyOf(writes), Map.copyOf(blocks), List.copyOf(next));
        }
    }

    @Test
    void fullPlacementAndRandomConsumptionMatchAcrossVeinsExposureAndPooledReuse() {
        int totalWrites = 0;
        for (long seed : new long[]{0L, 42L, 6381247891023L}) {
            for (int size : new int[]{0, 1, 8, 33, 64}) {
                for (float exposure : new float[]{0.0F, 0.35F, 1.0F}) {
                    final BlockPos origin = new BlockPos(8, (seed & 1) == 0 ? 1 : -1, 8);
                    final Outcome expected = new Grid().run(false, size, exposure, seed, origin);
                    final Outcome actual = new Grid().run(true, size, exposure, seed, origin);
                    assertEquals(expected, actual, "seed=" + seed + ", size=" + size + ", exposure=" + exposure);
                    totalWrites += actual.writes().size();
                }
            }
        }
        assertTrue(totalWrites > 100, "The fixture must exercise actual ore placement, not only empty features");
        assertTrue(OreBitSetPool.retainedBits() <= OreBitSetPool.MAX_RETAINED_BITS);
    }

    @Test
    void nestedActualOrePlacementCannotClearTheOuterScan() {
        final Grid originalOuter = new Grid(), originalInner = new Grid(), patchedOuter = new Grid(), patchedInner = new Grid();
        final List<Outcome> nested = new ArrayList<>();
        originalOuter.firstWrite = () -> nested.add(originalInner.run(false, 33, 0.35F, 999, new BlockPos(8, -1, 8)));
        patchedOuter.firstWrite = () -> nested.add(patchedInner.run(true, 33, 0.35F, 999, new BlockPos(8, -1, 8)));
        final Outcome expected = originalOuter.run(false, 64, 0.35F, 4242, new BlockPos(8, 1, 8));
        final Outcome actual = patchedOuter.run(true, 64, 0.35F, 4242, new BlockPos(8, 1, 8));
        assertEquals(2, nested.size()); assertEquals(nested.get(0), nested.get(1)); assertEquals(expected, actual);
        assertFalse(actual.writes().isEmpty()); assertFalse(nested.get(1).writes().isEmpty());
    }

    @Test
    void failedPlacementReleasesScratchBeforeTheNextActualFeature() {
        final Grid broken = new Grid(); broken.firstWrite = () -> { throw new IllegalStateException("controlled feature callback failure"); };
        assertThrows(IllegalStateException.class, () -> broken.run(true, 33, 0.35F, 4242, new BlockPos(8, 1, 8)));
        assertEquals(new Grid().run(false, 64, 0.35F, 4242, new BlockPos(8, -1, 8)),
            new Grid().run(true, 64, 0.35F, 4242, new BlockPos(8, -1, 8)));
    }
}
