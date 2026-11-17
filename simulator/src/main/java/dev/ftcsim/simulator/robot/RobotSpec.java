package dev.ftcsim.simulator.robot;

import dev.ftcsim.contracts.hardware.HardwareDirectory;
import dev.ftcsim.contracts.session.RobotMapping;
import dev.ftcsim.contracts.snapshot.RobotJoint;
import dev.ftcsim.mechanisms.CaptureSpec;
import dev.ftcsim.mechanisms.LaunchSpec;
import dev.ftcsim.mechanisms.Mechanisms;
import dev.ftcsim.mechanisms.PlantView;
import dev.ftcsim.mechanisms.StorageSpec;
import dev.ftcsim.physics.MecanumConfig;
import dev.ftcsim.physics.PieceSpec;
import dev.ftcsim.physics.WheelPosition;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the rig and the mechanisms are built from: the robot of a game with a student's mapping applied, made by
 * {@link RobotDesign#spec}. The hubs and which motor is on which port, the chassis body, the specs of the mechanisms
 * that handle game pieces, and where the sensors that depend on the robot are.
 *
 * @param name        the robot's name
 * @param hubs        the REV hubs, the parent first
 * @param chassis     the drivetrain body, or null if the robot has no wheels
 * @param motors      by the name robot code uses, in the order they were mapped
 * @param servos      the continuous-rotation servos, by the name robot code uses
 * @param turret      the robot's turret, or null if it has none
 * @param storages    the paths pieces are carried along
 * @param captures    the intakes
 * @param launches    the launchers
 * @param imu         how the Control Hub's IMU is mounted
 * @param colorSensor where the colour sensor is
 * @param limelight   the Limelight's mount and pipeline
 * @param joints      what the window's picture of the robot follows
 */
public record RobotSpec(
        String name,
        List<HardwareDirectory.Hub> hubs,
        MecanumConfig chassis,
        Map<String, Motor> motors,
        Map<String, Servo> servos,
        Turret turret,
        List<StorageSpec> storages,
        List<CaptureSpec> captures,
        List<LaunchSpec> launches,
        ImuMount imu,
        ColorSensor colorSensor,
        Limelight limelight,
        Joints joints) {

    public RobotSpec {
        hubs = List.copyOf(hubs);
        motors = Collections.unmodifiableMap(new LinkedHashMap<>(motors));
        servos = Collections.unmodifiableMap(new LinkedHashMap<>(servos));
        storages = List.copyOf(storages);
        captures = List.copyOf(captures);
        launches = List.copyOf(launches);
        joints = joints == null ? Joints.NONE : joints;
    }

    /**
     * What the window's picture of the robot follows: the motors whose shafts turn its rollers and flywheel, and the
     * servos that move its hood, stopper and colibri arms.
     *
     * @param intake   the intake roller's motor, or null
     * @param conveyor the indexer's motor, or null
     * @param flywheel the flywheel's motor, or null
     * @param servos   the positional servos by the joint each moves
     */
    public record Joints(
            String intake, int intakeSign, String conveyor, int conveyorSign, String flywheel, int flywheelSign,
            List<ServoJoint> servos) {

        /** A robot whose picture has nothing to follow. */
        public static final Joints NONE = new Joints(null, 1, null, 1, null, 1, List.of());

        public Joints {
            servos = List.copyOf(servos);
        }
    }

    /**
     * A positional servo and the joint it moves: the joint's value is {@code rest + servo angle / ratio}.
     *
     * @param ratio turns of the servo for one turn of the part; above 0
     * @param rest  the joint's value in radians when the servo's shaft is at 0
     * @param limit the most the joint can turn from {@code rest}, in radians (a hard stop), or infinity
     */
    public record ServoJoint(RobotJoint joint, String servo, double ratio, double rest, double limit) {
        /** A joint with no stop short of the servo's travel. */
        public ServoJoint(RobotJoint joint, String servo, double ratio, double rest) {
            this(joint, servo, ratio, rest, Double.POSITIVE_INFINITY);
        }
    }

    /**
     * A motor port and what its motor turns: exactly one of {@code wheel} and {@code shaft}.
     *
     * @param hub   the hub address
     * @param port  0..3
     * @param model the catalogue id of a shaft's motor; null for a wheel, which has the chassis's drivetrain motor
     */
    public record Motor(int hub, int port, String model, Wheel wheel, Shaft shaft) {}

    /**
     * A drivetrain wheel.
     *
     * @param polarity +1 when positive volts roll the robot forward, -1 when the motor is mounted the other way round
     */
    public record Wheel(WheelPosition position, int polarity) {}

    /**
     * A shaft that is not a wheel: a flywheel, an intake roller, a conveyor, a turret.
     *
     * @param loadInertiaKgM2   inertia of everything the shaft turns, besides the rotor
     * @param frictionNmsPerRad viscous friction
     */
    public record Shaft(double loadInertiaKgM2, double frictionNmsPerRad) {}

    /**
     * A servo on a hub's servo port: the turret's is continuous-rotation, the mechanisms' go to positions.
     *
     * @param hub        the hub address
     * @param port       0..5
     * @param model      the catalogue id of the servo ({@link ServoCatalog})
     * @param positional whether it goes to the position it is sent, and does not turn without end
     * @param mirrored   for a positional servo, whether its travel runs the other way round
     */
    public record Servo(int hub, int port, String model, boolean positional, boolean mirrored) {

        /** A continuous-rotation servo. */
        public Servo(int hub, int port, String model) {
            this(hub, port, model, false, false);
        }
    }

    /**
     * The robot's turret: what turns it, how, and what rides on it.
     *
     * @param shaft          the name of the motor or servo that turns it, as robot code names it
     * @param ratio          turns of that shaft for one turn of the turret
     * @param sign           +1 when the shaft turning forward turns the turret to the left (counter-clockwise seen from above)
     * @param axisX          where the turret turns about, metres ahead of the chassis centre
     * @param axisY          and metres to the left of it
     * @param cameraOnTurret whether the Limelight turns with it
     * @param encoders       the absolute encoders on the turret, with their models spelled out
     */
    public record Turret(
            String shaft, double ratio, int sign, double axisX, double axisY, boolean cameraOnTurret,
            List<RobotMapping.TurretEncoder> encoders) {

        public Turret {
            encoders = List.copyOf(encoders);
        }

        /** What turns the launcher, as the mechanisms know it. */
        public LaunchSpec.Turret aim() {
            return new LaunchSpec.Turret(shaft, ratio, sign);
        }

        /** The turret's yaw, counter-clockwise from facing forward, when its shaft has turned {@code shaftAngleRad}. */
        public double yawRadians(double shaftAngleRad) {
            return aim().yawRadians(shaftAngleRad);
        }
    }

    /** The directions the Control Hub's logo and USB ports face, as the SDK names them. */
    public record ImuMount(String logo, String usb) {

        /** The settings of the IMU's model. */
        public Map<String, Object> settings() {
            return Map.of("mount.logo", logo, "mount.port", usb);
        }
    }

    /** Where the colour sensor is in the robot frame, and the way it looks: 0 degrees forward, 90 left. */
    public record ColorSensor(double x, double y, double z, double azimuthDegrees) {

        /** The settings of the colour sensor's model. */
        public Map<String, Object> settings() {
            return Map.of("x", x, "y", y, "z", z, "azimuthDegrees", azimuthDegrees);
        }
    }

    /**
     * The Limelight: where it is, and its pipeline 0, which finds AprilTags.
     *
     * @param fieldMap   the tags the pipeline believes in: {@code field} for the fixed tags of the field the robot stands on
     * @param resolution {@code 640x480} or {@code 1280x960}
     * @param onTurret   whether the camera turns with the turret instead of staying on the chassis
     */
    public record Limelight(RobotMapping.CameraMount mount, String fieldMap, String resolution, boolean onTurret) {

        /** A Limelight fixed to the chassis. */
        public Limelight(RobotMapping.CameraMount mount, String fieldMap, String resolution) {
            this(mount, fieldMap, resolution, false);
        }

        /** The settings of the camera's model. */
        public Map<String, Object> settings() {
            Map<String, Object> settings = new LinkedHashMap<>();
            settings.put("mount.x", mount.x());
            settings.put("mount.y", mount.y());
            settings.put("mount.z", mount.z());
            settings.put("mount.yawDegrees", mount.yawDegrees());
            settings.put("mount.pitchDegrees", mount.pitchDegrees());
            settings.put("mount.rollDegrees", mount.rollDegrees());
            settings.put("pipeline0.type", "apriltag");
            settings.put("pipeline0.fieldMap", fieldMap);
            settings.put("pipeline0.resolution", resolution);
            if (onTurret) {
                settings.put("mount.on", "turret");
            }
            return settings;
        }
    }

    /** The address of the hub the robot controller is connected to, which carries the sensors. */
    public int parentAddress() {
        return hubs.stream().filter(HardwareDirectory.Hub::parent).findFirst().orElse(hubs.get(0)).address();
    }

    /**
     * The mechanisms that handle game pieces, over the robot's motors.
     *
     * @param pieceSpecs the physical properties of every kind of piece of the field
     * @throws RobotException if the mechanisms name a motor the robot does not turn as a shaft
     */
    public Mechanisms mechanisms(PlantView plants, Map<Integer, PieceSpec> pieceSpecs) {
        try {
            return new Mechanisms(storages, captures, launches, plants, pieceSpecs);
        } catch (IllegalArgumentException invalid) {
            throw new RobotException("mechanisms: " + invalid.getMessage(), invalid);
        }
    }
}
