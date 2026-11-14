package dev.ftcsim.platform;

import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * Waits for an absolute deadline on a clock: parks for most of the wait and spins for the final stretch, with the
 * spin margin adapted to how far the parker has recently overshot, so that a coarse timer costs a little more CPU
 * and a fine one almost none. Not thread-safe; one waiter belongs to one thread.
 */
public final class DeadlineWaiter {
    private static final long MIN_SPIN_NANOS = 100_000L;
    private static final long MAX_SPIN_NANOS = 2_000_000L;
    private static final long SPIN_HEADROOM_NANOS = 100_000L;
    private static final int OVERSHOOT_WINDOW = 64;

    private final LongSupplier clock;
    private final LongConsumer parker;
    private final long[] overshoots = new long[OVERSHOOT_WINDOW];
    private int overshootIndex;
    private long spinMarginNanos = 1_000_000L;

    /**
     * @param clock  nanosecond clock, normally {@link System#nanoTime()}
     * @param parker waits roughly the requested nanoseconds and may wake early or late; a finer timer than
     *               {@link java.util.concurrent.locks.LockSupport#parkNanos(long)} lets the spin margin shrink
     */
    public DeadlineWaiter(LongSupplier clock, LongConsumer parker) {
        this.clock = clock;
        this.parker = parker;
    }

    /** Blocks until the clock reaches {@code deadlineNanos} and returns how many nanoseconds past it the call woke. */
    public long awaitUntil(long deadlineNanos) {
        long now = clock.getAsLong();
        while (true) {
            long remaining = deadlineNanos - now;
            if (remaining <= spinMarginNanos) {
                break;
            }
            long intended = now + remaining - spinMarginNanos;
            parker.accept(remaining - spinMarginNanos);
            now = clock.getAsLong();
            recordOvershoot(now - intended);
        }
        while ((now = clock.getAsLong()) < deadlineNanos) {
            Thread.onSpinWait();
        }
        return now - deadlineNanos;
    }

    long spinMarginNanos() {
        return spinMarginNanos;
    }

    private void recordOvershoot(long overshoot) {
        overshoots[overshootIndex] = Math.max(0L, overshoot);
        overshootIndex = (overshootIndex + 1) % OVERSHOOT_WINDOW;
        long worst = 0L;
        for (long value : overshoots) {
            worst = Math.max(worst, value);
        }
        spinMarginNanos = Math.min(MAX_SPIN_NANOS, Math.max(MIN_SPIN_NANOS, worst + SPIN_HEADROOM_NANOS));
    }
}
