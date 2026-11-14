package dev.ftcsim.core.kernel;

/**
 * The part of the simulation that advances in fixed steps: physics, sensor sampling, hub updates. The kernel
 * decides when time moves; the world only does the work for the steps that fall inside the move.
 */
public interface WorldStepper {

    /** The time of the next fixed step, strictly later than the time the world has been advanced to. */
    long nextStepNanos();

    /** Performs every fixed step due at or before the given absolute time. */
    void advanceTo(long nanos);
}
