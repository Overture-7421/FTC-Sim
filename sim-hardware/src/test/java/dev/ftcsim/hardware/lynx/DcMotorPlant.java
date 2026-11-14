package dev.ftcsim.hardware.lynx;

import dev.ftcsim.contracts.hardware.MotorPlant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A brushed DC gearmotor with a rigid inertia on its output shaft, as a motor plant for the hub tests. The winding
 * is a Thevenin source: with the terminals driven at {@code volts} the torque is
 * {@code stallTorque * (volts / nominalVolts - speed / freeSpeed)}, so shorted terminals (volts = 0) brake the shaft
 * and floating terminals leave only viscous friction. A step integrates the resulting linear equation of motion
 * exactly for the voltage held over it.
 */
final class DcMotorPlant implements MotorPlant {

    private static final double TWO_PI = 2.0 * Math.PI;

    private final double stallTorque;
    private final double freeSpeed;
    private final double nominalVolts;
    private final double stallCurrent;
    private final double inertia;
    private final double viscousFriction;
    private final double countsPerRevolution;

    private final List<String> calls = new ArrayList<>();
    private double angle;
    private double speed;
    private double volts;
    private boolean floating = true;

    DcMotorPlant(
            double stallTorque,
            double freeSpeed,
            double nominalVolts,
            double stallCurrent,
            double inertia,
            double viscousFriction,
            double countsPerRevolution) {
        this.stallTorque = stallTorque;
        this.freeSpeed = freeSpeed;
        this.nominalVolts = nominalVolts;
        this.stallCurrent = stallCurrent;
        this.inertia = inertia;
        this.viscousFriction = viscousFriction;
        this.countsPerRevolution = countsPerRevolution;
    }

    /** A goBILDA 5203 435 rpm Yellow Jacket (1.834 N m stall, 9.2 A at 12 V) driving the given extra inertia. */
    static DcMotorPlant goBilda435(double loadInertiaKgM2) {
        double reflectedRotor = 5.0e-6 * 13.7 * 13.7;
        return new DcMotorPlant(
                1.834, 435.0 * TWO_PI / 60.0, 12.0, 9.2, reflectedRotor + loadInertiaKgM2, 5.0e-4, 384.5);
    }

    /** Advances the mechanics by {@code seconds} with the terminals as they were left. */
    void step(double seconds) {
        double back = floating ? 0.0 : stallTorque / freeSpeed;
        double drive = floating ? 0.0 : stallTorque * volts / nominalVolts;
        double damping = back + viscousFriction;
        double rate = damping / inertia;
        double terminalSpeed = drive / damping;
        double decay = Math.exp(-rate * seconds);
        angle += terminalSpeed * seconds + (speed - terminalSpeed) * (1.0 - decay) / rate;
        speed = terminalSpeed + (speed - terminalSpeed) * decay;
    }

    /** The speed in counts per second that the shaft settles to with these terminal volts. */
    double settlingCountsPerSecond(double terminalVolts) {
        double drive = stallTorque * terminalVolts / nominalVolts;
        return drive / (stallTorque / freeSpeed + viscousFriction) / TWO_PI * countsPerRevolution;
    }

    /** What the hub did to the terminals, in order, as text that compares exactly. */
    List<String> calls() {
        return List.copyOf(calls);
    }

    /** The volts last applied to the terminals; meaningful while they are not floating. */
    double volts() {
        return volts;
    }

    boolean isFloating() {
        return floating;
    }

    double angleRadians() {
        return angle;
    }

    @Override
    public void applyVoltage(double terminalVolts) {
        volts = terminalVolts;
        floating = false;
        calls.add(String.format(Locale.ROOT, "volts %.9f", terminalVolts));
    }

    @Override
    public void floatTerminals() {
        floating = true;
        calls.add("float");
    }

    @Override
    public long encoderCounts() {
        return (long) Math.floor(angle / TWO_PI * countsPerRevolution);
    }

    @Override
    public double encoderCountsPerSecond() {
        return speed / TWO_PI * countsPerRevolution;
    }

    @Override
    public double currentAmps() {
        return floating ? 0.0 : Math.abs(stallCurrent * (volts / nominalVolts - speed / freeSpeed));
    }
}
