package dev.ftcsim.acceptance;

import static dev.ftcsim.acceptance.BiobuzzTestRobot.value;
import static dev.ftcsim.acceptance.SessionTelemetry.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.field.Alliance;
import dev.ftcsim.contracts.robot.GamepadState;
import dev.ftcsim.contracts.robot.RobotPhase;
import dev.ftcsim.contracts.snapshot.BodyState;
import dev.ftcsim.contracts.world.BodyPose;
import dev.ftcsim.field.EntityIds;
import dev.ftcsim.field.FieldSetup;
import dev.ftcsim.game.biobuzz.BiobuzzField;
import dev.ftcsim.game.biobuzz.BiobuzzIds;
import dev.ftcsim.simulator.SimulationSession;
import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The two alliance TeleOps ({@code BiobuzzRedTeleOp}, {@code BiobuzzBlueTeleOp}) on the real SDK, the real field and the real
 * HIVE: with the right bumper held, a robot on the open side of its own HIVE puts its three POLLEN into the CELL that faces up and
 * tips it, which the field's score and the HIVE's own angle say. The turret and the hood follow the HIVE with no button, the
 * pose starts as an approximate start and is corrected by the tags, and nothing stays on once the button is let go.
 */
@Timeout(600)
class BiobuzzAllianceSessionTest {
    private static final long SECOND = 1_000_000_000L;
    private static final BiobuzzField FIELD = new BiobuzzField();

    private static GamepadState pad(boolean intake, boolean shoot) {
        return new GamepadState(
                0f, 0f, 0f, 0f, 0f, 0f, false, false, false, false, false, false, false, false, intake, shoot, false, false, false, false,
                false);
    }

    private static String opMode(Alliance alliance) {
        return alliance == Alliance.RED ? "BIOBUZZ Red" : "BIOBUZZ Blue";
    }

    /** The side of its HIVE that the CELL facing up at the start opens to: +1 audience, -1 scoring. */
    private static int openSide(Alliance alliance) {
        return alliance == Alliance.RED ? 1 : -1;
    }

    /** A session of {@code alliance}'s TeleOp with the robot at {@code (x, y)} turned {@code turn} radians from facing its HIVE's CELL. */
    private static TestSessions.Handle open(Alliance alliance, double x, double y, double turn) throws IOException {
        double hiveY = alliance == Alliance.RED ? -0.3236 : 0.3236;
        double heading = Math.atan2(hiveY - y, openSide(alliance) * 0.4 - x) + turn;
        return TestSessions.open(BiobuzzTestRobot.spec(), TestSessions.program(), FIELD, new FieldSetup(1, alliance), x, y, heading);
    }

    private static void initAndStart(SimulationSession session, Alliance alliance) {
        session.init(opMode(alliance));
        session.runFor(SECOND);
        assertEquals(RobotPhase.INIT, session.phase(), session.telemetry().toString());
        assertEquals("READY", text(session, "Pinpoint"));
        session.start();
    }

    /** The HIVE TIPs of the robot's own alliance so far, as the field's score counts them. */
    private static int tips(SimulationSession session) {
        return session.score().lines().stream().filter(line -> line.label().equals("HIVE TIP in TELEOP")).findFirst().orElseThrow().count();
    }

    /** How far a HIVE's arm is from level, in degrees: -30 with its audience CELL up, +30 with its scoring CELL up. */
    private static double hiveDegrees(SimulationSession session, int entity) {
        BodyState hive = session.snapshot().bodies().stream().filter(body -> body.entityId() == entity).findFirst().orElseThrow();
        return Math.toDegrees(2.0 * Math.atan2(hive.qy(), hive.qw()));
    }

    private static int own(Alliance alliance) {
        return alliance == Alliance.RED ? BiobuzzIds.HIVE_RED : BiobuzzIds.HIVE_BLUE;
    }

    private static int other(Alliance alliance) {
        return alliance == Alliance.RED ? BiobuzzIds.HIVE_BLUE : BiobuzzIds.HIVE_RED;
    }

    private static String summary(SimulationSession session) {
        return text(session, "Estimate") + " | " + text(session, "Aim") + " | " + text(session, "Shooter") + " | " + text(session, "Mechanisms");
    }

