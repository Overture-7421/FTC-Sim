package dev.ftcsim.hardware.lynx;

/** A digital input as the hub sees the pin: its logic level at a simulated time. */
@FunctionalInterface
public interface DigitalInput {

    /** True when the line is high. */
    boolean level(long nowNanos);
}
