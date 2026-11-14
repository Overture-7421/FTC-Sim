package dev.ftcsim.physics;

/**
 * One convex solid of a collider: a box or a cylinder, placed in the frame of the collider it belongs to. A
 * collider is a list of these, so that a structure such as a ring of segments or a tray with walls is one body.
 */
public sealed interface ShapePart {

    /** Where the middle of the solid is, in the collider's frame. */
    Vector3 center();

    /** A box turned about the unit {@code axis} by {@code angleRadians}. */
    record Box(Vector3 center, Vector3 halfExtents, Vector3 axis, double angleRadians) implements ShapePart {

        /** A box whose edges are along the axes of the collider's frame. */
        public Box(Vector3 center, Vector3 halfExtents) {
            this(center, halfExtents, Vector3.Y, 0.0);
        }
    }

    /** A cylinder whose own axis is the collider's Z axis before it is turned about {@code axis} by {@code angleRadians}. */
    record Cylinder(Vector3 center, double radius, double halfHeight, Vector3 axis, double angleRadians)
            implements ShapePart {

        /** An upright cylinder. */
        public Cylinder(Vector3 center, double radius, double halfHeight) {
            this(center, radius, halfHeight, Vector3.Y, 0.0);
        }
    }
}
