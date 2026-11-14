package dev.ftcsim.physics;

import static dev.ftcsim.physics.TestWorlds.steps;
import static dev.ftcsim.physics.TestWorlds.worldWithFloor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.world.BodyPose;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The two losses that do not depend on speed: friction in the wheels and rolling resistance of the rollers. */
class DriveLossesTest {

    private static final int ROBOT = 1;
    private static final int ONE_SECOND = PhysicsWorld.STEPS_PER_SECOND;
    private static final double[] FORWARD = {1, 1, 1, 1};
    private static final double[] STRAFE_LEFT = {-1, 1, 1, -1};
    private static final double REST_SPEED = 0.01;
    private static final double WHEEL_FRICTION_NM = 0.12;
    private static final double ROLLING_RESISTANCE = 0.053;
    private static final double GRAVITY = 9.81;

    /** The generic robot with its drag replaced by the given losses: no viscous drag in the rollers, and zero for what is not given. */
    private static MecanumConfig config(double wheelDamping, double wheelFriction, double rollingResistance) {
        return DriveLossesScenario.withDrag(
                wheelDamping, wheelFriction, MecanumConfig.generic18Inch().rollerFriction(), 0.0, rollingResistance);
    }

    /** What the robot's mass and the reflected inertia of its four wheels resist a push along the floor with. */
    private static double effectiveMassKg(MecanumConfig config) {
        double radius = config.wheelRadiusM();
        return config.massKg() + 4 * config.wheelSpinInertiaKgM2() / (radius * radius);
    }

    private static MecanumChassis driven(
            PhysicsWorld world, MecanumConfig config, int entityId, double x, double[] pattern, double power) {
        MecanumChassis robot = world.addMecanumChassis(entityId, 12, config, new Vector3(x, 0, 0), 0);
        for (WheelPosition wheel : WheelPosition.values()) {
            robot.setWheelCommand(wheel, power * pattern[wheel.ordinal()]);
        }
        return robot;
    }

    private static void floatMotors(MecanumChassis robot) {
        for (WheelPosition wheel : WheelPosition.values()) {
            robot.wheelPlant(wheel, 1).floatTerminals();
        }
    }

    private static double speed(PhysicsWorld world, int entityId) {
        BodyPose pose = world.bodyPose(entityId);
        return Math.hypot(pose.vx(), pose.vy());
    }

    /** What happened after the motors were let go: the speed then, and when and where the robot came to rest. */
    private record Coast(double speedAtCut, double secondsToRest, double distance) {}

    /**
     * Drives for a second at the commanded power, lets the motors float and follows the robot until it is at rest
     * or {@code maxSeconds} have passed, in which case the time to rest is infinite.
     */
    private static Coast coast(MecanumConfig config, double[] pattern, double power, double maxSeconds) {
        try (PhysicsWorld world = worldWithFloor()) {
            MecanumChassis robot = driven(world, config, ROBOT, -2, pattern, power);
            steps(world, ONE_SECOND);
            BodyPose cut = world.bodyPose(ROBOT);
            floatMotors(robot);
            int coasted = 0;
            while (speed(world, ROBOT) > REST_SPEED && coasted < maxSeconds * ONE_SECOND) {
                world.step();
                coasted++;
            }
            BodyPose rest = world.bodyPose(ROBOT);
            return new Coast(
                    Math.hypot(cut.vx(), cut.vy()),
                    speed(world, ROBOT) > REST_SPEED ? Double.POSITIVE_INFINITY : coasted / (double) ONE_SECOND,
                    Math.hypot(rest.x() - cut.x(), rest.y() - cut.y()));
        }
    }

