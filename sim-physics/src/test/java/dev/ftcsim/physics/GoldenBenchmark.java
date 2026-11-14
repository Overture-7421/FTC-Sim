package dev.ftcsim.physics;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Timing report for the golden scenario on the single-threaded job system, printed as plain lines.
 * The first run in the JVM shows the cold start, where the JIT is still compiling the Java side;
 * the later runs show the steady state. Only {@code PhysicsWorld.step()} is timed; the scenario's
 * own script, which scans the snapshot for the intake, is reported separately.
 */
public final class GoldenBenchmark {

    private static final int WARM_RUNS = 3;
    private static final int COLD_SECOND_STEPS = PhysicsWorld.STEPS_PER_SECOND;
    private static final int CALLS = 300;
    private static final int SLOWEST_STEPS = 5;

    private GoldenBenchmark() {}

    public static void main(String[] args) throws InterruptedException {
        Path nativeDirectory = Path.of(System.getProperty("ftcsim.nativeDirectory"));
        System.out.println("BENCH java " + System.getProperty("java.vm.version") + " on " + System.getProperty("os.name")
                + ", " + Runtime.getRuntime().availableProcessors() + " logical processors");
        System.out.printf("BENCH machine CPU load before the run: %.0f%%%n", 100 * machineLoadOverOneSecond());
        for (int run = 0; run <= WARM_RUNS; run++) {
            long start = System.nanoTime();
            try (PhysicsWorld world = PhysicsWorld.create(nativeDirectory, TestNatives.RELEASE_SHA256, 0)) {
                double createMillis = (System.nanoTime() - start) / 1e6;
                if (run == 0) {
                    System.out.println("BENCH " + world.engineInfo());
                }
                report(run == 0 ? "cold JVM run" : "warm run " + run, run == 0, world, createMillis);
            }
        }
        for (boolean withRobot : new boolean[] {false, true}) {
            try (PhysicsWorld world = PhysicsWorld.create(nativeDirectory, TestNatives.RELEASE_SHA256, 0)) {
                floorOnly(withRobot, world);
            }
        }
        System.out.printf("BENCH machine CPU load after the run: %.0f%%%n", 100 * machineLoadOverOneSecond());
    }

    // A bare floor, with and without a robot that keeps driving back and forth: what the engine call
    // costs without pieces, and what the chassis adds with its four probe rays and four forces per step.
    private static void floorOnly(boolean withRobot, PhysicsWorld world) {
        world.addStaticBox(new Vector3(0, 0, -0.5), new Vector3(5, 5, 0.5));
        MecanumChassis robot = withRobot
                ? world.addMecanumChassis(1, 1, MecanumConfig.generic18Inch(), new Vector3(0, 0, 0), 0)
                : null;
        long[] stepNanos = new long[GoldenScenario.STEPS];
        for (int i = 0; i < stepNanos.length; i++) {
            if (robot != null && i % PhysicsWorld.STEPS_PER_SECOND == 0) {
                double command = (i / PhysicsWorld.STEPS_PER_SECOND) % 2 == 0 ? 1.0 : -1.0;
                for (WheelPosition wheel : WheelPosition.values()) {
                    robot.setWheelCommand(wheel, command);
                }
            }
            long start = System.nanoTime();
            world.step();
            stepNanos[i] = System.nanoTime() - start;
        }
        System.out.println("BENCH == " + (withRobot ? "bare floor with one driving robot" : "bare floor, nothing else")
                + " (" + stepNanos.length + " steps)");
        describe("all steps", stepNanos, 0, stepNanos.length);
    }

