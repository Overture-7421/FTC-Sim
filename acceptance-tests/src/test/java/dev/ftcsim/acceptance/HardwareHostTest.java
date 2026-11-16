package dev.ftcsim.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.physics.DcMotor;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Robot code driving simulated hardware through the real SDK, the real hub protocol and the hub emulation, with
 * mechanism plants on the hub's ports and every instant decided by the simulation kernel.
 */
@Timeout(120)
class HardwareHostTest {
    private static final long MS = 1_000_000L;
    private static final double BATTERY_VOLTS = 12.6;
    private static final double LOAD_INERTIA = 4.0e-4;
    private static final double FRICTION = 2.0e-4;

    private static TestRobot.Builder flywheelRobot() {
        return TestRobot.builder().motor("flywheel", 2, DcMotor.goBilda435(), LOAD_INERTIA, FRICTION);
    }

    private static void advanceInSlices(VirtualSession session, long total, long slice) {
        long end = session.now() + total;
        while (session.now() < end) {
            session.kernel.runUntil(Math.min(end, session.now() + slice));
        }
    }

    /** What a one second run of 'Motor Drive' looks like from the robot program and from the plant. */
    private record MotorRun(Map<String, List<Double>> series, long plantCounts, double plantSpeed, List<String> problems) {}

    private static MotorRun motorRun(long slice) throws Exception {
        try (VirtualSession session = new VirtualSession(flywheelRobot())) {
            session.init("Motor Drive");
            session.start();
            advanceInSlices(session, 1_000 * MS, slice);
            Map<String, List<Double>> series = session.world.run("dev.ftcsim.scenarios.ReadSeries");
            return new MotorRun(
                    series,
                    session.robot.plant(0).encoderCounts(),
                    session.robot.plant(0).speedRadPerSec(),
                    session.kernel.diagnostics().problems());
        }
    }

    @Test
    void aMotorSetToHalfPowerReachesItsPlantAsHalfTheBatteryVoltageAndTheEncoderReportsWhatThePlantDid() throws Exception {
        MotorRun run = motorRun(Long.MAX_VALUE / 4);

        DcMotor motor = DcMotor.goBilda435();
        double damping = motor.stallTorqueNm() / motor.freeSpeedRadPerSec();
        double expectedSpeed = motor.stallTorqueNm() * (0.5 * BATTERY_VOLTS / motor.nominalVolts()) / (damping + FRICTION);
        assertEquals(expectedSpeed, run.plantSpeed(), expectedSpeed * 0.01);
        assertEquals(List.of(), run.problems());

        List<Double> positions = run.series().get("position");
        List<Double> velocities = run.series().get("velocity");
        assertTrue(positions.size() > 100, "the OpMode looped " + positions.size() + " times in a second");
        assertTrue(positions.size() < 1_000, "reading hardware must cost time: " + positions.size() + " loops in a second");
        for (int i = 1; i < positions.size(); i++) {
            assertTrue(positions.get(i) >= positions.get(i - 1), "the encoder went backwards at loop " + i);
        }
        double lastPosition = positions.get(positions.size() - 1);
        double ticksPerSecond = run.plantSpeed() / (2 * Math.PI) * motor.encoderTicksPerRevolution();
        assertTrue(run.plantCounts() >= lastPosition, run.plantCounts() + " < " + lastPosition);
        assertTrue(run.plantCounts() - lastPosition < ticksPerSecond * 0.02, run.plantCounts() + " vs " + lastPosition);
        assertEquals(ticksPerSecond, velocities.get(velocities.size() - 1), ticksPerSecond * 0.02);
        assertEquals(0.5, run.series().get("power").get(0));
    }

    @Test
    void theSlicingOfSimulatedTimeDoesNotChangeWhatTheProgramReads() throws Exception {
        MotorRun first = motorRun(Long.MAX_VALUE / 4);

        for (long slice : new long[] {1 * MS, 7 * MS, 33 * MS}) {
            assertEquals(first, motorRun(slice), "slice of " + slice + " ns");
        }
    }

    @Test
    void manualBulkCachingFreezesTheReadingsUntilTheCacheIsClearedAndOffLetsThemMove() throws Exception {
        for (String mode : new String[] {"Bulk Cache Manual", "Bulk Cache Off"}) {
            try (VirtualSession session = new VirtualSession(flywheelRobot())) {
                session.init(mode);
                session.start();
                advanceInSlices(session, 500 * MS, 5 * MS);
                Map<String, List<Double>> series = session.world.run("dev.ftcsim.scenarios.ReadSeries");

                List<Double> fresh = series.get("fresh");
                List<Double> later = series.get("later");
                assertTrue(fresh.size() > 5, mode + " looped " + fresh.size() + " times");
                for (int i = 1; i < later.size(); i++) {
                    assertTrue(fresh.get(i) > fresh.get(i - 1), mode + ": a cleared cache must show the motor moved, loop " + i);
                    if (mode.equals("Bulk Cache Manual")) {
                        assertEquals(fresh.get(i), later.get(i), mode + ": the cached read must repeat, loop " + i);
                    } else {
                        assertTrue(later.get(i) > fresh.get(i), mode + ": an uncached read must see 20 ms of motion, loop " + i);
                    }
                }
            }
        }
    }

    @Test
    void aServoSetToAQuarterPutsAPulseOfThatWidthOnThePortOfTheHub() throws Exception {
        try (VirtualSession session = new VirtualSession()) {
            session.init("Servo Move");
            session.start();
            advanceInSlices(session, 100 * MS, 10 * MS);

            int port = session.robot.wiring.servo("hood").port();
            assertTrue(session.robot.controlHub.servoEnabled(port));
            assertEquals(600 + 0.25 * (2400 - 600), session.robot.controlHub.servoPulseWidthMicros(port), 1.0);
            assertEquals(0.25, session.world.<Map<String, List<Double>>>run("dev.ftcsim.scenarios.ReadSeries").get("position").get(0));
            assertEquals(List.of(), session.robot.controlHub.unsupported());
        }
    }
}
