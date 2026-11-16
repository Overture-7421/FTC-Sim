package dev.ftcsim.sdk.hardware;

import com.qualcomm.hardware.bosch.BHI260IMU;
import com.qualcomm.hardware.bosch.BNO055IMU;
import com.qualcomm.hardware.gobilda.GoBildaPinpointDriver;
import com.qualcomm.hardware.lynx.LynxEmbeddedBNO055IMUNew;
import com.qualcomm.hardware.lynx.LynxEmbeddedIMU;
import com.qualcomm.hardware.lynx.LynxModule;
import com.qualcomm.hardware.lynx.commands.core.LynxFirmwareVersionManager;
import com.qualcomm.hardware.rev.RevColorSensorV3;
import com.qualcomm.robotcore.hardware.ColorRangeSensor;
import com.qualcomm.robotcore.hardware.ColorSensor;
import com.qualcomm.robotcore.hardware.HardwareDevice;
import com.qualcomm.robotcore.hardware.I2cDeviceSynch;
import com.qualcomm.robotcore.hardware.I2cDeviceSynchDevice;
import com.qualcomm.robotcore.hardware.I2cDeviceSynchImplOnSimple;
import com.qualcomm.robotcore.hardware.I2cDeviceSynchSimple;
import com.qualcomm.robotcore.hardware.IMU;
import com.qualcomm.robotcore.hardware.NormalizedColorSensor;
import dev.ftcsim.contracts.hardware.HardwareDirectory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;

/**
 * The SDK drivers of the robot's I2C sensors, built by the same constructor the SDK uses for a configured device, on
 * the SDK's own I2C transport (wrapped in its synchronous client for the older drivers that take one). The robot has
 * three fixed sensors and robot code finds each by the driver class it asks for, under any name:
 *
 * <ul>
 *   <li>{@code GoBildaPinpointDriver} is the Pinpoint;
 *   <li>{@code ColorRangeSensor}, {@code NormalizedColorSensor}, {@code ColorSensor} and {@code RevColorSensorV3} are
 *       the REV colour sensor;
 *   <li>{@code IMU}, {@code BNO055IMU} and the Control Hub's own driver classes are the BNO055 inside the Control Hub.
 *       {@code IMU} gets the new driver of the hub's own IMU and {@code BNO055IMU} the legacy one; the same name can be
 *       asked for by both, as a robot configuration with both drivers allows.
 * </ul>
 *
 * Each sensor has one name: a second name for the same sensor is refused, as in a real configuration. Every other I2C
 * driver (an OctoQuad, an external IMU, {@code BHI260IMU}, because the simulated Control Hub is the older one) is
 * unsupported: the lookup fails with a message and the directory records it, so that it is among the session's
 * problems.
 */
final class I2cDriverProvider implements DeviceProvider {
    private static final Set<Class<?>> COLOR_SENSOR_INTERFACES =
            Set.of(ColorRangeSensor.class, NormalizedColorSensor.class, ColorSensor.class);

    /** What robot code asks for when it wants an IMU of a Control Hub: the two APIs and the hub's two drivers. */
    private static final Set<Class<?>> IMU_REQUESTS =
            Set.of(IMU.class, BNO055IMU.class, LynxEmbeddedBNO055IMUNew.class, LynxEmbeddedIMU.class);

    private enum Kind { PINPOINT, COLOR_SENSOR, IMU, UNSUPPORTED }

    @Override
    public String family() {
        return "I2C sensor (GoBildaPinpointDriver as the Pinpoint; ColorRangeSensor, NormalizedColorSensor, ColorSensor and"
                + " RevColorSensorV3 as the REV colour sensor; IMU and BNO055IMU as the IMU inside the Control Hub; each under any name)";
    }

    @Override
    public boolean provides(Class<?> requested) {
        return kindOf(requested) != Kind.UNSUPPORTED || requested == BHI260IMU.class || isConcreteDriver(requested);
    }

    @Override
    public boolean drivesSameHardwareAs(Class<?> requested, List<HardwareDevice> existing) {
        return IMU_REQUESTS.contains(requested)
                && existing.stream().anyMatch(device -> device instanceof LynxEmbeddedBNO055IMUNew || device instanceof LynxEmbeddedIMU);
    }

    private static Kind kindOf(Class<?> requested) {
        if (IMU_REQUESTS.contains(requested)) {
            return Kind.IMU;
        }
        if (GoBildaPinpointDriver.class.isAssignableFrom(requested)) {
            return Kind.PINPOINT;
        }
        if (COLOR_SENSOR_INTERFACES.contains(requested) || RevColorSensorV3.class.isAssignableFrom(requested)) {
            return Kind.COLOR_SENSOR;
        }
        return Kind.UNSUPPORTED;
    }

