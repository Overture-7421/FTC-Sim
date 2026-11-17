package dev.ftcsim.contracts.session;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * What a student says about their robot: which motor of the robot's code is which part of the fixed robot, how each is
 * mounted, and a few numbers of the body. The robot itself (its size, mechanisms, sensors and the physics of its parts)
 * is the fixed design of the game, so that this is all a person has to describe.
 *
 * @param name        the robot's name
 * @param motors      the motors by the name the robot's code gives them; at most {@value #MAX_MOTORS}
 * @param massKg      the robot's mass, in kilograms
 * @param wheelDiameterMm the drive wheels' diameter, in millimetres
 * @param driveMotor  the id of the catalogue motor on all four drive wheels
 * @param turretRatio motor revolutions for one turn of the turret (BIOBUZZ's robot); above 0
 * @param hubLogo     which way the Control Hub's logo faces on the robot, one of {@link #DIRECTIONS}
 * @param hubUsb      which way its USB ports face; at right angles to the logo
 * @param camera      where the Limelight is mounted
 * @param turretParts what else turns or measures the turret (BIOBUZZ's robot): its servo, the absolute encoders on it and
 *                    whether the camera rides on it; {@link TurretParts#NONE} when none of that is mapped
 */
public record RobotMapping(
        String name,
        List<MotorMapping> motors,
        double massKg,
        double wheelDiameterMm,
        String driveMotor,
        double turretRatio,
        String hubLogo,
        String hubUsb,
        CameraMount camera,
        TurretParts turretParts) {

    /** The two hubs have four motor ports each. */
    public static final int MAX_MOTORS = 8;

    /** The OctoQuad has eight channels. */
    public static final int MAX_TURRET_ENCODERS = 8;

    /** The most the turret's axis may be from the robot's centre, in metres. */
    public static final double MAX_TURRET_AXIS_M = 0.5;

    /** The directions a hub's logo and USB ports may face. */
    public static final List<String> DIRECTIONS = List.of("UP", "DOWN", "FORWARD", "BACKWARD", "LEFT", "RIGHT");

    public static final double DEFAULT_MASS_KG = 11.3;
    public static final double DEFAULT_WHEEL_DIAMETER_MM = 96;
    public static final String DEFAULT_DRIVE_MOTOR = "goBILDA-5203-435";

    /** The goBILDA motor with no gearbox, which the flywheels usually turn on. */
    public static final String BARE_MOTOR = "goBILDA-bare-6000";

    /** The parts of the robot a motor can be. A wheel holds one motor; the others hold any number. */
    public enum Slot {
        FRONT_LEFT(true),
        FRONT_RIGHT(true),
        REAR_LEFT(true),
        REAR_RIGHT(true),
        INTAKE(false),
        CONVEYOR(false),
        SHOOTER(false),
        TURRET(false);

        private final boolean wheel;

        Slot(boolean wheel) {
            this.wheel = wheel;
        }

        public boolean wheel() {
            return wheel;
        }
    }

    /**
     * One motor of the robot's code.
     *
     * @param name     the name the code asks the hardware map for
     * @param slot     the part it turns
     * @param model    the id of its catalogue motor; empty for the part's usual one (a wheel takes the drive motor)
     * @param mirrored whether it is mounted the other way round, so that positive power turns its part backwards
     */
    public record MotorMapping(String name, Slot slot, String model, boolean mirrored) {

        public MotorMapping {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(slot, "slot");
            model = model == null ? "" : model;
        }
    }

    /**
     * Where the Limelight is on the robot, in the robot's frame: X forward, Y left, Z up, metres from the centre of its
     * body, and angles in degrees.
     */
    public record CameraMount(double x, double y, double z, double yawDegrees, double pitchDegrees, double rollDegrees) {}

    /**
     * A continuous-rotation servo of the robot's code.
     *
     * @param name     the name the code asks the hardware map for
     * @param model    the id of its servo; empty for the usual one
     * @param mirrored whether turning it forward (a pulse above neutral) turns the turret clockwise instead of counter-clockwise
     */
    public record ServoMapping(String name, String model, boolean mirrored) {

        public ServoMapping {
            Objects.requireNonNull(name, "name");
            model = model == null ? "" : model;
        }
    }

    /**
     * An absolute encoder on the turret, read through the OctoQuad.
     *
     * @param channel            the OctoQuad port, 0 to 7
     * @param model              the id of the encoder; empty for the usual one
     * @param turnsPerTurretTurn how many turns the encoder makes for one turn of the turret; above 0
     * @param zeroOffsetDegrees  the encoder's angle, in degrees of its own turn, when the turret points forward
     * @param mirrored           whether the encoder's pulse falls instead of rising as the turret turns counter-clockwise
     */
    public record TurretEncoder(int channel, String model, double turnsPerTurretTurn, double zeroOffsetDegrees, boolean mirrored) {

        public TurretEncoder {
            model = model == null ? "" : model;
        }
    }

    /** What a servo of the BIOBUZZ robot other than the turret's does. */
    public enum ServoRole {
        /** The hood: its angle sets how high a piece leaves. */
        HOOD,
        /** The stopper that holds pieces in the indexer until it opens. */
        STOPPER,
        /** The left colibri arm, which lifts pieces out of the flower. */
        COLIBRI_LEFT,
        /** The right colibri arm. */
        COLIBRI_RIGHT
    }

    /**
     * A positional servo of the robot's code and the part it moves. The Torque servo of the BIOBUZZ robot is the usual
     * one; {@code mirrored} is a servo mounted so that its position 0 is the end of its travel where 1 would be.
     */
    public record MechanismServo(ServoRole role, ServoMapping servo) {

        public MechanismServo {
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(servo, "servo");
        }
    }

    /**
     * What else is on the turret of a robot that has one.
     *
     * @param servo         the servo that turns it, or null when a motor does (or nothing does)
     * @param encoders      the absolute encoders on it, in the order they were mapped
     * @param axisX         where the turret turns about, metres ahead of the chassis centre
     * @param axisY         and metres to the left of it
     * @param cameraOnTurret whether the Limelight turns with the turret
     * @param mechanisms    the positional servos of the hood, the stopper and the colibri arms, one at most for each role
     */
    public record TurretParts(
            ServoMapping servo, List<TurretEncoder> encoders, double axisX, double axisY, boolean cameraOnTurret,
            List<MechanismServo> mechanisms) {

        /** Nothing mapped: no servo, no encoders, the turret about the centre and the camera on the chassis. */
        public static final TurretParts NONE = new TurretParts(null, List.of(), 0.0, 0.0, false);

        public TurretParts {
            encoders = List.copyOf(encoders);
            mechanisms = List.copyOf(mechanisms);
        }

        /** Parts without any mechanism servo. */
        public TurretParts(ServoMapping servo, List<TurretEncoder> encoders, double axisX, double axisY, boolean cameraOnTurret) {
            this(servo, encoders, axisX, axisY, cameraOnTurret, List.of());
        }

        /** The servo that moves the part of this role, or null. */
        public ServoMapping servoFor(ServoRole role) {
            for (MechanismServo mechanism : mechanisms) {
                if (mechanism.role() == role) {
                    return mechanism.servo();
                }
            }
            return null;
        }

        /** Whether anything about the turret beyond its motors is mapped. */
        public boolean mapped() {
            return servo != null || !encoders.isEmpty() || cameraOnTurret || axisX != 0.0 || axisY != 0.0 || !mechanisms.isEmpty();
        }
    }

    public RobotMapping {
        Objects.requireNonNull(name, "name");
        motors = List.copyOf(motors);
        Objects.requireNonNull(driveMotor, "driveMotor");
        Objects.requireNonNull(hubLogo, "hubLogo");
        Objects.requireNonNull(hubUsb, "hubUsb");
        Objects.requireNonNull(camera, "camera");
        Objects.requireNonNull(turretParts, "turretParts");
    }

    /** A mapping with nothing on the turret but its motors. */
    public RobotMapping(
            String name, List<MotorMapping> motors, double massKg, double wheelDiameterMm, String driveMotor, double turretRatio,
            String hubLogo, String hubUsb, CameraMount camera) {
        this(name, motors, massKg, wheelDiameterMm, driveMotor, turretRatio, hubLogo, hubUsb, camera, TurretParts.NONE);
    }

    /** A robot with no motors and the usual numbers: nothing is mapped yet. */
    public static RobotMapping empty(String name) {
        return new RobotMapping(
                name, List.of(), DEFAULT_MASS_KG, DEFAULT_WHEEL_DIAMETER_MM, DEFAULT_DRIVE_MOTOR, 1.0, "UP", "FORWARD",
                new CameraMount(0.16, 0.0, 0.25, 0.0, 20.0, 0.0));
    }

    /** The catalogue motor a part has when a mapping names none: the flywheels turn on the bare motor, everything else on the 435 rpm one. */
    public static String usualModel(Slot slot) {
        return slot == Slot.SHOOTER ? BARE_MOTOR : DEFAULT_DRIVE_MOTOR;
    }

    /** A copy with these motors. */
    public RobotMapping withMotors(List<MotorMapping> changed) {
        return new RobotMapping(name, changed, massKg, wheelDiameterMm, driveMotor, turretRatio, hubLogo, hubUsb, camera, turretParts);
    }

    /** A copy with this on the turret. */
    public RobotMapping withTurretParts(TurretParts changed) {
        return new RobotMapping(name, motors, massKg, wheelDiameterMm, driveMotor, turretRatio, hubLogo, hubUsb, camera, changed);
    }

    /** The motors in [slot], in the order they were mapped. */
    public List<MotorMapping> in(Slot slot) {
        return motors.stream().filter(motor -> motor.slot() == slot).toList();
    }

    /** Whether the four drive wheels are mapped. */
    public boolean drives() {
        return in(Slot.FRONT_LEFT).size() + in(Slot.FRONT_RIGHT).size() + in(Slot.REAR_LEFT).size() + in(Slot.REAR_RIGHT).size() == 4;
    }

    /**
     * What is wrong with this mapping, one short sentence each; empty when the simulator can build the robot from it.
     *
     * @param turret whether the game's robot has a turret, so that a motor may be mapped to it
     */
    public List<String> problems(boolean turret) {
        List<String> problems = new ArrayList<>();
        if (name.isBlank()) {
            problems.add("The robot has no name");
        }
        if (motors.size() > MAX_MOTORS) {
            problems.add("At most " + MAX_MOTORS + " motors fit on two hubs");
        }
        Set<String> names = new HashSet<>();
        for (MotorMapping motor : motors) {
            if (motor.name().isBlank()) {
                problems.add("A motor has no name");
            } else if (!names.add(motor.name())) {
                problems.add(motor.name() + " is mapped twice");
            }
            if (motor.slot() == Slot.TURRET && !turret) {
                problems.add(motor.name() + ": this game's robot has no turret");
            }
        }
        int wheels = 0;
        for (Slot slot : Slot.values()) {
            if (!slot.wheel()) {
                continue;
            }
            int count = in(slot).size();
            wheels += count;
            if (count > 1) {
                problems.add("More than one motor on " + slot.name().toLowerCase().replace('_', ' '));
            }
        }
        if (wheels != 0 && wheels != 4) {
            problems.add("Map all four wheels or none");
        }
        if (!(massKg >= 1 && massKg <= 40)) {
            problems.add("Mass is 1 to 40 kg");
        }
        if (!(wheelDiameterMm >= 40 && wheelDiameterMm <= 200)) {
            problems.add("Wheel diameter is 40 to 200 mm");
        }
        if (!(turretRatio > 0)) {
            problems.add("Turret ratio is above 0");
        }
        turretProblems(turret, names, problems);
        if (!DIRECTIONS.contains(hubLogo) || !DIRECTIONS.contains(hubUsb) || axis(hubLogo).equals(axis(hubUsb))) {
            problems.add("Hub logo and USB face at right angles");
        }
        return problems;
    }

    private void turretProblems(boolean turret, Set<String> motorNames, List<String> problems) {
        TurretParts parts = turretParts;
        if (!parts.mapped()) {
            return;
        }
        if (!turret) {
            problems.add("This game's robot has no turret for a servo, encoders or an axis");
            return;
        }
        ServoMapping servo = parts.servo();
        if (servo != null) {
            if (servo.name().isBlank()) {
                problems.add("The turret servo has no name");
            } else if (motorNames.contains(servo.name())) {
                problems.add(servo.name() + " is mapped as a motor and as the turret servo");
            }
            if (!in(Slot.TURRET).isEmpty()) {
                problems.add("The turret has a motor and a servo: map one of them");
            }
        }
        if (parts.encoders().size() > MAX_TURRET_ENCODERS) {
            problems.add("At most " + MAX_TURRET_ENCODERS + " encoders fit on the OctoQuad");
        }
        Set<Integer> channels = new HashSet<>();
        for (TurretEncoder encoder : parts.encoders()) {
            if (encoder.channel() < 0 || encoder.channel() >= MAX_TURRET_ENCODERS) {
                problems.add("An encoder is on OctoQuad port " + encoder.channel() + ", which is 0 to 7");
            } else if (!channels.add(encoder.channel())) {
                problems.add("OctoQuad port " + encoder.channel() + " has two encoders");
            }
            if (!(encoder.turnsPerTurretTurn() > 0 && Double.isFinite(encoder.turnsPerTurretTurn()))) {
                problems.add("Encoder " + encoder.channel() + ": turns per turret turn is above 0");
            }
            if (!Double.isFinite(encoder.zeroOffsetDegrees())) {
                problems.add("Encoder " + encoder.channel() + ": zero offset is a number");
            }
        }
        if (!(Math.abs(parts.axisX()) <= MAX_TURRET_AXIS_M && Math.abs(parts.axisY()) <= MAX_TURRET_AXIS_M)) {
            problems.add("The turret axis is within " + MAX_TURRET_AXIS_M + " m of the robot's centre");
        }
        Set<ServoRole> roles = new HashSet<>();
        Set<String> servoNames = new HashSet<>();
        if (parts.servo() != null) {
            servoNames.add(parts.servo().name());
        }
        for (MechanismServo mechanism : parts.mechanisms()) {
            String name = mechanism.servo().name();
            String role = mechanism.role().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
            if (!roles.add(mechanism.role())) {
                problems.add("Two servos are the " + role);
            }
            if (name.isBlank()) {
                problems.add("The " + role + " servo has no name");
            } else if (motorNames.contains(name)) {
                problems.add(name + " is mapped as a motor and as the " + role + " servo");
            } else if (!servoNames.add(name)) {
                problems.add(name + " is the servo of two parts");
            }
        }
    }

    private static String axis(String direction) {
        return switch (direction) {
            case "UP", "DOWN" -> "Z";
            case "FORWARD", "BACKWARD" -> "X";
            default -> "Y";
        };
    }
}
