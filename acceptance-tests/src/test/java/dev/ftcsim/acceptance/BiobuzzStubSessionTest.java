package dev.ftcsim.acceptance;

import static dev.ftcsim.acceptance.SessionTelemetry.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.field.Alliance;
import dev.ftcsim.contracts.field.FieldTag;
import dev.ftcsim.contracts.field.StartPose;
import dev.ftcsim.contracts.robot.GamepadState;
import dev.ftcsim.contracts.robot.RobotPhase;
import dev.ftcsim.contracts.snapshot.BodyKinds;
import dev.ftcsim.contracts.snapshot.BodyState;
import dev.ftcsim.contracts.snapshot.RobotJoint;
import dev.ftcsim.contracts.world.BodyPose;
import dev.ftcsim.field.EntityIds;
import dev.ftcsim.field.FieldSetup;
import dev.ftcsim.game.biobuzz.BiobuzzField;
import dev.ftcsim.scenarios.opmodes.BiobuzzHive;
import dev.ftcsim.simulator.SimulationSession;
import dev.ftcsim.simulator.robot.RobotSpec;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The stand-in program of the BIOBUZZ robot ({@code BiobuzzStubTeleOp}: mecanum drive, Pinpoint, Limelight) on the
 * BIOBUZZ field, written without any knowledge of the simulator. It shows that the robot drives as the sticks say, that
 * the Pinpoint reports what the chassis really did and that the Limelight finds the HIVE tags, which is all the field
 * has for a camera to see. The camera is not asked for a pose: every tag is on a HIVE, which moves, so the camera's map
 * has none.
 */
@Timeout(300)
class BiobuzzStubSessionTest {
    private static final long SECOND = 1_000_000_000L;
    private static final String OPMODE = "BIOBUZZ Stub";
    private static final double FOUR_BAR_POD_TICKS_PER_MM = 19.89436789;
    private static final BiobuzzField FIELD = new BiobuzzField();

    private static RobotSpec robot() {
        return BiobuzzTestRobot.spec();
    }

    private static StartPose redCentre() {
        return FIELD.description().startPoses().stream().filter(pose -> pose.name().equals("RED wall, centre")).findFirst().orElseThrow();
    }

    private static TestSessions.Handle open(double x, double y, double heading) throws IOException {
        return TestSessions.open(robot(), TestSessions.program(), FIELD, new FieldSetup(1, Alliance.RED), x, y, heading);
    }

    /** Init, let the Pinpoint calibrate as a team's init loop does, then Start. */
    private static void initAndStart(SimulationSession session) {
        session.init(OPMODE);
        session.runFor(SECOND);
        assertEquals(RobotPhase.INIT, session.phase(), session.telemetry().toString());
        assertEquals("READY", text(session, "Pinpoint"));
        session.start();
    }

    private static double value(String line, String key) {
        return BiobuzzTestRobot.value(line, key);
    }

    /** What the Pinpoint says, and what the chassis really did, both in the robot's frame at the start. */
    private record Drive(double pinpointX, double pinpointY, double pinpointDegrees, double trueX, double trueY, double trueDegrees,
            double encoderX, double encoderY) {}

    private static Drive drive(GamepadState sticks, long duration) throws IOException {
        StartPose start = redCentre();
        try (TestSessions.Handle handle = open(start.x(), start.y(), start.headingRadians())) {
            SimulationSession session = handle.session;
            initAndStart(session);
            session.setGamepad(1, sticks);
            session.runFor(duration);
            session.setGamepad(1, GamepadState.NEUTRAL);
            // The robot coasts a good way on its SWYFT Spike drive: let it stop before comparing.
            session.runFor(SECOND * 3 / 2);
            assertEquals(RobotPhase.RUNNING, session.phase(), handle.log.toString());
            assertEquals(List.of(), session.problems());

            BodyPose pose = session.world().bodyPose(EntityIds.CHASSIS);
            double dx = pose.x() - start.x();
            double dy = pose.y() - start.y();
            double cos = Math.cos(start.headingRadians());
            double sin = Math.sin(start.headingRadians());
            String reported = text(session, "Pose");
            String encoders = text(session, "Encoders");
            double turned = Math.toDegrees(Math.IEEEremainder(pose.yaw() - start.headingRadians(), 2 * Math.PI));
            return new Drive(
                    value(reported, "x"), value(reported, "y"), value(reported, "h"),
                    (dx * cos + dy * sin) * 1000, (-dx * sin + dy * cos) * 1000, turned,
                    value(encoders, "x"), value(encoders, "y"));
        }
    }

