package dev.ftcsim.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.field.Alliance;
import dev.ftcsim.contracts.robot.GamepadState;
import dev.ftcsim.contracts.session.RobotMapping;
import dev.ftcsim.contracts.session.RobotMapping.MotorMapping;
import dev.ftcsim.contracts.session.RobotMapping.Slot;
import dev.ftcsim.contracts.snapshot.BodyState;
import dev.ftcsim.contracts.world.BodyPose;
import dev.ftcsim.contracts.world.Vec3;
import dev.ftcsim.field.FieldSetup;
import dev.ftcsim.game.decode.DecodeField;
import dev.ftcsim.simulator.SimulationSession;
import dev.ftcsim.simulator.robot.RobotDesign;
import dev.ftcsim.simulator.robot.RobotSpec;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A team's intake, shooter and turret driving simulated mechanisms: the SDK, the hubs, the shaft plants and the zones that
 * take pieces in, carry them and launch them, with nothing in the robot program that knows about any of it. The BIOBUZZ
 * robot's turret has no real team program to be checked with (Hornet's is DECODE's), so the fixture OpMode here, a
 * position loop on a turret motor, is its only validation.
 */
@Timeout(180)
class MechanismsSessionTest {
    private static final long SECOND = 1_000_000_000L;
    // Piece kinds of the DECODE field: purple and green artifacts.
    private static final int PURPLE = 101;
    private static final int GREEN = 102;
    /** The 13.7:1 of a 435 rpm Yellow Jacket, which these rigs have always had on the rollers. */
    private static final double GEARED = 13.7;
    private static final double TURRET_RATIO = 3.0;
    private static final double SERVO_TURRET_RATIO = 2.75;

    /** Hornet's robot but for the names its OpMode of the fixture uses: four wheels, the roller, the belt and two flywheels. */
    private static final RobotMapping MAPPING = RobotMapping.empty("mechanism test rig").withMotors(List.of(
            new MotorMapping("frontLeft", Slot.FRONT_LEFT, "", true),
            new MotorMapping("backLeft", Slot.REAR_LEFT, "", true),
            new MotorMapping("frontRight", Slot.FRONT_RIGHT, "", false),
            new MotorMapping("backRight", Slot.REAR_RIGHT, "", false),
            new MotorMapping("intake", Slot.INTAKE, "", false, GEARED),
            new MotorMapping("up", Slot.CONVEYOR, "", false, GEARED),
            new MotorMapping("shooterLeft", Slot.SHOOTER, "", false),
            new MotorMapping("shooterRight", Slot.SHOOTER, "", false)));

    private static GamepadState pad(float rightTrigger, boolean a) {
        return new GamepadState(
                0f, 0f, 0f, 0f, 0f, rightTrigger, false, false, false, false, a, false, false, false, false, false, false, false,
                false, false, false);
    }

    private static boolean inWorld(SimulationSession session, int id) {
        return session.world().hasPiece(id);
    }

    @Test
    void thePiecesInFrontOfTheIntakeAreTakenInThenCarriedThenShotWhenTheFlywheelsAreUpToSpeed() throws IOException {
        RobotSpec robot = RobotDesign.decode().spec(MAPPING);
        try (TestSessions.Handle handle = TestSessions.open(robot, new TestField(), 0, 0, 0)) {
            SimulationSession session = handle.session;
            session.init("Intake Shooter");
            session.start();

            session.setGamepad(1, pad(1f, false));
            session.runFor(6 * SECOND);

            for (int id : TestField.ON_FLOOR) {
                assertFalse(inWorld(session, id), "piece " + id + " should be inside the robot");
            }
            List<BodyState> carried = session.snapshot().bodies().stream().filter(b -> TestField.ON_FLOOR.contains(b.entityId())).toList();
            assertEquals(3, carried.size(), "carried pieces are part of what a viewer sees");
            assertEquals(List.of(), session.problems(), handle.log.toString());

            session.setGamepad(1, pad(0f, true));
            Set<Integer> shot = new HashSet<>();
            for (int i = 0; i < 300; i++) {
                session.runFor(SECOND / 100);
                TestField.ON_FLOOR.stream().filter(id -> inWorld(session, id)).forEach(shot::add);
            }

            // The flywheels are still spinning up when the first pieces go, so these may drop in front of the intake, which
            // is still running, and be taken in again: what the test holds is that every piece was shot out.
            assertEquals(Set.copyOf(TestField.ON_FLOOR), shot, "every piece should have been shot out at some time");
            assertEquals(List.of(), session.problems());
        }
    }

