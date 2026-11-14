package dev.ftcsim.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.snapshot.BodyState;
import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * The determinism gate: the golden scenario must reproduce the committed state hashes bit for bit
 * in this JVM and in fresh JVMs that run Java code in different JIT modes or load the debug build of
 * the native library. Job systems with worker threads are only reported, never failed.
 */
class GoldenDeterminismTest {

    private static final long REQUIRED_TIMEOUT_SECONDS = 300;
    private static final long INFORMATIONAL_TIMEOUT_SECONDS = 90;

    private static GoldenChildJvm.Variant variant(String name, boolean debugNatives, int workerThreads, String... jvmArguments) {
        return new GoldenChildJvm.Variant(name, List.of(jvmArguments), debugNatives, workerThreads);
    }

    @Test
    void thisJvmReproducesTheGoldenHashesAfterEveryReset() {
        // A reset is a brand-new world in the same JVM; nothing may leak from the previous one.
        for (int run = 1; run <= 2; run++) {
            try (PhysicsWorld world = TestNatives.newWorld()) {
                assertNull(GoldenHashes.firstDifference(GoldenHashes.lines(GoldenScenario.run(world))), "run " + run);
            }
        }
    }

    @TestFactory
    Stream<DynamicTest> freshJvmsReproduceTheGoldenHashes() {
        return Stream.of(
                        variant("default JIT", false, 0),
                        variant("-Xint", false, 0, "-Xint"),
                        variant("-XX:TieredStopAtLevel=1", false, 0, "-XX:TieredStopAtLevel=1"),
                        variant("DebugSp natives", true, 0))
                .map(variant -> DynamicTest.dynamicTest(variant.name(), () ->
                        assertNull(
                                GoldenHashes.firstDifference(GoldenChildJvm.run(variant, REQUIRED_TIMEOUT_SECONDS)),
                                variant.name())));
    }

    @Test
    void jobSystemsWithWorkerThreadsAreReportedNotEnforced() throws InterruptedException {
        for (int workers : new int[] {1, 2, 4}) {
            String outcome;
            try {
                String difference = GoldenHashes.firstDifference(
                        GoldenChildJvm.run(variant(workers + " workers", false, workers), INFORMATIONAL_TIMEOUT_SECONDS));
                outcome = difference == null ? "identical to the golden hashes" : "DIFFERS: " + difference;
            } catch (IOException | AssertionError | RuntimeException e) {
                outcome = "could not be run: " + e.getMessage();
            }
            System.out.println("DETERMINISM (informational) job system with " + workers + " worker threads: " + outcome);
        }
    }

    @Test
    void theScenarioExercisesEverythingItClaimsToCover() {
        try (PhysicsWorld world = TestNatives.newWorld()) {
            GoldenScenario scenario = new GoldenScenario(world);
            Coverage coverage = new Coverage(scenario);
            for (int step = 1; step <= GoldenScenario.STEPS; step++) {
                scenario.step();
                if (step % Coverage.SAMPLE_INTERVAL == 0) {
                    coverage.sample(world.snapshot().bodies());
                }
            }
            System.out.printf(
                    "SCENARIO COVERAGE pitch %.1f..%.1f deg, %d pushing samples, strafed=%s, turned=%s, %d taken in, %d shot, gate %.0f deg%n",
                    coverage.minPitchDegrees, coverage.maxPitchDegrees, coverage.pushingSamples, coverage.strafed,
                    coverage.turned, scenario.piecesTakenIn(), scenario.piecesShot(), Math.toDegrees(coverage.widestGateAngle));
            assertTrue(coverage.maxPitchDegrees > 10, "the robot never climbed the ramp: " + coverage.maxPitchDegrees);
            assertTrue(coverage.minPitchDegrees < -20, "the robot never dropped off the ramp: " + coverage.minPitchDegrees);
            assertTrue(coverage.pushingSamples > 40, "the robot never pushed against a wall: " + coverage.pushingSamples);
            assertTrue(coverage.strafed, "the robot never strafed");
            assertTrue(coverage.turned, "the robot never turned on the spot");
            assertTrue(coverage.lostWheelContact, "no wheel ever left the ground");
            assertTrue(scenario.piecesTakenIn() >= 20, "pieces taken in: " + scenario.piecesTakenIn());
            assertTrue(scenario.piecesShot() >= 20, "pieces shot: " + scenario.piecesShot());
            assertTrue(Math.toDegrees(coverage.widestGateAngle) > 20, "the gate never opened");
            assertTrue(coverage.hiveWasPositive && coverage.hiveReturnedNegative, "the hive did not tip both ways");
            assertEquals(0, coverage.piecesOutside, "pieces left the closed arena");
        }
    }