    static Stream<Arguments> directions() {
        return Stream.of(Arguments.of("forward", FORWARD), Arguments.of("sideways", STRAFE_LEFT));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("directions")
    void wheelFrictionBringsAFloatingRobotToAHaltInFiniteTimeAndDistance(String direction, double[] pattern) {
        MecanumConfig config = config(0, WHEEL_FRICTION_NM, 0);
        Coast coast = coast(config, pattern, 0.5, 5);

        // Four wheels each hold back the robot with the friction torque over the wheel radius.
        double deceleration = 4 * WHEEL_FRICTION_NM / (config.wheelRadiusM() * effectiveMassKg(config));
        double expectedSeconds = coast.speedAtCut() / deceleration;
        double expectedDistance = coast.speedAtCut() * coast.speedAtCut() / (2 * deceleration);
        assertTrue(coast.speedAtCut() > 0.8, "the robot did not get going: " + coast);
        assertEquals(expectedSeconds, coast.secondsToRest(), 0.03 * expectedSeconds, "time to rest " + coast);
        assertEquals(expectedDistance, coast.distance(), 0.03 * expectedDistance, "distance " + coast);
    }

    @Test
    void withoutWheelFrictionAFloatingRobotRollsOnFarLonger() {
        Coast coast = coast(config(5.0e-4, 0, 0), FORWARD, 0.5, 5);

        assertEquals(Double.POSITIVE_INFINITY, coast.secondsToRest(), "stopped after " + coast);
        assertTrue(coast.distance() > 3.0, "rolled only " + coast.distance() + " m");
    }

    @Test
    void aRobotThatHasStoppedStaysWhereItIsWithItsWheelsLocked() {
        try (PhysicsWorld world = worldWithFloor()) {
            MecanumChassis robot = driven(world, config(0, WHEEL_FRICTION_NM, 0), ROBOT, 0, FORWARD, 0.5);
            steps(world, ONE_SECOND);
            floatMotors(robot);
            steps(world, 3 * ONE_SECOND);
            BodyPose stopped = world.bodyPose(ROBOT);
            double[] angles = new double[WheelPosition.values().length];
            for (WheelPosition wheel : WheelPosition.values()) {
                angles[wheel.ordinal()] = robot.wheelAngleRadians(wheel);
            }

            steps(world, 2 * ONE_SECOND);

            BodyPose later = world.bodyPose(ROBOT);
            assertEquals(stopped.x(), later.x(), 1.0e-3);
            assertEquals(stopped.y(), later.y(), 1.0e-3);
            for (WheelPosition wheel : WheelPosition.values()) {
                assertEquals(angles[wheel.ordinal()], robot.wheelAngleRadians(wheel), 1.0e-6, wheel.name());
                assertEquals(0.0, robot.wheelSpeedRadPerSec(wheel), 1.0e-9, wheel.name());
            }
        }
    }

    @Test
    void aMotorTorqueBelowTheWheelFrictionDoesNotTurnTheWheelAndOneAboveDoes() {
        // At 12 V a command of 0.05 is 0.09 N m on the wheel, a command of 0.2 is 0.37 N m, and the friction is 0.12 N m.
        MecanumConfig config = config(0, WHEEL_FRICTION_NM, 0);
        try (PhysicsWorld world = worldWithFloor()) {
            MecanumChassis weak = driven(world, config, ROBOT, -2, FORWARD, 0.05);
            driven(world, config, ROBOT + 1, 2, FORWARD, 0.2);
            steps(world, ONE_SECOND);

            assertEquals(-2.0, world.bodyPose(ROBOT).x(), 1.0e-3);
            assertEquals(0.0, weak.wheelSpeedRadPerSec(WheelPosition.FRONT_LEFT), 1.0e-9);
            assertTrue(speed(world, ROBOT + 1) > 0.2, "speed " + speed(world, ROBOT + 1));
        }
    }

    @Test
    void rollerRollingResistanceBringsASidewaysCoastToAHaltAtTheRateTheResistanceGives() {
        MecanumConfig config = config(0, 0, ROLLING_RESISTANCE);
        Coast coast = coast(config, STRAFE_LEFT, 0.5, 8);

        // Each wheel drags with the share of its load along its free direction and slips across its rollers at
        // sqrt(2) times the sideways speed, so that is what the robot's kinetic energy is spent on.
        double deceleration = Math.sqrt(2) * ROLLING_RESISTANCE * GRAVITY * config.massKg() / effectiveMassKg(config);
        double expectedSeconds = coast.speedAtCut() / deceleration;
        assertTrue(coast.speedAtCut() > 0.8, "the robot did not get going: " + coast);
        assertEquals(expectedSeconds, coast.secondsToRest(), 0.03 * expectedSeconds, "time to rest " + coast);
    }
}
