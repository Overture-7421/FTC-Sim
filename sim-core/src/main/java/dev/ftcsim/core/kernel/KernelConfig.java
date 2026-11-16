package dev.ftcsim.core.kernel;

import java.util.function.Predicate;

/**
 * Tunable behaviour of the kernel.
 *
 * @param robotThread        identifies the thread that runs the robot program; the SDK adapter knows what its
 *                           OpMode thread is called, the kernel does not
 * @param pollCostNanos      simulated time charged for each clock read by the robot, so that a loop waiting on the
 *                           clock always makes progress
 * @param yieldCostNanos     simulated time charged for each yield by the robot
 * @param backEdgeCostNanos  simulated time charged each time the robot jumps backwards, so that a loop waiting for
 *                           something to change, without reading the clock, still lets time pass; small enough
 *                           that the computation the robot does is not mistaken for time spent
 * @param epochMillis        the wall-clock time, in milliseconds since 1970, that simulated time zero corresponds
 *                           to, so that the robot program sees the dates of the simulated calendar
 * @param robotLivenessMillis how long, in real time, the robot thread may run without reaching a synchronisation
 *                           point before the kernel asks it to end, and twice that before it gives up on it; a robot
 *                           thread that never reaches one, because it loops inside the JDK, would otherwise hang the
 *                           simulator
 */
public record KernelConfig(
        Predicate<Thread> robotThread,
        long pollCostNanos,
        long yieldCostNanos,
        long backEdgeCostNanos,
        long epochMillis,
        long robotLivenessMillis) {

    /** 2026-01-01T00:00:00Z, an arbitrary start of the simulated calendar. */
    public static final long DEFAULT_EPOCH_MILLIS = 1_767_225_600_000L;

    public static final long DEFAULT_ROBOT_LIVENESS_MILLIS = 10_000L;

    public KernelConfig {
        if (pollCostNanos <= 0 || yieldCostNanos <= 0 || backEdgeCostNanos <= 0) {
            throw new IllegalArgumentException("every clock read, yield and loop iteration must cost some simulated time");
        }
        if (robotLivenessMillis <= 0) {
            throw new IllegalArgumentException("the robot thread must be given some real time to reach a synchronisation point");
        }
    }

    /** A configuration with the default liveness limit. */
    public KernelConfig(
            Predicate<Thread> robotThread, long pollCostNanos, long yieldCostNanos, long backEdgeCostNanos, long epochMillis) {
        this(robotThread, pollCostNanos, yieldCostNanos, backEdgeCostNanos, epochMillis, DEFAULT_ROBOT_LIVENESS_MILLIS);
    }

    public static KernelConfig defaults(Predicate<Thread> robotThread) {
        return new KernelConfig(robotThread, 200L, 1_000L, 1L, DEFAULT_EPOCH_MILLIS);
    }

    public KernelConfig withBackEdgeCostNanos(long nanos) {
        return new KernelConfig(robotThread, pollCostNanos, yieldCostNanos, nanos, epochMillis, robotLivenessMillis);
    }

    public KernelConfig withEpochMillis(long millis) {
        return new KernelConfig(robotThread, pollCostNanos, yieldCostNanos, backEdgeCostNanos, millis, robotLivenessMillis);
    }

    public KernelConfig withRobotLivenessMillis(long millis) {
        return new KernelConfig(robotThread, pollCostNanos, yieldCostNanos, backEdgeCostNanos, epochMillis, millis);
    }
}
