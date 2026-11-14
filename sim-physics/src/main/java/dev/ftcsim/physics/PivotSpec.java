package dev.ftcsim.physics;

import java.util.List;

/**
 * A rigid body that swings about a fixed axis between two stops, moved only by gravity, by whatever touches it and
 * by its own bearing friction and damper. The tipping structures of a game are made of these.
 *
 * <p>The body has its own frame, whose origin is {@code pivot} and which is aligned with the world axes when the
 * body is at angle zero; the collision solids, the centre of mass and the inertia are all given in that frame.
 * The angle is a rotation about {@code axis} by the right-hand rule.
 *
 * @param pivot               a point on the axis
 * @param axis                direction of the axis in the world
 * @param parts               collision solids, in the body frame
 * @param massKg              mass of the whole body
 * @param centerOfMass        where the mass is centred, in the body frame
 * @param inertiaKgM2         principal moments of inertia about the centre of mass, along the body-frame axes
 * @param initialAngleRadians where the body starts
 * @param minAngleRadians     the lower stop
 * @param maxAngleRadians     the upper stop
 * @param bearingFrictionNm   torque the bearing always resists turning with
 * @param damperZoneRadians   next to each stop, over this angle, a damper takes the speed out of a body approaching
 *                            the stop; zero for no damper
 * @param damperNmsPerRad     what the damper resists with, per unit of angular speed, at the stop itself
 * @param friction            surface friction of the solids
 * @param restitution         bounciness of the solids
 */
public record PivotSpec(
        Vector3 pivot,
        Vector3 axis,
        List<ShapePart> parts,
        double massKg,
        Vector3 centerOfMass,
        Vector3 inertiaKgM2,
        double initialAngleRadians,
        double minAngleRadians,
        double maxAngleRadians,
        double bearingFrictionNm,
        double damperZoneRadians,
        double damperNmsPerRad,
        float friction,
        float restitution) {

    public PivotSpec {
        parts = List.copyOf(parts);
        if (parts.isEmpty() || !(massKg > 0) || !(minAngleRadians < maxAngleRadians)) {
            throw new IllegalArgumentException("a pivot needs solids, a positive mass and two different stops");
        }
        if (initialAngleRadians < minAngleRadians || initialAngleRadians > maxAngleRadians) {
            throw new IllegalArgumentException("the starting angle lies outside the stops");
        }
    }
}
