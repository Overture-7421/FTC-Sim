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
    void holdingTheRightBumperFeedsOutAllFourBallsBecauseTheIntakeRollerMovesTheLastOneAlong() throws IOException {
        try (TestSessions.Handle handle = open(Alliance.RED, 1.4, -1.0, 0.0)) {
            SimulationSession session = handle.session;
            initAndStart(session, Alliance.RED);
            session.runFor(SECOND);
            assertTrue(session.hardware().stream().anyMatch(line -> line.name().equals("storage magazine") && line.value().equals("4 of 4 pieces")),
                    session.hardware().toString());
            session.setGamepad(1, pad(false, true));
            String held = "";
            for (int i = 0; i < 300 && !held.equals("0 of 4 pieces"); i++) {
                session.runFor(SECOND / 10);
                held = session.hardware().stream().filter(line -> line.name().equals("storage magazine")).findFirst().orElseThrow().value();
            }
            assertEquals("0 of 4 pieces", held, "every ball left the robot: " + summary(session));
        }
    }

    @Test
    void theFourBallsLeaveApartAndEveryOneGoesIntoTheCellBecauseTheFeedIsSlowAfterTheFirst() throws IOException {
        try (TestSessions.Handle handle = open(Alliance.RED, 1.4, -1.0, 0.0)) {
            SimulationSession session = handle.session;
            initAndStart(session, Alliance.RED);
            session.runFor(SECOND * 8);
            session.setGamepad(1, pad(false, true));
            double[] aim = dev.ftcsim.scenarios.opmodes.BiobuzzHive.upCell(
                    dev.ftcsim.scenarios.opmodes.BiobuzzHive.Alliance.RED, dev.ftcsim.scenarios.opmodes.BiobuzzHive.Tilt.AUDIENCE_UP).aimPoint();
            var preloads = session.snapshot().bodies().stream()
                    .filter(body -> body.kind() == BiobuzzIds.KIND_POLLEN && !session.world().hasPiece(body.entityId()))
                    .map(BodyState::entityId).toList();
            java.util.Map<Integer, double[]> flights = new java.util.LinkedHashMap<>();
            for (int i = 0; i < 240 * 4; i++) {
                session.runFor(SECOND / 240);
                for (BodyState body : session.snapshot().bodies()) {
                    if (!preloads.contains(body.entityId()) || !session.world().hasPiece(body.entityId())) {
                        continue;
                    }
                    if (!flights.containsKey(body.entityId())) {
                        flights.put(body.entityId(), new double[] {i / 240.0, 1e9});
                    }
                    double[] flight = flights.get(body.entityId());
                    if (flight != null) {
                        flight[1] = Math.min(flight[1], Math.sqrt(Math.pow(body.x() - aim[0], 2) + Math.pow(body.y() - aim[1], 2) + Math.pow(body.z() - aim[2], 2)));
                    }
                }
            }
            assertEquals(4, flights.size(), "all four left");
            double previous = -1.0;
            for (double[] flight : flights.values()) {
                assertTrue(previous < 0.0 || flight[0] - previous > 0.03, "the balls leave apart, not in a line: " + flight[0] + " after " + previous);
                assertTrue(flight[1] < 0.1, "every ball, the last too, goes through the CELL's mouth: " + flight[1] + " m from its middle");
                previous = flight[0];
            }
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
                                && Math.hypot(Math.hypot(body.x() - last.x(), body.y() - last.y()), body.z() - last.z()) > 0.018) {
                            ball = body;
                        }
                    }
                }
                assertTrue(ball != null, "a ball left");
                BodyPose chassis = session.world().bodyPose(EntityIds.CHASSIS);
                double heading = chassis.yaw() + session.rig().turretYawRadians();
                double hood = value(text(session, "Mechanisms"), "hood");
                double theta = Math.toRadians(45.0 + hood * 300.0 / (365.0 / 72.0));
                // From the turret's axis, along the turret: the ball rides round the wheels' rim to where the hood's end lets it go, which is
                // back over the axle for a steep launch (x 0.162 - 0.0737 sin, z 0.2146 + 0.0737 cos); not out in front of the wheels.
                double dx = ball.x() - (chassis.x() + 0.045 * Math.cos(chassis.yaw()));
                double dy = ball.y() - (chassis.y() + 0.045 * Math.sin(chassis.yaw()));
                double ahead = dx * Math.cos(heading) + dy * Math.sin(heading);
                double across = -dx * Math.sin(heading) + dy * Math.cos(heading);
                double expectedAhead = 0.162 - 0.0737 * Math.sin(theta) - 0.045;
                double expectedHeight = 0.2146 + 0.0737 * Math.cos(theta);
                // The ball has had up to a physics step (4 ms, about 2.5 cm of flight) since it was let go.
                assertEquals(expectedAhead, ahead, 0.04, "ahead of the axis at " + Math.toDegrees(theta) + " degrees");
                assertEquals(expectedHeight, ball.z() - chassis.z(), 0.04, "above the chassis' centre");
                assertEquals(0.0, across, 0.03, "on the turret's line");
                // The Limelight's box is on its 45 degree panel at (0.217, 0.0035, 0.256) of the robot: the ball is not there.
                double limelightAhead = 0.217 - 0.045;
                double fromLimelight = Math.hypot(ahead - limelightAhead, ball.z() - chassis.z() - 0.256);
                assertTrue(fromLimelight > 0.08, "the ball starts " + fromLimelight + " m from the Limelight");
            }
        }
    }

    /**
     * Drives with the sticks to {@code (wx, wy)} and turns to face {@code headingDegrees}, as a red driver does, from the robot's true
     * pose: the left stick is the field as seen from the red wall (up is +Y, right is +X), whichever way the robot faces.
     */
    private static void driveTo(SimulationSession session, double wx, double wy, double headingDegrees) {
        for (int i = 0; i < 400; i++) {
            BodyPose t = session.world().bodyPose(EntityIds.CHASSIS);
            double ex = wx - t.x();
            double ey = wy - t.y();
            double turn = Math.IEEEremainder(Math.toRadians(headingDegrees) - t.yaw(), 2.0 * Math.PI);
            double distance = Math.hypot(ex, ey);
            if (distance < 0.08 && Math.abs(turn) < 0.05) {
                break;
            }
            double gain = Math.min(0.8, 1.5 * distance) / Math.max(distance, 1e-6);
            session.setGamepad(1, GamepadState.sticks(
                    (float) (ex * gain), (float) (-ey * gain), (float) Math.max(-0.6, Math.min(0.6, -1.5 * turn)), 0f));
            session.runFor(SECOND / 10);
        }
        session.setGamepad(1, GamepadState.NEUTRAL);
        session.runFor(SECOND);
    }

    @ParameterizedTest(name = "{0}, turned {1} seconds")
    @MethodSource("alliancesAndTurns")
    void theLeftStickDrivesTheFieldAsTheDriverSeesItWhicheverWayTheRobotFaces(Alliance alliance, double turnSeconds) throws IOException {
        double wall = alliance == Alliance.RED ? -1.0 : 1.0;
        // On the driver's wall, facing the field as the start rules say, so that the camera sees nothing and the pose stays the odometry's.
        try (TestSessions.Handle handle = TestSessions.open(BiobuzzTestRobot.spec(), TestSessions.program(), FIELD, new FieldSetup(1, alliance),
                0.0, wall * 1.5575, -wall * Math.PI / 2)) {
            SimulationSession session = handle.session;
            initAndStart(session, alliance);
            session.runFor(SECOND / 2);
            double facing = session.world().bodyPose(EntityIds.CHASSIS).yaw();
            if (turnSeconds != 0.0) {
                session.setGamepad(1, GamepadState.sticks(0f, 0f, (float) Math.signum(turnSeconds), 0f));
                session.runFor((long) (Math.abs(turnSeconds) * SECOND));
                session.setGamepad(1, GamepadState.NEUTRAL);
                session.runFor(SECOND);
            }
            BodyPose start = session.world().bodyPose(EntityIds.CHASSIS);
            assertTrue(turnSeconds == 0.0 || Math.abs(Math.IEEEremainder(start.yaw() - facing, 2.0 * Math.PI)) > 0.3, "the robot has turned: " + start);
            // Forward on the stick is away from the driver, to the far wall, even with the robot facing any way.
            session.setGamepad(1, GamepadState.sticks(0f, -1f, 0f, 0f));
            session.runFor(SECOND * 3 / 4);
            BodyPose moved = session.world().bodyPose(EntityIds.CHASSIS);
            session.setGamepad(1, GamepadState.NEUTRAL);
            double toward = -wall;
            double ahead = (moved.y() - start.y()) * toward;
            assertTrue(ahead > 0.15, alliance + " forward moves away from its wall: " + start + " -> " + moved);
            // A mecanum robot drifts a little while it speeds up; what it must not do is go sideways.
            assertTrue(Math.abs(moved.x() - start.x()) < 0.4 * ahead, "and not sideways: " + start + " -> " + moved);
            // Right on the stick is the driver's right: +X for the red driver, -X for the blue.
            session.runFor(SECOND);
            BodyPose second = session.world().bodyPose(EntityIds.CHASSIS);
            session.setGamepad(1, GamepadState.sticks(1f, 0f, 0f, 0f));
            session.runFor(SECOND * 3 / 4);
            BodyPose right = session.world().bodyPose(EntityIds.CHASSIS);
            session.setGamepad(1, GamepadState.NEUTRAL);
            double across = (right.x() - second.x()) * toward;
            assertTrue(across > 0.15, alliance + " right moves to the driver's right: " + second + " -> " + right);
            assertTrue(Math.abs(right.y() - second.y()) < 0.4 * across, "and not forward: " + second + " -> " + right);
        }
    }

    private static Stream<Arguments> alliancesAndTurns() {
        return Stream.of(
                Arguments.of(Alliance.RED, 0.0), Arguments.of(Alliance.RED, 0.8), Arguments.of(Alliance.RED, -1.5), Arguments.of(Alliance.RED, 1.2),
                Arguments.of(Alliance.BLUE, 0.0), Arguments.of(Alliance.BLUE, 0.8), Arguments.of(Alliance.BLUE, -1.5));
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
                assertTrue(value(text(session, "Mechanisms"), "flywheels") > 800 || i < 40, "the flywheels idle, they are not off: " + summary(session));
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
    void theFirstBallLeavesSoonAfterTheButtonGoesDownBecauseTheStopperOpensWhileTheFlywheelsSpinUp() throws IOException {
        try (TestSessions.Handle handle = open(Alliance.RED, 1.4, -1.0, 0.0)) {
            SimulationSession session = handle.session;
            initAndStart(session, Alliance.RED);
            // With the hood and the turret where the shot wants them, as they are a few seconds after the start.
            session.runFor(SECOND * 8);
            session.setGamepad(1, pad(false, true));
            java.util.Map<Integer, BodyState> before = new java.util.HashMap<>();
            double seconds = -1.0;
            for (int i = 0; i < 240 * 10 && seconds < 0.0; i++) {
                session.runFor(SECOND / 240);
                for (BodyState body : session.snapshot().bodies()) {
                    BodyState last = before.put(body.entityId(), body);
                    if (seconds < 0.0 && last != null && body.kind() == BiobuzzIds.KIND_POLLEN
                            && Math.hypot(Math.hypot(body.x() - last.x(), body.y() - last.y()), body.z() - last.z()) > 0.018) {
                        seconds = i / 240.0;
                    }
                }
            }
            assertTrue(seconds > 0.0, "a ball left: " + summary(session));
            assertTrue(seconds < 0.9, "the first ball leaves " + seconds + " s after the press: " + summary(session));
        }
    }

    @Test
    void theIntakeAndTheIndexerStopAtOnceWhenTheButtonIsLetGoBecauseTheyBrake() throws IOException {
        try (TestSessions.Handle handle = open(Alliance.RED, 1.4, -1.0, 0.0)) {
            SimulationSession session = handle.session;
            initAndStart(session, Alliance.RED);
            session.runFor(SECOND);
            session.setGamepad(1, pad(true, false));
            session.runFor(SECOND);
            double running = Math.abs(value(hardware(session, "intake"), "rate"));
            assertTrue(running > 100.0, "the intake runs while the left bumper is held: " + running);
            session.setGamepad(1, GamepadState.NEUTRAL);
            StringBuilder decay = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                session.runFor(SECOND / 20);
                decay.append(String.format(" %.0f", Math.abs(value(hardware(session, "intake"), "rate"))));
            }
            double after = Math.abs(value(hardware(session, "intake"), "rate"));
            assertTrue(after < 0.1 * running, "a fifth of a second after letting go it has all but stopped (every 50 ms:" + decay + " of " + running + ")");
        }
    }

    /** What the hardware readout says of the motor {@code name}, as {@code "<number> ticks/s ..."}. */
    private static String hardware(SimulationSession session, String name) {
        String value = session.hardware().stream().filter(line -> line.name().equals(name)).findFirst().orElseThrow().value();
        return "rate=" + value.split(" ")[0];
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
            assertTrue(session.hardware().stream().anyMatch(line -> line.name().equals("storage magazine") && line.value().equals("4 of 4 pieces")),
                    "the robot starts with its four preloads, and holds four: " + session.hardware());

            // The hood's servo takes seconds to get anywhere.
            session.runFor(SECOND * 6);
            assertTrue(value(text(session, "Mechanisms"), "hood") > 0.03,
                    "the hood is already where the shot wants it, with no button held: " + summary(session));
            assertTrue(text(session, "Shooter").startsWith("IDLE"), summary(session));
            assertTrue(value(text(session, "Mechanisms"), "flywheels") > 800 && text(session, "Mechanisms").contains("stopper=shut"),
                    "the flywheels already run, with no button held: " + summary(session));
            BodyPose truth = session.world().bodyPose(EntityIds.CHASSIS);
            double axisX = truth.x() + 0.045 * Math.cos(truth.yaw());
            double axisY = truth.y() + 0.045 * Math.sin(truth.yaw());
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
            assertEquals(flywheels, value(text(session, "Mechanisms"), "flywheels"), 0.1 * flywheels, "the flywheels are not let go of: " + summary(session));
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
            double fastest = 0.0;
            double previousYaw = 0.0;
            // Two turns and a half of the robot, clockwise, which carries the HIVE round the turret the other way.
            session.setGamepad(1, GamepadState.sticks(0f, 0f, 1f, 0f));
            for (int i = 0; i < 60; i++) {
                session.runFor(SECOND / 4);
                double yaw = Math.toDegrees(session.rig().turretYawRadians());
                fastest = Math.max(fastest, Math.abs(yaw - previousYaw) * 4.0);
                previousYaw = yaw;
                least = Math.min(least, yaw);
                most = Math.max(most, yaw);
                // Not just the same direction: past half a turn the reading must say 200, not -160, or the controller winds on.
                if (yaw > -160.0 && yaw < 245.0) {
                    assertEquals(yaw, value(text(session, "Turret"), "deg"), 45.0, "the turret's angle as the program reads it");
                }
            }
            session.setGamepad(1, GamepadState.NEUTRAL);
            session.runFor(SECOND * 8);

            assertTrue(least > -176.0 && most < 256.0, "the turret stayed in its range, which the two encoders can read: " + least + " to " + most);
            assertTrue(fastest > 120.0, "and it was let turn at near what its servo can do (about 510 degrees a second), not a share of it: " + fastest);
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
