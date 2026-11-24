package dev.ftcsim.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.world.BodyPose;
import dev.ftcsim.field.EntityIds;
import dev.ftcsim.physics.MecanumChassis;
import dev.ftcsim.physics.PhysicsWorld;
import dev.ftcsim.physics.Vector3;
import dev.ftcsim.physics.WheelPosition;
import dev.ftcsim.simulator.robot.RobotDesign;
import dev.ftcsim.simulator.robot.RobotSpec;
import dev.ftcsim.simulator.RobotRig;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Both built-in drivetrains against the four measurements in their teams' Pedro Pathing constants: top speed forward and
 * sideways at full power, and coasting deceleration in both directions. Hornet's tuners themselves run in
 * {@link HornetBringupTest#pedrosOwnTunersMeasureTheSimulatedDrivetrain}, which needs Hornet's package; here the same
 * measurements are taken straight from the chassis, with the exact speed instead of what the odometry reports.
 */
class DrivetrainCalibrationTest {

    private static final double INCH = 0.0254;
    private static final double BATTERY_VOLTS = 12.6;
    private static final double TOLERANCE = 0.05;
    private static final double RUN_INCHES = 48;
    private static final double COAST_FROM_INCHES_PER_SECOND = 30;
    private static final double COAST_TO_INCHES_PER_SECOND = 0.1;

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
            assertTrue(steps < 240 * 30, "the drive or coast did not finish in 30 simulated seconds");
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

    /** What the velocity tuners report: the speed at full power once the robot has travelled 48 inches from rest. */
    private double velocity(String field, boolean sideways) {
        try (Run run = new Run(RobotDesign.forField(field).spec(), sideways)) {
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
    private double zeroPowerAcceleration(String field, boolean sideways) {
        try (Run run = new Run(RobotDesign.forField(field).spec(), sideways)) {
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

    private static void assertWithinTolerance(String field, double measured, double simulated) {
        System.out.printf(Locale.ROOT, "DRIVETRAIN> %s measured %.6f simulated %.6f%n", field, measured, simulated);
        assertEquals(measured, simulated, TOLERANCE * Math.abs(measured));
    }

    @ParameterizedTest
    @CsvSource({"decode, 76.84", "biobuzz, 75.985802937674"})
    void theTopSpeedForwardIsWhatPedrosTunerMeasuredOnTheRealRobot(String field, double measured) {
        assertWithinTolerance(field, measured, velocity(field, false));
    }

    @ParameterizedTest
    @CsvSource({"decode, 58.91", "biobuzz, 60.56961875581709"})
    void theTopSpeedSidewaysIsWhatPedrosTunerMeasuredOnTheRealRobot(String field, double measured) {
        assertWithinTolerance(field, measured, velocity(field, true));
    }

    @ParameterizedTest
    @CsvSource({"decode, -32.88", "biobuzz, -29.970309689265445"})
    void theRobotCoastingForwardSlowsAsPedrosTunerMeasuredOnTheRealRobot(String field, double measured) {
        assertWithinTolerance(field, measured, zeroPowerAcceleration(field, false));
    }

    @ParameterizedTest
    @CsvSource({"decode, -72.06", "biobuzz, -62.232943481229135"})
    void theRobotCoastingSidewaysSlowsAsPedrosTunerMeasuredOnTheRealRobot(String field, double measured) {
        assertWithinTolerance(field, measured, zeroPowerAcceleration(field, true));
    }
}
