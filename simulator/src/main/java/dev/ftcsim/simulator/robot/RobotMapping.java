package dev.ftcsim.simulator.robot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * What the simulator says about a robot: which motor of the robot's code is which part of the fixed robot, how each is
 * mounted, where its sensors are and a few numbers of the body. {@link RobotProfiles} holds the two robots' data;
 * {@link RobotDesign} turns it into a {@link RobotSpec}.
 *
 * @param name        the robot's name
 * @param motors      the motors by the name the robot's code gives them; at most {@value #MAX_MOTORS}
 * @param massKg      the robot's mass, in kilograms
 * @param wheelDiameterMm the drive wheels' diameter, in millimetres
 * @param driveMotor  the id of the catalogue motor on all four drive wheels
 * @param turretRatio motor revolutions for one turn of the turret (BIOBUZZ's robot)
 * @param hubLogo     which way the Control Hub's logo faces on the robot: UP, DOWN, FORWARD, BACKWARD, LEFT or RIGHT
 * @param hubUsb      which way its USB ports face; at right angles to the logo
 * @param camera      where the Limelight is mounted
 * @param turretParts what else turns or measures the turret (BIOBUZZ's robot): its servo, the absolute encoders on it and
 *                    whether the camera rides on it; {@link TurretParts#NONE} when none of that is there
 * @param driveRatio  motor turns to one turn of a drive wheel, the same for all four
 * @param colorSensors the REV Color Sensor V3s by the name the code asks for; the first is on the Control Hub, the second on the
 *                    Expansion Hub
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
        TurretParts turretParts,
        double driveRatio,
        List<ColorSensorMapping> colorSensors) {

    /** The two hubs have four motor ports each. */
    public static final int MAX_MOTORS = 8;

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
     * @param model    the id of its catalogue motor; ignored for a wheel, which takes {@link RobotMapping#driveMotor()}
     * @param mirrored whether it is mounted the other way round, so that positive power turns its part backwards
     * @param ratio    motor turns to one turn of what it drives (2 for a 2:1, 43/30 for a belt). A wheel's is
     *                 {@link RobotMapping#driveRatio()}, not this
     */
    public record MotorMapping(String name, Slot slot, String model, boolean mirrored, double ratio) {

        public MotorMapping {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(slot, "slot");
            Objects.requireNonNull(model, "model");
        }

        /** A motor with a ratio of 1. */
        public MotorMapping(String name, Slot slot, String model, boolean mirrored) {
            this(name, slot, model, mirrored, 1.0);
        }
    }

    /**
     * Where the Limelight is on the robot, in the robot's frame: X forward, Y left, Z up, metres from the centre of its
     * body, and angles in degrees.
     */
    public record CameraMount(double x, double y, double z, double yawDegrees, double pitchDegrees, double rollDegrees) {}

    /**
     * A REV Color Sensor V3, looking along one ray.
     *
     * @param name           the name the code asks the hardware map for
     * @param x              where it is, robot frame, metres ahead of the centre of the body
     * @param y              and metres to the left of it
     * @param z              and metres above it
     * @param azimuthDegrees which way it looks, counter-clockwise from forward (-90 is to the right)
     */
    public record ColorSensorMapping(String name, double x, double y, double z, double azimuthDegrees) {

        public ColorSensorMapping {
            Objects.requireNonNull(name, "name");
        }
    }

    /**
     * A servo of the robot's code.
     *
     * @param name     the name the code asks the hardware map for
     * @param model    the id of its {@link ServoCatalog} servo
     * @param mirrored whether turning it forward (a pulse above neutral) turns the turret clockwise instead of counter-clockwise
     */
    public record ServoMapping(String name, String model, boolean mirrored) {

        public ServoMapping {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(model, "model");
        }
    }

    /**
     * An absolute encoder on the turret, read through the OctoQuad.
     *
     * @param channel            the OctoQuad port, 0 to 7
     * @param model              the id of the encoder
     * @param turnsPerTurretTurn how many turns the encoder makes for one turn of the turret
     * @param zeroOffsetDegrees  the encoder's angle, in degrees of its own turn, when the turret points forward
     * @param mirrored           whether the encoder's pulse falls instead of rising as the turret turns counter-clockwise
     */
    public record TurretEncoder(int channel, String model, double turnsPerTurretTurn, double zeroOffsetDegrees, boolean mirrored) {

        public TurretEncoder {
            Objects.requireNonNull(model, "model");
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

    /** A positional servo of the robot's code and the part it moves. */
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
     * @param encoders      the absolute encoders on it
     * @param axisX         where the turret turns about, metres ahead of the chassis centre
     * @param axisY         and metres to the left of it
     * @param cameraOnTurret whether the Limelight turns with the turret
     * @param mechanisms    the positional servos of the hood, the stopper and the colibri arms, one at most for each role
     */
    public record TurretParts(
            ServoMapping servo, List<TurretEncoder> encoders, double axisX, double axisY, boolean cameraOnTurret,
            List<MechanismServo> mechanisms) {

        /** Nothing on the turret: no servo, no encoders, the turret about the centre and the camera on the chassis. */
        public static final TurretParts NONE = new TurretParts(null, List.of(), 0.0, 0.0, false, List.of());

        public TurretParts {
            encoders = List.copyOf(encoders);
            mechanisms = List.copyOf(mechanisms);
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
    }

    public RobotMapping {
        Objects.requireNonNull(name, "name");
        motors = List.copyOf(motors);
        Objects.requireNonNull(driveMotor, "driveMotor");
        Objects.requireNonNull(hubLogo, "hubLogo");
        Objects.requireNonNull(hubUsb, "hubUsb");
        Objects.requireNonNull(camera, "camera");
        Objects.requireNonNull(turretParts, "turretParts");
        colorSensors = List.copyOf(colorSensors);
    }

    /** A robot with no motors, no turret and no sensors, on the numbers of Hornet's chassis; a base for tests to add to. */
    public static RobotMapping empty(String name) {
        return new RobotMapping(
                name, List.of(), 11.3, 96, MotorCatalog.GOBILDA_BARE, 1.0, "UP", "FORWARD",
                new CameraMount(0.16, 0.0, 0.25, 0.0, 20.0, 0.0), TurretParts.NONE, 13.7, List.of());
    }

    /** A copy with these motors. */
    public RobotMapping withMotors(List<MotorMapping> changed) {
        return new RobotMapping(
                name, changed, massKg, wheelDiameterMm, driveMotor, turretRatio, hubLogo, hubUsb, camera, turretParts, driveRatio, colorSensors);
    }

    /** A copy with this on the turret. */
    public RobotMapping withTurretParts(TurretParts changed) {
        return new RobotMapping(
                name, motors, massKg, wheelDiameterMm, driveMotor, turretRatio, hubLogo, hubUsb, camera, changed, driveRatio, colorSensors);
    }

    /** A copy with this drive motor and reduction. */
    public RobotMapping withDrive(String changedMotor, double changedRatio) {
        return new RobotMapping(
                name, motors, massKg, wheelDiameterMm, changedMotor, turretRatio, hubLogo, hubUsb, camera, turretParts, changedRatio, colorSensors);
    }

    /** A copy with these colour sensors. */
    public RobotMapping withColorSensors(List<ColorSensorMapping> changed) {
        return new RobotMapping(
                name, motors, massKg, wheelDiameterMm, driveMotor, turretRatio, hubLogo, hubUsb, camera, turretParts, driveRatio, changed);
    }

    /** The motors in [slot], in the order they were listed. */
    public List<MotorMapping> in(Slot slot) {
        return motors.stream().filter(motor -> motor.slot() == slot).toList();
    }

    /** Whether the four drive wheels are there. */
    public boolean drives() {
        return in(Slot.FRONT_LEFT).size() + in(Slot.FRONT_RIGHT).size() + in(Slot.REAR_LEFT).size() + in(Slot.REAR_RIGHT).size() == 4;
    }

    /** What stops the simulator building the robot from this, one short sentence each; empty when it can. */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (motors.size() > MAX_MOTORS) {
            problems.add("At most " + MAX_MOTORS + " motors fit on two hubs");
        }
        Set<String> names = new HashSet<>();
        for (MotorMapping motor : motors) {
            if (!names.add(motor.name())) {
                problems.add(motor.name() + " is listed twice");
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
                problems.add("More than one motor on " + slot.name().toLowerCase(Locale.ROOT).replace('_', ' '));
            }
        }
        if (wheels != 0 && wheels != 4) {
            problems.add("Four wheels or none");
        }
        servoProblems(names, problems);
        Set<String> sensors = new HashSet<>();
        for (ColorSensorMapping sensor : colorSensors) {
            if (!sensors.add(sensor.name())) {
                problems.add(sensor.name() + " is listed twice");
            }
        }
        if (colorSensors.size() > 2) {
            problems.add("At most 2 colour sensors have an I2C bus");
        }
        return problems;
    }

    private void servoProblems(Set<String> motorNames, List<String> problems) {
        Set<String> servoNames = new HashSet<>();
        ServoMapping turretServo = turretParts.servo();
        if (turretServo != null) {
            servoNames.add(turretServo.name());
            if (!in(Slot.TURRET).isEmpty()) {
                problems.add("The turret has a motor and a servo: one of them");
            }
        }
        Set<ServoRole> roles = new HashSet<>();
        for (MechanismServo mechanism : turretParts.mechanisms()) {
            if (!roles.add(mechanism.role())) {
                problems.add("Two servos are the " + mechanism.role().name().toLowerCase(Locale.ROOT).replace('_', ' '));
            }
            if (!servoNames.add(mechanism.servo().name())) {
                problems.add(mechanism.servo().name() + " is the servo of two parts");
            }
        }
        for (String servoName : servoNames) {
            if (motorNames.contains(servoName)) {
                problems.add(servoName + " is a motor and a servo");
            }
        }
    }
}
