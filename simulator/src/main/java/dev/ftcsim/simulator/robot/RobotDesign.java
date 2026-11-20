package dev.ftcsim.simulator.robot;

import dev.ftcsim.contracts.hardware.HardwareDirectory;
import dev.ftcsim.contracts.session.RobotMapping;
import dev.ftcsim.contracts.session.RobotMapping.MotorMapping;
import dev.ftcsim.contracts.session.RobotMapping.Slot;
import dev.ftcsim.contracts.snapshot.RobotJoint;
import dev.ftcsim.contracts.world.Vec3;
import dev.ftcsim.hardware.device.rev.ThroughBoreEncoder;
import dev.ftcsim.mechanisms.CaptureSpec;
import dev.ftcsim.mechanisms.Condition;
import dev.ftcsim.mechanisms.LaunchSpec;
import dev.ftcsim.mechanisms.StorageSpec;
import dev.ftcsim.mechanisms.Volume;
import dev.ftcsim.physics.DcMotor;
import dev.ftcsim.physics.MecanumConfig;
import dev.ftcsim.physics.WheelPosition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The robot of a game: a fixed drivetrain, intake, conveyor, shooter and sensors whose numbers are those of the robot
 * the simulator was fitted to (see {@code docs/robots/hornet-fit.md}), and a {@link RobotMapping} that says which
 * motors of a team's code are which of its parts. DECODE's robot has no turret; BIOBUZZ's is the same robot with one.
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

    /**
     * What the simulator builds a robot from.
     *
     * @throws RobotException if the mapping cannot be built, with every problem named
     */
    public RobotSpec spec(RobotMapping mapping) {
        List<String> problems = mapping.problems(turret());
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
                new RobotSpec.ColorSensor(-0.12, 0.09, 0.26, -90.0),
                new RobotSpec.Limelight(camera(mapping), "field", "1280x960", mapping.turretParts().cameraOnTurret()),
                joints(mapping));
    }

    /**
     * Where BIOBUZZ's Limelight sits, with the turret facing forward: on the 45 degree panel beside the shooter wheels (the owner
     * placed it; the CAD has no camera). It faces forward and 45 degrees up.
     */
    public static final RobotMapping.CameraMount BIOBUZZ_CAMERA = new RobotMapping.CameraMount(0.217, 0.0035, 0.256, 0.0, 45.0, 0.0);

    private RobotMapping.CameraMount camera(RobotMapping mapping) {
        boolean usual = mapping.camera().equals(RobotMapping.DEFAULT_CAMERA);
        return game == Game.BIOBUZZ && usual ? BIOBUZZ_CAMERA : mapping.camera();
    }

    /** Servo turns to one turn of a colibri arm (the CAD's 40:30 gears). */
    public static final double COLIBRI_RATIO = 40.0 / 30.0;

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
     * steep lobs for (65 to 85 degrees from close to the HIVE, 55 degrees from far). The servo (5 turns) reaches it at position 0.124.
     */
    public static final double HOOD_RANGE_RADIANS = Math.toRadians(44.0);

    /** How far the stopper's servo must have turned for pieces to pass, 60 degrees. Estimated. */
    public static final double STOPPER_OPEN_RADIANS = Math.toRadians(60.0);

    /** The turret servo has the first servo port of the Control Hub; the mechanisms' servos follow in the order of {@link RobotMapping.ServoRole}. */
    private static Map<String, RobotSpec.Servo> servos(RobotMapping mapping) {
        Map<String, RobotSpec.Servo> servos = new LinkedHashMap<>();
        RobotMapping.ServoMapping servo = mapping.turretParts().servo();
        if (servo != null) {
            String model = servo.model().isEmpty() ? ServoCatalog.USUAL : servo.model();
            ServoCatalog.noLoadRadPerSec(model);
            servos.put(servo.name(), new RobotSpec.Servo(CONTROL_HUB, 0, model));
        }
        for (RobotMapping.MechanismServo mechanism : mapping.turretParts().mechanisms()) {
            String model = mechanism.servo().model().isEmpty()
                    ? (mechanism.role() == RobotMapping.ServoRole.HOOD ? ServoCatalog.GOBILDA_TORQUE_5_TURN : ServoCatalog.USUAL_POSITIONAL)
                    : mechanism.servo().model();
            ServoCatalog.noLoadRadPerSec(model);
            servos.put(
                    mechanism.servo().name(),
                    new RobotSpec.Servo(CONTROL_HUB, 1 + mechanism.role().ordinal(), model, true, mechanism.servo().mirrored()));
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
                case COLIBRI_LEFT -> new RobotSpec.ServoJoint(RobotJoint.COLIBRI_LEFT, name, COLIBRI_RATIO, 0.0);
                case COLIBRI_RIGHT -> new RobotSpec.ServoJoint(RobotJoint.COLIBRI_RIGHT, name, COLIBRI_RATIO, 0.0);
            });
        }
        MotorMapping intake = firstMotor(mapping, Slot.INTAKE);
        MotorMapping conveyor = firstMotor(mapping, Slot.CONVEYOR);
        MotorMapping shooter = firstMotor(mapping, Slot.SHOOTER);
        return new RobotSpec.Joints(
                intake == null ? null : intake.name(), intake == null ? 1 : sign(intake), intake == null ? 1.0 : ratioOf(intake),
                conveyor == null ? null : conveyor.name(), conveyor == null ? 1 : sign(conveyor), conveyor == null ? 1.0 : ratioOf(conveyor),
                shooter == null ? null : shooter.name(), shooter == null ? 1 : sign(shooter), shooter == null ? 1.0 : ratioOf(shooter),
                servos);
    }

    /** Motor turns to one turn of what the motor drives: the mapping's ratio, or 1 when it gives none. */
    private static double ratioOf(MotorMapping motor) {
        return motor.ratio() == 0.0 ? 1.0 : motor.ratio();
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
                shaft, mapping.turretRatio(), sign, parts.axisX(), parts.axisY(), parts.cameraOnTurret(), encoders(parts));
    }

    /** The encoders on the turret, each with the model it has spelled out; the message of the exception lists the known models. */
    private static List<RobotMapping.TurretEncoder> encoders(RobotMapping.TurretParts parts) {
        List<RobotMapping.TurretEncoder> encoders = new ArrayList<>();
        for (RobotMapping.TurretEncoder encoder : parts.encoders()) {
            String model = encoder.model().isEmpty() ? ThroughBoreEncoder.USUAL : encoder.model();
            ThroughBoreEncoder.spec(model);
            encoders.add(new RobotMapping.TurretEncoder(
                    encoder.channel(), model, encoder.turnsPerTurretTurn(), encoder.zeroOffsetDegrees(), encoder.mirrored()));
        }
        return encoders;
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

    /** The drive wheel diameter of BIOBUZZ's robot in the CAD (the mecanum wheels are 112 mm across), used when the mapping leaves the generic 96 mm. */
    public static final double BIOBUZZ_WHEEL_DIAMETER_MM = 112.0;

    private MecanumConfig chassis(RobotMapping mapping) {
        if (!mapping.drives()) {
            return null;
        }
        MecanumConfig generic = MecanumConfig.generic18Inch();
        double driveRatio = mapping.driveRatio() == 0.0 ? 1.0 : mapping.driveRatio();
        double wheelDiameterMm = game == Game.BIOBUZZ && mapping.wheelDiameterMm() == RobotMapping.DEFAULT_WHEEL_DIAMETER_MM
                ? BIOBUZZ_WHEEL_DIAMETER_MM : mapping.wheelDiameterMm();
        return new MecanumConfig(
                mapping.massKg(), generic.lengthM(), generic.widthM(), generic.heightM(), generic.groundClearanceM(),
                generic.comHeightM(), wheelDiameterMm / 2000.0, generic.wheelbaseM(), generic.trackM(),
                MotorCatalog.motor(mapping.driveMotor(), driveRatio), generic.wheelInertiaKgM2(), 0.0034, generic.tireStiffnessNPerM(),
                generic.tireDampingNsPerM(), generic.tractionFriction(), generic.slipStiffnessNsPerM(), 0.5, 2.2, 0.12, 0.053);
    }

    private static RobotSpec.Motor wheel(MotorMapping motor, int hub, int port) {
        return new RobotSpec.Motor(hub, port, null, new RobotSpec.Wheel(WheelPosition.valueOf(motor.slot().name()), sign(motor)), null);
    }

    private RobotSpec.Motor shaft(MotorMapping motor, int hub, int port) {
        String model = motor.model().isEmpty() ? RobotMapping.USUAL_MOTOR : motor.model();
        DcMotor catalogue = MotorCatalog.motor(model);
        // Inertia and friction at the motor's shaft, estimated. A roller's inertia is on the far side of the reduction, so the motor
        // sees it divided by the ratio squared; its friction is 5 percent of what the motor's own curve is (stall torque over free speed).
        // BIOBUZZ's intake is a light roller on the compliant wheels of the CAD, which a braked motor stops in well under a tenth of a second.
        double ratio = ratioOf(motor);
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
     * part's own; the motor turns {@link #ratioOf} times faster.
     */
    private static Condition turning(MotorMapping motor, double partRadPerSec) {
        double radPerSec = partRadPerSec * ratioOf(motor);
        return motor.mirrored()
                ? Condition.atMost(motor.name(), -radPerSec)
                : Condition.atLeast(motor.name(), radPerSec);
    }

    /** The radius of BIOBUZZ's flywheels: 3 in Stealth wheels. */
    public static final double BIOBUZZ_FLYWHEEL_RADIUS_M = 0.0381;

    /** DECODE's path: in at the front, up and back to a fixed launcher over the rear. */
    private static final List<Vec3> DECODE_PATH = List.of(new Vec3(0.27, 0.0, -0.10), new Vec3(0.05, 0.0, 0.0), new Vec3(-0.12, 0.0, 0.26));

    /** The turret's axis in the CAD, ahead of the wheels' centre; the column the pieces climb is on it. */
    public static final double BIOBUZZ_TURRET_AXIS_X = 0.089;

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
     * the turret's axis) and through the turret, to where the piece waits for the shot ({@link #BIOBUZZ_WAIT}). The last two points
     * are inside the turret and turn with it. The coordinates come from the CAD's rollers, shafts and wheels.
     */
    private static final List<Vec3> BIOBUZZ_PATH = List.of(
            new Vec3(0.27, 0.0, -0.14), new Vec3(0.15, 0.0, -0.10), new Vec3(0.115, 0.0, -0.05), new Vec3(0.098, 0.0, 0.0),
            new Vec3(BIOBUZZ_TURRET_AXIS_X, 0.0, 0.06), new Vec3(BIOBUZZ_TURRET_AXIS_X, 0.0, 0.215), BIOBUZZ_WAIT);
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
            drives.add(new StorageSpec.Drive(motor.name(), 0.025 / ratioOf(motor), sign(motor), 0.0, 0.20));
        }
        for (MotorMapping motor : conveyor) {
            drives.add(new StorageSpec.Drive(motor.name(), 0.02 / ratioOf(motor), sign(motor), 0.15, length));
        }
        StorageSpec.Turned turned = cad ? new StorageSpec.Turned(BIOBUZZ_TURNED_FROM_POINT, BIOBUZZ_TURRET_AXIS_X, 0.0) : null;
        RobotMapping.ServoMapping stopper = mapping.turretParts().servoFor(RobotMapping.ServoRole.STOPPER);
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
                    .map(motor -> new LaunchSpec.Wheel(motor.name(), wheelRadius / ratioOf(motor), sign(motor))).toList();
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
