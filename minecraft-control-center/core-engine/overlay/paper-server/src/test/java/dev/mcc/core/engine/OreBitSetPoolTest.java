package dev.mcc.core.engine;

import java.util.BitSet;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OreBitSetPoolTest {
    @AfterEach
    void cleanWorker() {
        OreBitSetPool.clearCurrentWorker();
    }

    @Test
    void clearsAndReusesWithoutChangingBits() {
        final BitSet first = OreBitSetPool.acquire(8192);
        final BitSet expected = new BitSet(8192);
        for (int i = 0; i < 8192; i += 13) {
            first.set(i);
            expected.set(i);
        }
        assertEquals(expected, first);
        OreBitSetPool.release(first);
        final BitSet second = OreBitSetPool.acquire(512);
        assertSame(first, second);
        assertTrue(second.isEmpty());
        OreBitSetPool.release(second);
    }

    @Test
    void nestedUseDoesNotClearOrReleaseOuterBuffer() {
        final BitSet outer = OreBitSetPool.acquire(512);
        outer.set(72);
        final BitSet inner = OreBitSetPool.acquire(512);
        assertNotSame(outer, inner);
        inner.set(99);
        OreBitSetPool.release(inner);
        assertTrue(outer.get(72));
        assertFalse(outer.get(99));
        final BitSet anotherInner = OreBitSetPool.acquire(512);
        assertNotSame(outer, anotherInner);
        OreBitSetPool.release(anotherInner);
        OreBitSetPool.release(outer);
        assertSame(outer, OreBitSetPool.acquire(512));
    }

    @Test
    void largeDatapackAllocationIsNeverRetained() {
        final BitSet large = OreBitSetPool.acquire(OreBitSetPool.MAX_RETAINED_BITS + 1);
        large.set(OreBitSetPool.MAX_RETAINED_BITS);
        OreBitSetPool.release(large);
        assertEquals(0, OreBitSetPool.retainedBits());
        final BitSet small = OreBitSetPool.acquire(512);
        assertNotSame(large, small);
        OreBitSetPool.release(small);
        assertEquals(512, OreBitSetPool.retainedBits());
    }

    @Test
    void unadvertisedGrowthIsNotRetained() {
        final BitSet buffer = OreBitSetPool.acquire(512);
        buffer.set(OreBitSetPool.MAX_RETAINED_BITS + 1);
        OreBitSetPool.release(buffer);
        assertEquals(0, OreBitSetPool.retainedBits());
    }

    @Test
    void preservesNegativeSizeFailure() {
        assertThrows(NegativeArraySizeException.class, () -> OreBitSetPool.acquire(-1));
        assertEquals(0, OreBitSetPool.retainedBits());
    }

    @Test
    void zeroSizedBufferWorks() {
        final BitSet buffer = OreBitSetPool.acquire(0);
        assertTrue(buffer.isEmpty());
        OreBitSetPool.release(buffer);
        assertSame(buffer, OreBitSetPool.acquire(0));
    }

    @Test
    void generationWorkersHaveIndependentScratch() throws Exception {
        final BitSet main = OreBitSetPool.acquire(512);
        main.set(5);
        try (var workers = Executors.newSingleThreadExecutor()) {
            final BitSet worker = workers.submit(() -> {
                final BitSet own = OreBitSetPool.acquire(512);
                own.set(9);
                return own;
            }).get();
            assertNotSame(main, worker);
            assertTrue(main.get(5));
            assertFalse(main.get(9));
            assertTrue(worker.get(9));
        }
        OreBitSetPool.release(main);
    }
}
