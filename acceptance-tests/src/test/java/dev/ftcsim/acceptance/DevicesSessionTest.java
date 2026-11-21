package dev.ftcsim.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.hardware.UnmappedDevice;
import dev.ftcsim.contracts.robot.GamepadState;
import dev.ftcsim.contracts.world.BodyPose;
import dev.ftcsim.field.EntityIds;
import dev.ftcsim.simulator.robot.RobotDesign;
import dev.ftcsim.simulator.robot.RobotSpec;
import dev.ftcsim.simulator.SimulationSession;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The sensors of the robot, mounted on the simulated hubs and read through the SDK's own drivers by a program that
 * knows nothing about the simulator: the Pinpoint reports where the robot really is, a colour sensor sees what the
 * robot carries, and a sensor is found by its driver class under any name. Whatever else a program asks for is a problem
 * of the session.
 */
@Timeout(180)
class DevicesSessionTest {
    private static final long SECOND = 1_000_000_000L;

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

    private static void startDriving(SimulationSession session) {
        session.init("Drive And Sense");
        // The Pinpoint boots and calibrates its gyro before it reports a pose, as it does on a robot.
        session.runFor(SECOND);
        session.start();
    }

    @Test
    void thePinpointOnTheRobotReportsTheTruePoseOfTheRobotThroughTheSdksOwnDriver() throws IOException {
        try (TestSessions.Handle handle = TestSessions.open(hornet, 0, 0, 0)) {
            SimulationSession session = handle.session;
            startDriving(session);

            session.setGamepad(1, GamepadState.sticks(0f, -0.8f, 0f, 0f));
            session.runFor(SECOND + SECOND / 2);
            session.setGamepad(1, GamepadState.sticks(0.7f, 0f, 0f, 0f));
            session.runFor(SECOND);
            session.setGamepad(1, GamepadState.sticks(0f, 0f, 0.5f, 0f));
            session.runFor(SECOND / 2);
            session.setGamepad(1, GamepadState.NEUTRAL);
            session.runFor(SECOND);

            BodyPose truth = session.world().bodyPose(EntityIds.CHASSIS);
            double travelled = Math.hypot(truth.x(), truth.y());
            assertTrue(travelled > 1.5, "the robot should have gone somewhere: " + truth);
            // What the program shows is as of its latest loop, a few milliseconds behind a robot that is still moving.
            double lagMillimetres = 1000.0 * Math.hypot(truth.vx(), truth.vy()) * 0.02;
            double tolerance = 2.0 + lagMillimetres;
            assertEquals(truth.x() * 1000.0, reading(session, "pinpoint x mm"), tolerance);
            assertEquals(truth.y() * 1000.0, reading(session, "pinpoint y mm"), tolerance);
            double chip = reading(session, "pinpoint heading rad");
            double headingError = Math.atan2(Math.sin(truth.yaw() - chip), Math.cos(truth.yaw() - chip));
            assertEquals(0.0, headingError, 0.002 + Math.abs(truth.wz()) * 0.02, "heading of the chip against the chassis, " + truth);
            assertEquals(List.of(), session.problems(), handle.log.toString());
        }
    }

    @Test
    void theColourSensorSeesAPieceTheRobotCarriesAtTheDistanceItIsFromTheSensor() throws IOException {
        try (TestSessions.Handle handle = TestSessions.open(hornet, new TestField(), 0, 0, 0)) {
            SimulationSession session = handle.session;
            startDriving(session);
            session.runFor(SECOND / 2);
            double empty = reading(session, "up cm");

            assertEquals(1, session.preload("magazine", TestField.PIECE_KIND, 1));
            session.runFor(SECOND / 2);
            double carried = reading(session, "up cm");

            // Hornet's intake calls a piece "there" when the sensor reads under five centimetres.
            assertTrue(empty > 5.0, "nothing should be near the sensor: " + empty + " cm");
            assertTrue(carried < 5.0, "the carried piece should be under five centimetres from the sensor: " + carried + " cm");
            assertEquals(2.7, carried, 1.0, "the sensor's face is 9 cm from the piece's centre, which has a radius of 6.35 cm");
            assertEquals(List.of(), session.problems(), handle.log.toString());
        }
    }

    @Test
    void aSensorIsFoundByItsClassOrDeclaredNameAndWhatTheSimulatorCannotProvideIsAProblem() throws IOException {
        try (TestSessions.Handle handle = TestSessions.open(hornet, 0, 0, 0)) {
            SimulationSession session = handle.session;
            session.init("Sensors By Class");
            session.runFor(SECOND);

            assertEquals("found GoBildaPinpointDriver", text(session, "pinpoint"));
            assertEquals("found GoBildaPinpointDriver", text(session, "same pinpoint"));
            assertTrue(text(session, "second pinpoint").contains("\"another one\" and type GoBildaPinpointDriver"), text(session, "second pinpoint"));
            for (String caption : new String[] {"colour range", "colour normalized", "colour legacy", "colour v3"}) {
                assertEquals("found RevColorSensorV3", text(session, caption), caption);
            }
            assertTrue(text(session, "second colour").contains("\"other colour\""), text(session, "second colour"));
            assertTrue(text(session, "second colour").contains("colour sensors are [\"upSensor\"]"), text(session, "second colour"));
            assertTrue(text(session, "octoquad").contains("\"octo\""), text(session, "octoquad"));
            assertTrue(text(session, "external imu").contains("no other I2C device"), text(session, "external imu"));
            assertTrue(text(session, "webcam").contains("Webcam 1"), text(session, "webcam"));

            List<String> problems = session.problems();
            for (String unsupported : new String[] {"'another one'", "'other colour'", "'octo'", "'extImu'", "'Webcam 1'"}) {
                assertEquals(
                        1, problems.stream().filter(problem -> problem.contains(unsupported) && problem.contains("not supported")).count(),
                        unsupported + " is a problem of the session: " + problems);
            }
            assertTrue(
                    problems.stream().noneMatch(problem -> problem.contains("'my odometry computer'") || problem.contains("'upSensor'")),
                    "the sensors that are found are no problem: " + problems);
            assertEquals(
                    List.of(),
                    session.unmapped().stream().filter(device -> device.kind() != UnmappedDevice.Kind.MOTOR).toList(),
                    "a sensor is not an unmapped motor, servo or pin");
        }
    }
}
