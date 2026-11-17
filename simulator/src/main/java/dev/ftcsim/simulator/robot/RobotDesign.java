package dev.ftcsim.simulator.robot;

import dev.ftcsim.contracts.hardware.HardwareDirectory;
import dev.ftcsim.contracts.session.RobotMapping;
import dev.ftcsim.contracts.session.RobotMapping.MotorMapping;
import dev.ftcsim.contracts.session.RobotMapping.Slot;
import dev.ftcsim.contracts.world.Vec3;
import dev.ftcsim.mechanisms.CaptureSpec;
import dev.ftcsim.mechanisms.Condition;
import dev.ftcsim.mechanisms.LaunchSpec;
import dev.ftcsim.mechanisms.StorageSpec;
import dev.ftcsim.mechanisms.Volume;
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
                new RobotSpec.Limelight(mapping.camera(), "field", "1280x960", mapping.turretParts().cameraOnTurret()));
    }

    /** The turret servo has the first servo port of the Control Hub. */
    private static Map<String, RobotSpec.Servo> servos(RobotMapping mapping) {
        Map<String, RobotSpec.Servo> servos = new LinkedHashMap<>();
        RobotMapping.ServoMapping servo = mapping.turretParts().servo();
        if (servo != null) {
            String model = servo.model().isEmpty() ? ServoCatalog.USUAL : servo.model();
            ServoCatalog.noLoadRadPerSec(model);
            servos.put(servo.name(), new RobotSpec.Servo(CONTROL_HUB, 0, model));
        }
        return servos;
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
        return new RobotSpec.Turret(shaft, mapping.turretRatio(), sign, parts.axisX(), parts.axisY(), parts.cameraOnTurret(), parts.encoders());
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

    private static MecanumConfig chassis(RobotMapping mapping) {
        if (!mapping.drives()) {
            return null;
        }
        MecanumConfig generic = MecanumConfig.generic18Inch();
        return new MecanumConfig(
                mapping.massKg(), generic.lengthM(), generic.widthM(), generic.heightM(), generic.groundClearanceM(),
                generic.comHeightM(), mapping.wheelDiameterMm() / 2000.0, generic.wheelbaseM(), generic.trackM(),
                MotorCatalog.motor(mapping.driveMotor()), generic.wheelInertiaKgM2(), 0.0034, generic.tireStiffnessNPerM(),
                generic.tireDampingNsPerM(), generic.tractionFriction(), generic.slipStiffnessNsPerM(), 0.5, 2.2, 0.12, 0.053);
    }

    private static RobotSpec.Motor wheel(MotorMapping motor, int hub, int port) {
        return new RobotSpec.Motor(hub, port, null, new RobotSpec.Wheel(WheelPosition.valueOf(motor.slot().name()), sign(motor)), null);
    }

    private static RobotSpec.Motor shaft(MotorMapping motor, int hub, int port) {
        double[] load = switch (motor.slot()) {
            case INTAKE -> new double[] {2.0e-4, 2.0e-3};
            case CONVEYOR -> new double[] {1.0e-4, 2.0e-3};
            case SHOOTER -> new double[] {2.5e-4, 8.0e-5};
            default -> new double[] {1.0e-3, 5.0e-3};
        };
        String model = motor.model().isEmpty() ? RobotMapping.usualModel(motor.slot()) : motor.model();
        MotorCatalog.motor(model);
        return new RobotSpec.Motor(hub, port, model, null, new RobotSpec.Shaft(load[0], load[1]));
    }

    private static int sign(MotorMapping motor) {
        return motor.mirrored() ? -1 : 1;
    }

    /** Turning forward at least this fast in the sense the part works in, or backward at least this fast when mirrored. */
    private static Condition turning(MotorMapping motor, double radPerSec) {
        return motor.mirrored()
                ? Condition.atMost(motor.name(), -radPerSec)
                : Condition.atLeast(motor.name(), radPerSec);
    }

    private void mechanisms(
            RobotMapping mapping, RobotSpec.Turret turret, List<StorageSpec> storages, List<CaptureSpec> captures, List<LaunchSpec> launches) {
        List<MotorMapping> intake = mapping.in(Slot.INTAKE);
        List<MotorMapping> conveyor = mapping.in(Slot.CONVEYOR);
        List<MotorMapping> shooter = mapping.in(Slot.SHOOTER);
        if (intake.isEmpty() && conveyor.isEmpty() && shooter.isEmpty()) {
            return;
        }
        List<Vec3> path = List.of(new Vec3(0.27, 0.0, -0.10), new Vec3(0.05, 0.0, 0.0), new Vec3(-0.12, 0.0, 0.26));
        double length = new StorageSpec(STORAGE, 3, path, List.of()).lengthMeters();
        List<StorageSpec.Drive> drives = new ArrayList<>();
        for (MotorMapping motor : intake) {
            drives.add(new StorageSpec.Drive(motor.name(), 0.025, sign(motor), 0.0, 0.20));
        }
        for (MotorMapping motor : conveyor) {
            drives.add(new StorageSpec.Drive(motor.name(), 0.02, sign(motor), 0.15, length));
        }
        storages.add(new StorageSpec(STORAGE, 3, path, drives));
        if (!intake.isEmpty()) {
            captures.add(new CaptureSpec(
                    INTAKE, STORAGE, new Volume(new Vec3(0.30, 0.0, -0.12), new Vec3(0.06, 0.20, 0.07)),
                    intake.stream().map(motor -> turning(motor, 10.0)).toList(), game.minPieceM, game.maxPieceM));
        }
        if (!shooter.isEmpty()) {
            List<LaunchSpec.Wheel> wheels = shooter.stream().map(motor -> new LaunchSpec.Wheel(motor.name(), 0.05, sign(motor))).toList();
            List<Condition> feed = conveyor.isEmpty() ? List.of() : List.of(turning(conveyor.get(0), 20.0));
            LaunchSpec.Turret aim = turret == null || turret.shaft() == null ? null : turret.aim();
            launches.add(new LaunchSpec(LAUNCHER, STORAGE, feed, wheels, 0.49, new Vec3(0.5736, 0.0, 0.8192), 0.3, aim));
        }
    }
}
