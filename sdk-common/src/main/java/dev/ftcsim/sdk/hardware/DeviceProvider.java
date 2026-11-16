package dev.ftcsim.sdk.hardware;

import com.qualcomm.robotcore.hardware.HardwareDevice;
import java.util.List;

/**
 * Creates one family of simulated devices the first time robot code asks for one by name. A provider knows
 * how to build the SDK's own driver objects on top of the simulated hubs; it never decides where a device is
 * wired, which the robot's wiring does.
 */
interface DeviceProvider {

    /** A short description of the device family, for error messages. */
    String family();

    /** True when this provider can create a device that is an instance of {@code requested}. */
    boolean provides(Class<?> requested);

    /**
     * True when the devices already registered under a name are drivers of the same hardware that {@code requested}
     * is another driver of, such as the two drivers of one IMU, one for each of its APIs: a robot configuration
     * registers both under its single entry, so robot code may ask for either under the name.
     */
    default boolean drivesSameHardwareAs(Class<?> requested, List<HardwareDevice> existing) {
        return false;
    }

    /** Creates the device named {@code name}, wired where the directory says, and returns it unregistered. */
    HardwareDevice create(SimHubs hubs, Class<?> requested, String name);
}
