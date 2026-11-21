package dev.ftcsim.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.robot.GamepadState;
import dev.ftcsim.contracts.world.BodyPose;
import dev.ftcsim.field.EntityIds;
import dev.ftcsim.simulator.robot.RobotDesign;
import dev.ftcsim.simulator.robot.RobotMapping;
import dev.ftcsim.simulator.robot.RobotProfiles;
import dev.ftcsim.simulator.robot.RobotSpec;
import dev.ftcsim.simulator.SimulationSession;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The IMU of the Control Hub on the simulated robot as a whole: a program that knows nothing of the simulator turns the
 * real wheels through the real SDK motors, the physics world moves and tips the chassis, and the SDK's own IMU driver
 * reads from the chip what the chassis really did. The robot mapping says nothing about the IMU except how its hub is
 * mounted.
 */
@Timeout(300)
class ImuSessionTest {
    private static final long SECOND = 1_000_000_000L;
    private static final long MS = 1_000_000L;

    private static RobotSpec hornet;

    @BeforeAll
    static void loadProfile() {
        hornet = RobotDesign.decode().spec();
    }

    /** The number a telemetry line shows as "caption : value". */
    private static double reading(SimulationSession session, String caption) {
        return session.telemetry().lines().stream()
                .filter(line -> line.startsWith(caption + " : "))
                .mapToDouble(line -> Double.parseDouble(line.substring(caption.length() + 3)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no telemetry line '" + caption + "' in " + session.telemetry().lines()));
    }

    private static String text(SimulationSession session, String caption) {
        return session.telemetry().lines().stream()
                .filter(line -> line.startsWith(caption + " : "))
                .map(line -> line.substring(caption.length() + 3))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no telemetry line '" + caption + "' in " + session.telemetry().lines()));
    }

    /** The angle between two headings in degrees, in [-180, 180). */
    private static double difference(double a, double b) {
        return Math.IEEEremainder(a - b, 360.0);
    }

    /**
     * The SDK logs it when its own poll of the chip for a quaternion gives up, which its constructor and resetYaw do
     * inside a synchronized method, where the physics world cannot step: the chip must answer all the same.
     */
    private static void assertNoPollGaveUp(TestSessions.Handle handle) {
        assertFalse(handle.log.toString().contains("Failed to retrieve valid quaternion"), handle.log.toString());
    }

    /** Initialises an OpMode, waits for the IMU to boot and be set up, presses Start and lets the first loops run. */
    private static void start(SimulationSession session, String opMode) {
        session.init(opMode);
        session.runFor(2 * SECOND);
        session.start();
        session.runFor(200 * MS);
    }

    private static void turnGently(SimulationSession session) {
        session.setGamepad(1, GamepadState.sticks(0f, 0f, 0.3f, 0f));
        session.runFor(SECOND * 4 / 5);
    }

    @Test
    void wheelsTurnTheChassisAndTheImuReadsTheTurnAndItsRateFromTheChipAsTheWorldMovedIt() throws IOException {
        try (TestSessions.Handle handle = TestSessions.open(hornet, 0, 0, 0)) {
            SimulationSession session = handle.session;
            start(session, "IMU Drive");

            turnGently(session);
            BodyPose turning = session.world().bodyPose(EntityIds.CHASSIS);
            double rate = Math.toDegrees(turning.wz());
            assertTrue(Math.abs(rate) > 20, "the robot should be turning: " + turning);
            // What the program shows is as of its latest loop, a few milliseconds behind a robot that is still turning.
            assertEquals(rate, reading(session, "imu rate deg/s"), 3.0 + Math.abs(rate) * 0.1, "the turn rate: " + turning);
            assertEquals(Math.signum(rate), Math.signum(reading(session, "imu rate deg/s")), "the direction of the turn");

            session.setGamepad(1, GamepadState.NEUTRAL);
            session.runFor(SECOND);
            BodyPose still = session.world().bodyPose(EntityIds.CHASSIS);
            assertTrue(Math.abs(Math.toDegrees(still.yaw())) > 10, "it has turned some way: " + still);
            assertEquals(0.0, difference(reading(session, "imu yaw deg"), Math.toDegrees(still.yaw())), 0.5, "the heading: " + still);
            assertEquals(0.0, reading(session, "imu pitch deg"), 0.5);
            assertEquals(0.0, reading(session, "imu roll deg"), 0.5);
            assertEquals(List.of(), session.problems(), handle.log.toString());
            assertNoPollGaveUp(handle);
        }
    }

    @Test
    void aRobotThatClimbsARampTipsAndTheImuShowsThePitchOfTheSlopeAndNoRoll() throws IOException {
        try (TestSessions.Handle handle = TestSessions.open(hornet, new RampField(), 0, 0, 0)) {
            SimulationSession session = handle.session;
            start(session, "IMU Drive");
            assertEquals(0.0, reading(session, "imu pitch deg"), 0.3, "level on the floor");

            session.setGamepad(1, GamepadState.sticks(0f, -0.5f, 0f, 0f));
            session.runFor(SECOND);
            double steepest = 0;
            for (int i = 0; i < 8; i++) {
                session.runFor(100 * MS);
                BodyPose truth = session.world().bodyPose(EntityIds.CHASSIS);
                double truePitchUp = -Math.toDegrees(truth.pitch());
                steepest = Math.max(steepest, truePitchUp);
                assertEquals(truePitchUp, reading(session, "imu pitch deg"), 1.5, "pitch at " + truth);
                assertEquals(Math.toDegrees(truth.roll()), reading(session, "imu roll deg"), 1.5, "roll at " + truth);
            }
            session.setGamepad(1, GamepadState.NEUTRAL);

            assertEquals(RampField.ANGLE_DEGREES, steepest, 3.0, "the chassis lies on the ramp, nose up");
            assertEquals(List.of(), session.problems(), handle.log.toString());
        }
    }

    @Test
    void aHubMountedOnItsEdgeGivesTheRightAnglesToCodeThatSaysSoAndNoSecondNameIsFoundForIt() throws IOException {
        RobotMapping base = RobotProfiles.hornet();
        RobotSpec onEdge = RobotDesign.decode().spec(new RobotMapping(
                base.name(), base.motors(), base.massKg(), base.wheelDiameterMm(), base.driveMotor(), base.turretRatio(), "LEFT", "UP",
                base.camera(), base.turretParts(), base.driveRatio(), base.colorSensors()));

        try (TestSessions.Handle handle = TestSessions.open(onEdge, 0, 0, 0)) {
            SimulationSession session = handle.session;
            start(session, "IMU Drive Hub On Edge");
            turnGently(session);
            session.setGamepad(1, GamepadState.NEUTRAL);
            session.runFor(SECOND);

            BodyPose still = session.world().bodyPose(EntityIds.CHASSIS);
            assertTrue(Math.abs(Math.toDegrees(still.yaw())) > 10, "it has turned some way: " + still);
            assertEquals(0.0, difference(reading(session, "imu yaw deg"), Math.toDegrees(still.yaw())), 0.6, "the heading: " + still);
            assertEquals(0.0, reading(session, "imu pitch deg"), 0.6);
            assertEquals(0.0, reading(session, "imu roll deg"), 0.6);
            assertEquals(List.of(), session.problems(), handle.log.toString());
            assertNoPollGaveUp(handle);
        }
        try (TestSessions.Handle handle = TestSessions.open(onEdge, 0, 0, 0)) {
            handle.session.init("IMU Names");
            handle.session.runFor(2 * SECOND);

            assertTrue(text(handle.session, "second name").contains("\"imu\" already is it"), handle.session.telemetry().lines().toString());
        }
    }

    @Test
    void aHubStillHasOneImuUnderAnyNameAndNoNameIsReportedAsUnbound() throws IOException {
        try (TestSessions.Handle handle = TestSessions.open(hornet, 0, 0, 0)) {
            handle.session.init("IMU Names");
            handle.session.runFor(2 * SECOND);

            String refusal = text(handle.session, "second name");
            assertTrue(refusal.startsWith("Unable to find a hardware device with name \"gyro\" and type IMU"), refusal);
            assertTrue(refusal.contains("\"imu\" already is it"), refusal);
            assertTrue(
                    handle.session.problems().stream().anyMatch(problem -> problem.contains("'gyro'") && problem.contains("second name")),
                    "a second name for the one IMU is a problem of the session: " + handle.session.problems());
            assertTrue(handle.session.unmapped().isEmpty(), "and the IMU is not an unmapped device");
        }
    }

    @Test
    void aProgramThatSetsTheImuUpLeavesTheRobotWhereOneThatNeverAsksForItDrivesIt() throws IOException {
        BodyPose[] poses = new BodyPose[2];
        String[] opModes = {"Drive Without IMU", "IMU Set Up Only"};
        for (int i = 0; i < opModes.length; i++) {
            try (TestSessions.Handle handle = TestSessions.open(hornet, 0, 0, 0)) {
                SimulationSession session = handle.session;
                session.init(opModes[i]);
                session.runFor(2 * SECOND);
                session.start();
                session.setGamepad(1, GamepadState.sticks(0.3f, -0.4f, 0.15f, 0f));
                session.runFor(3 * SECOND);
                poses[i] = session.world().bodyPose(EntityIds.CHASSIS);
                assertTrue(Math.abs(poses[i].x()) > 0.1, "the robot drove");
                assertEquals(List.of(), session.problems(), handle.log.toString());
            }
        }

        assertEquals(poses[0].x(), poses[1].x(), 0.01, "the pose after the same drive, without and with a chip that is in use");
        assertEquals(poses[0].y(), poses[1].y(), 0.01);
        assertEquals(poses[0].yaw(), poses[1].yaw(), 0.01);
    }

    @Test
    void theSlicingOfSimulatedTimeChangesNeitherWhereTheRobotEndsUpNorWhatTheImuReads() throws IOException {
        BodyPose firstPose = null;
        double firstYaw = 0;
        for (long slice : new long[] {Long.MAX_VALUE / 4, 7 * MS, 250 * MS}) {
            try (TestSessions.Handle handle = TestSessions.open(hornet, 0, 0, 0)) {
                SimulationSession session = handle.session;
                session.init("IMU Drive");
                session.runFor(2 * SECOND);
                session.start();
                session.setGamepad(1, GamepadState.sticks(0.3f, -0.4f, 0.15f, 0f));
                long end = session.now() + SECOND;
                while (session.now() < end) {
                    session.runUntil(Math.min(end, session.now() + slice));
                }
                BodyPose pose = session.world().bodyPose(EntityIds.CHASSIS);
                double yaw = reading(session, "imu yaw deg");
                if (firstPose == null) {
                    firstPose = pose;
                    firstYaw = yaw;
                }
                assertEquals(firstPose.x(), pose.x(), 0.01, "the robot with slices of " + slice / MS + " ms");
                assertEquals(firstPose.y(), pose.y(), 0.01, "the robot with slices of " + slice / MS + " ms");
                assertEquals(firstYaw, yaw, 0.5, "the IMU's heading with slices of " + slice / MS + " ms");
                assertEquals(List.of(), session.problems());
            }
        }
    }
}
