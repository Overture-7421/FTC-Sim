package dev.ftcsim.field.support;

/** A volume of the field in the field frame, for deciding whether a piece is in a goal, a zone or a garden. */
public sealed interface Region {

    boolean contains(double x, double y, double z);

    /** A box with its edges along the field axes, given by its centre and half extents. */
    record Box(double cx, double cy, double cz, double hx, double hy, double hz) implements Region {
        @Override
        public boolean contains(double x, double y, double z) {
            return Math.abs(x - cx) <= hx && Math.abs(y - cy) <= hy && Math.abs(z - cz) <= hz;
        }
    }

    /** A box turned about the vertical axis by {@code yawRadians}, counter-clockwise from the field's +X. */
    record TurnedBox(double cx, double cy, double cz, double hx, double hy, double hz, double yawRadians) implements Region {
        @Override
        public boolean contains(double x, double y, double z) {
            double dx = x - cx;
            double dy = y - cy;
            double cos = StrictMath.cos(yawRadians);
            double sin = StrictMath.sin(yawRadians);
            double localX = dx * cos + dy * sin;
            double localY = -dx * sin + dy * cos;
            return Math.abs(localX) <= hx && Math.abs(localY) <= hy && Math.abs(z - cz) <= hz;
        }
    }

    /** An upright cylinder, given by the centre of its axis and its radius and half height. */
    record Cylinder(double cx, double cy, double cz, double radius, double halfHeight) implements Region {
        @Override
        public boolean contains(double x, double y, double z) {
            double dx = x - cx;
            double dy = y - cy;
            return dx * dx + dy * dy <= radius * radius && Math.abs(z - cz) <= halfHeight;
        }
    }
}
