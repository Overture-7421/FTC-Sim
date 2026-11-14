package dev.ftcsim.contracts.world;

/** The source of the chassis's true state for the devices that measure it; read it afresh at every use. */
@FunctionalInterface
public interface BodyView {

    /** The state at the current simulated instant. */
    BodyPose sample();
}