    /** Holds the shoot button until the HIVE tips or the time is up, then lets go and waits for the balls to settle. */
    private static void shootUntilTipped(SimulationSession session, int seconds) {
        session.setGamepad(1, pad(false, true));
        for (int i = 0; i < seconds * 10 && tips(session) == 0; i++) {
            session.runFor(SECOND / 10);
        }
        session.setGamepad(1, GamepadState.NEUTRAL);
        session.runFor(SECOND);
    }

    private static Stream<Arguments> placesToShootFrom() {
        // Near the HIVE, to its side, far, far to the corner and at the end of the field: all on the side its CELL opens to.
        double[][] red = {{1.0, -0.8}, {1.2, -0.3}, {1.4, -1.0}, {1.5, -1.5}, {1.7, -1.0}};
        return Stream.of(red).flatMap(place -> Stream.of(
                Arguments.of(Alliance.RED, place[0], place[1]),
                // The field is the same when turned half a turn about its centre, which takes the red places to the blue ones.
                Arguments.of(Alliance.BLUE, -place[0], -place[1])));
    }

    @ParameterizedTest(name = "{0} from x={1} y={2}")
    @MethodSource("placesToShootFrom")
    void holdingTheRightBumperTipsTheAlliancesOwnHiveFromEveryPlaceOnTheOpenSide(Alliance alliance, double x, double y) throws IOException {
        try (TestSessions.Handle handle = open(alliance, x, y, 0.0)) {
            SimulationSession session = handle.session;
            initAndStart(session, alliance);
            double start = hiveDegrees(session, own(alliance));
            double otherStart = hiveDegrees(session, other(alliance));
            session.runFor(SECOND / 2);
            assertEquals(0, tips(session));
            shootUntilTipped(session, 25);

            String message = summary(session);
            assertEquals(1, tips(session), "the own HIVE tipped: " + message);
            assertEquals(-start, hiveDegrees(session, own(alliance)), 4.0, "and rests on its other stop: " + message);
            assertEquals(otherStart, hiveDegrees(session, other(alliance)), 4.0, "while the other alliance's HIVE has not moved");
            assertEquals(List.of(), session.problems(), handle.log.toString());
        }
    }

    @Test
    void theBallLeavesFromBetweenTheWheelsAndTheHoodWhereTheHoodSetsItNotFromTheLimelight() throws IOException {
        for (double[] place : new double[][] {{1.0, -0.8}, {1.7, -1.0}}) {
            try (TestSessions.Handle handle = open(Alliance.RED, place[0], place[1], 0.0)) {
                SimulationSession session = handle.session;
                initAndStart(session, Alliance.RED);
                session.setGamepad(1, pad(false, true));
                BodyState ball = null;
                java.util.Map<Integer, BodyState> before = new java.util.HashMap<>();
                for (int i = 0; i < 4800 && ball == null; i++) {
                    session.runFor(SECOND / 240);
                    for (BodyState body : session.snapshot().bodies()) {
                        BodyState last = before.put(body.entityId(), body);
                        // Held and staged balls creep; a ball in the air covers centimetres a step.
                        if (ball == null && last != null && body.kind() == BiobuzzIds.KIND_POLLEN
                                && Math.hypot(Math.hypot(body.x() - last.x(), body.y() - last.y()), body.z() - last.z()) > 0.012) {
                            ball = body;
                        }
                    }
                }
                assertTrue(ball != null, "a ball left");
                BodyPose chassis = session.world().bodyPose(EntityIds.CHASSIS);
                double heading = chassis.yaw() + session.rig().turretYawRadians();
                double hood = value(text(session, "Mechanisms"), "hood");
                double theta = Math.toRadians(45.0 + hood * 1800.0 / (730.0 / 24.0));
                // From the turret's axis, along the turret: the ball rides round the wheels' rim to where the hood's end lets it go, which is
                // back over the axle for a steep launch (x 0.162 - 0.0737 sin, z 0.2146 + 0.0737 cos); not out in front of the wheels.
                double dx = ball.x() - (chassis.x() + 0.089 * Math.cos(chassis.yaw()));
                double dy = ball.y() - (chassis.y() + 0.089 * Math.sin(chassis.yaw()));
                double ahead = dx * Math.cos(heading) + dy * Math.sin(heading);
                double across = -dx * Math.sin(heading) + dy * Math.cos(heading);
                double expectedAhead = 0.162 - 0.0737 * Math.sin(theta) - 0.089;
                double expectedHeight = 0.2146 + 0.0737 * Math.cos(theta);
                // The ball has had up to a physics step (4 ms, about 2.5 cm of flight) since it was let go.
                assertEquals(expectedAhead, ahead, 0.04, "ahead of the axis at " + Math.toDegrees(theta) + " degrees");
                assertEquals(expectedHeight, ball.z() - chassis.z(), 0.04, "above the chassis' centre");
                assertEquals(0.0, across, 0.03, "on the turret's line");
                // The Limelight's box is on its 45 degree panel at (0.217, 0.0035, 0.256) of the robot: the ball is not there.
                double limelightAhead = 0.217 - 0.089;
                double fromLimelight = Math.hypot(ahead - limelightAhead, ball.z() - chassis.z() - 0.256);
                assertTrue(fromLimelight > 0.08, "the ball starts " + fromLimelight + " m from the Limelight");
            }
        }
    }

