package dev.ftcsim.simulator.robot;

import dev.ftcsim.contracts.hardware.HardwareDirectory;
import dev.ftcsim.contracts.snapshot.RobotJoint;
import dev.ftcsim.contracts.world.Vec3;
import dev.ftcsim.mechanisms.CaptureSpec;
import dev.ftcsim.mechanisms.Condition;
import dev.ftcsim.mechanisms.LaunchSpec;
import dev.ftcsim.mechanisms.StorageSpec;
import dev.ftcsim.mechanisms.Volume;
import dev.ftcsim.physics.DcMotor;
import dev.ftcsim.physics.MecanumConfig;
import dev.ftcsim.physics.WheelPosition;
import dev.ftcsim.simulator.robot.RobotMapping.MotorMapping;
import dev.ftcsim.simulator.robot.RobotMapping.Slot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The robot of a game: a fixed drivetrain, intake, conveyor, shooter and sensors whose numbers are those of the robot
 * the simulator was fitted to (see {@code docs/robots/hornet-fit.md}), and a {@link RobotMapping} (the built-in
 * {@link RobotProfiles}) that says which motors of its code are which of its parts. DECODE's robot has no turret; BIOBUZZ's is the same robot with one.
 *
 * <p>Parts exist only when the mapping gives them a motor: no intake motor, no intake. The conveyor and the intake
 * roller move the pieces along one path, the launcher lets the waiting piece go while the first conveyor motor feeds it
 * forward, and the intake takes pieces in while its roller turns in. Mirrored flips the sense of each motor's part. The
 * turret turns the launcher about the robot's vertical axis by its motor's angle over the gear ratio.
 */
public final class RobotDesign {

    /** The games whose robots exist. */
    public enum Game {
        DECODE("decode", false, 0.10, 0.15),
        BIOBUZZ("biobuzz", true, 0.06, 0.085);

        private final String fieldId;
        private final boolean turret;
        private final double minPieceM;
        private final double maxPieceM;

        Game(String fieldId, boolean turret, double minPieceM, double maxPieceM) {
            this.fieldId = fieldId;
            this.turret = turret;
            this.minPieceM = minPieceM;
            this.maxPieceM = maxPieceM;
        }
    }

    private static final int CONTROL_HUB = 173;
    private static final int EXPANSION_HUB = 2;
    private static final int PORTS = 4;

    private static final String STORAGE = "magazine";
    private static final String INTAKE = "front";
    private static final String LAUNCHER = "shooter";

    private final Game game;

    private RobotDesign(Game game) {
        this.game = game;
    }

    public static RobotDesign decode() {
        return new RobotDesign(Game.DECODE);
    }

    public static RobotDesign biobuzz() {
        return new RobotDesign(Game.BIOBUZZ);
    }

    /** The design for a field id of the catalogue: BIOBUZZ's robot on its field, DECODE's on any other. */
    public static RobotDesign forField(String fieldId) {
        return Game.BIOBUZZ.fieldId.equals(fieldId) ? biobuzz() : decode();
    }

    public Game game() {
        return game;
    }

    /** Whether this robot has a turret. */
    public boolean turret() {
        return game.turret;
    }

    /** What the simulator builds this game's robot from: the built-in profile of its field. */
    public RobotSpec spec() {
        return spec(RobotProfiles.forField(game.fieldId));
    }

