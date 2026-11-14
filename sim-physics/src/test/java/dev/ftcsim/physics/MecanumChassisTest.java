package dev.ftcsim.physics;

import static dev.ftcsim.physics.TestWorlds.body;
import static dev.ftcsim.physics.TestWorlds.pitchDegrees;
import static dev.ftcsim.physics.TestWorlds.steps;
import static dev.ftcsim.physics.TestWorlds.worldWithFloor;
import static dev.ftcsim.physics.TestWorlds.yaw;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.snapshot.BodyState;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class MecanumChassisTest {

    private static final MecanumConfig CONFIG = MecanumConfig.generic18Inch();
    private static final int ROBOT = 1;
    private static final double FREE_SPEED = CONFIG.motor().freeSpeedRadPerSec();
    private static final double STEP = 1.0 / PhysicsWorld.STEPS_PER_SECOND;
    private static final int ONE_SECOND = PhysicsWorld.STEPS_PER_SECOND;
    private static final double HALF_LENGTH = 0.5 * CONFIG.lengthM();

    /** Velocity along the chassis axes in m/s and yaw rate in rad/s. */
    private record Twist(double forward, double left, double yawRate) {}

    private static MecanumChassis robotAt(PhysicsWorld world, double x, double y, double yaw) {
        return world.addMecanumChassis(ROBOT, 12, CONFIG, new Vector3(x, y, 0), yaw);
    }

    private static void drive(MecanumChassis robot, double frontLeft, double frontRight, double rearLeft, double rearRight) {
        robot.setWheelCommand(WheelPosition.FRONT_LEFT, frontLeft);
        robot.setWheelCommand(WheelPosition.FRONT_RIGHT, frontRight);
        robot.setWheelCommand(WheelPosition.REAR_LEFT, rearLeft);
        robot.setWheelCommand(WheelPosition.REAR_RIGHT, rearRight);
    }

    /** Advances the world and returns the chassis' average twist over those steps. */
    private static Twist twistOver(PhysicsWorld world, int count) {
        BodyState before = body(world, ROBOT);
        steps(world, count);
        BodyState after = body(world, ROBOT);
        double seconds = count * STEP;
        double turn = Math.IEEEremainder(yaw(after) - yaw(before), 2.0 * Math.PI);
        double heading = yaw(before) + 0.5 * turn;
        double dx = (after.x() - before.x()) / seconds;
        double dy = (after.y() - before.y()) / seconds;
        return new Twist(
                dx * Math.cos(heading) + dy * Math.sin(heading),
                -dx * Math.sin(heading) + dy * Math.cos(heading),
                turn / seconds);
    }

    /** The motion that mecanum wheels turning at these speeds would give a chassis that does not slip. */
    private static Twist kinematics(MecanumChassis robot) {
        double frontLeft = robot.wheelSpeedRadPerSec(WheelPosition.FRONT_LEFT);
        double frontRight = robot.wheelSpeedRadPerSec(WheelPosition.FRONT_RIGHT);
        double rearLeft = robot.wheelSpeedRadPerSec(WheelPosition.REAR_LEFT);
        double rearRight = robot.wheelSpeedRadPerSec(WheelPosition.REAR_RIGHT);
        double radius = CONFIG.wheelRadiusM();
        double lever = 0.5 * (CONFIG.wheelbaseM() + CONFIG.trackM());
        return new Twist(
                radius / 4 * (frontLeft + frontRight + rearLeft + rearRight),
                radius / 4 * (-frontLeft + frontRight + rearLeft - rearRight),
                radius / (4 * lever) * (-frontLeft + frontRight - rearLeft + rearRight));
    }

    private static void addRamp(PhysicsWorld world, double lowEdgeX) {
        double tilt = Math.toRadians(15);
        double length = 0.8;
        double thickness = 0.05;
        Vector3 center = new Vector3(
                lowEdgeX + 0.5 * length * Math.cos(tilt) + thickness * Math.sin(tilt),
                0,
                0.5 * length * Math.sin(tilt) - thickness * Math.cos(tilt));
        world.addStaticBox(center, new Vector3(0.5 * length, 0.3, thickness), Vector3.Y, -tilt);
    }

    @Test
    void fullForwardPowerAcceleratesAndTheWheelsRollWithTheChassis() {
        try (PhysicsWorld world = worldWithFloor()) {
            MecanumChassis robot = robotAt(world, -3, 0, 0);
            drive(robot, 1, 1, 1, 1);
            steps(world, ONE_SECOND / 10);
            Twist launching = twistOver(world, ONE_SECOND / 10);
            assertTrue(launching.forward() > 0.3, "the robot is not accelerating: " + launching);

            steps(world, 2 * ONE_SECOND);
            Twist cruising = twistOver(world, ONE_SECOND / 10);
            assertEquals(kinematics(robot).forward(), cruising.forward(), 0.03);
            assertTrue(cruising.forward() > 2.0, "top speed " + cruising.forward());
            assertEquals(0.0, cruising.left(), 0.02);
            assertEquals(0.0, cruising.yawRate(), 0.02);
            for (WheelPosition wheel : WheelPosition.values()) {
                assertEquals(robot.wheelSpeedRadPerSec(WheelPosition.FRONT_LEFT), robot.wheelSpeedRadPerSec(wheel), 0.2);
            }
        }
    }

    static Stream<Arguments> maneuvers() {
        return Stream.of(
                Arguments.of("strafe left", new double[] {-1, 1, 1, -1}, 0, 1, 0),
                Arguments.of("strafe right", new double[] {1, -1, -1, 1}, 0, -1, 0),
                Arguments.of("rotate counter-clockwise", new double[] {-1, 1, -1, 1}, 0, 0, 1),
                Arguments.of("diagonal forward-left", new double[] {0, 1, 1, 0}, 1, 1, 0));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("maneuvers")
    void wheelSpeedsGiveTheTextbookMecanumMotion(
            String name, double[] commands, int forwardSign, int leftSign, int turnSign) {
        try (PhysicsWorld world = worldWithFloor()) {
            MecanumChassis robot = robotAt(world, 0, 0, 0);
            drive(robot, commands[0], commands[1], commands[2], commands[3]);
            steps(world, ONE_SECOND);
            Twist measured = twistOver(world, ONE_SECOND / 10);
            Twist expected = kinematics(robot);
            assertEquals(expected.forward(), measured.forward(), 0.06);
            assertEquals(expected.left(), measured.left(), 0.06);
            assertEquals(expected.yawRate(), measured.yawRate(), 0.15);
            assertEquals(forwardSign, direction(measured.forward(), 0.3), name + " forward " + measured);
            assertEquals(leftSign, direction(measured.left(), 0.3), name + " left " + measured);
            assertEquals(turnSign, direction(measured.yawRate(), 1.0), name + " turn " + measured);
        }
    }

    private static int direction(double value, double threshold) {
        return Math.abs(value) < threshold ? 0 : (int) Math.signum(value);
    }

    @Test
    void reversingTheCommandBrakesTheRobotAtTheLimitOfTraction() {
        try (PhysicsWorld world = worldWithFloor()) {
            MecanumChassis robot = robotAt(world, -4, 0, 0);
            drive(robot, 1, 1, 1, 1);
            steps(world, ONE_SECOND);
            double before = twistOver(world, 6).forward();
            assertTrue(before > 1.9, "speed before reversing " + before);

            drive(robot, -1, -1, -1, -1);
            steps(world, 6);
            double soon = twistOver(world, 6).forward();
            double deceleration = (before - soon) / (12 * STEP);
            // At most the friction of four wheels, which the motors can far exceed, and not an instant stop.
            assertTrue(deceleration > 3.0 && deceleration < 8.0, "deceleration " + deceleration + " m/s^2");

            steps(world, ONE_SECOND / 2);
            assertTrue(twistOver(world, 6).forward() < 0.1, "still rolling forward");
        }
    }

    @Test
    void pushingAgainstAWallSlipsTheWheelsWhileTheChassisStaysPut() {
        try (PhysicsWorld world = worldWithFloor()) {
            world.addStaticBox(new Vector3(1.2, 0, 0.5), new Vector3(0.05, 3, 0.5));
            MecanumChassis robot = robotAt(world, 0, 0, 0);
            drive(robot, 1, 1, 1, 1);
            steps(world, 2 * ONE_SECOND);

            BodyState pressed = body(world, ROBOT);
            long ticksBefore = robot.encoderTicks(WheelPosition.FRONT_LEFT);
            steps(world, ONE_SECOND);
            BodyState later = body(world, ROBOT);

            assertEquals(1.15 - HALF_LENGTH, later.x(), 0.01, "the chassis rests against the wall");
            assertEquals(pressed.x(), later.x(), 0.001);
            assertEquals(pressed.y(), later.y(), 0.001);
            DcMotor.Thevenin motor = CONFIG.motor().thevenin(1.0, CONFIG.motor().nominalVolts());
            for (WheelPosition wheel : WheelPosition.values()) {
                // A wheel that cannot move the robot settles where the motor torque balances the roller
                // grip, which is capped at friction times load and acts through the wheel radius / sqrt(2).
                double gripTorque = CONFIG.wheelRadiusM() / Math.sqrt(2) * CONFIG.tractionFriction() * robot.wheelNormalForceN(wheel);
                double balance = (motor.zeroSpeedTorqueNm() - gripTorque) / (motor.dampingNmsPerRad() + CONFIG.wheelDampingNmsPerRad());
                assertEquals(balance, robot.wheelSpeedRadPerSec(wheel), 0.03 * balance, wheel + " is not slipping at the friction limit");
            }
            assertTrue(robot.encoderTicks(WheelPosition.FRONT_LEFT) - ticksBefore > 1000, "the encoder stopped counting");
        }
    }

    @Test
    void drivingOverARampPitchesTheChassisAlongTheSlope() {
        try (PhysicsWorld world = worldWithFloor()) {
            addRamp(world, 0.0);
            MecanumChassis robot = robotAt(world, -0.6, 0, 0);
            drive(robot, 1, 1, 1, 1);
            double maxPitch = 0;
            double maxHeight = 0;
            for (int i = 0; i < ONE_SECOND; i++) {
                world.step();
                BodyState state = body(world, ROBOT);
                maxPitch = Math.max(maxPitch, pitchDegrees(state));
                maxHeight = Math.max(maxHeight, state.z());
            }
            assertEquals(15.0, maxPitch, 3.0, "pitch on a 15 degree ramp");
            assertTrue(maxHeight > 0.30, "the chassis climbed to " + maxHeight);
        }
    }

    @Test
    void aWheelOverAPitGivesNoTraction() {
        try (PhysicsWorld world = worldWithPitUnderFrontLeftWheel()) {
            MecanumChassis robot = robotAt(world, 0, 0, 0);
            robot.setWheelCommand(WheelPosition.FRONT_LEFT, 1.0);
            steps(world, ONE_SECOND / 2);

            BodyState state = body(world, ROBOT);
            assertEquals(0.0, robot.wheelNormalForceN(WheelPosition.FRONT_LEFT));
            assertTrue(robot.wheelSpeedRadPerSec(WheelPosition.FRONT_LEFT) > 0.95 * FREE_SPEED, "the lifted wheel is loaded");
            assertEquals(0.0, Math.hypot(state.x(), state.y()), 0.03);
            assertEquals(0.0, Math.toDegrees(yaw(state)), 1.0);
        }
        // The same drive on a wheel that stands on the floor shoves the robot around.
        try (PhysicsWorld world = worldWithPitUnderFrontLeftWheel()) {
            MecanumChassis robot = robotAt(world, 0, 0, 0);
            robot.setWheelCommand(WheelPosition.FRONT_RIGHT, 1.0);
            steps(world, ONE_SECOND / 2);

            BodyState state = body(world, ROBOT);
            assertTrue(Math.hypot(state.x(), state.y()) > 0.1 || Math.abs(Math.toDegrees(yaw(state))) > 10.0, "the wheel has no grip");
        }
    }

    // Two floor slabs leave the quadrant x > 0.05, y > 0.05 empty, which is where the front-left hub of a robot at the origin is.
    private static PhysicsWorld worldWithPitUnderFrontLeftWheel() {
        PhysicsWorld world = TestNatives.newWorld();
        world.addStaticBox(new Vector3(-2.45, 0, -0.5), new Vector3(2.5, 5, 0.5));
        world.addStaticBox(new Vector3(2.55, -2.45, -0.5), new Vector3(2.5, 2.5, 0.5));
        return world;
    }

    @Test
    void aRobotInTheAirGetsNoTractionUntilItLands() {
        try (PhysicsWorld world = worldWithFloor()) {
            MecanumChassis robot = world.addMecanumChassis(ROBOT, 12, CONFIG, new Vector3(0, 0, 0.3), 0);
            drive(robot, 1, 1, 1, 1);
            steps(world, 6);
            // Nothing loads the wheels, so they follow the implicit-Euler spin-up of the motor's torque
            // line against the wheel inertia plus the rotor inertia reflected through the gearbox.
            DcMotor.Thevenin motor = CONFIG.motor().thevenin(1.0, CONFIG.motor().nominalVolts());
            double damping = motor.dampingNmsPerRad() + CONFIG.wheelDampingNmsPerRad();
            double inertia = CONFIG.wheelInertiaKgM2() + CONFIG.motor().reflectedRotorInertiaKgM2();
            double expected = motor.zeroSpeedTorqueNm() / damping * (1.0 - Math.pow(inertia / (inertia + damping * STEP), 6));
            assertEquals(expected, robot.wheelSpeedRadPerSec(WheelPosition.FRONT_LEFT), 1e-4 * expected);

            steps(world, ONE_SECOND / 5 - 6);
            BodyState falling = body(world, ROBOT);
            assertTrue(falling.z() > 0.2, "the robot has already landed");
            assertEquals(0.0, falling.x(), 1e-6);
            for (WheelPosition wheel : WheelPosition.values()) {
                assertEquals(0.0, robot.wheelNormalForceN(wheel));
                assertTrue(robot.wheelSpeedRadPerSec(wheel) > 0.95 * FREE_SPEED, wheel + " is loaded in the air");
            }

            steps(world, ONE_SECOND / 2);
            assertTrue(body(world, ROBOT).x() > 0.1, "the robot did not drive off after landing");
        }
    }

    @Test
    void aWeakBatteryLowersTheTopSpeed() {
        try (PhysicsWorld world = worldWithFloor()) {
            MecanumChassis robot = robotAt(world, -3, 0, 0);
            robot.setBatteryVolts(9.0);
            drive(robot, 1, 1, 1, 1);
            steps(world, 2 * ONE_SECOND);
            double speed = robot.wheelSpeedRadPerSec(WheelPosition.FRONT_LEFT);
            assertTrue(speed > 0.70 * FREE_SPEED && speed < 0.78 * FREE_SPEED, "wheel speed on 9 V: " + speed);
        }
    }
}
