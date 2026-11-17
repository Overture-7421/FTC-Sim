package dev.ftcsim.mechanisms;

/** A servo on a hub's port as mechanisms and the picture see it: a pulse in, a shaft angle out. */
public interface ServoPlant {

    /**
     * Advances the servo by {@code seconds} with the pulse the hub is sending.
     *
     * @param enabled     whether the hub's output for the servo is on
     * @param pulseMicros the pulse width it is set to
     */
    void step(double seconds, boolean enabled, int pulseMicros);

    /** The output shaft's angle, in radians. */
    double angleRadians();

    /** The output shaft's speed, in radians per second. */
    double speedRadPerSec();
}
