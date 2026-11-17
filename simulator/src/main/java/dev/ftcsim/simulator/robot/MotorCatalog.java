package dev.ftcsim.simulator.robot;

import dev.ftcsim.contracts.session.MotorChoice;
import dev.ftcsim.physics.DcMotor;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The motors a mapping can name: the base motors a team gives its own reduction to (the mapping's ratios), each with its
 * published numbers at 12 V. A 435 rpm Yellow Jacket is the 6000 rpm one with a ratio of 13.7.
 */
public final class MotorCatalog {

    public static final String GOBILDA_BARE = "goBILDA-bare-6000";
    public static final String SWYFT_SPIKE = "swyft-spike-6000";
    public static final String REV_HD_HEX = "rev-hd-hex-6000";
    /** Core Hex, NeveRest 40 and TorqueNADO are sold with their gearbox on: a ratio is given on top of it. */
    public static final String REV_CORE_HEX = "rev-core-hex";
    public static final String NEVEREST_40 = "andymark-neverest-40";
    public static final String TORQUENADO = "tetrix-torquenado-60";

    private static final double KG_CM = 0.0980665;
    private static final double OZ_IN = 0.00706155;
    private static final double RPM = 2.0 * Math.PI / 60.0;
    /** The rotor of a 550-class motor, estimated; a gearmotor's is reflected through its built-in gearbox. */
    private static final double ROTOR_INERTIA = 5.0e-6;

    /** The bare motor's encoder: 28 counts per turn of the rotor, before any gearbox. */
    private static final double CORE_TICKS_PER_REVOLUTION = 28.0;

    private static final Map<String, DcMotor> MOTORS = new TreeMap<>();

    static {
        // The Yellow Jacket's winding with no gearbox, from the measured 435 rpm one (which is what Hornet's flywheels were fitted on).
        MOTORS.put(GOBILDA_BARE, behindGearbox(DcMotor.goBilda435(), 1.0));
        // Published numbers at 12 V: free speed, stall torque, stall current, built-in reduction, encoder counts per turn of the output.
        MOTORS.put(SWYFT_SPIKE, published(6000, 1.47 * KG_CM, 9.2, 1.0, 28));
        MOTORS.put(REV_HD_HEX, published(6000, 0.105, 8.5, 1.0, 28));
        MOTORS.put(REV_CORE_HEX, published(125, 3.2, 4.4, 72.0, 288));
        MOTORS.put(NEVEREST_40, published(160, 2.472, 11.5, 40.0, 1120));
        MOTORS.put(TORQUENADO, published(100, 700 * OZ_IN, 8.7, 60.0, 1440));
    }

    private MotorCatalog() {}

    private static DcMotor published(double rpm, double stallNm, double stallAmps, double builtInReduction, double ticksPerRevolution) {
        return new DcMotor(stallNm, rpm * RPM, 12.0, stallAmps, ROTOR_INERTIA, builtInReduction, ticksPerRevolution);
    }

    /** What a window offers: the base motors, each once, with a name for people. */
    public static List<MotorChoice> choices() {
        return List.of(
                new MotorChoice(GOBILDA_BARE, "goBILDA Yellow Jacket (6000 rpm)"),
                new MotorChoice(SWYFT_SPIKE, "SWYFT Spike 550 (6000 rpm)"),
                new MotorChoice(REV_HD_HEX, "REV HD Hex motor (6000 rpm)"),
                new MotorChoice(REV_CORE_HEX, "REV Core Hex (72:1 built in)"),
                new MotorChoice(NEVEREST_40, "AndyMark NeveRest 40 (40:1 built in)"),
                new MotorChoice(TORQUENADO, "TETRIX TorqueNADO (60:1 built in)"));
    }
    /** The core of {@code measured}, a motor with a gearbox, behind a gearbox of its own with the given reduction. */
    private static DcMotor behindGearbox(DcMotor measured, double reduction) {
        return new DcMotor(
                measured.stallTorqueNm() / measured.gearRatio() * reduction,
                measured.freeSpeedRadPerSec() * measured.gearRatio() / reduction,
                measured.nominalVolts(),
                measured.stallCurrentA(),
                measured.rotorInertiaKgM2(),
                reduction,
                CORE_TICKS_PER_REVOLUTION * reduction);
    }

    /**
     * The motor of that name behind a further reduction: what the drive wheels have, a wheel being on the output of
     * {@code ratio} turns of the motor. The encoder counts the motor's turns, so it counts {@code ratio} times as many for a
     * turn of the wheel. A ratio of 1 is the motor itself.
     */
    public static DcMotor motor(String name, double ratio) {
        DcMotor base = motor(name);
        if (ratio == 1.0) {
            return base;
        }
        return new DcMotor(
                base.stallTorqueNm() * ratio, base.freeSpeedRadPerSec() / ratio, base.nominalVolts(), base.stallCurrentA(),
                base.rotorInertiaKgM2(), base.gearRatio() * ratio, base.encoderTicksPerRevolution() * ratio);
    }

    /** The motor of that name; the message of the exception lists the known names. */
    public static DcMotor motor(String name) {
        DcMotor motor = MOTORS.get(name);
        if (motor == null) {
            throw new IllegalArgumentException("unknown motor '" + name + "'; the known motors are " + MOTORS.keySet());
        }
        return motor;
    }
}
