package dev.ftcsim.core.kernel;

import dev.ftcsim.contracts.hooks.Hooks;
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
 *                           to; fixed so that every run sees the same dates
 * @param seed               what the robot program's random numbers derive from; the same seed gives it the same
 *                           numbers
 * @param robotLivenessMillis how long, in real time, the robot thread may run without reaching a synchronisation
 *                           point before the kernel asks it to end, and twice that before it gives up on it; a robot
 *                           thread that never reaches one, because it loops inside the JDK, would otherwise hang the
 *                           simulator
 * @param worldFrame         tells whether a stack frame belongs to the robot world, which is how the kernel knows a
 *                           thread other than the robot thread is running robot code; by default a frame of the class
 *                           loader called {@link Hooks#WORLD_LOADER_NAME}
 * @param worldQuietMillis   how long, in real time, the kernel waits for such a thread to come to rest before it
 *                           records a problem and stops waiting for it
 */
public record KernelConfig(
        Predicate<Thread> robotThread,
        long pollCostNanos,
        long yieldCostNanos,
        long backEdgeCostNanos,
        long epochMillis,
        long seed,
        long robotLivenessMillis,
        Predicate<StackTraceElement> worldFrame,
        long worldQuietMillis) {

    /** 2026-01-01T00:00:00Z, an arbitrary but fixed start of the simulated calendar. */
    public static final long DEFAULT_EPOCH_MILLIS = 1_767_225_600_000L;

    public static final long DEFAULT_SEED = 0x5EED_F7C5_1A11L;

    public static final long DEFAULT_ROBOT_LIVENESS_MILLIS = 10_000L;

    public static final long DEFAULT_WORLD_QUIET_MILLIS = 20_000L;

    /** A frame of the robot world's class loader. */
    public static final Predicate<StackTraceElement> ROBOT_WORLD_FRAME =
            frame -> Hooks.WORLD_LOADER_NAME.equals(frame.getClassLoaderName());

    public KernelConfig {
        if (pollCostNanos <= 0 || yieldCostNanos <= 0 || backEdgeCostNanos <= 0) {
            throw new IllegalArgumentException("every clock read, yield and loop iteration must cost some simulated time");
        }
        if (robotLivenessMillis <= 0) {
            throw new IllegalArgumentException("the robot thread must be given some real time to reach a synchronisation point");
        }
        if (worldFrame == null || worldQuietMillis <= 0) {
            throw new IllegalArgumentException("the kernel needs a way to tell robot code and some real time to wait for it");
        }
    }

    /** A configuration that waits for threads of the robot world for the default time. */
    public KernelConfig(
            Predicate<Thread> robotThread,
            long pollCostNanos,
            long yieldCostNanos,
            long backEdgeCostNanos,
            long epochMillis,
            long seed,
            long robotLivenessMillis) {
        this(
                robotThread, pollCostNanos, yieldCostNanos, backEdgeCostNanos, epochMillis, seed, robotLivenessMillis,
                ROBOT_WORLD_FRAME, DEFAULT_WORLD_QUIET_MILLIS);
    }

    /** A configuration with the default seed and liveness limit. */
    public KernelConfig(
            Predicate<Thread> robotThread, long pollCostNanos, long yieldCostNanos, long backEdgeCostNanos, long epochMillis) {
        this(robotThread, pollCostNanos, yieldCostNanos, backEdgeCostNanos, epochMillis, DEFAULT_SEED, DEFAULT_ROBOT_LIVENESS_MILLIS);
    }

    public static KernelConfig defaults(Predicate<Thread> robotThread) {
        return new KernelConfig(robotThread, 200L, 1_000L, 1L, DEFAULT_EPOCH_MILLIS);
    }

    public KernelConfig withBackEdgeCostNanos(long nanos) {
        return new KernelConfig(
                robotThread, pollCostNanos, yieldCostNanos, nanos, epochMillis, seed, robotLivenessMillis, worldFrame, worldQuietMillis);
    }

    public KernelConfig withSeed(long newSeed) {
        return new KernelConfig(
                robotThread, pollCostNanos, yieldCostNanos, backEdgeCostNanos, epochMillis, newSeed, robotLivenessMillis,
                worldFrame, worldQuietMillis);
    }

    public KernelConfig withEpochMillis(long millis) {
        return new KernelConfig(
                robotThread, pollCostNanos, yieldCostNanos, backEdgeCostNanos, millis, seed, robotLivenessMillis,
                worldFrame, worldQuietMillis);
    }

    public KernelConfig withRobotLivenessMillis(long millis) {
        return new KernelConfig(
                robotThread, pollCostNanos, yieldCostNanos, backEdgeCostNanos, epochMillis, seed, millis, worldFrame, worldQuietMillis);
    }

    /** What counts as robot code for the threads the kernel waits for, and for how long it waits for one. */
    public KernelConfig withWorldThreads(Predicate<StackTraceElement> frame, long quietMillis) {
        return new KernelConfig(
                robotThread, pollCostNanos, yieldCostNanos, backEdgeCostNanos, epochMillis, seed, robotLivenessMillis,
                frame, quietMillis);
    }
}
