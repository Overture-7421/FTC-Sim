package dev.ftcsim.game.biobuzz;

import dev.ftcsim.contracts.field.FieldPart;
import dev.ftcsim.contracts.field.Pose3;
import dev.ftcsim.contracts.field.Scenery;
import dev.ftcsim.contracts.field.Shape;
import dev.ftcsim.physics.ShapePart;
import dev.ftcsim.physics.Vector3;

/**
 * One solid of the fixed field: how it looks, where it is in the field frame and whether it also stops things.
 * Drawing and colliding share this one description, so that what is seen is what is touched.
 */
record Solid(String name, Shape shape, Pose3 pose, int rgb, boolean visible, boolean collides) {

    Scenery scenery() {
        return new Scenery(name, new FieldPart(shape, Pose3.IDENTITY, rgb), pose);
    }

    ShapePart part() {
        Vector3 center = new Vector3(pose.x(), pose.y(), pose.z());
        double w = Math.max(-1.0, Math.min(1.0, pose.qw()));
        double angle = 2.0 * StrictMath.acos(w);
        double s = StrictMath.sqrt(1.0 - w * w);
        Vector3 axis = s < 1e-9 ? Vector3.Y : new Vector3(pose.qx() / s, pose.qy() / s, pose.qz() / s);
        if (s < 1e-9) {
            angle = 0.0;
        }
        return switch (shape) {
            case Shape.Box box -> new ShapePart.Box(center, new Vector3(box.halfX(), box.halfY(), box.halfZ()), axis, angle);
            case Shape.Cylinder cylinder -> new ShapePart.Cylinder(center, cylinder.radius(), cylinder.halfHeight(), axis, angle);
            case Shape.Sphere sphere -> throw new IllegalArgumentException("a field solid is a box or a cylinder, not " + sphere);
        };
    }
}