    /**
     * What the simulator builds a robot from, for a mapping other than the built-in profiles.
     *
     * @throws RobotException if the mapping cannot be built, with every problem named
     */
    public RobotSpec spec(RobotMapping mapping) {
        List<String> problems = mapping.problems();
        if (!problems.isEmpty()) {
            throw new RobotException("the robot mapping is wrong: " + String.join("; ", problems));
        }
        Map<String, RobotSpec.Motor> motors = new LinkedHashMap<>();
        Map<String, int[]> placed = new LinkedHashMap<>();
        Map<Slot, Integer> wheelPorts = Map.of(Slot.FRONT_LEFT, 0, Slot.REAR_LEFT, 1, Slot.FRONT_RIGHT, 2, Slot.REAR_RIGHT, 3);
        boolean[] used = new boolean[2 * PORTS];
        for (MotorMapping motor : mapping.motors()) {
            if (motor.slot().wheel()) {
                int port = wheelPorts.get(motor.slot());
                used[port] = true;
                placed.put(motor.name(), new int[] {CONTROL_HUB, port});
            }
        }
        for (MotorMapping motor : mapping.motors()) {
            if (!motor.slot().wheel()) {
                placed.put(motor.name(), freePort(used));
            }
        }
        for (MotorMapping motor : mapping.motors()) {
            int[] at = placed.get(motor.name());
            motors.put(motor.name(), motor.slot().wheel() ? wheel(motor, at[0], at[1]) : shaft(motor, at[0], at[1]));
        }
        List<StorageSpec> storages = new ArrayList<>();
        List<CaptureSpec> captures = new ArrayList<>();
        List<LaunchSpec> launches = new ArrayList<>();
        RobotSpec.Turret turret = turret(mapping);
        mechanisms(mapping, turret, storages, captures, launches);
        return new RobotSpec(
                mapping.name(), hubs(), chassis(mapping), motors, servos(mapping), turret, storages, captures, launches,
                new RobotSpec.ImuMount(mapping.hubLogo(), mapping.hubUsb()),
                colorSensors(mapping),
                new RobotSpec.Limelight(mapping.camera(), "field", "1280x960", mapping.turretParts().cameraOnTurret()),
                joints(mapping));
    }

    /** The first sensor is on the Control Hub's I2C bus 2, the second on the Expansion Hub's bus 0. */
    private static List<RobotSpec.ColorSensor> colorSensors(RobotMapping mapping) {
        int[][] places = {{CONTROL_HUB, 2}, {EXPANSION_HUB, 0}};
        List<RobotSpec.ColorSensor> sensors = new ArrayList<>();
        for (RobotMapping.ColorSensorMapping sensor : mapping.colorSensors()) {
            int[] at = places[sensors.size()];
            sensors.add(new RobotSpec.ColorSensor(
                    sensor.name(), at[0], at[1], sensor.x(), sensor.y(), sensor.z(), sensor.azimuthDegrees()));
        }
        return sensors;
    }

    /**
     * BIOBUZZ's calibrated Limelight pose: 5.434666889764 inches ahead of the turret's 3.522 inch pivot, 16.96944346457 inches
     * above the floor, pitched 41 degrees up. The profile uses the chassis box centre, 0.1824 m above the floor, as its origin.
     */
    public static final RobotMapping.CameraMount BIOBUZZ_CAMERA = new RobotMapping.CameraMount(
            (3.522 + 5.434666889764) * 0.0254, 0.0, 16.96944346457 * 0.0254 - 0.1824, 0.0, 41.0, 0.0);

    /** Servo turns to one turn of a colibri arm (the CAD's 40:30 gears). */
    public static final double COLIBRI_RATIO = 40.0 / 30.0;

    /**
     * The colibri arm's angle when its 300-degree servo is at 0 rad (position 1.0 on the mirrored left servo): calibrated
     * so the team's open position (0.49) puts the arms down at 0 degrees (FLOWER height) and closed (0.05) stows them up
     * at 99 degrees.
     */
    public static final double COLIBRI_REST_RADIANS = -Math.toRadians((1.0 - 0.49) * 300.0 / COLIBRI_RATIO);

    /** Servo turns to one turn of the hood (the team's table: 5.0694444, 365:72). */
    public static final double HOOD_RATIO = 365.0 / 72.0;

    /** Servo turns to one turn of the stopper. */
    public static final double STOPPER_RATIO = 1.0;

    /** The hood's angle above level with its servo's shaft at 0: the lower limit of its range in the CAD. */
    public static final double HOOD_REST_RADIANS = Math.toRadians(1.0);

    /** How high a piece leaves with the servo's shaft at 0, the hood at its 1 degree: 45 degrees, and the hood's angle adds to it. Estimated. */
    public static final double HOOD_BASE_ELEVATION_RADIANS = Math.toRadians(45.0);

