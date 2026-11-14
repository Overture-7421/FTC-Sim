package dev.ftcsim.game.decode;

import dev.ftcsim.contracts.field.Pose3;
import dev.ftcsim.physics.Vector3;

/** A rotation as a unit quaternion. Trigonometry goes through {@link StrictMath}, so fields build alike everywhere. */
record Orientation(double w, double x, double y, double z) {

    static final Orientation IDENTITY = new Orientation(1, 0, 0, 0);

    static Orientation aboutAxis(Vector3 axis, double radians) {
        Vector3 unit = axis.normalized();
        double half = 0.5 * radians;
        double sin = StrictMath.sin(half);
        return new Orientation(StrictMath.cos(half), unit.x() * sin, unit.y() * sin, unit.z() * sin);
    }

    static Orientation yaw(double radians) {
        return aboutAxis(Vector3.Z, radians);
    }

    /**
     * The orientation of something flat that stands upright and faces horizontally towards {@code facingYawRadians},
     * counter-clockwise from +X: its Z axis points out of its face, its Y axis up and its X axis to the right of someone
     * who looks at the face.
     */
    static Orientation facing(double facingYawRadians) {
        return yaw(facingYawRadians).times(new Orientation(0.5, 0.5, 0.5, 0.5));
    }

    static Orientation aboutY(double radians) {
        return aboutAxis(Vector3.Y, radians);
    }

    /** The rotation that carries the X axis onto {@code xAxis} and the Y axis onto {@code yAxis}, which must be perpendicular. */
    static Orientation fromAxes(Vector3 xAxis, Vector3 yAxis) {
        Vector3 ex = xAxis.normalized();
        Vector3 ez = ex.cross(yAxis).normalized();
        Vector3 ey = ez.cross(ex);
        double m00 = ex.x(), m10 = ex.y(), m20 = ex.z();
        double m01 = ey.x(), m11 = ey.y(), m21 = ey.z();
        double m02 = ez.x(), m12 = ez.y(), m22 = ez.z();
        double trace = m00 + m11 + m22;
        if (trace > 0) {
            double s = 2 * Math.sqrt(trace + 1);
            return new Orientation(0.25 * s, (m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s);
        }
        if (m00 > m11 && m00 > m22) {
            double s = 2 * Math.sqrt(1 + m00 - m11 - m22);
            return new Orientation((m21 - m12) / s, 0.25 * s, (m01 + m10) / s, (m02 + m20) / s);
        }
        if (m11 > m22) {
            double s = 2 * Math.sqrt(1 + m11 - m00 - m22);
            return new Orientation((m02 - m20) / s, (m01 + m10) / s, 0.25 * s, (m12 + m21) / s);
        }
        double s = 2 * Math.sqrt(1 + m22 - m00 - m11);
        return new Orientation((m10 - m01) / s, (m02 + m20) / s, (m12 + m21) / s, 0.25 * s);
    }

    /** The rotation that applies {@code other} first and this one second (the Hamilton product). */
    Orientation times(Orientation other) {
        return new Orientation(
                w * other.w - x * other.x - y * other.y - z * other.z,
                w * other.x + x * other.w + y * other.z - z * other.y,
                w * other.y - x * other.z + y * other.w + z * other.x,
                w * other.z + x * other.y - y * other.x + z * other.w);
    }

    /** The same rotation seen in a mirror that swaps y with -y. */
    Orientation mirrored() {
        return new Orientation(w, -x, y, -z);
    }

    Vector3 rotate(Vector3 v) {
        Vector3 u = new Vector3(x, y, z);
        Vector3 t = u.cross(v).times(2.0);
        return v.plus(t.times(w)).plus(u.cross(t));
    }

    /** The unit axis of the rotation; the Y axis when there is no rotation. */
    Vector3 axis() {
        Vector3 v = new Vector3(x, y, z);
        return v.length() < 1e-12 ? Vector3.Y : v.normalized();
    }

    double angleRadians() {
        return 2 * StrictMath.atan2(new Vector3(x, y, z).length(), w);
    }

    Pose3 poseAt(Vector3 position) {
        return new Pose3(position.x(), position.y(), position.z(), w, x, y, z);
    }
}