    @Test
    void aSensorOnTheRobotSeesAPieceTheRobotCarriesThoughItIsNotInThePhysicsWorld() throws IOException {
        RobotSpec robot = RobotDesign.decode().spec(MAPPING);
        try (TestSessions.Handle handle = TestSessions.open(robot, new TestField(), 0, 0, 0)) {
            SimulationSession session = handle.session;
            session.init("Intake Shooter");
            session.start();
            session.setGamepad(1, pad(1f, false));
            session.runFor(6 * SECOND);
            session.setGamepad(1, GamepadState.NEUTRAL);
            session.runFor(SECOND / 2);

            // The piece that was taken in first waits at the end of the path, 0.26 m above the middle of the chassis's box.
            double endHeight = robot.chassis().restBoxCenterHeightM() + 0.26;
            double from = 0.9;
            var hit = session.rays().cast(new Vec3(-0.12, 0.0, from), new Vec3(0, 0, -1), 1.0);

            assertNotNull(hit, "the carried piece should be visible to a ray");
            assertEquals(from - endHeight - TestField.RADIUS, hit.distanceMeters(), 0.02);
            assertEquals(TestField.ON_FLOOR.get(0), hit.entityId(), "the piece that was taken in first is the one at the end");
            assertEquals(TestField.PIECE_KIND, hit.kind());
            assertEquals(0x7e57c2, hit.rgb());
        }
    }

    @Test
    void thePiecesTheFieldHoldsInReserveCanBeLoadedIntoTheRobotAsAPreload() throws IOException {
        RobotSpec robot = RobotDesign.decode().spec(MAPPING);
        try (TestSessions.Handle handle = TestSessions.open(robot, new TestField(), 0, 0, 0)) {
            assertEquals(2, handle.session.preload("magazine", TestField.PIECE_KIND, 5), "the reserve only holds two");

            handle.session.runFor(SECOND / 100);
            assertEquals(2, handle.session.snapshot().bodies().stream().filter(b -> TestField.IN_RESERVE.contains(b.entityId())).count());
        }
    }

    @Test
    void aSessionOnTheRealFieldsStartsWithThreeArtifactsInTheRobotAndAWorldResetLoadsThemAgain() throws IOException {
        RobotSpec robot = RobotDesign.decode().spec(MAPPING);
        try (TestSessions.Handle handle = TestSessions.open(
                robot, TestSessions.program(), new DecodeField(), new FieldSetup(1, Alliance.BLUE), 0, 0, 0)) {
            SimulationSession session = handle.session;
            assertEquals(0, session.preload("magazine", PURPLE, 1), "the magazine already holds its three");
            assertEquals(0, session.preload("magazine", GREEN, 1));

            session.resetWorld();

            assertEquals(0, session.preload("magazine", PURPLE, 1), "the reset world holds three again");
        }
    }

    /** The robot of the BIOBUZZ game with the turret the fixture OpMode holds at an angle of the left stick's choosing; it has no wheels. */
    private static RobotSpec turretRobot(boolean mirrored) {
        List<MotorMapping> motors = new ArrayList<>(MAPPING.motors().subList(5, 8));
        motors.add(new MotorMapping("turret", Slot.TURRET, "", mirrored));
        RobotMapping geared = new RobotMapping(
                "turret test rig", motors, MAPPING.massKg(), MAPPING.wheelDiameterMm(), MAPPING.driveMotor(), TURRET_RATIO,
                MAPPING.hubLogo(), MAPPING.hubUsb(), MAPPING.camera());
        return RobotDesign.biobuzz().spec(geared);
    }

    /**
     * Holds the turret at {@code stickX * 90} degrees with the OpMode's position loop, spins the flywheels, feeds a preloaded
     * piece and returns the heading, in degrees from the field's +X, of the way it leaves.
     */
    private static double launchHeadingDegrees(RobotSpec robot, float stickX) throws IOException {
        try (TestSessions.Handle handle = TestSessions.open(robot, new TestField(), 0, 0, 0)) {
            SimulationSession session = handle.session;
            assertEquals(1, session.preload("magazine", TestField.PIECE_KIND, 1));
            int id = TestField.IN_RESERVE.get(0);
            session.init("Turret Aim");
            session.start();
            session.setGamepad(1, new GamepadState(
                    stickX, 0f, 0f, 0f, 0f, 1f, false, false, false, false, false, false, false, false, false, false, false, false,
                    false, false, false));
            session.runFor(4 * SECOND);
            assertFalse(inWorld(session, id), "the piece waits in the robot until the belt feeds the flywheels");

            session.setGamepad(1, new GamepadState(
                    stickX, 0f, 0f, 0f, 0f, 1f, false, false, false, false, true, false, false, false, false, false, false, false,
                    false, false, false));
            for (int i = 0; i < 300 && !inWorld(session, id); i++) {
                session.runFor(SECOND / 100);
            }
            assertTrue(inWorld(session, id), "the piece was launched: " + handle.log);
            BodyPose piece = session.world().bodyPose(id);
            assertTrue(piece.velocity().length() > 1.0, "the flywheels were up to speed: " + piece.velocity());
            assertEquals(List.of(), session.problems(), handle.log.toString());
            return Math.toDegrees(Math.atan2(piece.vy(), piece.vx()));
        }
    }

