package dev.ftcsim.platform;

import java.util.concurrent.locks.LockSupport;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * Holds a thread to a fixed cadence. A frame that is more than one period late re-anchors the schedule instead of
 * bursting to catch up.
 */
public final class FrameLimiter implements FramePacer {
    private final long periodNanos;
    private final LongSupplier clock;
    private final DeadlineWaiter waiter;
    private long deadline;
    private boolean anchored;

    public FrameLimiter(double hz) {
        this(Math.round(1_000_000_000.0 / hz), System::nanoTime, LockSupport::parkNanos);
    }

    /**
     * @param clock nanosecond clock, normally {@link System#nanoTime()}
     * @param parker waits roughly the requested nanoseconds and may wake early or late; see {@link DeadlineWaiter}
     */
    public FrameLimiter(long periodNanos, LongSupplier clock, LongConsumer parker) {
        if (periodNanos <= 0) {
            throw new IllegalArgumentException("periodNanos must be positive: " + periodNanos);
        }
        this.periodNanos = periodNanos;
        this.clock = clock;
        this.waiter = new DeadlineWaiter(clock, parker);
    }

    public long periodNanos() {
        return periodNanos;
    }

    /** Blocks until the next frame boundary and returns how many nanoseconds past it the call woke. */
    @Override
    public long awaitNextFrame() {
        if (!anchored) {
            anchored = true;
            deadline = clock.getAsLong() + periodNanos;
        }
        long late = waiter.awaitUntil(deadline);
        long now = deadline + late;
        deadline = late >= periodNanos ? now + periodNanos : deadline + periodNanos;
        return late;
    }

    long spinMarginNanos() {
        return waiter.spinMarginNanos();
    }
}
