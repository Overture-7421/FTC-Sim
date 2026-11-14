package dev.ftcsim.simulator;

import dev.ftcsim.contracts.world.RayView;
import dev.ftcsim.contracts.world.SurfaceHit;
import dev.ftcsim.contracts.world.Vec3;
import dev.ftcsim.mechanisms.Mechanisms;
import dev.ftcsim.physics.PhysicsWorld;
import dev.ftcsim.physics.RayHit;
import dev.ftcsim.physics.Vector3;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * What a ray meets in the simulated world: the free bodies and fixed geometry of the physics world, and the pieces
 * the robot carries, which are not in the physics world and are found analytically as spheres. The robot's own
 * chassis is never hit.
 */
final class WorldRays implements RayView {

    /** The colour of fixed field geometry, whose real colour the simulator does not know. */
    static final int SCENERY_RGB = 0x808080;

    private final PhysicsWorld world;
    private final int ignoredEntity;
    private final Supplier<List<Mechanisms.Carried>> carried;
    private final Map<Integer, Integer> kindColours;

    /**
     * @param ignoredEntity the chassis the sensors are mounted on
     * @param kindColours   the colour of each kind of body, by kind
     */
    WorldRays(PhysicsWorld world, int ignoredEntity, Supplier<List<Mechanisms.Carried>> carried, Map<Integer, Integer> kindColours) {
        this.world = world;
        this.ignoredEntity = ignoredEntity;
        this.carried = carried;
        this.kindColours = Map.copyOf(kindColours);
    }

    @Override
    public SurfaceHit cast(Vec3 origin, Vec3 direction, double maxMeters) {
        RayHit hit = world.castRay(vector(origin), vector(direction), maxMeters, ignoredEntity);
        SurfaceHit nearest = hit == null
                ? null
                : new SurfaceHit(
                        hit.distanceMeters(), hit.kind(), hit.entityId(),
                        hit.entityId() == RayHit.FIXED ? WorldRays.SCENERY_RGB : colourOf(hit.kind()));
        double best = nearest == null ? maxMeters : nearest.distanceMeters();
        for (Mechanisms.Carried piece : carried.get()) {
            double distance = distanceToSphere(origin, direction, piece.center(), piece.radiusM());
            if (distance >= 0.0 && distance < best) {
                best = distance;
                nearest = new SurfaceHit(distance, piece.kind(), piece.entityId(), colourOf(piece.kind()));
            }
        }
        return nearest;
    }

    private int colourOf(int kind) {
        return kindColours.getOrDefault(kind, SCENERY_RGB);
    }

    /** Distance along the ray to the first point of the sphere, 0 from inside it, or -1 if the ray misses it. */
    static double distanceToSphere(Vec3 origin, Vec3 direction, Vec3 center, double radius) {
        Vec3 toOrigin = origin.minus(center);
        double along = toOrigin.dot(direction);
        double outside = toOrigin.dot(toOrigin) - radius * radius;
        if (outside <= 0.0) {
            return 0.0;
        }
        double discriminant = along * along - outside;
        if (discriminant < 0.0) {
            return -1.0;
        }
        double distance = -along - Math.sqrt(discriminant);
        return distance >= 0.0 ? distance : -1.0;
    }

    private static Vector3 vector(Vec3 v) {
        return new Vector3(v.x(), v.y(), v.z());
    }
}
