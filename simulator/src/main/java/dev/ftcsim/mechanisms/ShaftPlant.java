package dev.ftcsim.mechanisms;

import dev.ftcsim.contracts.hardware.MotorPlant;
import dev.ftcsim.physics.DcMotor;

/**
 * One motor turning a load through its gearbox: a flywheel, an intake roller, a conveyor. The hub's output stage
 * sets the voltage on the winding; the plant integrates the shaft's own motion and reports what the encoder and the
 * current sensor would show. Nothing here knows what the shaft is attached to.
 *
 * <p>The shaft obeys {@code J dw/dt = zeroSpeedTorque - (motorDamping + friction) * w}, which is linear while the
 * terminals stay as they are, so a step integrates it exactly: the result does not depend on how a span of time
 * is cut into steps. Floating terminals take the winding out of the equation and leave only friction; zero volts
 * shorts the winding, which brakes the shaft.
 */
public final class ShaftPlant implements MotorPlant {

    private static final double TWO_PI = 2.0 * Math.PI;

    private final DcMotor motor;
    private final double inertiaKgM2;
    private final double frictionNmsPerRad;
    private final double ticksPerRadian;

    private double angleRadians;
    private double speedRadPerSec;
    private double volts;
    private boolean floating = true;

    /**
     * @param motor             the motor and gearbox; speeds and angles here are at its output shaft
     * @param loadInertiaKgM2   inertia of everything the output shaft turns, besides the rotor
     * @param frictionNmsPerRad viscous friction of the load and bearings, at the output shaft
     */
    public ShaftPlant(DcMotor motor, double loadInertiaKgM2, double frictionNmsPerRad) {
        if (!(loadInertiaKgM2 >= 0.0 && frictionNmsPerRad >= 0.0)) {
            throw new IllegalArgumentException("inertia and friction cannot be negative");
        }
        this.motor = motor;
        this.inertiaKgM2 = motor.reflectedRotorInertiaKgM2() + loadInertiaKgM2;
        if (!(inertiaKgM2 > 0.0)) {
            throw new IllegalArgumentException("a shaft needs some inertia");
        }
        this.frictionNmsPerRad = frictionNmsPerRad;
        this.ticksPerRadian = motor.encoderTicksPerRevolution() / TWO_PI;
    }

    /** Advances the shaft by {@code seconds} with the terminals as the hub last left them. */
    public void step(double seconds) {
        double drive = 0.0;
        double damping = frictionNmsPerRad;
        if (!floating) {
            DcMotor.Thevenin winding = motor.theveninAtVolts(volts);
            drive = winding.zeroSpeedTorqueNm();
            damping += winding.dampingNmsPerRad();
        }
        if (damping == 0.0) {
            angleRadians += (speedRadPerSec + drive / inertiaKgM2 * seconds / 2.0) * seconds;
            speedRadPerSec += drive / inertiaKgM2 * seconds;
            return;
        }
        double rate = damping / inertiaKgM2;
        double terminalSpeed = drive / damping;
        double decay = StrictMath.exp(-rate * seconds);
        angleRadians += terminalSpeed * seconds + (speedRadPerSec - terminalSpeed) * (1.0 - decay) / rate;
        speedRadPerSec = terminalSpeed + (speedRadPerSec - terminalSpeed) * decay;
    }

    /** The output shaft's angle since the plant was created. */
    public double angleRadians() {
        return angleRadians;
    }

    /** The output shaft's angular speed; positive while positive volts spin it up. */
    public double speedRadPerSec() {
        return speedRadPerSec;
    }

    /**
     * Takes kinetic energy out of the shaft's rotation, as a flywheel loses what it gives to a piece it launches.
     * The shaft slows down in the direction it was turning and never reverses because of it.
     */
    public void extractEnergy(double joules) {
        if (!(joules >= 0.0)) {
            throw new IllegalArgumentException("energy to extract cannot be negative: " + joules);
        }
        double remaining = inertiaKgM2 * speedRadPerSec * speedRadPerSec / 2.0 - joules;
        speedRadPerSec = remaining <= 0.0 ? 0.0 : Math.copySign(StrictMath.sqrt(2.0 * remaining / inertiaKgM2), speedRadPerSec);
    }

    @Override
    public void applyVoltage(double terminalVolts) {
        volts = terminalVolts;
        floating = false;
    }

    @Override
    public void floatTerminals() {
        floating = true;
    }

    @Override
    public long encoderCounts() {
        return (long) Math.floor(angleRadians * ticksPerRadian);
    }

    @Override
    public double encoderCountsPerSecond() {
        return speedRadPerSec * ticksPerRadian;
    }

    @Override
    public double currentAmps() {
        return floating ? 0.0 : Math.abs(motor.currentAmpsAtVolts(volts, speedRadPerSec));
    }
}