    @Test
    void aBiobuzzPieceLeavesAlongTheHeadingTheTurretWasHeldAtByTheTeamsPositionLoop() throws IOException {
        RobotSpec robot = turretRobot(false);

        assertEquals(0.0, launchHeadingDegrees(robot, 0f), 3.0, "a turret at its starting angle shoots straight ahead");
        assertEquals(45.0, launchHeadingDegrees(robot, 0.5f), 3.0, "positive turns the turret to the left");
        assertEquals(-90.0, launchHeadingDegrees(robot, -1f), 3.0, "and negative to the right");
    }

    @Test
    void aMirroredTurretMotorTurnsTheTurretTheOtherWayRoundThanItsEncoderCounts() throws IOException {
        // The loop takes the encoder to +45 degrees of turret; a motor mounted the other way round puts the turret at -45.
        RobotSpec mirrored = turretRobot(true);

        assertEquals(-45.0, launchHeadingDegrees(mirrored, 0.5f), 3.0);
    }

    /** The robot of the BIOBUZZ game whose turret a continuous-rotation servo turns, 2.75 servo turns to one of the turret's. */
    private static RobotSpec servoTurretRobot(boolean mirrored) {
        List<MotorMapping> motors = new ArrayList<>(MAPPING.motors().subList(5, 8));
        RobotMapping geared = new RobotMapping(
                "servo turret rig", motors, MAPPING.massKg(), MAPPING.wheelDiameterMm(), MAPPING.driveMotor(), SERVO_TURRET_RATIO,
                MAPPING.hubLogo(), MAPPING.hubUsb(), MAPPING.camera(),
                new RobotMapping.TurretParts(new RobotMapping.ServoMapping("turretServo", "", mirrored), List.of(), 0, 0, false));
        return RobotDesign.biobuzz().spec(geared);
    }

    /** Turns the turret with the left stick pushed {@code stickX} for a second, then shoots; the heading the piece leaves along and the turret's yaw, in degrees. */
    private static double[] servoLaunchHeadingDegrees(RobotSpec robot, float stickX) throws IOException {
        try (TestSessions.Handle handle = TestSessions.open(robot, new TestField(), 0, 0, 0)) {
            SimulationSession session = handle.session;
            assertEquals(1, session.preload("magazine", TestField.PIECE_KIND, 1));
            int id = TestField.IN_RESERVE.get(0);
            session.init("Turret Servo");
            session.start();
            session.setGamepad(1, new GamepadState(
                    stickX, 0f, 0f, 0f, 0f, 1f, false, false, false, false, false, false, false, false, false, false, false, false,
                    false, false, false));
            session.runFor(SECOND);
            session.setGamepad(1, pad(1f, false));
            session.runFor(3 * SECOND);
            assertFalse(inWorld(session, id), "the piece waits in the robot until the belt feeds the flywheels");
            double yaw = Math.toDegrees(session.rig().turretYawRadians());

            session.setGamepad(1, pad(1f, true));
            for (int i = 0; i < 300 && !inWorld(session, id); i++) {
                session.runFor(SECOND / 100);
            }
            assertTrue(inWorld(session, id), "the piece was launched: " + handle.log);
            BodyPose piece = session.world().bodyPose(id);
            assertTrue(piece.velocity().length() > 1.0, "the flywheels were up to speed: " + piece.velocity());
            assertEquals(List.of(), session.problems(), handle.log.toString());
            return new double[] {
                Math.toDegrees(Math.atan2(piece.vy(), piece.vx())), yaw, piece.x(), piece.y(), piece.z(),
                session.rig().chassisView().sample().position().z()};
        }
    }

    @Test
    void aShotLeavesFromTheOpeningInFrontOfTheFlywheelsAndTheOpeningTurnsWithTheTurret() throws IOException {
        for (float stick : new float[] {0f, 0.5f, -0.5f}) {
            double[] shot = servoLaunchHeadingDegrees(servoTurretRobot(false), stick);
            double yaw = Math.toRadians(shot[1]);
            // The opening is 0.20 m ahead of the robot's centre and 0.225 m above its middle, turned about the turret's axis 0.089 m ahead.
            double expectedX = 0.089 + (0.20 - 0.089) * Math.cos(yaw);
            double expectedY = (0.20 - 0.089) * Math.sin(yaw);
            assertEquals(expectedX, shot[2], 0.08, "x, turret at " + shot[1] + " degrees");
            assertEquals(expectedY, shot[3], 0.08, "y, turret at " + shot[1] + " degrees");
            assertEquals(shot[5] + 0.225, shot[4], 0.08, "the opening is above the robot, not inside it");
        }
    }

