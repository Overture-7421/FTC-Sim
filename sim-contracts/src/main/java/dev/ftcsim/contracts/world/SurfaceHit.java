package dev.ftcsim.contracts.world;

/**
 * The first surface a ray meets.
 *
 * @param distanceMeters from the ray's origin along its direction
 * @param kind           the kind of body that was hit, as a field package numbers them, or {@link #SCENERY} for
 *                       fixed field geometry
 * @param entityId       the body's id in the world snapshot, or -1 for scenery
 * @param rgb            sRGB colour of the surface at the hit, 0xRRGGBB
 */
public record SurfaceHit(double distanceMeters, int kind, int entityId, int rgb) {

    public static final int SCENERY = -1;
}
