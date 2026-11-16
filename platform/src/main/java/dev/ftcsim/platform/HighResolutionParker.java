package dev.ftcsim.platform;

import java.util.concurrent.locks.LockSupport;
import java.util.function.LongConsumer;

/**
 * Waits on the Windows high-resolution timer, whose wake-ups are within about half a millisecond, and on
 * {@link LockSupport#parkNanos(long)} where that timer cannot be opened. A parker belongs to one thread, because
 * the timer it holds can serve only one wait at a time.
 */
public final class HighResolutionParker implements LongConsumer, AutoCloseable {
    private final WindowsWaitableTimer timer;
    private final String fallbackReason;

    public HighResolutionParker() {
        WindowsWaitableTimer opened = null;
        String reason = null;
        try {
            opened = WindowsWaitableTimer.openHighResolution();
        } catch (RuntimeException unavailable) {
            reason = unavailable.getMessage();
        }
        timer = opened;
        fallbackReason = reason;
    }

    @Override
    public void accept(long nanos) {
        if (timer != null) {
            timer.parkNanos(nanos);
        } else {
            LockSupport.parkNanos(nanos);
        }
    }

    public String description() {
        return timer != null ? "high-resolution timer" : "park (high-resolution timer unavailable: " + fallbackReason + ")";
    }

    @Override
    public void close() {
        if (timer != null) {
            timer.close();
        }
    }
}
