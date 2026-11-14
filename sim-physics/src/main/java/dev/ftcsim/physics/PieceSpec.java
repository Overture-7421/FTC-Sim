package dev.ftcsim.physics;

/**
 * Sphere game piece.
 *
 * @param rollingDamping    angular damping in 1/s: a drag proportional to the spin, in the air as on the ground
 * @param rollingResistance coefficient of rolling resistance on the ground, as a fraction of the normal force: a
 *                          rolling ball slows at a constant 5/7 of this times g and comes to rest. Zero leaves the
 *                          piece to the engine, whose damped ball never stops rolling on the floor; the package-private
 *                          {@code RollingResistance} explains why, and what else a non-zero value changes
 */
public record PieceSpec(
        float radiusM, float massKg, float friction, float restitution, float rollingDamping, float rollingResistance) {

    public PieceSpec {
        if (!(radiusM > 0 && massKg > 0)) {
            throw new IllegalArgumentException("radius and mass must be positive");
        }
        if (!(rollingResistance >= 0 && Float.isFinite(rollingResistance))) {
            throw new IllegalArgumentException("rolling resistance must be zero or positive");
        }
    }

    /** A piece without rolling resistance. */
    public PieceSpec(float radiusM, float massKg, float friction, float restitution, float rollingDamping) {
        this(radiusM, massKg, friction, restitution, rollingDamping, 0f);
    }
}
