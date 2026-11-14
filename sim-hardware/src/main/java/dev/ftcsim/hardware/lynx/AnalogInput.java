package dev.ftcsim.hardware.lynx;

/** An analog input as the hub sees the pin: the voltage on it at a simulated time. */
@FunctionalInterface
public interface AnalogInput {

    /** The top of the range the hub's converter measures, in volts; the bottom is zero. */
    double FULL_SCALE_VOLTS = 3.3;

    /** The pin voltage in volts; the hub clamps it to the 0 to {@link #FULL_SCALE_VOLTS} its converter can measure. */
    double volts(long nowNanos);
}
