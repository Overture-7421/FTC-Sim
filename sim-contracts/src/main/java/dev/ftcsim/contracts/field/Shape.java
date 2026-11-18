package dev.ftcsim.contracts.field;

/** A simple solid, centred on its own origin; a cylinder's axis is its local Z. */
public sealed interface Shape {

    record Box(double halfX, double halfY, double halfZ) implements Shape {}

    record Sphere(double radius) implements Shape {}

    record Cylinder(double radius, double halfHeight) implements Shape {}

    /**
     * A triangular prism: the gable whose base, 2 * halfY wide, lies on the local z = -halfZ and whose apex, at y = 0, is at
     * z = +halfZ, extruded over 2 * halfX along local X.
     */
    record Wedge(double halfX, double halfY, double halfZ) implements Shape {}
}
