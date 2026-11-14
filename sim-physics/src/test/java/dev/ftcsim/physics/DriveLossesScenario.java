package dev.ftcsim.physics;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A chassis with every loss of the drivetrain switched on is driven forward, left to float until its wheels lock,
 * driven sideways, braked, spun on the spot, given commands that stay below its wheel friction, and driven again. It
 * is the scenario of {@link DriveLossesDeterminismTest}, which also starts {@link #main} in fresh JVMs.
 */
public final class DriveLossesScenario {

    static final int STEPS = 2400;
    static final int ROBOT = 1;
    /** The step by which the robot, floating since the first second, has come to rest. */
    static final int AT_REST_BY = 950;

    private static final int FLOAT_STEP = 240;
    private static final int STRAFE_STEP = 960;
    private static final int BRAKE_STEP = 1200;
    private static final int SPIN_STEP = 1380;
    private static final int NUDGE_STEP = 1680;
    private static final int DRIVE_STEP = 1800;

    private final PhysicsWorld world;
    private final MecanumChassis robot;
    private int step;

    DriveLossesScenario(PhysicsWorld world) {
        this.world = world;
        world.addStaticBox(new Vector3(0, 0, -0.5), new Vector3(5, 5, 0.5));
        robot = world.addMecanumChassis(ROBOT, 12, config(), new Vector3(-3, 0, 0), 0);
    }

    /**
     * The generic robot with the given drag: viscous and constant friction in the wheels, and in the rollers the
     * friction limit, viscous drag and rolling resistance.
     */
    static MecanumConfig withDrag(
            double wheelDamping, double wheelFriction, double rollerFriction, double rollerViscous, double rollingResistance) {
        MecanumConfig generic = MecanumConfig.generic18Inch();
        return new MecanumConfig(
                generic.massKg(), generic.lengthM(), generic.widthM(), generic.heightM(), generic.groundClearanceM(),
                generic.comHeightM(), generic.wheelRadiusM(), generic.wheelbaseM(), generic.trackM(), generic.motor(),
                generic.wheelInertiaKgM2(), wheelDamping, generic.tireStiffnessNPerM(), generic.tireDampingNsPerM(),
                generic.tractionFriction(), generic.slipStiffnessNsPerM(), rollerFriction, rollerViscous, wheelFriction,
                rollingResistance);
    }

    static MecanumConfig config() {
        return withDrag(3.4e-3, 0.12, 0.5, 2.2, 0.053);
    }

    /** Hash of the world after every {@link GoldenScenario#HASH_INTERVAL} steps of a complete run. */
    static List<String> run(PhysicsWorld world) {
        DriveLossesScenario scenario = new DriveLossesScenario(world);
        List<String> hashes = new ArrayList<>();
        for (int i = 1; i <= STEPS; i++) {
            scenario.step();
            if (i % GoldenScenario.HASH_INTERVAL == 0) {
                hashes.add(world.stateHash());
            }
        }
        return hashes;
    }

    public static void main(String[] args) {
        String dllSha256 = args.length > 0 ? args[0] : TestNatives.RELEASE_SHA256;
        int workerThreads = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        try (PhysicsWorld world = PhysicsWorld.create(Path.of(System.getProperty("ftcsim.nativeDirectory")), dllSha256, workerThreads)) {
            GoldenHashes.lines(run(world)).forEach(System.out::println);
        }
    }

    MecanumChassis robot() {
        return robot;
    }

    void step() {
        switch (step) {
            case 0 -> command(1, 1, 1, 1);
            case FLOAT_STEP -> floatMotors();
            case STRAFE_STEP -> command(-1, 1, 1, -1);
            case BRAKE_STEP -> shortMotors();
            case SPIN_STEP -> command(-1, 1, -1, 1);
            case NUDGE_STEP -> command(0.05, 0.05, 0.05, 0.05);
            case DRIVE_STEP -> command(0.6, 0.6, 0.6, 0.6);
            default -> {}
        }
        world.step();
        step++;
    }

    private void command(double frontLeft, double frontRight, double rearLeft, double rearRight) {
        robot.setWheelCommand(WheelPosition.FRONT_LEFT, frontLeft);
        robot.setWheelCommand(WheelPosition.FRONT_RIGHT, frontRight);
        robot.setWheelCommand(WheelPosition.REAR_LEFT, rearLeft);
        robot.setWheelCommand(WheelPosition.REAR_RIGHT, rearRight);
    }

    private void floatMotors() {
        for (WheelPosition wheel : WheelPosition.values()) {
            robot.wheelPlant(wheel, 1).floatTerminals();
        }
    }

    private void shortMotors() {
        for (WheelPosition wheel : WheelPosition.values()) {
            robot.wheelPlant(wheel, 1).applyVoltage(0.0);
        }
    }
}
