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
        CameraMount camera) {

    /** The two hubs have four motor ports each. */
    public static final int MAX_MOTORS = 8;

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

    public RobotMapping {
        Objects.requireNonNull(name, "name");
        motors = List.copyOf(motors);
        Objects.requireNonNull(driveMotor, "driveMotor");
        Objects.requireNonNull(hubLogo, "hubLogo");
        Objects.requireNonNull(hubUsb, "hubUsb");
        Objects.requireNonNull(camera, "camera");
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
        return new RobotMapping(name, changed, massKg, wheelDiameterMm, driveMotor, turretRatio, hubLogo, hubUsb, camera);
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
        if (!DIRECTIONS.contains(hubLogo) || !DIRECTIONS.contains(hubUsb) || axis(hubLogo).equals(axis(hubUsb))) {
            problems.add("Hub logo and USB face at right angles");
        }
        return problems;
    }

    private static String axis(String direction) {
        return switch (direction) {
            case "UP", "DOWN" -> "Z";
            case "FORWARD", "BACKWARD" -> "X";
            default -> "Y";
        };
    }
}