    /**
     * How far the hood turns from its rest to its stop: 44 degrees, which sends a piece up at 45 to 89 degrees. The CAD allows only
     * 1 to 25 degrees; the range is widened so that a CELL can be reached from near and from far, which a HIVE's open end needs
     * steep lobs for (65 to 85 degrees from close to the HIVE, 55 degrees from far). The servo (a Super Speed, 300 degrees) reaches it at position 0.7435.
     */
    public static final double HOOD_RANGE_RADIANS = Math.toRadians(44.0);

    /** Estimated release point halfway between the calibrated stopper commands: 150 degrees shut, 300 open. */
    public static final double STOPPER_OPEN_RADIANS = Math.toRadians(225.0);

    /** The turret servo has the first servo port of the Control Hub; the mechanisms' servos follow in the order of {@link RobotMapping.ServoRole}. */
    private static Map<String, RobotSpec.Servo> servos(RobotMapping mapping) {
        Map<String, RobotSpec.Servo> servos = new LinkedHashMap<>();
        RobotMapping.ServoMapping servo = mapping.turretParts().servo();
        if (servo != null) {
            servos.put(servo.name(), new RobotSpec.Servo(CONTROL_HUB, 0, servo.model()));
        }
        for (RobotMapping.MechanismServo mechanism : mapping.turretParts().mechanisms()) {
            servos.put(
                    mechanism.servo().name(),
                    new RobotSpec.Servo(
                            CONTROL_HUB, 1 + mechanism.role().ordinal(), mechanism.servo().model(), true, mechanism.servo().mirrored()));
        }
        return servos;
    }

    /** What the picture of the robot follows: its first intake, conveyor and shooter motors and its mechanism servos. */
    private static RobotSpec.Joints joints(RobotMapping mapping) {
        List<RobotSpec.ServoJoint> servos = new ArrayList<>();
        for (RobotMapping.MechanismServo mechanism : mapping.turretParts().mechanisms()) {
            String name = mechanism.servo().name();
            servos.add(switch (mechanism.role()) {
                case HOOD -> new RobotSpec.ServoJoint(RobotJoint.HOOD, name, HOOD_RATIO, HOOD_REST_RADIANS, HOOD_RANGE_RADIANS);
                case STOPPER -> new RobotSpec.ServoJoint(RobotJoint.STOPPER, name, STOPPER_RATIO, 0.0);
                case COLIBRI_LEFT -> new RobotSpec.ServoJoint(RobotJoint.COLIBRI_LEFT, name, COLIBRI_RATIO, COLIBRI_REST_RADIANS);
                case COLIBRI_RIGHT -> new RobotSpec.ServoJoint(RobotJoint.COLIBRI_RIGHT, name, COLIBRI_RATIO, COLIBRI_REST_RADIANS);
            });
        }
        MotorMapping intake = firstMotor(mapping, Slot.INTAKE);
        MotorMapping conveyor = firstMotor(mapping, Slot.CONVEYOR);
        MotorMapping shooter = firstMotor(mapping, Slot.SHOOTER);
        return new RobotSpec.Joints(
                intake == null ? null : intake.name(), intake == null ? 1 : sign(intake), intake == null ? 1.0 : intake.ratio(),
                conveyor == null ? null : conveyor.name(), conveyor == null ? 1 : sign(conveyor), conveyor == null ? 1.0 : conveyor.ratio(),
                shooter == null ? null : shooter.name(), shooter == null ? 1 : sign(shooter), shooter == null ? 1.0 : shooter.ratio(),
                servos);
    }

    private static MotorMapping firstMotor(RobotMapping mapping, Slot slot) {
        List<MotorMapping> motors = mapping.in(slot);
        return motors.isEmpty() ? null : motors.get(0);
    }

    /** The turret a BIOBUZZ robot has: turned by its motor, or by its servo, or by nothing if neither is mapped. */
    private RobotSpec.Turret turret(RobotMapping mapping) {
        if (!turret()) {
            return null;
        }
        RobotMapping.TurretParts parts = mapping.turretParts();
        List<MotorMapping> motors = mapping.in(Slot.TURRET);
        String shaft = null;
        int sign = 1;
        if (!motors.isEmpty()) {
            shaft = motors.get(0).name();
            sign = sign(motors.get(0));
        } else if (parts.servo() != null) {
            shaft = parts.servo().name();
            sign = parts.servo().mirrored() ? -1 : 1;
        }
        return new RobotSpec.Turret(
                shaft, mapping.turretRatio(), sign, parts.axisX(), parts.axisY(), parts.cameraOnTurret(), parts.encoders());
    }