    /** Drives with the sticks to {@code (wx, wy)} and turns to face {@code headingDegrees}, as a driver does, from the robot's true pose. */
    private static void driveTo(SimulationSession session, double wx, double wy, double headingDegrees) {
        for (int i = 0; i < 400; i++) {
            BodyPose t = session.world().bodyPose(EntityIds.CHASSIS);
            double ex = wx - t.x();
            double ey = wy - t.y();
            double forward = ex * Math.cos(t.yaw()) + ey * Math.sin(t.yaw());
            double left = -ex * Math.sin(t.yaw()) + ey * Math.cos(t.yaw());
            double turn = Math.IEEEremainder(Math.toRadians(headingDegrees) - t.yaw(), 2.0 * Math.PI);
            double distance = Math.hypot(ex, ey);
            if (distance < 0.08 && Math.abs(turn) < 0.05) {
                break;
            }
            double gain = Math.min(0.8, 1.5 * distance) / Math.max(distance, 1e-6);
            session.setGamepad(1, GamepadState.sticks(
                    (float) (-left * gain), (float) (-forward * gain), (float) Math.max(-0.6, Math.min(0.6, -1.5 * turn)), 0f));
            session.runFor(SECOND / 10);
        }
        session.setGamepad(1, GamepadState.NEUTRAL);
        session.runFor(SECOND);
    }

    @Test
    void afterTheTipTheRobotCanDriveRoundTheFieldToTheOtherCellAndTheTurretStaysOnItsAim() throws IOException {
        try (TestSessions.Handle handle = open(Alliance.RED, 1.4, -0.95, 0.0)) {
            SimulationSession session = handle.session;
            initAndStart(session, Alliance.RED);
            session.runFor(SECOND);
            shootUntilTipped(session, 25);
            assertEquals(1, tips(session), summary(session));

            // Up the audience side, along the far wall (the HIVE's other side, seen only in passing) and down the scoring side: the turret
            // follows the CELL that faced up for a while, which takes it past half a turn, where the encoders must still read it.
            driveTo(session, 1.5, 0.9, 90.0);
            driveTo(session, -1.4, 0.9, 90.0);
            driveTo(session, -1.4, -0.6, 0.0);
            session.runFor(SECOND * 2);

            BodyPose truth = session.world().bodyPose(EntityIds.CHASSIS);
            assertTrue(text(session, "Alliance").contains("scoring CELL"), "the tags seen in passing say the other CELL faces up: " + summary(session));
            assertEquals(truth.x(), value(text(session, "Estimate"), "x"), 0.1, summary(session));
            assertEquals(truth.y(), value(text(session, "Estimate"), "y"), 0.1, summary(session));
            double yaw = Math.toDegrees(session.rig().turretYawRadians());
            assertEquals(0.0, Math.IEEEremainder(value(text(session, "Turret"), "deg") - yaw, 360.0), 6.0, "the encoders read the turret: " + summary(session));
            assertTrue(text(session, "Aim").contains("margin"), "and there is a shot from here: " + summary(session));
            double aimed = Double.parseDouble(text(session, "Aim").replaceAll("^bearing (-?[0-9.]+) .*", "$1"));
            assertEquals(aimed, value(text(session, "Turret"), "deg"), 3.0, "the turret points at the CELL: " + summary(session));
            assertEquals(List.of(), session.problems(), handle.log.toString());
        }
    }

