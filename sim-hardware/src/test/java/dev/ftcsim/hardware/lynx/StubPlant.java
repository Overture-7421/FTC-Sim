package dev.ftcsim.hardware.lynx;

import dev.ftcsim.contracts.hardware.MotorPlant;

/** A plant whose readings the test sets directly, to probe the wire formats at their limits. */
class StubPlant implements MotorPlant {

    long counts;
    double countsPerSecond;
    double amps;
    boolean floating = true;
    double volts;

    @Override
    public void applyVoltage(double terminalVolts) {
        floating = false;
        volts = terminalVolts;
    }

    @Override
    public void floatTerminals() {
        floating = true;
    }

    @Override
    public long encoderCounts() {
        return counts;
    }

    @Override
    public double encoderCountsPerSecond() {
        return countsPerSecond;
    }

    @Override
    public double currentAmps() {
        return amps;
    }
}
