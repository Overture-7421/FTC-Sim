package dev.ftcsim.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.ftcsim.contracts.world.BodyPose;
import dev.ftcsim.field.EntityIds;
import dev.ftcsim.physics.MecanumChassis;
import dev.ftcsim.physics.PhysicsWorld;
import dev.ftcsim.physics.Vector3;
import dev.ftcsim.physics.WheelPosition;
import dev.ftcsim.simulator.RobotRig;
import dev.ftcsim.simulator.robot.RobotSpec;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The fixed DECODE robot mapped as Hornet against the four constants that Hornet's team measured on the real robot with Pedro
 * Pathing's own tuners (Hornet's {@code Constants}): the top speed forward and sideways at full power, and the
 * deceleration of the coasting robot, forward and sideways. The tuners themselves run in
 * {@link HornetBringupTest#pedrosOwnTunersMeasureTheSimulatedDrivetrain}, which needs Hornet's package; here the same
 * measurements are taken straight from the chassis, with the exact speed instead of what the odometry reports.
 */
class HornetDrivetrainCalibrationTest {

    private static final double INCH = 0.0254;
    private static final double BATTERY_VOLTS = 12.6;
    private static final double TOLERANCE = 0.05;
    private static final double RUN_INCHES = 48;
    private static final double COAST_FROM_INCHES_PER_SECOND = 30;
    private static final double COAST_TO_INCHES_PER_SECOND = 0.1;

    // What Pedro's tuners measured on the real robot: Hornet's Constants, in inches and seconds.
    private static final double X_VELOCITY = 76.84;
    private static final double Y_VELOCITY = 58.91;
    private static final double FORWARD_ZERO_POWER_ACCELERATION = -32.88;
    private static final double LATERAL_ZERO_POWER_ACCELERATION = -72.06;

    /** The simulated robot alone on a floor, at the origin facing +X, its motors driven the way a hub drives them. */
    private static final class Run implements AutoCloseable {
        private static final double[] FORWARD = {1, 1, 1, 1};
        private static final double[] LEFT = {-1, 1, 1, -1};

        private final PhysicsWorld world = PhysicsWorld.create(Path.of(System.getProperty("ftcsim.nativeDirectory")));
        private final MecanumChassis chassis;
        private final boolean sideways;
        private int steps;

        Run(RobotSpec profile, boolean sideways) {
            this.sideways = sideways;
            world.addStaticBox(new Vector3(0, 0, -0.5), new Vector3(20, 20, 0.5));
            chassis = world.addMecanumChassis(
                    EntityIds.CHASSIS, RobotRig.CHASSIS_KIND, profile.chassis(), Vector3.ZERO, 0);
            double[] pattern = sideways ? LEFT : FORWARD;
            for (WheelPosition wheel : WheelPosition.values()) {
                chassis.wheelPlant(wheel, 1).applyVoltage(pattern[wheel.ordinal()] * BATTERY_VOLTS);
            }
        }

        void floatMotors() {
            for (WheelPosition wheel : WheelPosition.values()) {
                chassis.wheelPlant(wheel, 1).floatTerminals();
            }
        }

        void step() {
            world.step();
            steps++;
        }

        double seconds() {
            return steps / (double) PhysicsWorld.STEPS_PER_SECOND;
        }

        /** The speed in the direction of the drive, in inches per second. */
        double speed() {
            BodyPose pose = world.bodyPose(EntityIds.CHASSIS);
            return Math.abs(sideways ? pose.vy() : pose.vx()) / INCH;
        }

        double inchesTravelled() {
            BodyPose pose = world.bodyPose(EntityIds.CHASSIS);
            return Math.abs(sideways ? pose.y() : pose.x()) / INCH;
        }

        @Override
        public void close() {
            world.close();
        }
    }

    private static RobotSpec hornet;

    @BeforeAll
    static void loadHornet() throws IOException {
        hornet = HornetMapping.spec();
    }

    /** What the velocity tuners report: the speed at full power once the robot has travelled 48 inches from rest. */
    private double velocity(boolean sideways) {
        try (Run run = new Run(hornet, sideways)) {
            while (run.inchesTravelled() < RUN_INCHES) {
                run.step();
            }
            return run.speed();
        }
    }

    /**
     * What the zero power acceleration tuners report: the robot runs at full power until it passes 30 inches per
     * second, its motors float, and the mean acceleration is taken until it is slower than 0.1 inch per second.
     */
    private double zeroPowerAcceleration(boolean sideways) {
        try (Run run = new Run(hornet, sideways)) {
            while (run.speed() <= COAST_FROM_INCHES_PER_SECOND) {
                run.step();
            }
            double fromSpeed = run.speed();
            double fromSeconds = run.seconds();
            run.floatMotors();
            while (run.speed() >= COAST_TO_INCHES_PER_SECOND) {
                run.step();
            }
            return (run.speed() - fromSpeed) / (run.seconds() - fromSeconds);
        }
    }

    private static void assertWithinTolerance(double hornetsMeasurement, double simulated) {
        assertEquals(hornetsMeasurement, simulated, TOLERANCE * Math.abs(hornetsMeasurement));
    }

    @Test
    void theTopSpeedForwardIsWhatPedrosTunerMeasuredOnTheRealRobot() {
        assertWithinTolerance(X_VELOCITY, velocity(false));
    }

    @Test
    void theTopSpeedSidewaysIsWhatPedrosTunerMeasuredOnTheRealRobot() {
        assertWithinTolerance(Y_VELOCITY, velocity(true));
    }

    @Test
    void theRobotCoastingForwardSlowsAsPedrosTunerMeasuredOnTheRealRobot() {
        assertWithinTolerance(FORWARD_ZERO_POWER_ACCELERATION, zeroPowerAcceleration(false));
    }

    @Test
    void theRobotCoastingSidewaysSlowsAsPedrosTunerMeasuredOnTheRealRobot() {
        assertWithinTolerance(LATERAL_ZERO_POWER_ACCELERATION, zeroPowerAcceleration(true));
    }
}
