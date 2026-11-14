package dev.ftcsim.hardware.device;

import dev.ftcsim.contracts.field.FieldTag;
import dev.ftcsim.contracts.hardware.EncoderSource;
import dev.ftcsim.contracts.world.BodyView;
import dev.ftcsim.contracts.world.RayView;
import java.util.List;

/**
 * Everything a device model may know about the simulated world, and nothing else: where the chassis it is mounted
 * on really is, what a ray meets, the time, the encoders the robot profile names, and the physics steps. Devices
 * that look at the field, such as cameras, may also ask where the field's tags are and where the bodies that carry
 * some of them are. The host implements it once per robot; {@link DeviceHost} is the implementation to use.
 */
public interface DeviceContext {

    /** The chassis the device is mounted on. Sample it afresh whenever the true pose is needed. */
    BodyView body();

    /** What a ray from a point of the field meets first. */
    RayView rays();

    /** The current simulated time, in nanoseconds. */
    long nowNanos();

    /**
     * The encoder of the mechanism the robot profile calls {@code name}, for devices that count an encoder
     * which is not wired to a hub port.
     *
     * @throws IllegalArgumentException if the profile has no such encoder
     */
    EncoderSource encoder(String name);

    /** Asks for {@code listener} to run after every physics step. Listeners run in the order they were added. */
    void onStep(StepListener listener);

    /**
     * Where another body of the world really is, by its entity id, for devices that look at things which move, such
     * as a tag stuck on a hive. Sample it at every step that matters; it describes the current simulated instant.
     *
     * @throws IllegalArgumentException if the host does not give devices a view of that entity
     */
    default BodyView entity(int entityId) {
        throw new IllegalArgumentException("this host gives devices no view of entity " + entityId);
    }

    /** The AprilTags of the field the robot stands on, fixed and moving; empty on a bare floor. */
    default List<FieldTag> fieldTags() {
        return List.of();
    }

    /**
     * The fixed tags of a game's official AprilTag map, by the game's id (such as {@code decode}), which is what a
     * camera whose map was made for another game believes in, wherever the robot really stands.
     *
     * @throws IllegalArgumentException if the host has no such map
     */
    default List<FieldTag> gameMap(String gameId) {
        throw new IllegalArgumentException("this host has no AprilTag map for the game \"" + gameId + "\"");
    }
}
