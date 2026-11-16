package dev.ftcsim.hardware.camera;

import dev.ftcsim.hardware.device.DeviceConfig;
import dev.ftcsim.hardware.device.DeviceContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The camera models there are, by type id. Adding a camera is writing its model
 * and adding one line to {@link #standard()}; nothing else in the simulator knows what cameras exist.
 */
public final class CameraModels {

    private final Map<String, CameraFactory> factories = new LinkedHashMap<>();

    /** A registry with no cameras, for hosts and tests that bring their own. */
    public static CameraModels empty() {
        return new CameraModels();
    }

    /** Every camera the simulator models. */
    public static CameraModels standard() {
        return new CameraModels().register("limelight-3a", LimelightModel.FACTORY);
    }

    public CameraModels register(String typeId, CameraFactory factory) {
        Objects.requireNonNull(factory, "factory");
        if (factories.putIfAbsent(typeId, factory) != null) {
            throw new IllegalStateException("camera type \"" + typeId + "\" is registered twice");
        }
        return this;
    }

    public List<String> typeIds() {
        return List.copyOf(factories.keySet());
    }

    /**
     * Builds the camera the robot's design calls {@code cameraName}.
     *
     * @param settings the camera's settings, numbers and text
     * @param seed     fixes the camera's noise
     * @throws IllegalArgumentException if the type is unknown, or a setting is missing, wrong or not understood
     */
    public CameraModel create(String typeId, String cameraName, Map<String, ?> settings, DeviceContext context, long seed) {
        CameraFactory factory = factory(typeId, cameraName);
        DeviceConfig config = DeviceConfig.of(typeId, cameraName, settings);
        CameraModel camera = factory.create(config, context, seed);
        config.requireAllRead();
        return camera;
    }

    /** Checks a camera's settings without building it; see {@link CameraFactory#check}. */
    public void check(String typeId, String cameraName, Map<String, ?> settings) {
        CameraFactory factory = factory(typeId, cameraName);
        DeviceConfig config = DeviceConfig.of(typeId, cameraName, settings);
        factory.check(config);
        config.requireAllRead();
    }

    private CameraFactory factory(String typeId, String cameraName) {
        CameraFactory factory = factories.get(typeId);
        if (factory == null) {
            throw new IllegalArgumentException("camera \"" + cameraName + "\" has the unknown type \"" + typeId
                    + "\"; the types are " + String.join(", ", factories.keySet()));
        }
        return factory;
    }
}
