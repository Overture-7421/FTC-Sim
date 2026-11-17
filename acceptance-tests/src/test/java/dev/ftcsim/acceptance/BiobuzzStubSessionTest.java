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
import dev.ftcsim.contracts.session.RobotMapping;
import dev.ftcsim.contracts.session.RobotMapping.MotorMapping;
import dev.ftcsim.contracts.session.RobotMapping.Slot;
import dev.ftcsim.contracts.snapshot.BodyState;
import dev.ftcsim.contracts.world.BodyPose;
import dev.ftcsim.field.EntityIds;
import dev.ftcsim.field.FieldSetup;
import dev.ftcsim.game.biobuzz.BiobuzzField;
import dev.ftcsim.game.biobuzz.BiobuzzIds;
import dev.ftcsim.simulator.SimulationSession;
import dev.ftcsim.simulator.robot.RobotDesign;
import dev.ftcsim.simulator.robot.RobotSpec;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private static final Pattern NUMBER = Pattern.compile("(\\w+)=(-?\\d+(?:\\.\\d+)?)");
    private static final double FOUR_BAR_POD_TICKS_PER_MM = 19.89436789;
    private static final BiobuzzField FIELD = new BiobuzzField();

    /** The four wheels as the stub names them, mounted as a team mounts a mecanum robot (its left side reversed in code). */
    private static RobotSpec robot() {
        return RobotDesign.biobuzz().spec(RobotMapping.empty("BIOBUZZ stub").withMotors(List.of(
                new MotorMapping("frontLeft", Slot.FRONT_LEFT, "", true),
                new MotorMapping("backLeft", Slot.REAR_LEFT, "", true),
                new MotorMapping("frontRight", Slot.FRONT_RIGHT, "", false),
                new MotorMapping("backRight", Slot.REAR_RIGHT, "", false))));
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
        Matcher matcher = NUMBER.matcher(line);
        while (matcher.find()) {
            if (matcher.group(1).equals(key)) {
                return Double.parseDouble(matcher.group(2));
            }
        }
        throw new AssertionError("no " + key + " in " + line);
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
            session.runFor(SECOND / 2);
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
        Drive turn = drive(GamepadState.sticks(0f, 0f, 1f, 0f), SECOND / 5);

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
}
