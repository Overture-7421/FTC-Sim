package dev.ftcsim.contracts.world;

/** What range and colour sensors ask of the world: what does a ray from here meet first? */
@FunctionalInterface
public interface RayView {

    /**
     * Casts a ray in the field frame at the current simulated instant. The chassis the sensor is mounted on is
     * never hit.
     *
     * @param origin    where the ray starts
     * @param direction unit vector the ray travels along
     * @param maxMeters the longest the ray may be
     * @return the nearest surface within range, or null if there is none
     */
    SurfaceHit cast(Vec3 origin, Vec3 direction, double maxMeters);
}