    private static int[] freePort(boolean[] used) {
        // The Expansion Hub is filled first; the Control Hub's ports the wheels leave free take what is left over.
        for (int i = 0; i < used.length; i++) {
            int slot = (i + PORTS) % used.length;
            if (!used[slot]) {
                used[slot] = true;
                return new int[] {slot < PORTS ? CONTROL_HUB : EXPANSION_HUB, slot % PORTS};
            }
        }
        throw new RobotException("the robot mapping is wrong: more motors than ports");
    }

    private static List<HardwareDirectory.Hub> hubs() {
        return List.of(
                new HardwareDirectory.Hub(CONTROL_HUB, "Control Hub", true, true),
                new HardwareDirectory.Hub(EXPANSION_HUB, "Expansion Hub " + EXPANSION_HUB, false, false));
    }

    private MecanumConfig chassis(RobotMapping mapping) {
        if (!mapping.drives()) {
            return null;
        }
        MecanumConfig generic = MecanumConfig.generic18Inch();
        boolean biobuzz = game == Game.BIOBUZZ;
        return new MecanumConfig(
                mapping.massKg(), generic.lengthM(), generic.widthM(), generic.heightM(), generic.groundClearanceM(),
                generic.comHeightM(), mapping.wheelDiameterMm() / 2000.0, generic.wheelbaseM(), generic.trackM(),
                MotorCatalog.motor(mapping.driveMotor(), mapping.driveRatio()), generic.wheelInertiaKgM2(),
                biobuzz ? 0.0172 : 0.0034, generic.tireStiffnessNPerM(),
                generic.tireDampingNsPerM(), generic.tractionFriction(), generic.slipStiffnessNsPerM(),
                0.5, biobuzz ? 1.7 : 2.2, biobuzz ? 0.05 : 0.12, biobuzz ? 0.044 : 0.053);
    }

    private static RobotSpec.Motor wheel(MotorMapping motor, int hub, int port) {
        return new RobotSpec.Motor(hub, port, null, new RobotSpec.Wheel(WheelPosition.valueOf(motor.slot().name()), sign(motor)), null);
    }

    private RobotSpec.Motor shaft(MotorMapping motor, int hub, int port) {
        String model = motor.model();
        DcMotor catalogue = MotorCatalog.motor(model);
        // Inertia and friction at the motor's shaft, estimated. A roller's inertia is on the far side of the reduction, so the motor
        // sees it divided by the ratio squared; its friction is 5 percent of what the motor's own curve is (stall torque over free speed).
        // BIOBUZZ's intake is a light roller on the compliant wheels of the CAD, which a braked motor stops in well under a tenth of a second.
        double ratio = motor.ratio();
        double intakeInertia = game == Game.BIOBUZZ ? 4.0e-5 : 2.0e-4;
        double[] load = switch (motor.slot()) {
            case INTAKE -> new double[] {intakeInertia / (ratio * ratio), 0.05 * catalogue.stallTorqueNm() / catalogue.freeSpeedRadPerSec()};
            case CONVEYOR -> new double[] {1.0e-4 / (ratio * ratio), 0.05 * catalogue.stallTorqueNm() / catalogue.freeSpeedRadPerSec()};
            case SHOOTER -> new double[] {2.5e-4, 8.0e-5};
            default -> new double[] {1.0e-3, 5.0e-3};
        };
        return new RobotSpec.Motor(hub, port, model, null, new RobotSpec.Shaft(load[0], load[1]));
    }

    private static int sign(MotorMapping motor) {
        return motor.mirrored() ? -1 : 1;
    }

    /**
     * Turning forward at least this fast in the sense the part works in, or backward at least this fast when mirrored. The speed is the
     * part's own; the motor turns its ratio times faster.
     */
    private static Condition turning(MotorMapping motor, double partRadPerSec) {
        double radPerSec = partRadPerSec * motor.ratio();
        return motor.mirrored()
                ? Condition.atMost(motor.name(), -radPerSec)
                : Condition.atLeast(motor.name(), radPerSec);
    }

