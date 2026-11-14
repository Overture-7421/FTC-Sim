package dev.ftcsim.hardware.device;

import dev.ftcsim.hardware.device.bosch.Bno055Model;
import dev.ftcsim.hardware.device.digitalchickenlabs.OctoQuadModel;
import dev.ftcsim.hardware.device.gobilda.GoBildaPinpointModel;
import dev.ftcsim.hardware.device.rev.RevColorSensorV3Model;
import dev.ftcsim.hardware.i2c.I2cPeripheral;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The device models there are, by the type id a robot profile names them with. Adding a device is writing its model
 * and adding one line to {@link #standard()}; nothing else in the simulator knows what devices exist, except that a
 * robot with a Control Hub has the IMU of the hub without any profile line, which the robot's rig builds from
 * {@link #BNO055_IMU}.
 */
public final class DeviceModels {

    /** The type id of the Bosch BNO055: the IMU inside a REV hub, and every external IMU the SDK has a driver for. */
    public static final String BNO055_IMU = "bno055-imu";

    private final Map<String, DeviceFactory> factories = new LinkedHashMap<>();

    /** A registry with no devices, for hosts and tests that bring their own. */
    public static DeviceModels empty() {
        return new DeviceModels();
    }

    /** Every device the simulator models. */
    public static DeviceModels standard() {
        return new DeviceModels()
                .register("gobilda-pinpoint", GoBildaPinpointModel::create)
                .register("rev-color-sensor-v3", RevColorSensorV3Model::create)
                .register("octoquad-ftc-mk2", OctoQuadModel::create)
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
     * Builds the device a profile calls {@code deviceName}.
     *
     * @param typeId   the profile's type id, which must have been registered
     * @param settings the profile's settings of the device, numbers and text
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