    @Test
    void pushingTheLeftStickForwardDrivesTheRobotAheadAndThePinpointReportsTheSameTravel() throws IOException {
        Drive forward = drive(GamepadState.sticks(0f, -1f, 0f, 0f), SECOND / 2);

        assertTrue(forward.trueX() > 300, "the robot drove ahead: " + forward);
        assertEquals(0.0, forward.trueY(), 100.0, "and stayed on its line: " + forward);
        assertEquals(forward.trueX(), forward.pinpointX(), 15.0, "the Pinpoint's X is the travel ahead: " + forward);
        assertEquals(forward.trueY(), forward.pinpointY(), 15.0, "and its Y the sideways drift: " + forward);
        assertEquals(forward.trueX() * FOUR_BAR_POD_TICKS_PER_MM, forward.encoderX(), 0.03 * forward.trueX() * FOUR_BAR_POD_TICKS_PER_MM,
                "the pod's counts follow the travel at the resolution the code set: " + forward);
    }

    @Test
    void pushingTheLeftStickLeftStrafesTheRobotToItsLeftAndThePinpointCallsThatPositiveY() throws IOException {
        Drive left = drive(GamepadState.sticks(-1f, 0f, 0f, 0f), SECOND / 2);

        assertTrue(left.trueY() > 200, "the robot moved to its left: " + left);
        assertEquals(0.0, left.trueX(), 100.0, left.toString());
        assertEquals(left.trueY(), left.pinpointY(), 15.0, left.toString());
        assertEquals(0.0, left.trueDegrees(), 8.0, "without turning: " + left);
    }

    @Test
    void pushingTheRightStickRightTurnsTheRobotClockwiseAndThePinpointHeadingFollowsIt() throws IOException {
        Drive turn = drive(GamepadState.sticks(0f, 0f, 1f, 0f), SECOND / 4);

        assertTrue(turn.trueDegrees() < -20, "the robot turned clockwise: " + turn);
        assertEquals(turn.trueDegrees(), turn.pinpointDegrees(), 3.0, "and the Pinpoint's heading agrees: " + turn);
    }

    // ---- the Limelight ------------------------------------------------------------------------------------------

    private static double[] rotate(BodyState q, double x, double y, double z) {
        double cx = q.qy() * z - q.qz() * y + q.qw() * x;
        double cy = q.qz() * x - q.qx() * z + q.qw() * y;
        double cz = q.qx() * y - q.qy() * x + q.qw() * z;
        double rx = q.qy() * cz - q.qz() * cy;
        double ry = q.qz() * cx - q.qx() * cz;
        double rz = q.qx() * cy - q.qy() * cx;
        return new double[] {x + 2 * rx, y + 2 * ry, z + 2 * rz};
    }

    private static BodyState localAsBody(FieldTag tag) {
        var local = tag.local();
        return new BodyState(0, 0, 0f, 0f, 0f, (float) local.qx(), (float) local.qy(), (float) local.qz(), (float) local.qw());
    }

    /** A spot {@code distance} metres from the tag, on the floor, looking at it: x, y, heading. */
    private static double[] standingBefore(SimulationSession session, FieldTag tag, double distance) {
        BodyState host = session.snapshot().bodies().stream().filter(body -> body.entityId() == tag.hostEntityId()).findFirst().orElseThrow();
        double[] at = rotate(host, tag.local().x(), tag.local().y(), tag.local().z());
        double[] inHost = rotate(localAsBody(tag), 0, 0, 1);
        double[] normal = rotate(host, inHost[0], inHost[1], inHost[2]);
        double length = Math.hypot(normal[0], normal[1]);
        double x = host.x() + at[0] + normal[0] / length * distance;
        double y = host.y() + at[1] + normal[1] / length * distance;
        return new double[] {x, y, Math.atan2(-normal[1], -normal[0])};
    }

    private static FieldTag tag(int id) {
        return FIELD.description().tags().stream().filter(candidate -> candidate.id() == id).findFirst().orElseThrow();
    }

    /** What the stub shows about the camera after looking for a second from {@code yawOffset} radians left of tag 31. */
    private static String[] look(double yawOffset) throws IOException {
        double[] spot;
        StartPose start = redCentre();
        try (TestSessions.Handle probe = open(start.x(), start.y(), start.headingRadians())) {
            spot = standingBefore(probe.session, tag(31), 1.0);
        }
        try (TestSessions.Handle handle = open(spot[0], spot[1], spot[2] + yawOffset)) {
            SimulationSession session = handle.session;
            initAndStart(session);
            session.runFor(SECOND);
            assertEquals(List.of(), session.problems(), handle.log.toString());
            return new String[] {text(session, "Tags"), text(session, "Target"), text(session, "Botpose tags")};
        }
    }

