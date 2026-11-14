package dev.ftcsim.simulator;

/**
 * The relation between wall-clock time and simulated time while a run is shown in real time: how far simulated
 * time should have got by now at the chosen speed, and what to do when the machine cannot keep up. Pure
 * arithmetic on the numbers it is given, so it can be tested without waiting for anything.
 *
 * <p>Simulated time is never skipped to catch up: when the simulation falls too far behind it is the relation
 * that is re-anchored, and the run goes on slower than real time for as long as the machine needs.
 */
final class Pace {

    private final long maxLagNanos;
    private double speed;
    private long wallAnchor;
    private long simAnchor;

    /**
     * @param speed       simulated seconds per wall-clock second
     * @param maxLagNanos how far behind real time the simulation may fall before it gives up catching up
     */
    Pace(double speed, long maxLagNanos) {
        if (!(speed > 0.0) || maxLagNanos <= 0) {
            throw new IllegalArgumentException("speed and the lag limit must be positive: " + speed + ", " + maxLagNanos);
        }
        this.speed = speed;
        this.maxLagNanos = maxLagNanos;
    }

    /** From now on, {@code simNanos} corresponds to {@code wallNanos}. */
    void anchor(long wallNanos, long simNanos) {
        wallAnchor = wallNanos;
        simAnchor = simNanos;
    }

    void setSpeed(double newSpeed, long wallNanos, long simNanos) {
        if (!(newSpeed > 0.0)) {
            throw new IllegalArgumentException("speed must be positive: " + newSpeed);
        }
        speed = newSpeed;
        anchor(wallNanos, simNanos);
    }

    double speed() {
        return speed;
    }

    /** The simulated time that real time has reached by {@code wallNanos}. */
    long simAt(long wallNanos) {
        return simAnchor + Math.round((wallNanos - wallAnchor) * speed);
    }

    /** The wall-clock time at which simulated time reaches {@code simNanos}. */
    long wallAt(long simNanos) {
        return wallAnchor + Math.round((simNanos - simAnchor) / speed);
    }

    /**
     * How far simulated time {@code simNanos} is behind where real time says it should be at {@code wallNanos};
     * zero or negative when it is ahead. A lag beyond the limit re-anchors the relation to the present, which is
     * how a slow machine ends up running slower than real time instead of falling further behind forever.
     */
    long lag(long wallNanos, long simNanos) {
        long behind = simAt(wallNanos) - simNanos;
        if (behind > maxLagNanos) {
            anchor(wallNanos, simNanos);
            return 0;
        }
        return behind;
    }
}