    @Test
    void theApproximateStartIsCorrectedByTheTagsAndNothingFeedsBeforeThat() throws IOException {
        // On the wall facing it the camera sees no tag, wherever the turret looks: the pose stays the start the program was given.
        try (TestSessions.Handle handle = TestSessions.open(BiobuzzTestRobot.spec(), TestSessions.program(), FIELD, new FieldSetup(1, Alliance.RED),
                0.0, -1.5575, -Math.PI / 2)) {
            SimulationSession session = handle.session;
            initAndStart(session, Alliance.RED);
            session.setGamepad(1, pad(false, true));
            session.runFor(SECOND / 2);
            double lowest = 0.0;
            double highest = 0.0;
            for (int i = 0; i < 150; i++) {
                session.runFor(SECOND / 10);
                assertTrue(text(session, "Estimate").contains("approximate"), summary(session));
                assertTrue(text(session, "Shooter").startsWith("REFUSED"), summary(session));
                assertTrue(text(session, "Mechanisms").contains("stopper=shut") && text(session, "Mechanisms").contains("indexer=off"),
                        "nothing is fed: " + summary(session));
                assertEquals(0.0, value(text(session, "Mechanisms"), "flywheels"), 1.0, "and the flywheels are off: " + summary(session));
                double turret = value(text(session, "Turret"), "deg");
                lowest = Math.min(lowest, turret);
                highest = Math.max(highest, turret);
            }
            assertTrue(highest - lowest > 100.0, "the turret sweeps about where the HIVE should be while it looks: " + lowest + " to " + highest);
            assertEquals(0, tips(session));
        }

        // Facing the HIVE the first frame places the robot: the estimate is the true pose, whatever the start said.
        try (TestSessions.Handle handle = open(Alliance.RED, 1.4, -1.0, 0.0)) {
            SimulationSession session = handle.session;
            initAndStart(session, Alliance.RED);
            session.runFor(SECOND);
            BodyPose truth = session.world().bodyPose(EntityIds.CHASSIS);
            String estimate = text(session, "Estimate");
            assertTrue(estimate.contains("confirmed") && !estimate.contains("approximate"), estimate);
            assertEquals(truth.x(), value(estimate, "x"), 0.05);
            assertEquals(truth.y(), value(estimate, "y"), 0.05);
            assertEquals(0.0, Math.IEEEremainder(Math.toDegrees(truth.yaw()) - value(estimate, "h"), 360.0), 3.0);
        }
    }

