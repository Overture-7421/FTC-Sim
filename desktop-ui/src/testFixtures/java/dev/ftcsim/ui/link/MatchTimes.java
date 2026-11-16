package dev.ftcsim.ui.link;

/**
 * How long the three periods of a timed match last in the fake simulator, in simulated nanoseconds. The real
 * simulator keeps its own lengths; nothing in the window knows these numbers, it only shows what the status reports.
 */
public record MatchTimes(long autonomousNanos, long transitionNanos, long teleOpNanos) {
    private static final long SECOND = 1_000_000_000L;

    /** The lengths of an FTC match. */
    public static final MatchTimes DEFAULT = new MatchTimes(30 * SECOND, 8 * SECOND, 120 * SECOND);

    public MatchTimes {
        if (autonomousNanos <= 0 || transitionNanos <= 0 || teleOpNanos <= 0) {
            throw new IllegalArgumentException("every period needs a length: " + autonomousNanos + ", " + transitionNanos + ", " + teleOpNanos);
        }
    }

    /** Periods of the given lengths in milliseconds, for tests that cannot wait half a minute. */
    public static MatchTimes ofMillis(long autonomous, long transition, long teleOp) {
        return new MatchTimes(autonomous * 1_000_000L, transition * 1_000_000L, teleOp * 1_000_000L);
    }
}
