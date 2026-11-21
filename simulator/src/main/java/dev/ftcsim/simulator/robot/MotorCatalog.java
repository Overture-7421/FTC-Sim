package dev.ftcsim.simulator.robot;

import dev.ftcsim.physics.DcMotor;
import java.util.Map;
import java.util.TreeMap;

/**
 * The motors of the two robots: the base motors each is given its own reduction to (the profiles' ratios), with their
 * published numbers at 12 V. A 435 rpm Yellow Jacket is the 6000 rpm one with a ratio of 13.7.
 */
public final class MotorCatalog {

    public static final String GOBILDA_BARE = "goBILDA-bare-6000";
    public static final String SWYFT_SPIKE = "swyft-spike-6000";

    private static final double KG_CM = 0.0980665;
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
    }

    private MotorCatalog() {}

    private static DcMotor published(double rpm, double stallNm, double stallAmps, double builtInReduction, double ticksPerRevolution) {
        return new DcMotor(stallNm, rpm * RPM, 12.0, stallAmps, ROTOR_INERTIA, builtInReduction, ticksPerRevolution);
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