    @Test
    void theTurretAndTheHoodFollowTheHiveWithNoButtonAndTheBumpersOnlyWorkWhileHeld() throws IOException {
        try (TestSessions.Handle handle = open(Alliance.RED, 1.4, -1.0, 0.0)) {
            SimulationSession session = handle.session;
            initAndStart(session, Alliance.RED);
            BodyPose chassis = session.world().bodyPose(EntityIds.CHASSIS);
            long heldAtTheStopper = session.snapshot().bodies().stream()
                    .filter(body -> body.kind() == BiobuzzIds.KIND_POLLEN && Math.abs(body.z() - chassis.z() - 0.06) < 0.06
                            && Math.hypot(body.x() - chassis.x() - 0.089 * Math.cos(chassis.yaw()),
                                    body.y() - chassis.y() - 0.089 * Math.sin(chassis.yaw())) < 0.05)
                    .count();
            assertTrue(heldAtTheStopper >= 1, "the preloads wait low in the column against the shut stopper, not in the shooter");

            // The hood's servo takes seconds to get anywhere.
            session.runFor(SECOND * 6);
            assertTrue(value(text(session, "Mechanisms"), "hood") > 0.2,
                    "the hood is already where the shot wants it, with no button held: " + summary(session));
            assertTrue(text(session, "Shooter").startsWith("IDLE"), summary(session));
            assertTrue(text(session, "Mechanisms").contains("flywheels=0") && text(session, "Mechanisms").contains("stopper=shut"));
            BodyPose truth = session.world().bodyPose(EntityIds.CHASSIS);
            double axisX = truth.x() + 0.089 * Math.cos(truth.yaw());
            double axisY = truth.y() + 0.089 * Math.sin(truth.yaw());
            double expected = Math.toDegrees(Math.atan2(-0.3236 - axisY, 0.4 - axisX) - truth.yaw());
            assertEquals(0.0, Math.IEEEremainder(Math.toDegrees(session.rig().turretYawRadians()) - expected, 360.0), 6.0,
                    "the turret faces the HIVE's CELL: " + summary(session));

            // Hold the left bumper: the intake runs and the indexer carries balls slowly to the shut stopper; let go and they stop.
            session.setGamepad(1, pad(true, false));
            session.runFor(SECOND / 2);
            assertTrue(text(session, "Mechanisms").contains("intake=on") && text(session, "Mechanisms").contains("indexer=staging"),
                    summary(session));
            assertTrue(text(session, "Mechanisms").contains("stopper=shut"), "intaking does not shoot");
            session.setGamepad(1, GamepadState.NEUTRAL);
            session.runFor(SECOND / 2);
            assertTrue(text(session, "Mechanisms").contains("intake=off") && text(session, "Mechanisms").contains("indexer=off"), summary(session));

            // Hold the right bumper until the balls are going out, then let go.
            session.setGamepad(1, pad(false, true));
            for (int i = 0; i < 200 && !text(session, "Shooter").startsWith("FEEDING"); i++) {
                session.runFor(SECOND / 10);
            }
            assertTrue(text(session, "Shooter").startsWith("FEEDING"), summary(session));
            assertTrue(value(text(session, "Mechanisms"), "flywheels") > 800, summary(session));
            double flywheels = value(text(session, "Mechanisms"), "flywheels");
            session.setGamepad(1, GamepadState.NEUTRAL);
            session.runFor(SECOND / 4);
            assertTrue(text(session, "Shooter").startsWith("IDLE"), summary(session));
            assertTrue(text(session, "Mechanisms").contains("stopper=shut") && text(session, "Mechanisms").contains("indexer=off"), summary(session));
            session.runFor(SECOND * 2);
            assertTrue(value(text(session, "Mechanisms"), "flywheels") < flywheels * 0.8, "the flywheels were let go of: " + summary(session));
            assertEquals(List.of(), session.problems(), handle.log.toString());
        }
    }

    @Test
    void theTurretStaysInItsRangeAndTheEncodersStayReadableWhileTheRobotSpins() throws IOException {
        try (TestSessions.Handle handle = open(Alliance.RED, 1.4, -1.0, 0.0)) {
            SimulationSession session = handle.session;
            initAndStart(session, Alliance.RED);
            session.runFor(SECOND);
            double least = 0.0;
            double most = 0.0;
            // Two turns and a half of the robot, clockwise, which carries the HIVE round the turret the other way.
            session.setGamepad(1, GamepadState.sticks(0f, 0f, 1f, 0f));
            for (int i = 0; i < 60; i++) {
                session.runFor(SECOND / 4);
                double yaw = Math.toDegrees(session.rig().turretYawRadians());
                least = Math.min(least, yaw);
                most = Math.max(most, yaw);
                // Not just the same direction: past half a turn the reading must say 200, not -160, or the controller winds on.
                if (yaw > -160.0 && yaw < 245.0) {
                    assertEquals(yaw, value(text(session, "Turret"), "deg"), 15.0, "the turret's angle as the program reads it");
                }
            }
            session.setGamepad(1, GamepadState.NEUTRAL);
            session.runFor(SECOND * 8);

            assertTrue(least > -176.0 && most < 256.0, "the turret stayed in its range, which the two encoders can read: " + least + " to " + most);
            double yaw = Math.toDegrees(session.rig().turretYawRadians());
            assertEquals(0.0, Math.IEEEremainder(value(text(session, "Turret"), "deg") - yaw, 360.0), 6.0, "and what the encoders say is where it is");
            BodyPose truth = session.world().bodyPose(EntityIds.CHASSIS);
            assertTrue(text(session, "Estimate").contains("confirmed"), summary(session));
            assertEquals(truth.x(), value(text(session, "Estimate"), "x"), 0.1, "and the pose is still the robot's: " + summary(session));
            assertEquals(truth.y(), value(text(session, "Estimate"), "y"), 0.1);
            assertEquals(List.of(), session.problems(), handle.log.toString());
        }
    }

