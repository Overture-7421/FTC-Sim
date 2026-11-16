package dev.ftcsim.hardware.device;

import dev.ftcsim.hardware.device.bosch.Bno055Model;
import dev.ftcsim.hardware.device.gobilda.GoBildaPinpointModel;
import dev.ftcsim.hardware.device.rev.RevColorSensorV3Model;
import dev.ftcsim.hardware.i2c.I2cPeripheral;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The device models there are, by type id. Adding a device is writing its model and adding one line to
 * {@link #standard()}; the robot's rig then attaches it on the bus its sensor kind has
 * ({@link dev.ftcsim.contracts.hardware.HardwareDirectory.Sensor}).
 */
public final class DeviceModels {

    /** The type id of the Bosch BNO055, the IMU inside a REV Control Hub. */
    public static final String BNO055_IMU = "bno055-imu";

    /** The type id of the goBILDA Pinpoint. */
    public static final String PINPOINT = "gobilda-pinpoint";

    /** The type id of the REV Color Sensor V3. */
    public static final String COLOR_SENSOR = "rev-color-sensor-v3";

    private final Map<String, DeviceFactory> factories = new LinkedHashMap<>();

    /** A registry with no devices, for hosts and tests that bring their own. */
    public static DeviceModels empty() {
        return new DeviceModels();
    }

    /** Every device the simulator models. */
    public static DeviceModels standard() {
        return new DeviceModels()
                .register(PINPOINT, GoBildaPinpointModel::create)
                .register(COLOR_SENSOR, RevColorSensorV3Model::create)
                .register(BNO055_IMU, Bno055Model::create);
    }

    public DeviceModels register(String typeId, DeviceFactory factory) {
        Objects.requireNonNull(factory, "factory");
        if (factories.putIfAbsent(typeId, factory) != null) {
            throw new IllegalStateException("device type \"" + typeId + "\" is registered twice");
        }
        return this;
    }

    public List<String> typeIds() {
        return List.copyOf(factories.keySet());
    }

    /**
     * Builds a device.
     *
     * @param typeId     the type id, which must have been registered
     * @param deviceName what the device is called, for messages
     * @param settings   the device's settings, numbers and text
     * @throws IllegalArgumentException if the type is unknown, or a setting is missing, wrong or not understood
     */
    public I2cPeripheral create(String typeId, String deviceName, Map<String, ?> settings, DeviceContext context) {
        DeviceFactory factory = factories.get(typeId);
        if (factory == null) {
            throw new IllegalArgumentException("device \"" + deviceName + "\" has the unknown type \"" + typeId
                    + "\"; the types are " + String.join(", ", factories.keySet()));
        }
        DeviceConfig config = DeviceConfig.of(typeId, deviceName, settings);
        I2cPeripheral device = factory.create(config, context);
        config.requireAllRead();
        return device;
    }
}
