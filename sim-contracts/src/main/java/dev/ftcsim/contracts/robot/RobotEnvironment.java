package dev.ftcsim.contracts.robot;

import dev.ftcsim.contracts.hardware.CameraWire;
import dev.ftcsim.contracts.hardware.HardwareDirectory;
import dev.ftcsim.contracts.hardware.HubWire;
import java.util.Objects;

/**
 * Everything outside the SDK that the simulated robot controller is connected to: the serial bus its hubs sit
 * on, the robot's account of what is plugged in where, and the smart cameras on its USB ports. The simulator
 * builds it; the SDK host only uses it.
 *
 * @param hubs      the bus the robot controller talks to its REV hubs over
 * @param directory where each named device is wired
 * @param cameras   the link to the robot's smart cameras; {@link CameraWire#NONE} when it has none
 */
public record RobotEnvironment(HubWire hubs, HardwareDirectory directory, CameraWire cameras) {
    public RobotEnvironment {
        Objects.requireNonNull(hubs, "hubs");
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(cameras, "cameras");
    }

    /** A robot controller whose host has no cameras: robot code that asks for one gets an unplugged camera. */
    public RobotEnvironment(HubWire hubs, HardwareDirectory directory) {
        this(hubs, directory, CameraWire.NONE);
    }
}
