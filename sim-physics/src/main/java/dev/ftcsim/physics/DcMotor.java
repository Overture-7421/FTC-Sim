package dev.ftcsim.physics;

/**
 * Brushed DC motor with its gearbox, described at the output shaft. It is plain arithmetic without
 * any engine dependency so that mechanism plants can use it as well as drivetrain wheels.
 *
 * <p>The windings give a torque that falls linearly with speed and rises linearly with the applied
 * voltage: {@code torque = stallTorque * (volts / nominalVolts - speed / freeSpeed)}.
 *
 * @param stallTorqueNm      output torque at standstill with {@code nominalVolts} applied
 * @param freeSpeedRadPerSec unloaded output speed with {@code nominalVolts} applied
 * @param stallCurrentA      winding current at standstill with {@code nominalVolts} applied
 * @param rotorInertiaKgM2   inertia of the rotor on the motor side of the gearbox
 * @param gearRatio          motor revolutions per output revolution
 */
public record DcMotor(
        double stallTorqueNm,
        double freeSpeedRadPerSec,
        double nominalVolts,
        double stallCurrentA,
        double rotorInertiaKgM2,
        double gearRatio,
        double encoderTicksPerRevolution) {

    /**
     * The motor as seen from the load: {@code zeroSpeedTorque - damping * speed}. Both numbers stay
     * constant over a step, so a load that integrates its own speed implicitly can solve for it.
     */
    public record Thevenin(double zeroSpeedTorqueNm, double dampingNmsPerRad) {

        public double torqueNm(double speedRadPerSec) {
            return zeroSpeedTorqueNm - dampingNmsPerRad * speedRadPerSec;
        }
    }

    public DcMotor {
        if (!(stallTorqueNm > 0 && freeSpeedRadPerSec > 0 && nominalVolts > 0 && stallCurrentA > 0
                && rotorInertiaKgM2 >= 0 && gearRatio > 0 && encoderTicksPerRevolution > 0)) {
            throw new IllegalArgumentException("motor parameters must be positive");
        }
    }

    /** goBILDA 5203 Yellow Jacket, 13.7:1 (435 rpm, 18.7 kg-cm stall, 9.2 A stall at 12 V, 384.5 ticks per revolution). */
    public static DcMotor goBilda435() {
        // The rotor inertia is an estimate for a 555-class motor (mechanical time constant of about 18 ms).
        return new DcMotor(1.834, 435.0 * 2.0 * Math.PI / 60.0, 12.0, 9.2, 5.0e-6, 13.7, 384.5);
    }

    /** Equivalent circuit for a normalized command in [-1, 1]; the winding sees {@code command * batteryVolts}. */
    public Thevenin thevenin(double command, double batteryVolts) {
        return theveninAtVolts(appliedVolts(command, batteryVolts));
    }

    /** Equivalent circuit with the winding held at {@code volts}, which a motor port's output stage can set directly. */
    public Thevenin theveninAtVolts(double volts) {
        return new Thevenin(stallTorqueNm * volts / nominalVolts, stallTorqueNm / freeSpeedRadPerSec);
    }

    /** Winding current; it is negative while the back EMF exceeds the applied voltage. */
    public double currentAmps(double command, double batteryVolts, double speedRadPerSec) {
        return currentAmpsAtVolts(appliedVolts(command, batteryVolts), speedRadPerSec);
    }

    /** Winding current with the winding held at {@code volts}; negative while the back EMF exceeds them. */
    public double currentAmpsAtVolts(double volts, double speedRadPerSec) {
        return stallCurrentA * (volts / nominalVolts - speedRadPerSec / freeSpeedRadPerSec);
    }

    /** Rotor inertia as the output shaft feels it. */
    public double reflectedRotorInertiaKgM2() {
        return rotorInertiaKgM2 * gearRatio * gearRatio;
    }

    private static double appliedVolts(double command, double batteryVolts) {
        return Math.max(-1.0, Math.min(1.0, command)) * batteryVolts;
    }
}