    /** The radius of BIOBUZZ's flywheels: 3 in Stealth wheels. */
    public static final double BIOBUZZ_FLYWHEEL_RADIUS_M = 0.0381;

    /** DECODE's path: in at the front, up and back to a fixed launcher over the rear. */
    private static final List<Vec3> DECODE_PATH = List.of(new Vec3(0.27, 0.0, -0.10), new Vec3(0.05, 0.0, 0.0), new Vec3(-0.12, 0.0, 0.26));

    /** The centre of the feed column in the CAD, ahead of the wheels' centre. */
    private static final double BIOBUZZ_COLUMN_X = 0.089;

    /** The shooter wheels' axle in the CAD (robot frame, metres), under the hood's pivot. */
    public static final Vec3 BIOBUZZ_WHEEL_AXLE = new Vec3(0.162, 0.0, 0.2146);

    /** How far from the axle a POLLEN's centre is as it rides round the wheels' rim: the wheel's radius and the piece's (1.40 in). */
    public static final double BIOBUZZ_EXIT_RADIUS_M = BIOBUZZ_FLYWHEEL_RADIUS_M + 0.0356;

    /**
     * Where a piece waits for the shot: pinched between the feeder roller (axis x 0.045, z 0.245, radius 0.013) and the wheels, so
     * 0.0485 m from the roller's axis and {@link #BIOBUZZ_EXIT_RADIUS_M} from the wheels'. From the CAD.
     */
    private static final Vec3 BIOBUZZ_WAIT = new Vec3(0.092, 0.0, 0.233);

    /**
     * BIOBUZZ's path in the CAD, for the centre of a POLLEN (robot frame, metres): in at the intake roller, back and up the
     * curve of compliant wheels between the front wheels, up the column between the three cross shafts (x 0.042 and 0.137, under
     * the turret) and through the turret, to where the piece waits for the shot ({@link #BIOBUZZ_WAIT}). The last two points
     * are inside the turret and turn with it. The coordinates come from the CAD's rollers, shafts and wheels.
     */
    private static final List<Vec3> BIOBUZZ_PATH = List.of(
            new Vec3(0.27, 0.0, -0.14), new Vec3(0.15, 0.0, -0.10), new Vec3(0.115, 0.0, -0.05), new Vec3(0.098, 0.0, 0.0),
            new Vec3(BIOBUZZ_COLUMN_X, 0.0, 0.06), new Vec3(BIOBUZZ_COLUMN_X, 0.0, 0.215), BIOBUZZ_WAIT);
    /** BIOBUZZ's robot holds four POLLEN, its preloads (the manual allows four), in a column that is long enough for them to queue; DECODE's three. */
    public static final int BIOBUZZ_CAPACITY = 4;
    private static final int DECODE_CAPACITY = 3;
    private static final int BIOBUZZ_TURNED_FROM_POINT = 5;
    /** The stopper holds the pieces at the foot of the column, path point 4. */
    private static final int BIOBUZZ_GATE_POINT = 4;

    /**
     * Where the BIOBUZZ shot leaves, from the CAD's shooter: the two 3 in wheels side by side on an axle at x 0.162, z 0.2146, the
     * feeder rollers behind them at x 0.045, and the hood's side plates, which carry both and turn about the axle. A piece rides
     * round the wheels' upper rim, held by the plates, and is let go where the hood's end is, so it leaves on the circle round the axle
     * at the angle the hood sets: about (0.110, 0.267) for the 45 degrees of the hood at rest, and back over the axle, nearly above
     * the column, for a steep launch. A launcher with no hood leaves at its fixed 55 degrees.
     */
    private static final LaunchSpec.Exit BIOBUZZ_EXIT = new LaunchSpec.Exit(BIOBUZZ_WHEEL_AXLE, BIOBUZZ_EXIT_RADIUS_M);
    private static final double BIOBUZZ_FIXED_ELEVATION_RADIANS = Math.atan2(0.8192, 0.5736);

