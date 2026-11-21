package dev.ftcsim.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.field.Alliance;
import dev.ftcsim.contracts.robot.GamepadState;
import dev.ftcsim.contracts.robot.RobotPhase;
import dev.ftcsim.contracts.world.BodyPose;
import dev.ftcsim.field.EntityIds;
import dev.ftcsim.field.FieldSetup;
import dev.ftcsim.game.decode.DecodeField;
import dev.ftcsim.importer.pack.PreparedProgram;
import dev.ftcsim.simulator.robot.RobotDesign;
import dev.ftcsim.simulator.robot.RobotSpec;
import dev.ftcsim.simulator.RobotProgram;
import dev.ftcsim.simulator.SimulationSession;
import java.util.Locale;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Hornet's driver-controlled OpMode with a driver's hands on the simulated gamepad: the sticks drive the robot, a
 * trigger shoots what it carries. Hornet's code reads the gamepad as it would on a robot and nothing here knows how it
 * does. It runs with {@code -Pftcsim.hornetPackage=<folder>} (see {@link HornetFixture}).
 */
@Timeout(3600)
class HornetTeleopTest {
    private static final long SECOND = 1_000_000_000L;
    private static final double INCH = 0.0254;
    private static final int PURPLE = 101;
    private static final int GREEN = 102;

    private static void report(String line) {
        System.out.println("HORNET-TELEOP> " + line);
    }

    /**
     * Hornet's driver-controlled OpModes assume the robot starts at (8, 8) in Pedro Pathing's frame, facing +x: the corner
     * by the audience wall, 8 inches from both walls, which is where the robot's outline touches them (and the
     * 5 inch artifacts of the loading zone lie). Its code believes that pose until its camera tells it better.
     */
    private static double[] teleopCorner() {
        return new double[] {(72 - 8) * INCH, (8 - 72) * INCH, Math.toRadians(0 + 90)};
    }

    /** A free place in the open with the same heading, for driving about. */
    private static double[] openFloor() {
        return new double[] {0.3, -0.3, Math.toRadians(0 + 90)};
    }

    private static GamepadState sticks(float leftX, float leftY, float rightX) {
        return GamepadState.sticks(leftX, leftY, rightX, 0f);
    }

    private static GamepadState triggers(float left, float right) {
        return new GamepadState(
                0f, 0f, 0f, 0f, left, right, false, false, false, false, false, false, false, false, false, false, false, false,
                false, false, false);
    }

    private static String where(BodyPose pose) {
        return String.format(Locale.ROOT, "(%.2f, %.2f, yaw %.2f)", pose.x(), pose.y(), pose.yaw());
    }

    private static long carried(SimulationSession session, BodyPose chassis) {
        return session.snapshot().bodies().stream()
                .filter(body -> body.kind() == PURPLE || body.kind() == GREEN)
                .filter(body -> Math.hypot(body.x() - chassis.x(), body.y() - chassis.y()) < 0.35 && body.z() < 0.5)
                .count();
    }

    private interface Scene {
        void play(SimulationSession session) throws Exception;
    }

    private static void withTeleop(Alliance alliance, double[] start, Scene scene) throws Exception {
        PreparedProgram prepared = HornetFixture.prepared();
        RobotProgram program = HornetFixture.program(prepared);
        RobotSpec profile = RobotDesign.decode().spec();
        try (TestSessions.Handle handle = TestSessions.open(
                profile, program, new DecodeField(), new FieldSetup(1, alliance), start[0], start[1], start[2])) {
            SimulationSession session = handle.session;
            session.init(alliance == Alliance.BLUE ? "Teleop - Blue" : "Teleop - Red");
            session.runFor(2 * SECOND);
            assertEquals(RobotPhase.INIT, session.phase(), "the OpMode should be initialised: " + session.telemetry().lines());
            session.start();
            session.runFor(SECOND);
            scene.play(session);
            report("problems: " + session.problems());
        }
    }

    @Test
    void theLeftStickDrivesTheRobotAcrossTheFieldAndLettingGoBringsItToRest() throws Exception {
        withTeleop(Alliance.BLUE, openFloor(), session -> {
            BodyPose before = session.world().bodyPose(EntityIds.CHASSIS);
            session.setGamepad(1, sticks(0f, -1f, 0f));
            session.runFor(2 * SECOND);
            BodyPose moving = session.world().bodyPose(EntityIds.CHASSIS);
            session.setGamepad(1, GamepadState.NEUTRAL);
            session.runFor(2 * SECOND);
            BodyPose after = session.world().bodyPose(EntityIds.CHASSIS);
            report("stick forward for 2 s: " + where(before) + " -> " + where(moving) + " at " + String.format(Locale.ROOT, "%.2f m/s", Math.hypot(moving.vx(), moving.vy()))
                    + "; two seconds after letting go " + where(after) + " at " + String.format(Locale.ROOT, "%.2f m/s", Math.hypot(after.vx(), after.vy())));
            assertTrue(Math.hypot(moving.x() - before.x(), moving.y() - before.y()) > 0.5, "the robot should have driven: " + where(moving));
            assertTrue(Math.hypot(after.vx(), after.vy()) < 0.1, "the robot should have stopped: " + where(after));
        });
    }

    @Test
    void theRightTriggerRunsTheIntakeAndTheLeftTriggerAlignsAndShootsWhatTheRobotCarries() throws Exception {
        withTeleop(Alliance.BLUE, teleopCorner(), session -> {
            BodyPose start = session.world().bodyPose(EntityIds.CHASSIS);
            report("carried " + carried(session, start));

            session.setGamepad(1, triggers(0f, 1f));
            session.runFor(2 * SECOND);
            report("right trigger for 2 s: " + session.hardware().stream()
                    .filter(readout -> readout.name().equals("intake") || readout.name().equals("upMotor"))
                    .map(readout -> readout.name() + " " + readout.value().split("  ")[0]).collect(Collectors.joining(", ")));
            session.setGamepad(1, GamepadState.NEUTRAL);
            session.runFor(SECOND);

            session.setGamepad(1, triggers(1f, 0f));
            for (int second = 1; second <= 12; second++) {
                session.runFor(SECOND);
                BodyPose pose = session.world().bodyPose(EntityIds.CHASSIS);
                report(String.format(
                        Locale.ROOT, "left trigger, t=%2d s: yaw %.2f  carried %d  flywheels %s  score %s", second, pose.yaw(), carried(session, pose),
                        session.hardware().stream().filter(readout -> readout.name().startsWith("shooter"))
                                .map(readout -> readout.value().split("  ")[0]).collect(Collectors.joining("/")),
                        session.score().lines().stream().filter(line -> line.count() > 0)
                                .map(line -> line.label() + " " + line.count()).collect(Collectors.joining("; "))));
            }
            session.setGamepad(1, GamepadState.NEUTRAL);
            BodyPose end = session.world().bodyPose(EntityIds.CHASSIS);
            assertTrue(carried(session, end) < 3, "the three pieces should have been shot out of the robot");
        });
    }
}
