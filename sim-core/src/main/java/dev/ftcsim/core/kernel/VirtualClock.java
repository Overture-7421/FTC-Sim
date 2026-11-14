package dev.ftcsim.core.kernel;

/**
 * The simulated time axis, in nanoseconds since the session began. Only the simulator thread moves it, and only
 * forward; everything else, including the robot program, reads it.
 */
public final class VirtualClock {
    private volatile long nanos;

    public long now() {
        return nanos;
    }

    void advanceTo(long target) {
        if (target < nanos) {
            throw new IllegalArgumentException("simulated time cannot run backwards: " + nanos + " -> " + target);
        }
        nanos = target;
    }
}
