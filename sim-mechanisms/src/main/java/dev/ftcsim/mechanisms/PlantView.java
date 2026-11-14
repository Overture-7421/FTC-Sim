package dev.ftcsim.mechanisms;

/**
 * What mechanisms may know of the robot's motors. A shaft is named as robot code names the motor; its speed is
 * positive in the direction its encoder counts up, which is the direction positive volts spin it.
 */
public interface PlantView {

    /** Whether the robot has a motor of that name with a shaft that mechanisms can follow. */
    boolean hasShaft(String motor);

    double shaftSpeedRadPerSec(String motor);

    /** Takes energy out of the shaft's rotation, as a flywheel loses what it gives to a piece it launches. */
    void extractEnergy(String motor, double joules);
}
