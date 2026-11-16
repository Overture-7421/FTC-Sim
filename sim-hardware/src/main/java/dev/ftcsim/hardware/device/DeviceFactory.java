package dev.ftcsim.hardware.device;

import dev.ftcsim.hardware.i2c.I2cPeripheral;

/** Builds the model of one kind of device from the settings the robot's design gives it. */
@FunctionalInterface
public interface DeviceFactory {

    /**
     * Creates the peripheral. Read every setting through {@code config}: whatever the factory never asks for is
     * rejected afterwards as a misspelling. Register step listeners on {@code context} here, not in a constructor.
     *
     * @throws IllegalArgumentException for a setting that is missing or out of range; build the exception with
     *                                  {@link DeviceConfig#rejected} so it names the device and the setting
     */
    I2cPeripheral create(DeviceConfig config, DeviceContext context);
}
