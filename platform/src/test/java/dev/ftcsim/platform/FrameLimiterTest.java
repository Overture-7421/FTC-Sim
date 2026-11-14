package dev.ftcsim.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

class FrameLimiterTest {
    private static final long PERIOD = 6_944_444L;

    /** Virtual time: reading the clock costs 1 us and a park lasts the request plus a fixed overshoot. */
    private static final class FakeTime implements LongSupplier, LongConsumer {
        long now;
        long parkOvershoot;

        @Override
        public long getAsLong() {
            now += 1_000;
            return now;
        }

        @Override
        public void accept(long nanos) {
            now += nanos + parkOvershoot;
        }
    }

    @Test
    void wakesOnEachDeadlineWhenWorkIsShorterThanThePeriod() {
        FakeTime time = new FakeTime();
        FrameLimiter limiter = new FrameLimiter(PERIOD, time, time);

        long previous = -1;
        for (int frame = 0; frame < 100; frame++) {
            time.now += 500_000; // simulated work
            limiter.awaitNextFrame();
            if (previous >= 0) {
                assertEquals(PERIOD, time.now - previous, 3_000, "frame " + frame);
            }
            previous = time.now;
        }
    }

    @Test
    void adaptsItsSpinMarginToTheObservedParkOvershoot() {
        FakeTime time = new FakeTime();
        time.parkOvershoot = 800_000;
        FrameLimiter limiter = new FrameLimiter(PERIOD, time, time);

        for (int frame = 0; frame < 200; frame++) {
            limiter.awaitNextFrame();
        }

        assertTrue(limiter.spinMarginNanos() >= 800_000, "margin " + limiter.spinMarginNanos());
        assertTrue(limiter.spinMarginNanos() <= 2_000_000);
    }

    @Test
    void reAnchorsInsteadOfBurstingAfterAFrameThatOverranByMoreThanAPeriod() {
        FakeTime time = new FakeTime();
        FrameLimiter limiter = new FrameLimiter(PERIOD, time, time);
        limiter.awaitNextFrame();

        time.now += 5 * PERIOD;
        long late = limiter.awaitNextFrame();
        assertTrue(late >= 4 * PERIOD);

        long before = time.now;
        limiter.awaitNextFrame();
        assertEquals(PERIOD, time.now - before, 3_000);
    }

    @Test
    void rejectsAnInvalidPeriod() {
        FakeTime time = new FakeTime();
        assertThrows(IllegalArgumentException.class, () -> new FrameLimiter(0, time, time));
    }
}
