package dev.ftcsim.contracts.hardware;

/**
 * Anything a motor port of a hub can drive: a wheel, a flywheel, an intake roller. The hub's output stage sets
 * the voltage; the plant integrates its own mechanics and reports what the motor's encoder and current sensor
 * would. A plant never learns what the hub is doing, and the hub never learns what the plant is attached to.
 */
public interface MotorPlant extends EncoderSource {

    /** Drives the motor terminals with this signed voltage, in volts, until the next call. Zero volts is braking. */
    void applyVoltage(double volts);

    /** Disconnects the motor terminals, so it coasts: no current flows and only friction and load act on it. */
    void floatTerminals();

    /** The encoder's count since the plant was created. Positive voltage makes it increase. */
    @Override
    long encoderCounts();

    /** The encoder's velocity in counts per second, signed. */
    @Override
    double encoderCountsPerSecond();

    /** The current the motor draws from the supply, in amperes, never negative. */
    double currentAmps();
}