    @Test
    void facingTheLowCellOfTheRedHiveTheLimelightSeesItsTagsAndTheirBearingFollowsTheTurn() throws IOException {
        String[] ahead = look(0.0);
        String[] turnedLeft = look(Math.toRadians(12));

        assertFalse(ahead[0].equals("none"), "the camera sees tags: " + Arrays.toString(ahead));
        List<Integer> seen = Arrays.stream(ahead[0].split(",")).map(Integer::parseInt).toList();
        assertTrue(seen.contains(31), "the one it looks at: " + ahead[0]);
        assertTrue(seen.stream().allMatch(id -> id >= 30 && id <= 33), "and only its CELL's: " + ahead[0]);
        assertEquals("0", ahead[2], "no botpose: BIOBUZZ has no fixed tags for the camera's map");
        double straight = value(ahead[1], "tx");
        double left = value(turnedLeft[1], "tx");
        assertTrue(left > straight + 6.0, String.format(Locale.ROOT, "turning the robot left moves the tag right in the image: %.1f then %.1f", straight, left));
    }

    // ---- the mechanisms the window draws -------------------------------------------------------------------------

    private static GamepadState pad(float leftTrigger, float rightTrigger, boolean dpadUp, boolean b, boolean x, boolean y) {
        return new GamepadState(
                0f, 0f, 0f, 0f, leftTrigger, rightTrigger, dpadUp, false, false, false, false, b, x, y, false, false, false, false,
                false, false, false);
    }

    /** What a joint of the picture is, as the snapshot says it, in degrees. */
    private static double joint(SimulationSession session, RobotJoint joint) {
        BodyState body = session.snapshot().bodies().stream().filter(candidate -> candidate.kind() == joint.kind()).findFirst().orElseThrow();
        return Math.toDegrees(body.x());
    }

    /** Remembers how far each joint has been from where it started. */
    private static void note(SimulationSession session, double[] atRest, double[] moved) {
        for (RobotJoint each : RobotJoint.values()) {
            moved[each.ordinal()] = Math.max(moved[each.ordinal()], Math.abs(joint(session, each) - atRest[each.ordinal()]));
        }
    }

    @Test
    void everyMovingPartOfThePictureFollowsTheMotorOrServoThatMovesIt() throws IOException {
        StartPose start = redCentre();
        try (TestSessions.Handle handle = open(start.x(), start.y(), start.headingRadians())) {
            SimulationSession session = handle.session;
            initAndStart(session);
            session.runFor(SECOND / 2);
            double[] atRest = new double[RobotJoint.values().length];
            double[] moved = new double[atRest.length];
            for (RobotJoint each : RobotJoint.values()) {
                atRest[each.ordinal()] = joint(session, each);
            }

            assertEquals(1.0, joint(session, RobotJoint.HOOD), 0.2, "the hood stands at the bottom of its range, 1 degree");
            assertEquals(45.0, joint(session, RobotJoint.STOPPER), 0.5, "the stopper is shut (below the 60 degrees at which it lets a ball by)");
            assertEquals(0.0, joint(session, RobotJoint.COLIBRI_LEFT), 0.5);
            double wheel = joint(session, RobotJoint.WHEEL_FRONT_LEFT);

            session.setGamepad(1, GamepadState.sticks(0f, -1f, 0f, 0f));
            session.runFor(SECOND / 2);
            session.setGamepad(1, GamepadState.NEUTRAL);
            note(session, atRest, moved);
            assertTrue(joint(session, RobotJoint.WHEEL_FRONT_LEFT) - wheel > 90, "driving ahead turns the wheels forward");

            session.setGamepad(1, pad(1f, 0f, false, false, false, false));
            session.runFor(SECOND);
            double intake = joint(session, RobotJoint.INTAKE);
            session.runFor(SECOND / 4);
            note(session, atRest, moved);
            assertTrue(joint(session, RobotJoint.INTAKE) - intake > 30, "the left trigger runs the intake roller");

            session.setGamepad(1, pad(0f, 1f, false, true, false, false));
            session.runFor(SECOND);
            double flywheel = joint(session, RobotJoint.FLYWHEEL);
            double conveyor = joint(session, RobotJoint.CONVEYOR);
            session.runFor(SECOND / 4);
            note(session, atRest, moved);
            assertTrue(joint(session, RobotJoint.FLYWHEEL) - flywheel > 100, "the right trigger spins the flywheel");
            assertTrue(joint(session, RobotJoint.CONVEYOR) - conveyor > 30, "and B the indexer");

            session.setGamepad(1, pad(0f, 0f, false, false, true, true));
            session.runFor(SECOND);
            note(session, atRest, moved);
            assertEquals(75.0, joint(session, RobotJoint.STOPPER), 3.0, "X sends the stopper's servo to 0.25, a quarter of 300 degrees: past the 60 at which it lets a ball by");
            assertEquals(0.4 * 300.0 / (40.0 / 30.0), joint(session, RobotJoint.COLIBRI_LEFT), 3.0, "Y raises the arms, 90 degrees");
            assertEquals(joint(session, RobotJoint.COLIBRI_LEFT), joint(session, RobotJoint.COLIBRI_RIGHT), 1.0);

            session.setGamepad(1, pad(0f, 0f, true, false, false, false));
            session.runFor(SECOND);
            note(session, atRest, moved);
            assertTrue(joint(session, RobotJoint.HOOD) > 3.0, "the D-pad's up raises the hood: " + joint(session, RobotJoint.HOOD));

            session.setGamepad(1, turretPad(true, false, false));
            session.runFor(SECOND);
            double yaw = Math.toDegrees(session.rig().turretYawRadians());
            assertEquals(-yaw * 5.5, joint(session, RobotJoint.ENCODER_A), 5.0, "the encoder gear turns 5.5 times to the turret's one, the other way");
            assertEquals(-yaw * 44.0 / 7.0, joint(session, RobotJoint.ENCODER_B), 5.0, "and the other 44/7 times");
            note(session, atRest, moved);
            for (RobotJoint each : RobotJoint.values()) {
                assertTrue(moved[each.ordinal()] > 1.0, each + " moved from where it started: " + moved[each.ordinal()]);
            }
            assertEquals(List.of(), session.problems(), handle.log.toString());
        }
    }

