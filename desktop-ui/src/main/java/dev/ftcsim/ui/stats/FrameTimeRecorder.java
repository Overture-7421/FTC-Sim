package dev.ftcsim.ui.stats;

/**
 * Keeps the most recent intervals from one writer thread in a ring of fixed size. The element is stored before the
 * count is published, so another thread may summarise while the writer keeps running; a summary taken during a write
 * may mix in one newer interval, which does not matter for statistics.
 */
public final class FrameTimeRecorder {
    private final long[] intervals;
    private volatile long written;
    private volatile long cleared;
    private long lastMarkNanos;
    private boolean hasLastMark;

    public FrameTimeRecorder(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.intervals = new long[capacity];
    }

    /** Records the time since the previous call; the first call, or the first after {@link #breakSequence}, only starts the clock. */
    public void markFrame(long nowNanos) {
        if (hasLastMark) {
            record(nowNanos - lastMarkNanos);
        }
        lastMarkNanos = nowNanos;
        hasLastMark = true;
    }

    /** Forgets the last mark, so that time spent resting is not recorded as one long interval; writer thread. */
    public void breakSequence() {
        hasLastMark = false;
    }

    public void record(long intervalNanos) {
        long index = written;
        intervals[(int) (index % intervals.length)] = intervalNanos;
        written = index + 1;
    }

    /** Makes everything recorded so far invisible to {@link #copyIntervals}; any thread, the writer is not disturbed. */
    public void clear() {
        cleared = written;
    }

    /** The newest intervals recorded since the last {@link #clear}, at most the capacity, oldest first, in nanoseconds. */
    public long[] copyIntervals() {
        long total = written;
        int count = (int) Math.min(total - cleared, intervals.length);
        long[] copy = new long[count];
        long first = total - count;
        for (int i = 0; i < count; i++) {
            copy[i] = intervals[(int) ((first + i) % intervals.length)];
        }
        return copy;
    }
}