    private static boolean isConcreteDriver(Class<?> type) {
        return I2cDeviceSynchDevice.class.isAssignableFrom(type)
                && !Modifier.isAbstract(type.getModifiers())
                && (hasConstructor(type, I2cDeviceSynchSimple.class) || hasConstructor(type, I2cDeviceSynch.class));
    }

    private static boolean hasConstructor(Class<?> type, Class<?> client) {
        try {
            type.getConstructor(client, boolean.class);
            return true;
        } catch (NoSuchMethodException noSuchConstructor) {
            return false;
        }
    }

    @Override
    public HardwareDevice create(SimHubs hubs, Class<?> requested, String name) {
        HardwareDirectory directory = hubs.directory();
        Kind kind = kindOf(requested);
        HardwareDirectory.I2cWiring wiring;
        try {
            wiring = switch (kind) {
                case IMU -> directory.imu(name);
                case PINPOINT -> directory.sensor(name, HardwareDirectory.Sensor.PINPOINT);
                case COLOR_SENSOR -> directory.sensor(name, HardwareDirectory.Sensor.COLOR_SENSOR);
                case UNSUPPORTED -> throw unsupported(directory, requested, name);
            };
        } catch (IllegalStateException refused) {
            directory.unsupported(name, requested.getSimpleName() + " under a second name");
            throw new IllegalArgumentException("Unable to find a hardware device with name \"" + name + "\" and type "
                    + requested.getSimpleName() + ": " + refused.getMessage(), refused);
        }
        LynxModule module = hubs.module(wiring.hubAddress());
        I2cDeviceSynchSimple transport = LynxFirmwareVersionManager.createLynxI2cDeviceSynch(hubs.context(), module, wiring.bus());
        transport.setUserConfiguredName(name);
        Class<?> driver = kind == Kind.IMU ? imuDriver(requested) : driverOf(requested);
        try {
            return (HardwareDevice) construct(driver, transport);
        } catch (InvocationTargetException failure) {
            throw new IllegalStateException("The SDK driver " + driver.getName() + " failed to start for \"" + name + "\"", failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Could not create the SDK driver " + driver.getName() + " for \"" + name + "\"", failure);
        }
    }

    private static IllegalArgumentException unsupported(HardwareDirectory directory, Class<?> requested, String name) {
        directory.unsupported(name, requested.getSimpleName());
        String why = requested == BHI260IMU.class
                ? "the simulated Control Hub is the older one, whose IMU is a BNO055, and the simulator does not model the BHI260AP of"
                        + " the newer one. Ask for the IMU interface instead, hardwareMap.get(IMU.class, \"" + name
                        + "\"), which is the same program on both kinds of Control Hub (or BNO055IMU for the legacy driver)"
                : "the simulator provides the Pinpoint, the REV colour sensor and the Control Hub's IMU, and no other I2C device";
        return new IllegalArgumentException("Unable to find a hardware device with name \"" + name + "\" and type "
                + requested.getSimpleName() + ": " + why + ".");
    }

    /** The driver, built as the SDK builds it: the older ones take the synchronous client, the newer ones the simple one. */
    private static Object construct(Class<?> driver, I2cDeviceSynchSimple transport) throws ReflectiveOperationException {
        if (hasConstructor(driver, I2cDeviceSynchSimple.class)) {
            return driver.getConstructor(I2cDeviceSynchSimple.class, boolean.class).newInstance(transport, true);
        }
        return driver.getConstructor(I2cDeviceSynch.class, boolean.class).newInstance(new I2cDeviceSynchImplOnSimple(transport, true), true);
    }

    /** The driver of an IMU request: the hub's own, which the two APIs reach through the hub's two classes. */
    private static Class<?> imuDriver(Class<?> requested) {
        if (requested == IMU.class) {
            return LynxEmbeddedBNO055IMUNew.class;
        }
        return requested == BNO055IMU.class ? LynxEmbeddedIMU.class : requested;
    }

    /** The driver class that answers a request: the REV colour sensor for its interfaces, the requested class otherwise. */
    private static Class<?> driverOf(Class<?> requested) {
        return COLOR_SENSOR_INTERFACES.contains(requested) ? RevColorSensorV3.class : requested.asSubclass(HardwareDevice.class);
    }
}