    // ---- the turret ---------------------------------------------------------------------------------------------

    private static GamepadState turretPad(boolean leftBumper, boolean rightBumper, boolean a) {
        return new GamepadState(
                0f, 0f, 0f, 0f, 0f, 0f, false, false, false, false, a, false, false, false, leftBumper, rightBumper,
                false, false, false, false, false);
    }

    private static double turretDegrees(SimulationSession session) {
        return value(text(session, "Turret"), "deg");
    }

    @Test
    void theBumpersTurnTheTurretAndTheTwoAbsoluteEncodersGiveItsAngleToAFractionOfADegreeAllTheWayRound() throws IOException {
        StartPose start = redCentre();
        try (TestSessions.Handle handle = open(start.x(), start.y(), start.headingRadians())) {
            SimulationSession session = handle.session;
            initAndStart(session);
            session.runFor(SECOND / 4);
            assertEquals(0.0, turretDegrees(session), 0.5, "the turret starts pointing forward");

            double worst = 0.0;
            double highest = 0.0;
            double lowest = 0.0;
            StringBuilder trace = new StringBuilder();
            // Left to about 250 degrees, then right to about -165: the range the two encoders tell apart without ambiguity.
            for (int i = 0; i < 24; i++) {
                boolean left = i < 9;
                session.setGamepad(1, turretPad(left, !left, false));
                session.runFor(SECOND / 2);
                session.setGamepad(1, turretPad(false, false, false));
                session.runFor(SECOND / 2);
                double truth = Math.toDegrees(session.rig().turretYawRadians());
                double error = Math.abs(Math.IEEEremainder(turretDegrees(session) - truth, 360.0));
                if (error > 1.0) {
                    trace.append(String.format(Locale.ROOT, " [%d: %s truth %.1f]", i, text(session, "Turret"), truth));
                }
                worst = Math.max(worst, error);
                highest = Math.max(highest, truth);
                lowest = Math.min(lowest, truth);
            }
            assertTrue(highest > 200, "the left bumper turned the turret to the left, past a half turn: " + highest);
            assertTrue(lowest < -150, "and the right bumper back and round to the right: " + lowest);
            assertTrue(worst < 1.0, "the decoded angle was within a degree of the true one throughout: " + worst + trace);

            BodyState drawn = session.snapshot().bodies().stream().filter(body -> body.kind() == BodyKinds.TURRET).findFirst().orElseThrow();
            double drawnYaw = Math.toDegrees(2 * Math.atan2(drawn.qz(), drawn.qw()));
            double chassisYaw = Math.toDegrees(session.world().bodyPose(EntityIds.CHASSIS).yaw());
            double turretYaw = Math.toDegrees(session.rig().turretYawRadians());
            assertEquals(0.0, Math.IEEEremainder(drawnYaw - chassisYaw - turretYaw, 360.0), 0.5, "the window draws the turret turned as it is");

            assertEquals(List.of(), session.problems(), handle.log.toString());
        }
    }

