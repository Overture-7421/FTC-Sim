package dev.ftcsim.contracts.hardware;

import java.util.Objects;

/**
 * A device that robot code asked the hardware map for under a name the robot mapping does not place, so that the simulator
 * gave it a port of its own. The robot editor offers these names, so that a person adds the motor the code uses instead of
 * typing its name.
 *
 * @param kind what the code asked for
 * @param name the name the code used
 */
public record UnmappedDevice(Kind kind, String name) {

    /** The kinds of device a robot places by name. */
    public enum Kind {
        MOTOR,
        SERVO,
        I2C,
        DIGITAL,
        ANALOG
    }

    public UnmappedDevice {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(name, "name");
    }
}
