package dev.ftcsim.acceptance;

import static dev.ftcsim.acceptance.SessionTelemetry.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.field.Alliance;
import dev.ftcsim.contracts.field.StartPose;
import dev.ftcsim.contracts.robot.GamepadState;
import dev.ftcsim.field.FieldSetup;
import dev.ftcsim.game.biobuzz.BiobuzzField;
import dev.ftcsim.simulator.SimulationSession;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * BIOBUZZ's two colour sensors on the stub program: {@code upSensor} sees the ball that waits against the stopper and
 * {@code intakeSensor} the fourth ball of the queue, so the first reads a ball whenever one has reached the stopper and
 * the second only when the fourth place of the queue is taken. A ball is read at under 5 cm (Hornet's own test); nothing in range reads the
 * sensor's 6 inch (15.24 cm) maximum.
 */
@Timeout(300)
class BiobuzzColourSensorsSessionTest {
    private static final long SECOND = 1_000_000_000L;
    private static final double NEAR_CM = 5.0;
    private static final double FAR_CM = 15.0;
    private static final BiobuzzField FIELD = new BiobuzzField();

    private static GamepadState pad(boolean intake, boolean flywheels, boolean feed, boolean open) {
        return new GamepadState(
                0f, 0f, 0f, 0f, intake ? 1f : 0f, flywheels ? 1f : 0f, false, false, false, false, false, feed, open, false, false, false,
                false, false, false, false, false);
    }

    private static TestSessions.Handle open() throws IOException {
        StartPose start = FIELD.description().startPoses().stream().filter(pose -> pose.name().equals("RED wall, centre")).findFirst().orElseThrow();
        return TestSessions.open(
                BiobuzzTestRobot.spec(), TestSessions.program(), FIELD, new FieldSetup(1, Alliance.RED), start.x(), start.y(), start.headingRadians());
    }

    private static void run(SimulationSession session) {
        session.init("BIOBUZZ Stub");
        session.runFor(SECOND);
        session.start();
        session.runFor(SECOND / 2);
    }

    private static double up(SimulationSession session) {
        return BiobuzzTestRobot.value(text(session, "Sensors"), "up");
    }

    private static double intake(SimulationSession session) {
        return BiobuzzTestRobot.value(text(session, "Sensors"), "intake");
    }

    private static String held(SimulationSession session) {
        return session.hardware().stream().filter(line -> line.name().equals("storage magazine")).findFirst().orElseThrow().value();
    }

    /** Spins the flywheels up, then feeds through the open stopper in short bursts until the magazine says [target]. */
    private static void shootDownTo(SimulationSession session, String target) {
        session.setGamepad(1, pad(false, true, false, false));
        session.runFor(3 * SECOND);
        StringBuilder seen = new StringBuilder();
        for (int i = 0; i < 100 && !held(session).equals(target); i++) {
            // The lowest ball is moved along only by the intake roller, so the last one needs it.
            session.setGamepad(1, pad(target.startsWith("0"), true, true, true));
            session.runFor(SECOND / 10);
            session.setGamepad(1, pad(false, true, false, false));
            session.runFor(SECOND / 2);
            seen.append(held(session)).append("; ");
        }
        assertEquals(target, held(session), seen.toString());
        session.setGamepad(1, GamepadState.NEUTRAL);
        session.runFor(SECOND);
    }
    @Test
    void withTheFourPreloadsAtTheStopperBothSensorsReadABall() throws IOException {
        try (TestSessions.Handle handle = open()) {
            SimulationSession session = handle.session;
            run(session);

            assertEquals("4 of 4 pieces", held(session));
            assertTrue(up(session) < NEAR_CM, "upSensor: " + text(session, "Sensors"));
            assertTrue(intake(session) < NEAR_CM, "intakeSensor: " + text(session, "Sensors"));
        }
    }

    @Test
    void afterAllFourAreShotBothSensorsReadNothing() throws IOException {
        try (TestSessions.Handle handle = open()) {
            SimulationSession session = handle.session;
            run(session);

            shootDownTo(session, "0 of 4 pieces");

            assertTrue(up(session) >= FAR_CM, "upSensor: " + text(session, "Sensors"));
            assertTrue(intake(session) >= FAR_CM, "intakeSensor: " + text(session, "Sensors"));
        }
    }

    @Test
    void theLastBallWaitingNearTheIntakeIsSeenOnlyByIntakeSensorAndOnceStagedAgainstTheStopperOnlyByUpSensor() throws IOException {
        try (TestSessions.Handle handle = open()) {
            SimulationSession session = handle.session;
            run(session);

            // The stopper lets the three balls ahead of it go together; the fourth, which only the intake roller moves, stays where it queued.
            shootDownTo(session, "1 of 4 pieces");

            assertTrue(up(session) >= FAR_CM, "upSensor: " + text(session, "Sensors"));
            assertTrue(intake(session) < NEAR_CM, "intakeSensor: " + text(session, "Sensors"));

            // The intake's staging run carries it up against the shut stopper.
            session.setGamepad(1, pad(true, false, false, false));
            session.runFor(3 * SECOND);
            session.setGamepad(1, GamepadState.NEUTRAL);
            session.runFor(SECOND);

            assertEquals("1 of 4 pieces", held(session));
            assertTrue(up(session) < NEAR_CM, "upSensor: " + text(session, "Sensors"));
            assertTrue(intake(session) >= FAR_CM, "intakeSensor: " + text(session, "Sensors"));
        }
    }
    @Test
    void aColourSensorUnderAnotherNameIsNotFound() throws IOException {
        try (TestSessions.Handle handle = open()) {
            IllegalArgumentException refused =
                    assertThrows(IllegalArgumentException.class, () -> handle.session.rig().wiring().colorSensor("colorSensor"));

            assertTrue(refused.getMessage().contains("upSensor") && refused.getMessage().contains("intakeSensor"), refused.getMessage());
        }
    }
}
