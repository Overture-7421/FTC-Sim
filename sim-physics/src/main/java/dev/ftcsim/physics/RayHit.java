package dev.ftcsim.physics;

/**
 * What a ray cast met.
 *
 * @param distanceMeters from the ray's origin along its direction
 * @param entityId       the body that was hit, or {@link #FIXED} for fixed field geometry
 * @param kind           the kind the body was added with, or {@link #FIXED}
 */
public record RayHit(double distanceMeters, int entityId, int kind) {

    /** Fixed field geometry has neither an entity id nor a kind. */
    public static final int FIXED = -1;
}
