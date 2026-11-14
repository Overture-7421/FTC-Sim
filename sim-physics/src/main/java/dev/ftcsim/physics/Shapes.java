package dev.ftcsim.physics;

import com.github.stephengold.joltjni.BoxShape;
import com.github.stephengold.joltjni.ConvexShape;
import com.github.stephengold.joltjni.CylinderShape;
import com.github.stephengold.joltjni.Quat;
import com.github.stephengold.joltjni.ShapeResult;
import com.github.stephengold.joltjni.StaticCompoundShapeSettings;
import com.github.stephengold.joltjni.Vec3;
import java.util.ArrayList;
import java.util.List;

/** Builds the engine shape of a collider made of {@link ShapePart}s. */
final class Shapes {

    private static final Quaternion CYLINDER_ALONG_Z = Quaternion.axisAngle(Vector3.X, 0.5 * Math.PI);

    private Shapes() {}

    /** The solids as one compound shape, in the frame the parts were given in. */
    static ShapeResult compoundOf(List<ShapePart> parts) {
        List<ConvexShape> solids = new ArrayList<>();
        try (StaticCompoundShapeSettings compound = new StaticCompoundShapeSettings()) {
            for (ShapePart part : parts) {
                ConvexShape solid;
                Quaternion turn;
                if (part instanceof ShapePart.Box box) {
                    solid = new BoxShape(vec(box.halfExtents()), 0f);
                    turn = Quaternion.axisAngle(box.axis(), box.angleRadians());
                } else if (part instanceof ShapePart.Cylinder cylinder) {
                    solid = new CylinderShape((float) cylinder.halfHeight(), (float) cylinder.radius(), 0f);
                    turn = Quaternion.axisAngle(cylinder.axis(), cylinder.angleRadians()).times(CYLINDER_ALONG_Z);
                } else {
                    throw new IllegalArgumentException("unknown part " + part);
                }
                solids.add(solid);
                compound.addShape(vec(part.center()), new Quat((float) turn.x(), (float) turn.y(), (float) turn.z(), (float) turn.w()), solid);
            }
            ShapeResult result = compound.create();
            if (result.hasError()) {
                throw new IllegalStateException("compound shape: " + result.getError());
            }
            return result;
        } finally {
            solids.forEach(ConvexShape::close);
        }
    }

    private static Vec3 vec(Vector3 v) {
        return new Vec3((float) v.x(), (float) v.y(), (float) v.z());
    }
}
