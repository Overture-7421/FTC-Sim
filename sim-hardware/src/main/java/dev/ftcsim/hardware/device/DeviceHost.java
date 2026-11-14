package dev.ftcsim.hardware.device;

import dev.ftcsim.contracts.field.FieldTag;
import dev.ftcsim.contracts.hardware.EncoderSource;
import dev.ftcsim.contracts.world.BodyView;
import dev.ftcsim.contracts.world.RayView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;

/**
 * The host's side of {@link DeviceContext}: it holds what the devices of one robot may look at and runs their step
 * listeners. The session calls {@link #step} after every physics step, once the world has moved.
 */
public final class DeviceHost implements DeviceContext {

    private final BodyView body;
    private final RayView rays;
    private final LongSupplier clock;
    private final Map<String, EncoderSource> encoders = new LinkedHashMap<>();
    private final Map<String, List<FieldTag>> gameMaps = new LinkedHashMap<>();
    private final List<StepListener> listeners = new ArrayList<>();
    private IntFunction<BodyView> entities = id -> {
        throw new IllegalArgumentException("this host gives devices no view of entity " + id);
    };
    private List<FieldTag> fieldTags = List.of();

    public DeviceHost(BodyView body, RayView rays, LongSupplier clock) {
        this.body = Objects.requireNonNull(body, "body");
        this.rays = Objects.requireNonNull(rays, "rays");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Makes the encoder of a mechanism available to devices under the name the robot profile gives it. */
    public DeviceHost provideEncoder(String name, EncoderSource source) {
        if (encoders.putIfAbsent(name, Objects.requireNonNull(source, "source")) != null) {
            throw new IllegalStateException("the robot already has an encoder called \"" + name + "\"");
        }
        return this;
    }

    /**
     * Lets devices look at any body of the world by its entity id. The function returns the live view of the body
     * and throws {@link IllegalArgumentException} for an id that is not in the world.
     */
    public DeviceHost provideEntities(IntFunction<BodyView> entities) {
        this.entities = Objects.requireNonNull(entities, "entities");
        return this;
    }

    /** The tags of the field the robot stands on, which cameras see whether or not they know them. */
    public DeviceHost provideFieldTags(List<FieldTag> tags) {
        this.fieldTags = List.copyOf(tags);
        return this;
    }

    /**
     * The official AprilTag map of a game, from that game's tags: only the tags fixed to the field are in it, as the
     * tags that ride on moving bodies have no fixed place to be mapped at.
     */
    public DeviceHost provideGameMap(String gameId, List<FieldTag> gameTags) {
        List<FieldTag> fixed = gameTags.stream().filter(tag -> tag.hostEntityId() == -1).toList();
        if (gameMaps.putIfAbsent(gameId, fixed) != null) {
            throw new IllegalStateException("the host already has an AprilTag map for the game \"" + gameId + "\"");
        }
        return this;
    }

    /** Runs every listener, in the order they were added, for the physics step that ended at {@code stepNanos}. */
    public void step(long stepNanos) {
        for (int i = 0; i < listeners.size(); i++) {
            listeners.get(i).afterStep(stepNanos);
        }
    }

    @Override
    public BodyView body() {
        return body;
    }

    @Override
    public RayView rays() {
        return rays;
    }

    @Override
    public long nowNanos() {
        return clock.getAsLong();
    }

    @Override
    public EncoderSource encoder(String name) {
        EncoderSource source = encoders.get(name);
        if (source == null) {
            throw new IllegalArgumentException("the robot has no encoder called \"" + name + "\"; it has " + encoders.keySet());
        }
        return source;
    }

    @Override
    public void onStep(StepListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    @Override
    public BodyView entity(int entityId) {
        return entities.apply(entityId);
    }

    @Override
    public List<FieldTag> fieldTags() {
        return fieldTags;
    }

    @Override
    public List<FieldTag> gameMap(String gameId) {
        List<FieldTag> map = gameMaps.get(gameId);
        if (map == null) {
            throw new IllegalArgumentException("this host has no AprilTag map for the game \"" + gameId + "\"; it has " + gameMaps.keySet());
        }
        return map;
    }
}
