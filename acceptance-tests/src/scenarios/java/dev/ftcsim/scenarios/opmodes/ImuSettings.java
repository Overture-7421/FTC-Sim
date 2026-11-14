package dev.ftcsim.scenarios.opmodes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What the IMU scenario OpModes are told, by the host test that runs them: the name robot code gives the IMU, how its
 * code believes the hub is mounted, and the like. It is static, so it lives as long as the robot world does; the host
 * sets the fields by reflection before it presses Init. The OpModes also leave the text of the exceptions they caught
 * here.
 */
public final class ImuSettings {
    /** The name the code asks the hardware map for its IMU by. */
    public static volatile String name = "imu";
    /** A second name, for the scenario that asks for another IMU. */
    public static volatile String otherName = "gyro";
    /** The name of the external IMU. */
    public static volatile String externalName = "extImu";
    /** The class of the driver the external IMU is asked for by, as {@link Class#forName} takes it. */
    public static volatile String externalDriver = "com.qualcomm.hardware.rev.Rev9AxisImu";
    /** The direction the code tells the SDK that the logo faces, as a {@code LogoFacingDirection} constant. */
    public static volatile String logo = "UP";
    /** The direction the code tells the SDK that the USB or I2C port faces. */
    public static volatile String usb = "FORWARD";
    /** Whether the code calls {@code initialize} with its mounting, or leaves the IMU as the hardware map gave it. */
    public static volatile boolean initialize = true;
    /** The seconds into the OpMode at which it calls {@code resetYaw}; none if negative. */
    public static volatile double resetYawAtSeconds = -1;
    /** Whether the legacy driver is told to report in radians. */
    public static volatile boolean radians;
    /** What the scenarios caught, one line each. */
    public static final List<String> messages = Collections.synchronizedList(new ArrayList<>());

    private ImuSettings() {}
}