    /** The BIOBUZZ robot with a hood and a stopper, each on a positional servo, and no turret. */
    private static RobotSpec hoodRobot() {
        List<MotorMapping> motors = new ArrayList<>(MAPPING.motors().subList(5, 8));
        RobotMapping.TurretParts parts = new RobotMapping.TurretParts(
                null, List.of(), 0, 0, false,
                List.of(
                        new RobotMapping.MechanismServo(RobotMapping.ServoRole.HOOD, new RobotMapping.ServoMapping("hoodServo", "", false)),
                        new RobotMapping.MechanismServo(RobotMapping.ServoRole.STOPPER, new RobotMapping.ServoMapping("stopperServo", "", false))));
        RobotMapping mapping = new RobotMapping(
                "hood rig", motors, MAPPING.massKg(), MAPPING.wheelDiameterMm(), MAPPING.driveMotor(), 1.0, MAPPING.hubLogo(), MAPPING.hubUsb(),
                MAPPING.camera(), parts);
        return RobotDesign.biobuzz().spec(mapping);
    }

    private static GamepadState hoodPad(float stickX, boolean flywheels, boolean conveyor, boolean open) {
        return new GamepadState(
                stickX, 0f, 0f, 0f, 0f, flywheels ? 1f : 0f, false, false, false, false, conveyor, open, false, false, false, false, false,
                false, false, false, false);
    }

    /** Spins up the flywheels, runs the belt for 3 seconds and reports whether the piece left, and how steeply when it did. */
    private static double[] hoodShot(float stickX, boolean stopperOpen) throws IOException {
        try (TestSessions.Handle handle = TestSessions.open(hoodRobot(), new TestField(), 0, 0, 0)) {
            SimulationSession session = handle.session;
            assertEquals(1, session.preload("magazine", TestField.PIECE_KIND, 1));
            int id = TestField.IN_RESERVE.get(0);
            session.init("Hood Stopper");
            session.start();
            session.setGamepad(1, hoodPad(stickX, true, false, stopperOpen));
            session.runFor(3 * SECOND);
            session.setGamepad(1, hoodPad(stickX, true, true, stopperOpen));
            for (int i = 0; i < 300 && !inWorld(session, id); i++) {
                session.runFor(SECOND / 100);
            }
            assertEquals(List.of(), session.problems(), handle.log.toString());
            if (!inWorld(session, id)) {
                return new double[] {0.0, 0.0};
            }
            BodyPose piece = session.world().bodyPose(id);
            return new double[] {1.0, Math.toDegrees(Math.atan2(piece.vz(), Math.hypot(piece.vx(), piece.vy())))};
        }
    }

    @Test
    void theStopperHoldsThePieceUntilItsServoOpensAndTheHoodSetsHowSteeplyItLeaves() throws IOException {
        double[] closed = hoodShot(-1f, false);
        double[] low = hoodShot(-1f, true);
        double[] mid = hoodShot(-0.6f, true);
        double[] high = hoodShot(1f, true);

        assertEquals(0.0, closed[0], "the belt cannot push the piece out past a closed stopper");
        assertEquals(1.0, low[0], "and it leaves once the stopper is open");
        assertEquals(1.0, high[0]);
        assertEquals(45.0, low[1], 3.0, "the hood at its lowest, 1 degree, sends it up at 45 degrees (a little less by the time it is looked at)");
        // The hood's servo has five turns: position 0.2 is 360 degrees of servo, 11.8 degrees of hood over the 30.4 to 1 gears.
        assertEquals(45.0 + 360.0 / (730.0 / 24.0), mid[1], 3.0, "position 0.2 raises the hood 11.8 degrees");
        assertEquals(69.0, high[1], 3.0, "and the servo's travel is more than the hood's: its stop at 25 degrees holds it, 24 degrees up");
    }

    @Test
    void aBiobuzzPieceLeavesAlongTheHeadingAContinuousRotationServoTurnedTheTurretTo() throws IOException {
        // Stick 0.25 is a pulse of 1725 us: 0.225 of the Axon Mini+'s 9.8 rad/s, for about a second, over 2.75 turns of servo.
        double nominal = Math.toDegrees(0.25 * 0.9 * 9.82 / SERVO_TURRET_RATIO);

        double[] left = servoLaunchHeadingDegrees(servoTurretRobot(false), 0.25f);
        double[] right = servoLaunchHeadingDegrees(servoTurretRobot(true), 0.25f);

        assertEquals(left[1], left[0], 3.0, "the piece leaves along the turret's yaw");
        assertEquals(nominal, left[1], 0.12 * nominal, "positive power turns the turret to the left about as far as the servo's speed says");
        assertEquals(-left[1], right[1], 0.5, "a mirrored servo turns it as far to the right");
        assertEquals(right[1], right[0], 3.0);
    }
}
