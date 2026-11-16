package dev.ftcsim.hardware.camera;

import dev.ftcsim.hardware.device.DeviceConfig;
import dev.ftcsim.hardware.device.DeviceContext;

/** Builds the model of one kind of camera from the settings the robot's design gives it. */
public interface CameraFactory {

    /**
     * Creates the camera. Read every setting through {@code config}: whatever the factory never asks for is rejected
     * afterwards as a misspelling. Register step listeners on {@code context} here, not in a constructor.
     *
     * @param seed fixes the camera's noise, so that equal seeds give equal pictures
     * @throws IllegalArgumentException for a setting that is missing or out of range; build the exception with
     *                                  {@link DeviceConfig#rejected} so it names the camera and the setting
     */
    CameraModel create(DeviceConfig config, DeviceContext context, long seed);

    /**
     * Checks the settings that can be checked without a world: names, ranges and spellings.
     *
     * @throws IllegalArgumentException as {@link #create} does
     */
    void check(DeviceConfig config);
}
