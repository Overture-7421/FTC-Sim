package dev.ftcsim.hardware.device;

/** Work a device does after each physics step, such as sampling where the chassis is. */
@FunctionalInterface
public interface StepListener {

    /** Called once per physics step, after the world has moved, with that step's simulated time. */
    void afterStep(long stepNanos);
}
