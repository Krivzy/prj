package dev.mcc.core;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RateLimiterTest {
    @Test void concurrentRequestsCannotRacePastThePerClientLimit() throws Exception {
        RateLimiter limiter = new RateLimiter();
        AtomicInteger accepted = new AtomicInteger();
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 100; i++) pool.submit(() -> { if (limiter.allow("client", 7, 60_000)) accepted.incrementAndGet(); });
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertEquals(7, accepted.get());
        assertTrue(limiter.allow("separate-client", 7, 60_000));
    }
}
