package dev.mcc.launcher;

import java.time.Duration;
import java.util.ArrayDeque;

/** Only an owned child's unexpected nonzero exit is eligible for bounded recovery. */
final class CrashPolicy {
    private static final long WINDOW_NANOS = Duration.ofMinutes(10).toNanos();
    private static final int[] DELAYS_SECONDS = {2, 10, 30};
    private final int maximum;
    private final ArrayDeque<Long> failures = new ArrayDeque<>();
    CrashPolicy(int maximum) {
        if (maximum < 0 || maximum > DELAYS_SECONDS.length) throw new IllegalArgumentException("Invalid recovery limit");
        this.maximum = maximum;
    }
    Integer delaySeconds(long nowNanos) {
        while (!failures.isEmpty() && nowNanos - failures.getFirst() >= WINDOW_NANOS) failures.removeFirst();
        failures.addLast(nowNanos);
        return failures.size() <= maximum ? DELAYS_SECONDS[failures.size() - 1] : null;
    }
    int attempts() { return failures.size(); }
}
