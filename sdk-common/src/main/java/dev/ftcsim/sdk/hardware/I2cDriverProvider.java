package dev.ftcsim.sdk.hardware;

import com.qualcomm.hardware.adafruit.AdafruitBNO055IMU;
import com.qualcomm.hardware.bosch.BHI260IMU;
import com.qualcomm.hardware.bosch.BNO055IMU;
import com.qualcomm.hardware.digitalchickenlabs.OctoQuad;
import com.qualcomm.hardware.digitalchickenlabs.OctoQuadImpl;
import com.qualcomm.hardware.lynx.LynxEmbeddedBNO055IMUNew;
import com.qualcomm.hardware.lynx.LynxEmbeddedIMU;
import com.qualcomm.hardware.lynx.LynxModule;
import com.qualcomm.hardware.lynx.commands.core.LynxFirmwareVersionManager;
import com.qualcomm.hardware.rev.Rev9AxisImu;
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
import java.util.Map;
import java.util.Set;

/**
 * Any SDK driver that talks to its device over a hub's I2C bus: the driver class itself, built by the same
 * constructor the SDK uses for a configured device, on the SDK's own I2C transport (wrapped in its synchronous client
 * for the older drivers that take one). Nothing here knows any particular device; what answers on the bus is whatever
 * the robot profile attached to it.
 *
 * <p>Robot code that asks for an interface names no driver, so a few well-known interfaces are mapped to the
 * driver a team would almost certainly have configured.
 *
 * <p>The IMU is the one device that robot code asks for without the robot profile having to list it: {@code IMU},
 * {@code BNO055IMU} and the Control Hub's own driver classes are the BNO055 inside the Control Hub, whatever name the
 * code gives it, and the directory refuses a second name for the one chip. {@code IMU} gets the new driver of the
 * hub's own IMU and {@code BNO055IMU} the legacy one; the same name can be asked for by both, as a robot
 * configuration with both drivers allows. A profile that lists the name on bus 0 of a hub (an Expansion Hub's own
 * IMU, say) has that hub's IMU there and gets the same drivers; one that lists it on another bus has an external
 * IMU, and the interfaces then get the REV 9-axis IMU ({@code IMU}) or the Adafruit driver ({@code BNO055IMU}); a
 * program that needs another driver names its class. {@code BHI260IMU}, the driver of the IMU of newer Control
 * Hubs, is refused, because the simulated Control Hub is the older one.
 */
final class I2cDriverProvider implements DeviceProvider {
    private static final Map<Class<?>, Class<? extends HardwareDevice>> DEFAULT_DRIVERS = Map.of(
            ColorRangeSensor.class, RevColorSensorV3.class,
            NormalizedColorSensor.class, RevColorSensorV3.class,
            ColorSensor.class, RevColorSensorV3.class,
            OctoQuad.class, OctoQuadImpl.class);

    /** What robot code asks for when it wants an IMU of a Control Hub: the two APIs and the hub's two drivers. */
    private static final Set<Class<?>> IMU_REQUESTS =
            Set.of(IMU.class, BNO055IMU.class, LynxEmbeddedBNO055IMUNew.class, LynxEmbeddedIMU.class);

    @Override
    public String family() {
        return "I2C device (any SDK driver class; ColorRangeSensor, NormalizedColorSensor and ColorSensor as the REV Color Sensor V3;"
                + " OctoQuad as the OctoQuad driver; IMU and BNO055IMU as the IMU inside the Control Hub)";
    }

    @Override
    public boolean provides(Class<?> requested) {
        return DEFAULT_DRIVERS.containsKey(requested)
                || IMU_REQUESTS.contains(requested)
                || requested == BHI260IMU.class
                || isConcreteDriver(requested);
    }

    @Override
    public boolean drivesSameHardwareAs(Class<?> requested, List<HardwareDevice> existing) {
        return IMU_REQUESTS.contains(requested)
                && existing.stream().anyMatch(device -> device instanceof LynxEmbeddedBNO055IMUNew || device instanceof LynxEmbeddedIMU);
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
        if (requested == BHI260IMU.class) {
            throw new IllegalArgumentException("Unable to find a hardware device with name \"" + name + "\" and type BHI260IMU: the"
                    + " simulated Control Hub is the older one, whose IMU is a BNO055, and the simulator does not model the"
                    + " BHI260AP of the newer one. Ask for the IMU interface instead, hardwareMap.get(IMU.class, \"" + name
                    + "\"), which is the same program on both kinds of Control Hub (or BNO055IMU for the legacy driver).");
        }
        boolean imu = IMU_REQUESTS.contains(requested);
        HardwareDirectory.I2cWiring wiring = imu ? imuWiring(hubs.directory(), requested, name) : hubs.directory().i2c(name);
        LynxModule module = hubs.module(wiring.hubAddress());
        I2cDeviceSynchSimple transport = LynxFirmwareVersionManager.createLynxI2cDeviceSynch(hubs.context(), module, wiring.bus());
        transport.setUserConfiguredName(name);
        Class<?> driver = imu
                ? imuDriver(requested, wiring)
                : DEFAULT_DRIVERS.getOrDefault(requested, requested.asSubclass(HardwareDevice.class));
        try {
            return (HardwareDevice) construct(driver, transport);
        } catch (InvocationTargetException failure) {
            throw new IllegalStateException("The SDK driver " + driver.getName() + " failed to start for \"" + name + "\"", failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Could not create the SDK driver " + driver.getName() + " for \"" + name + "\"", failure);
        }
    }

    /** The driver, built as the SDK builds it: the older ones take the synchronous client, the newer ones the simple one. */
    private static Object construct(Class<?> driver, I2cDeviceSynchSimple transport) throws ReflectiveOperationException {
        if (hasConstructor(driver, I2cDeviceSynchSimple.class)) {
            return driver.getConstructor(I2cDeviceSynchSimple.class, boolean.class).newInstance(transport, true);
        }
        return driver.getConstructor(I2cDeviceSynch.class, boolean.class).newInstance(new I2cDeviceSynchImplOnSimple(transport, true), true);
    }

    /** Where the IMU is; a name that would be a second IMU of the Control Hub is not found, as in a real configuration. */
    private static HardwareDirectory.I2cWiring imuWiring(HardwareDirectory directory, Class<?> requested, String name) {
        try {
            return directory.imu(name);
        } catch (IllegalStateException refused) {
            throw new IllegalArgumentException("Unable to find a hardware device with name \"" + name + "\" and type "
                    + requested.getSimpleName() + ": " + refused.getMessage(), refused);
        }
    }

    /** The driver of an IMU request: the hub's own if the IMU is wired to bus 0 of a hub, an external one if not. */
    private static Class<?> imuDriver(Class<?> requested, HardwareDirectory.I2cWiring wiring) {
        boolean hubsOwn = wiring.bus() == HardwareDirectory.EMBEDDED_IMU_BUS;
        if (requested == IMU.class) {
            return hubsOwn ? LynxEmbeddedBNO055IMUNew.class : Rev9AxisImu.class;
        }
        if (requested == BNO055IMU.class) {
            return hubsOwn ? LynxEmbeddedIMU.class : AdafruitBNO055IMU.class;
        }
        return requested;
    }
}
