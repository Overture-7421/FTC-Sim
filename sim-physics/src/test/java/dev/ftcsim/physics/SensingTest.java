package dev.ftcsim.physics;

import static dev.ftcsim.physics.TestWorlds.steps;
import static dev.ftcsim.physics.TestWorlds.worldWithFloor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.hardware.MotorPlant;
import dev.ftcsim.contracts.world.BodyPose;
import org.junit.jupiter.api.Test;

/** What the hardware layer and the sensors get from the world: wheel plants, the true pose, and ray casts. */
class SensingTest {

    private static final MecanumConfig CONFIG = MecanumConfig.generic18Inch();
    private static final int ROBOT = 1;
    private static final int PIECE = 2000;
    private static final int PIECE_KIND = 77;
    private static final int ONE_SECOND = PhysicsWorld.STEPS_PER_SECOND;

    private static MecanumChassis robotAt(PhysicsWorld world, double x, double y, double yaw) {
        return world.addMecanumChassis(ROBOT, 12, CONFIG, new Vector3(x, y, 0), yaw);
    }

    /** The four motors wired as a symmetric drivetrain is: right motors forward, left motors mounted mirrored. */
    private static MotorPlant[] wheelPlants(MecanumChassis robot) {
        return new MotorPlant[] {
            robot.wheelPlant(WheelPosition.FRONT_LEFT, -1),
            robot.wheelPlant(WheelPosition.FRONT_RIGHT, 1),
            robot.wheelPlant(WheelPosition.REAR_LEFT, -1),
            robot.wheelPlant(WheelPosition.REAR_RIGHT, 1)
        };
    }

    @Test
    void wheelPlantsDrivenLikeASdkProgramDrivesAMirroredDrivetrainRollTheRobotForward() {
        try (PhysicsWorld world = worldWithFloor()) {
            MecanumChassis robot = robotAt(world, -3, 0, 0);
            MotorPlant[] motors = wheelPlants(robot);
            // setPower(+0.5) with the left side REVERSE: the SDK sends negative volts to the left ports.
            motors[0].applyVoltage(-6.0);
            motors[1].applyVoltage(6.0);
            motors[2].applyVoltage(-6.0);
            motors[3].applyVoltage(6.0);

            steps(world, ONE_SECOND);

            BodyPose pose = world.bodyPose(ROBOT);
            assertTrue(pose.x() > -3 + 0.3, "the robot did not move forward: x = " + pose.x());
            assertEquals(0.0, pose.y(), 0.05);
            assertEquals(0.0, pose.yaw(), 0.05);
            assertTrue(pose.vx() > 0.5, "vx = " + pose.vx());
            // The encoders count up with the hub's own sign: the left ports were driven with negative volts.
            assertTrue(motors[1].encoderCounts() > 0 && motors[3].encoderCounts() > 0);
            assertTrue(motors[0].encoderCounts() < 0 && motors[2].encoderCounts() < 0);
            assertEquals(-motors[0].encoderCounts(), motors[1].encoderCounts(), 40);
            assertTrue(motors[1].currentAmps() > 0.0);
        }
    }

    @Test
    void aRobotSpinningInPlaceTurnsAtTheReportedYawRateWhileItsOriginStaysPut() {
        try (PhysicsWorld world = worldWithFloor()) {
            MecanumChassis robot = robotAt(world, 0, 0, 0);
            MotorPlant[] motors = wheelPlants(robot);
            // The same positive volts on all four ports turn a mirrored drivetrain: right side forward, left backward.
            motors[0].applyVoltage(6.0);
            motors[1].applyVoltage(6.0);
            motors[2].applyVoltage(6.0);
            motors[3].applyVoltage(6.0);
            steps(world, ONE_SECOND / 2);

            BodyPose pose = world.bodyPose(ROBOT);

            assertTrue(Math.abs(pose.wz()) > 0.5, "the robot is not turning: " + pose.wz());
            assertEquals(0.0, Math.hypot(pose.vx(), pose.vy()), 0.25, "the origin of a robot that spins in place stays put");
        }
    }

    @Test
    void floatingTerminalsLetTheWheelCoastWhileShortedOnesBrakeIt() {
        double[] speedAfterGlide = new double[2];
        for (int variant = 0; variant < 2; variant++) {
            try (PhysicsWorld world = worldWithFloor()) {
                MecanumChassis robot = robotAt(world, 0, 0, 0);
                robot.setBatteryVolts(12.0);
                robot.wheelPlant(WheelPosition.FRONT_RIGHT, 1).applyVoltage(12.0);
                steps(world, ONE_SECOND / 2);
                double spinning = robot.wheelSpeedRadPerSec(WheelPosition.FRONT_RIGHT);
                assertTrue(spinning > 5.0, "the wheel is not spinning: " + spinning);
                MotorPlant plant = robot.wheelPlant(WheelPosition.FRONT_RIGHT, 1);
                if (variant == 0) {
                    plant.floatTerminals();
                } else {
                    plant.applyVoltage(0.0);
                }

                steps(world, ONE_SECOND / 10);
                speedAfterGlide[variant] = robot.wheelSpeedRadPerSec(WheelPosition.FRONT_RIGHT);
            }
        }

        assertTrue(speedAfterGlide[0] > speedAfterGlide[1], "floating " + speedAfterGlide[0] + " vs shorted " + speedAfterGlide[1]);
    }

    @Test
    void aRayFindsAPieceTheFloorAndNothingBeyondItsRangeAndNeverTheRobotCarryingTheSensor() {
        try (PhysicsWorld world = worldWithFloor()) {
            MecanumChassis robot = robotAt(world, 0, 0, 0);
            PieceSpec spec = new PieceSpec(0.07f, 0.1f, 0.5f, 0.1f, 0.01f);
            world.spawnPiece(PIECE, PIECE_KIND, spec, new Vector3(2, 0, 0.07), Vector3.ZERO);
            steps(world, 30);
            assertNotNull(robot);

            // From inside the robot's own body, along the floor towards the piece.
            RayHit toPiece = world.castRay(new Vector3(0.0, 0.0, 0.07), Vector3.X, 5.0, ROBOT);
            assertNotNull(toPiece);
            assertEquals(PIECE, toPiece.entityId());
            assertEquals(PIECE_KIND, toPiece.kind());
            assertEquals(2.0 - 0.07, toPiece.distanceMeters(), 0.03);

            RayHit toFloor = world.castRay(new Vector3(-1.0, 0.0, 1.0), new Vector3(0, 0, -1), 5.0, ROBOT);
            assertNotNull(toFloor);
            assertEquals(RayHit.FIXED, toFloor.entityId());
            assertEquals(1.0, toFloor.distanceMeters(), 1e-3);

            assertNull(world.castRay(new Vector3(-1.0, 0.0, 1.0), new Vector3(0, 0, 1), 5.0, ROBOT));
            assertNull(world.castRay(new Vector3(-1.0, 0.0, 1.0), new Vector3(0, 0, -1), 0.5, ROBOT));

            // Without being told to ignore the robot, a ray from inside it hits it.
            RayHit hitsRobot = world.castRay(new Vector3(-0.5, 0.0, 0.15), Vector3.X, 5.0, -999);
            assertNotNull(hitsRobot);
            assertEquals(ROBOT, hitsRobot.entityId());
        }
    }
}
