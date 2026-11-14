package dev.ftcsim.physics;

import com.github.stephengold.joltjni.BodyInterface;
import com.github.stephengold.joltjni.RVec3;
import com.github.stephengold.joltjni.Vec3;
import com.github.stephengold.joltjni.enumerate.EMotionType;
import java.util.TreeMap;

/**
 * Rolling resistance of the pieces whose {@link PieceSpec#rollingResistance()} is set, applied before every step.
 *
 * <p>The engine alone never stops a ball that rolls on the floor. It remembers the contact points of two touching
 * bodies and reuses them until the bodies have moved 1 mm or turned 2 degrees against each other. A ball that rolls
 * less than 1 mm per step carries such a stale contact point round with it, so the support force acts behind its
 * centre and drives the roll. Damping balances that drive at about 0.24 m/s, 1 mm per step, and the ball crawls on
 * for ever. So the contacts of these pieces are recomputed every step.
 *
 * <p>The ground then takes spin off a ball like the torque of the coefficient times the radius times its normal
 * force, never more than the spin it has. The roll slows at a constant 5/7 of the coefficient times g and ends after
 * v^2 / (2 a) metres, where the engine puts the ball to sleep. The spin is changed directly instead of adding a
 * torque, because a torque added every step keeps a body from ever falling asleep. Ground is the static geometry, as
 * for the wheels of a chassis. A piece that is held or asleep is left alone, and one that is off the ground feels no
 * resistance.
 */
final class RollingResistance {

    private record Piece(int bodyId, double massKg, double radiusM, double coefficient) {}

    private static final Vector3 DOWN = new Vector3(0, 0, -1);
    // The probe reaches this many radii below the centre, enough to find a ball that rests on a steep slope.
    private static final double PROBE_REACH = 1.5;
    // A ball whose surface is this close to the ground rolls on it; a ball at rest sinks about a millimetre into it.
    private static final double CONTACT_TOLERANCE_M = 0.003;
    private static final double SOLID_SPHERE_INERTIA = 0.4;

    private final BodyInterface bodies;
    private final MecanumChassis.GroundProbe ground;
    private final TreeMap<Integer, Piece> pieces = new TreeMap<>();
    private final RVec3 position = new RVec3();
    private final Vec3 spin = new Vec3();

    RollingResistance(BodyInterface bodies, MecanumChassis.GroundProbe ground) {
        this.bodies = bodies;
        this.ground = ground;
    }

    /** Takes a piece into account if its spec asks for rolling resistance. */
    void add(int entityId, int bodyId, PieceSpec spec) {
        if (spec.rollingResistance() > 0) {
            pieces.put(entityId, new Piece(bodyId, spec.massKg(), spec.radiusM(), spec.rollingResistance()));
        }
    }

    void remove(int entityId) {
        pieces.remove(entityId);
    }

    /** Acts on every piece in ascending entity id order; call right before the engine steps. */
    void apply(double stepSeconds) {
        for (Piece piece : pieces.values()) {
            int bodyId = piece.bodyId();
            if (!bodies.isActive(bodyId) || bodies.getMotionType(bodyId) != EMotionType.Dynamic) {
                continue;
            }
            bodies.invalidateContactCache(bodyId);
            bodies.getAngularVelocity(bodyId, spin);
            double spinSpeed = Math.sqrt(
                    (double) spin.getX() * spin.getX() + (double) spin.getY() * spin.getY() + (double) spin.getZ() * spin.getZ());
            if (spinSpeed > 0) {
                double slowdown = Math.min(spinSlowdown(piece, stepSeconds), spinSpeed);
                if (slowdown > 0) {
                    float keep = (float) (1 - slowdown / spinSpeed);
                    bodies.setAngularVelocity(bodyId, spin.getX() * keep, spin.getY() * keep, spin.getZ() * keep);
                }
            }
        }
    }

    /** The spin in rad/s that the ground takes off a piece in one step; zero when the piece does not rest on it. */
    private double spinSlowdown(Piece piece, double stepSeconds) {
        bodies.getPosition(piece.bodyId(), position);
        Vector3 center = new Vector3(position.x(), position.y(), position.z());
        MecanumChassis.GroundHit hit = ground.cast(center, DOWN, PROBE_REACH * piece.radiusM());
        if (hit == null || hit.normal().z() <= 0) {
            return 0;
        }
        double gap = (center.z() - hit.point().z()) * hit.normal().z() - piece.radiusM();
        if (gap > CONTACT_TOLERANCE_M) {
            return 0;
        }
        double normalForce = piece.massKg() * PhysicsWorld.GRAVITY * hit.normal().z();
        double inertia = SOLID_SPHERE_INERTIA * piece.massKg() * piece.radiusM() * piece.radiusM();
        return piece.coefficient() * piece.radiusM() * normalForce * stepSeconds / inertia;
    }
}