    @Test
    void theCameraDoesNotCorrectThePoseWhileTheRobotDrivesBecauseItsFramesAreAboutATenthOfASecondOld() throws IOException {
        // Facing across the field from beside the wall, with the HIVE's tags in view, then a full-speed strafe to the right.
        try (TestSessions.Handle handle = TestSessions.open(BiobuzzTestRobot.spec(), TestSessions.program(), FIELD, new FieldSetup(1, Alliance.RED),
                0.0, -0.857, Math.PI / 2)) {
            SimulationSession session = handle.session;
            initAndStart(session, Alliance.RED);
            session.runFor(SECOND * 3 / 2);
            assertTrue(text(session, "Estimate").contains("confirmed"), summary(session));

            session.setGamepad(1, GamepadState.sticks(1f, 0f, 0f, 0f));
            session.runFor(SECOND / 3);
            // Telemetry is up to 50 ms old and the Pinpoint a little more, which is 10 cm at this speed: much more is what a frame that old does.
            double worst = 0.0;
            for (int i = 0; i < 6; i++) {
                session.runFor(SECOND / 10);
                BodyPose moving = session.world().bodyPose(EntityIds.CHASSIS);
                assertTrue(Math.hypot(moving.vx(), moving.vy()) > 0.5, "the robot is driving: " + moving);
                worst = Math.max(worst, Math.abs(value(text(session, "Estimate"), "x") - moving.x()));
            }
            assertTrue(worst < 0.18, "the estimate follows the odometry, not a frame a tenth of a second old: " + worst + " " + summary(session));
            session.setGamepad(1, GamepadState.NEUTRAL);
            session.runFor(SECOND * 3);
            BodyPose truth = session.world().bodyPose(EntityIds.CHASSIS);
            assertEquals(truth.x(), value(text(session, "Estimate"), "x"), 0.04, "a frame taken before the robot moved on would put it back: " + summary(session));
            assertEquals(truth.y(), value(text(session, "Estimate"), "y"), 0.04, summary(session));
        }
    }

    @Test
    void afterTheTipTheTagsTellTheTurretToTurnToTheOtherCellOnceTheRobotIsOnItsSide() throws IOException {
        // The robot faces across the field at the red HIVE, so that strafing left or right carries it past the HIVE on its wall side.
        try (TestSessions.Handle handle = TestSessions.open(BiobuzzTestRobot.spec(), TestSessions.program(), FIELD, new FieldSetup(1, Alliance.RED),
                1.4, -0.95, Math.PI / 2)) {
            SimulationSession session = handle.session;
            initAndStart(session, Alliance.RED);
            session.runFor(SECOND);
            assertTrue(text(session, "Alliance").contains("audience CELL"), text(session, "Alliance"));
            shootUntilTipped(session, 25);
            assertEquals(1, tips(session), summary(session));
            assertTrue(text(session, "Alliance").contains("audience CELL"), "the audience side cannot see the tags of the tipped HIVE yet");

            // Strafe left (towards the scoring side), along the wall side of the HIVE.
            session.setGamepad(1, GamepadState.sticks(-0.8f, 0f, 0f, 0f));
            session.runFor(SECOND * 3);
            session.setGamepad(1, GamepadState.NEUTRAL);
            session.runFor(SECOND * 2);

            BodyPose truth = session.world().bodyPose(EntityIds.CHASSIS);
            assertTrue(truth.x() < -0.8, "the robot is on the scoring side now: " + truth.x());
            assertTrue(text(session, "Alliance").contains("scoring CELL"), summary(session) + " " + text(session, "Tags"));
            assertFalse(text(session, "Aim").contains("other side"), "the CELL that faces up opens to this side: " + text(session, "Aim"));
        }
    }
}