    // ---- the pose estimate and the aim ----------------------------------------------------------------------------

    /** The tags the stub's map has, against the ones the field has at the start of a match, with each HIVE resting on its stop. */
    @Test
    void theStubsMapOfTheHiveTagsIsTheFieldsAtTheStartOfAMatch() throws IOException {
        StartPose start = redCentre();
        try (TestSessions.Handle handle = open(start.x(), start.y(), start.headingRadians())) {
            List<BodyState> bodies = handle.session.snapshot().bodies();
            for (FieldTag tag : FIELD.description().tags()) {
                BodyState host = bodies.stream().filter(body -> body.entityId() == tag.hostEntityId()).findFirst().orElseThrow();
                double[] at = rotate(host, tag.local().x(), tag.local().y(), tag.local().z());
                BiobuzzHive.Tag mapped = BiobuzzHive.TAGS.get(tag.id());
                assertEquals(host.x() + at[0], mapped.x(), 0.005, "tag " + tag.id() + " x");
                assertEquals(host.y() + at[1], mapped.y(), 0.005, "tag " + tag.id() + " y");
                assertEquals(host.z() + at[2], mapped.z(), 0.005, "tag " + tag.id() + " z");
            }
            assertEquals(16, BiobuzzHive.TAGS.size());
        }
    }

    /** x, y, heading in degrees from the stub's "Estimate" line, or null while it says there is none. */
    private static double[] estimate(SimulationSession session) {
        String line = text(session, "Estimate");
        if (line.startsWith("none")) {
            return null;
        }
        return new double[] {value(line, "x"), value(line, "y"), value(line, "h"), value(line, "fixes")};
    }

    private static void assertEstimateIsTrue(SimulationSession session, String when) {
        double[] estimate = estimate(session);
        assertTrue(estimate != null, when + ": the stub has a pose: " + text(session, "Estimate"));
        BodyPose truth = session.world().bodyPose(EntityIds.CHASSIS);
        // The map has the HIVEs as they start; a TIP moves their tags by up to 7 cm.
        assertEquals(truth.x(), estimate[0], 0.05, when + ": x");
        assertEquals(truth.y(), estimate[1], 0.05, when + ": y");
        assertEquals(0.0, Math.IEEEremainder(Math.toDegrees(truth.yaw()) - estimate[2], 360.0), 3.0, when + ": heading");
    }

    @Test
    void theStubFindsWhereItIsOnTheFieldFromTheTagsAndKeepsTrackOfItByTheOdometryWhenItDrivesAway() throws IOException {
        double[] spot;
        StartPose start = redCentre();
        try (TestSessions.Handle probe = open(start.x(), start.y(), start.headingRadians())) {
            spot = standingBefore(probe.session, tag(31), 1.0);
        }
        try (TestSessions.Handle handle = open(spot[0], spot[1], spot[2] + Math.toRadians(10))) {
            SimulationSession session = handle.session;
            initAndStart(session);
            session.runFor(SECOND / 4);
            assertEstimateIsTrue(session, "after a look at the tags");
            assertTrue(estimate(session)[3] >= 1, "the camera made a correction: " + text(session, "Estimate"));

            session.setGamepad(1, GamepadState.sticks(0f, 0.6f, 0f, 0f));
            session.runFor(SECOND);
            session.setGamepad(1, GamepadState.NEUTRAL);
            session.runFor(SECOND / 2);
            assertEstimateIsTrue(session, "after driving back from the HIVE");

            assertEquals(List.of(), session.problems(), handle.log.toString());
        }
    }

    @Test
    void theStubHasNoPoseUntilItSeesTwoTagsTogether() throws IOException {
        StartPose start = redCentre();
        // Facing the wall, the camera sees nothing.
        try (TestSessions.Handle handle = open(start.x(), start.y(), start.headingRadians() + Math.PI)) {
            SimulationSession session = handle.session;
            initAndStart(session);
            session.runFor(SECOND);

            assertEquals("none", text(session, "Tags"), "facing the wall the camera sees no tag");
            assertTrue(text(session, "Estimate").startsWith("none"), text(session, "Estimate"));
        }
    }
}

