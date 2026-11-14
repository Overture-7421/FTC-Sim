package dev.ftcsim.simulator;

import dev.ftcsim.hardware.camera.CameraModels;
import dev.ftcsim.hardware.camera.CameraRack;
import dev.ftcsim.hardware.device.DeviceContext;
import dev.ftcsim.simulator.profile.ProfileException;
import dev.ftcsim.simulator.profile.RobotProfile;

/**
 * The cameras a robot profile lists, built and plugged into a rack that robot code reaches through the SDK adapter's
 * {@link dev.ftcsim.contracts.hardware.CameraWire}. A host builds one per robot, hands {@link CameraRack} to the
 * robot controller in its {@link dev.ftcsim.contracts.robot.RobotEnvironment}, and adds
 * {@link CameraRack#problems()} to the problems it reports.
 */
public final class RobotCameras {

    private RobotCameras() {}

    /**
     * Builds every camera of the profile with the standard camera models.
     *
     * @param context what the cameras may look at, and the physics steps they follow: build it with
     *                {@link dev.ftcsim.hardware.device.DeviceHost}, give it the field's tags and the games' maps,
     *                and call its {@code step} after every physics step
     * @param seed    fixes the noise of the cameras' images; each camera also mixes in its own name
     * @throws ProfileException if a camera has a setting the model does not accept, or a map the host cannot supply
     */
    public static CameraRack build(RobotProfile profile, DeviceContext context, long seed) {
        return build(profile, CameraModels.standard(), context, seed);
    }

    public static CameraRack build(RobotProfile profile, CameraModels models, DeviceContext context, long seed) {
        CameraRack rack = new CameraRack();
        profile.cameras().forEach((name, camera) -> {
            try {
                rack.add(name, models.create(camera.type(), name, camera.settings(), context, seed ^ (name.hashCode() * 0x9E3779B97F4A7C15L)));
            } catch (ProfileException invalid) {
                throw new ProfileException("cameras." + name + "." + invalid.getMessage(), invalid);
            } catch (IllegalArgumentException invalid) {
                throw new ProfileException("cameras." + name + ": " + invalid.getMessage(), invalid);
            }
        });
        return rack;
    }
}