    /** Samples the running scenario and records which of its intended events actually happened. */
    private static final class Coverage {

        static final int SAMPLE_INTERVAL = 6;
        private static final double SAMPLE_SECONDS = SAMPLE_INTERVAL / (double) PhysicsWorld.STEPS_PER_SECOND;

        private final GoldenScenario scenario;
        private BodyState previous;
        double maxPitchDegrees = -90;
        double minPitchDegrees = 90;
        int pushingSamples;
        boolean strafed;
        boolean turned;
        boolean lostWheelContact;
        double widestGateAngle;
        boolean hiveWasPositive;
        boolean hiveReturnedNegative;
        int piecesOutside;

        Coverage(GoldenScenario scenario) {
            this.scenario = scenario;
        }

        void sample(List<BodyState> bodies) {
            BodyState robot = bodies.stream().filter(b -> b.entityId() == GoldenScenario.ROBOT_ID).findFirst().orElseThrow();
            double pitch = TestWorlds.pitchDegrees(robot);
            maxPitchDegrees = Math.max(maxPitchDegrees, pitch);
            minPitchDegrees = Math.min(minPitchDegrees, pitch);
            if (previous != null) {
                double dx = (robot.x() - previous.x()) / SAMPLE_SECONDS;
                double dy = (robot.y() - previous.y()) / SAMPLE_SECONDS;
                double yaw = TestWorlds.yaw(robot);
                double turnRate = Math.IEEEremainder(yaw - TestWorlds.yaw(previous), 2 * Math.PI) / SAMPLE_SECONDS;
                double left = -dx * Math.sin(yaw) + dy * Math.cos(yaw);
                double forward = dx * Math.cos(yaw) + dy * Math.sin(yaw);
                double speed = Math.hypot(dx, dy);
                strafed |= Math.abs(left) > 1.5 && Math.abs(forward) < 0.3;
                turned |= Math.abs(turnRate) > 4.0 && speed < 0.3;
                pushingSamples += speed < 0.05 && slowestWheel() > 10.0 ? 1 : 0;
            }
            previous = robot;
            lostWheelContact |= anyWheelUnloaded() && pitch < -20;
            widestGateAngle = Math.max(widestGateAngle, scenario.gate().angleRadians());
            hiveWasPositive |= scenario.hive().state() == Hive.State.AT_POSITIVE;
            hiveReturnedNegative |= hiveWasPositive && scenario.hive().state() == Hive.State.AT_NEGATIVE;
            for (BodyState body : bodies) {
                boolean inside = Math.abs(body.x()) < GoldenScenario.FIELD_HALF && Math.abs(body.y()) < GoldenScenario.FIELD_HALF
                        && body.z() > 0.0 && body.z() < GoldenScenario.CEILING;
                if (body.entityId() >= GoldenScenario.FIRST_BALL_ID && !inside) {
                    piecesOutside++;
                }
            }
        }

        private double slowestWheel() {
            double slowest = Double.MAX_VALUE;
            for (WheelPosition wheel : WheelPosition.values()) {
                slowest = Math.min(slowest, Math.abs(scenario.robot().wheelSpeedRadPerSec(wheel)));
            }
            return slowest;
        }

        private boolean anyWheelUnloaded() {
            for (WheelPosition wheel : WheelPosition.values()) {
                if (scenario.robot().wheelNormalForceN(wheel) == 0.0) {
                    return true;
                }
            }
            return false;
        }
    }
}
