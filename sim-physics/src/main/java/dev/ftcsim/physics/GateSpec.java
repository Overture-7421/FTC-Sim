package dev.ftcsim.physics;

/**
 * A flap hinged at its top edge about the world Y axis that hangs closed under gravity. Pieces
 * pushing it towards +X swing it open up to {@code openLimitRadians}; gravity closes it again.
 *
 * @param hinge             centre of the hinge line, at the top edge of the flap
 * @param angularDamping    damping in 1/s that keeps the flap from oscillating when it closes
 */
public record GateSpec(
        Vector3 hinge,
        double widthM,
        double heightM,
        double thicknessM,
        double massKg,
        double openLimitRadians,
        double angularDamping) {}