    private static void report(String label, boolean coldStart, PhysicsWorld world, double createMillis) {
        long buildStart = System.nanoTime();
        GoldenScenario scenario = new GoldenScenario(world);
        double buildMillis = (System.nanoTime() - buildStart) / 1e6;
        long[] stepNanos = new long[GoldenScenario.STEPS];
        long scriptNanos = 0;
        long snapshotNanos = 0;
        long hashNanos = 0;
        int hashes = 0;
        for (int i = 0; i < GoldenScenario.STEPS; i++) {
            long scriptStart = System.nanoTime();
            scenario.script();
            long stepStart = System.nanoTime();
            scriptNanos += stepStart - scriptStart;
            world.step();
            stepNanos[i] = System.nanoTime() - stepStart;
            if ((i + 1) % GoldenScenario.HASH_INTERVAL == 0) {
                long snapshotStart = System.nanoTime();
                world.snapshot();
                long hashStart = System.nanoTime();
                world.stateHash();
                long end = System.nanoTime();
                snapshotNanos += hashStart - snapshotStart;
                hashNanos += end - hashStart;
                hashes++;
            }
        }
        System.out.printf("BENCH == %s: native load and world creation %.1f ms, scenario build (about 160 bodies) %.1f ms%n",
                label, createMillis, buildMillis);
        System.out.println("BENCH " + GoldenScenario.STEPS + " steps, " + scenario.piecesTakenIn() + " pieces taken in, "
                + scenario.piecesShot() + " shot");
        describe("all steps", stepNanos, 0, stepNanos.length);
        describe("first second", stepNanos, 0, COLD_SECOND_STEPS);
        describe("after the first second", stepNanos, COLD_SECOND_STEPS, stepNanos.length);
        System.out.println("BENCH slowest steps (index: ms) " + slowest(stepNanos));
        System.out.printf("BENCH script ms/step mean=%.4f (intake and shooter scans, not engine work)%n",
                scriptNanos / 1e6 / GoldenScenario.STEPS);
        System.out.printf("BENCH snapshot() at the %d hash points: mean %.3f ms; stateHash(): mean %.3f ms%n",
                hashes, snapshotNanos / 1e6 / hashes, hashNanos / 1e6 / hashes);
        if (!coldStart) {
            repeatedCalls(world);
        }
    }

    private static void describe(String what, long[] nanos, int from, int to) {
        long[] sorted = Arrays.copyOfRange(nanos, from, to);
        Arrays.sort(sorted);
        System.out.printf("BENCH step %-22s ms: p50=%.4f p95=%.4f p99=%.4f max=%.4f mean=%.4f%n", what,
                at(sorted, 0.50), at(sorted, 0.95), at(sorted, 0.99), sorted[sorted.length - 1] / 1e6,
                Arrays.stream(sorted).average().orElse(0) / 1e6);
    }

    private static double at(long[] sorted, double quantile) {
        return sorted[Math.min(sorted.length - 1, (int) (quantile * sorted.length))] / 1e6;
    }

    private static String slowest(long[] nanos) {
        return IntStream.range(0, nanos.length).boxed()
                .sorted((a, b) -> Long.compare(nanos[b], nanos[a]))
                .limit(SLOWEST_STEPS)
                .map(i -> String.format("%d: %.2f", i, nanos[i] / 1e6))
                .collect(Collectors.joining(", "));
    }

    // The end state of the scenario, called back to back the way a viewer would poll it.
    private static void repeatedCalls(PhysicsWorld world) {
        long[] snapshots = new long[CALLS];
        long[] hashes = new long[CALLS];
        for (int i = 0; i < CALLS; i++) {
            long start = System.nanoTime();
            world.snapshot();
            long middle = System.nanoTime();
            world.stateHash();
            long end = System.nanoTime();
            snapshots[i] = middle - start;
            hashes[i] = end - middle;
        }
        Arrays.sort(snapshots);
        Arrays.sort(hashes);
        System.out.printf("BENCH %d back-to-back calls on the final state: snapshot() p50=%.3f max=%.3f ms; stateHash() p50=%.3f max=%.3f ms%n",
                CALLS, at(snapshots, 0.5), snapshots[CALLS - 1] / 1e6, at(hashes, 0.5), hashes[CALLS - 1] / 1e6);
    }

    private static double machineLoadOverOneSecond() throws InterruptedException {
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        os.getCpuLoad();
        Thread.sleep(1000);
        return os.getCpuLoad();
    }
}