    private void mechanisms(
            RobotMapping mapping, RobotSpec.Turret turret, List<StorageSpec> storages, List<CaptureSpec> captures, List<LaunchSpec> launches) {
        List<MotorMapping> intake = mapping.in(Slot.INTAKE);
        List<MotorMapping> conveyor = mapping.in(Slot.CONVEYOR);
        List<MotorMapping> shooter = mapping.in(Slot.SHOOTER);
        if (intake.isEmpty() && conveyor.isEmpty() && shooter.isEmpty()) {
            return;
        }
        boolean cad = game.turret;
        List<Vec3> path = cad ? BIOBUZZ_PATH : DECODE_PATH;
        int capacity = cad ? BIOBUZZ_CAPACITY : DECODE_CAPACITY;
        double length = new StorageSpec(STORAGE, capacity, path, List.of()).lengthMeters();
        List<StorageSpec.Drive> drives = new ArrayList<>();
        for (MotorMapping motor : intake) {
            drives.add(new StorageSpec.Drive(motor.name(), 0.025 / motor.ratio(), sign(motor), 0.0, 0.20));
        }
        for (MotorMapping motor : conveyor) {
            drives.add(new StorageSpec.Drive(motor.name(), 0.02 / motor.ratio(), sign(motor), 0.15, length));
        }
        RobotMapping.TurretParts parts = mapping.turretParts();
        StorageSpec.Turned turned = cad ? new StorageSpec.Turned(BIOBUZZ_TURNED_FROM_POINT, parts.axisX(), parts.axisY()) : null;
        RobotMapping.ServoMapping stopper = parts.servoFor(RobotMapping.ServoRole.STOPPER);
        // The stopper holds the pieces at the foot of the column, so the preloads wait there until it opens.
        StorageSpec.Gate gate = cad && stopper != null
                ? new StorageSpec.Gate(BIOBUZZ_GATE_POINT, stopper.name(), STOPPER_OPEN_RADIANS) : null;
        storages.add(new StorageSpec(STORAGE, capacity, path, drives, turned, gate));
        if (!intake.isEmpty()) {
            captures.add(new CaptureSpec(
                    INTAKE, STORAGE, new Volume(new Vec3(0.30, 0.0, -0.12), new Vec3(0.06, 0.20, 0.07)),
                    intake.stream().map(motor -> turning(motor, 10.0)).toList(), game.minPieceM, game.maxPieceM));
        }
        if (!shooter.isEmpty()) {
            // A flywheel's rim moves at the motor's speed times the wheel's radius over the reduction between them. BIOBUZZ's are
            // 3 in Stealth wheels behind a 43:30 belt; DECODE's radius is Hornet's fit, with the motor as the wheel (ratio 1).
            double wheelRadius = cad ? BIOBUZZ_FLYWHEEL_RADIUS_M : 0.05;
            List<LaunchSpec.Wheel> wheels = shooter.stream()
                    .map(motor -> new LaunchSpec.Wheel(motor.name(), wheelRadius / motor.ratio(), sign(motor))).toList();
            List<Condition> feed = new ArrayList<>(conveyor.isEmpty() ? List.of() : List.of(turning(conveyor.get(0), 20.0)));
            RobotMapping.ServoMapping hood = mapping.turretParts().servoFor(RobotMapping.ServoRole.HOOD);
            LaunchSpec.Hood tilt = hood == null ? null
                    : new LaunchSpec.Hood(hood.name(), HOOD_RATIO, HOOD_BASE_ELEVATION_RADIANS, HOOD_RANGE_RADIANS, cad ? BIOBUZZ_EXIT : null);
            LaunchSpec.Turret aim = turret == null || turret.shaft() == null ? null : turret.aim();
            Vec3 muzzle = cad ? BIOBUZZ_EXIT.at(BIOBUZZ_FIXED_ELEVATION_RADIANS, 1.0, 0.0) : null;
            launches.add(new LaunchSpec(
                    LAUNCHER, STORAGE, feed, wheels, 0.49, new Vec3(0.5736, 0.0, 0.8192), 0.3, aim, tilt, muzzle));
        }
    }
}
