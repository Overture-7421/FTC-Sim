package dev.ftcsim.contracts.field;

/** A simple solid, centred on its own origin; a cylinder's axis is its local Z. */
public sealed interface Shape {

    record Box(double halfX, double halfY, double halfZ) implements Shape {}

    record Sphere(double radius) implements Shape {}

    record Cylinder(double radius, double halfHeight) implements Shape {}
}
