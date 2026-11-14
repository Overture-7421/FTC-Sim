package dev.ftcsim.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.robot.GamepadState;
import dev.ftcsim.contracts.world.BodyPose;
import dev.ftcsim.field.EntityIds;
import dev.ftcsim.simulator.SimulationSession;
import dev.ftcsim.simulator.profile.ProfileException;
import dev.ftcsim.simulator.profile.RobotProfile;
import dev.ftcsim.simulator.profile.RobotProfileLoader;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The devices of a robot profile, mounted on the simulated hubs and read through the SDK's own drivers by a program
 * that knows nothing about the simulator: the chip that counts odometry pods follows the robot that carries them, and
 * a distance sensor sees what the robot carries.
 */
@Timeout(180)
class DevicesSessionTest {
    private static final long SECOND = 1_000_000_000L;

    private static RobotProfile hornet;

    @BeforeAll
    static void loadProfile() throws IOException {
        hornet = RobotProfileLoader.loadBundled("hornet-2026.json");
    }

    /** The number a telemetry line shows as "caption : value". */
    private static double reading(SimulationSession session, String caption) {
        return session.telemetry().lines().stream()
                .filter(line -> line.startsWith(caption + " : "))
                .mapToDouble(line -> Double.parseDouble(line.substring(caption.length() + 3)))
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
    void thePinpointOnTheRobotCountsTheTrueMotionOfTheRobotThroughTheSdksOwnDriver() throws IOException {
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
            double tolerance = 10.0 + 0.01 * travelled * 1000.0 + lagMillimetres;
            assertEquals(truth.x() * 1000.0, reading(session, "pinpoint x mm"), tolerance);
            assertEquals(truth.y() * 1000.0, reading(session, "pinpoint y mm"), tolerance);
            double chip = reading(session, "pinpoint heading rad");
            double headingError = Math.atan2(Math.sin(truth.yaw() - chip), Math.cos(truth.yaw() - chip));
            assertEquals(0.0, headingError, 0.02 + Math.abs(truth.wz()) * 0.02, "heading of the chip against the chassis, " + truth);
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
    void aDeviceTypeNoModelExistsForIsRefusedWithAMessageThatNamesTheDeviceAndTheTypes() {
        RobotProfile profile = RobotProfileLoader.parse("""
                {"schema": 1, "name": "bad device",
                 "devices": { "mystery": { "type": "no-such-sensor", "bus": 1 } } }
                """, "test profile");

        ProfileException refused = assertThrows(ProfileException.class, () -> TestSessions.open(profile, 0, 0, 0));

        assertTrue(refused.getMessage().contains("devices.mystery"), refused.getMessage());
        assertTrue(refused.getMessage().contains("no-such-sensor"), refused.getMessage());
        assertTrue(refused.getMessage().contains("gobilda-pinpoint"), "it should list the types there are: " + refused.getMessage());
    }
}
