package dev.mcc.core.engine;

import java.util.BitSet;

/**
 * One bounded scratch buffer per generation worker. Nested ore generation gets
 * its own buffer so a datapack or feature callback cannot clear an outer scan.
 * This pool never changes generation order, random calls, or block selection.
 */
public final class OreBitSetPool {
    static final int MAX_RETAINED_BITS = 65_536;
    private static final ThreadLocal<State> LOCAL = new ThreadLocal<>();

    private OreBitSetPool() {
    }

    public static BitSet acquire(final int bits) {
        if (bits < 0 || bits > MAX_RETAINED_BITS) {
            return new BitSet(bits);
        }
        State state = LOCAL.get();
        if (state == null) {
            state = new State();
            LOCAL.set(state);
        }
        if (state.inUse) {
            return new BitSet(bits);
        }
        if (state.buffer == null || state.buffer.size() < bits) {
            state.buffer = new BitSet(bits);
        }
        state.buffer.clear();
        state.inUse = true;
        return state.buffer;
    }

    public static void release(final BitSet buffer) {
        final State state = LOCAL.get();
        if (state != null && state.inUse && state.buffer == buffer) {
            buffer.clear();
            // A caller which exceeded its advertised size must not make the
            // retained worker buffer grow beyond the bound.
            if (buffer.size() > MAX_RETAINED_BITS) {
                state.buffer = null;
            }
            state.inUse = false;
        }
    }

    static int retainedBits() {
        final State state = LOCAL.get();
        return state == null || state.buffer == null ? 0 : state.buffer.size();
    }

    static void clearCurrentWorker() {
        LOCAL.remove();
    }

    private static final class State {
        private BitSet buffer;
        private boolean inUse;
    }
}
