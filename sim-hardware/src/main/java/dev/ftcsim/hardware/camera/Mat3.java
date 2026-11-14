package dev.ftcsim.hardware.camera;

import dev.ftcsim.contracts.world.Vec3;

/**
 * A 3x3 matrix, used for rotations. Immutable; every function of an angle goes through {@link StrictMath}, so the
 * same inputs give the same bits on every machine. The cameras and the inertial sensors of
 * {@code dev.ftcsim.hardware.device} share it, which is why its rotation functions are public.
 */
public final class Mat3 {

    static final Mat3 IDENTITY = new Mat3(1, 0, 0, 0, 1, 0, 0, 0, 1);

    final double m00;
    final double m01;
    final double m02;
    final double m10;
    final double m11;
    final double m12;
    final double m20;
    final double m21;
    final double m22;

    Mat3(double m00, double m01, double m02, double m10, double m11, double m12, double m20, double m21, double m22) {
        this.m00 = m00;
        this.m01 = m01;
        this.m02 = m02;
        this.m10 = m10;
        this.m11 = m11;
        this.m12 = m12;
        this.m20 = m20;
        this.m21 = m21;
        this.m22 = m22;
    }

    /** The matrix whose columns are the three vectors. */
    public static Mat3 columns(Vec3 c0, Vec3 c1, Vec3 c2) {
        return new Mat3(c0.x(), c1.x(), c2.x(), c0.y(), c1.y(), c2.y(), c0.z(), c1.z(), c2.z());
    }

    /** The rotation a quaternion (w, x, y, z) stands for; the quaternion is normalised first. */
    public static Mat3 fromQuaternion(double w, double x, double y, double z) {
        double norm = Math.sqrt(w * w + x * x + y * y + z * z);
        double qw = w / norm;
        double qx = x / norm;
        double qy = y / norm;
        double qz = z / norm;
        return new Mat3(
                1 - 2 * (qy * qy + qz * qz), 2 * (qx * qy - qw * qz), 2 * (qx * qz + qw * qy),
                2 * (qx * qy + qw * qz), 1 - 2 * (qx * qx + qz * qz), 2 * (qy * qz - qw * qx),
                2 * (qx * qz - qw * qy), 2 * (qy * qz + qw * qx), 1 - 2 * (qx * qx + qy * qy));
    }

    static Mat3 rotX(double radians) {
        double c = StrictMath.cos(radians);
        double s = StrictMath.sin(radians);
        return new Mat3(1, 0, 0, 0, c, -s, 0, s, c);
    }

    static Mat3 rotY(double radians) {
        double c = StrictMath.cos(radians);
        double s = StrictMath.sin(radians);
        return new Mat3(c, 0, s, 0, 1, 0, -s, 0, c);
    }

    public static Mat3 rotZ(double radians) {
        double c = StrictMath.cos(radians);
        double s = StrictMath.sin(radians);
        return new Mat3(c, -s, 0, s, c, 0, 0, 0, 1);
    }

    /** The rotation about the vector's direction by its length, in radians (Rodrigues' formula). */
    static Mat3 fromRotationVector(double x, double y, double z) {
        double angle = Math.sqrt(x * x + y * y + z * z);
        if (angle < 1e-12) {
            return new Mat3(1, -z, y, z, 1, -x, -y, x, 1);
        }
        double ax = x / angle;
        double ay = y / angle;
        double az = z / angle;
        double c = StrictMath.cos(angle);
        double s = StrictMath.sin(angle);
        double k = 1 - c;
        return new Mat3(
                c + ax * ax * k, ax * ay * k - az * s, ax * az * k + ay * s,
                ay * ax * k + az * s, c + ay * ay * k, ay * az * k - ax * s,
                az * ax * k - ay * s, az * ay * k + ax * s, c + az * az * k);
    }

    /** The rotation of a Z-Y-X decomposition: first roll about X, then pitch about Y, then yaw about Z, all fixed axes. */
    public static Mat3 fromYawPitchRoll(double yaw, double pitch, double roll) {
        return rotZ(yaw).times(rotY(pitch)).times(rotX(roll));
    }

    public Mat3 times(Mat3 o) {
        return new Mat3(
                m00 * o.m00 + m01 * o.m10 + m02 * o.m20, m00 * o.m01 + m01 * o.m11 + m02 * o.m21, m00 * o.m02 + m01 * o.m12 + m02 * o.m22,
                m10 * o.m00 + m11 * o.m10 + m12 * o.m20, m10 * o.m01 + m11 * o.m11 + m12 * o.m21, m10 * o.m02 + m11 * o.m12 + m12 * o.m22,
                m20 * o.m00 + m21 * o.m10 + m22 * o.m20, m20 * o.m01 + m21 * o.m11 + m22 * o.m21, m20 * o.m02 + m21 * o.m12 + m22 * o.m22);
    }

    public Vec3 times(Vec3 v) {
        return new Vec3(
                m00 * v.x() + m01 * v.y() + m02 * v.z(),
                m10 * v.x() + m11 * v.y() + m12 * v.z(),
                m20 * v.x() + m21 * v.y() + m22 * v.z());
    }

    public Mat3 transpose() {
        return new Mat3(m00, m10, m20, m01, m11, m21, m02, m12, m22);
    }

    Vec3 column(int index) {
        return switch (index) {
            case 0 -> new Vec3(m00, m10, m20);
            case 1 -> new Vec3(m01, m11, m21);
            default -> new Vec3(m02, m12, m22);
        };
    }

    /** The rotation about Z of the Z-Y-X decomposition, in (-pi, pi]. */
    public double yaw() {
        return StrictMath.atan2(m10, m00);
    }

    /** The rotation about Y of the same decomposition, positive when X points down, in [-pi/2, pi/2]. */
    public double pitch() {
        return StrictMath.asin(Math.max(-1.0, Math.min(1.0, -m20)));
    }

    /** The rotation about X of the same decomposition, in (-pi, pi]. */
    public double roll() {
        return StrictMath.atan2(m21, m22);
    }

    /** The unit quaternion {@code {w, x, y, z}} of this rotation, the one with {@code w >= 0}. */
    public double[] toQuaternion() {
        double w;
        double x;
        double y;
        double z;
        double trace = m00 + m11 + m22;
        if (trace > 0) {
            double s = 2.0 * Math.sqrt(trace + 1.0);
            w = 0.25 * s;
            x = (m21 - m12) / s;
            y = (m02 - m20) / s;
            z = (m10 - m01) / s;
        } else if (m00 > m11 && m00 > m22) {
            double s = 2.0 * Math.sqrt(1.0 + m00 - m11 - m22);
            w = (m21 - m12) / s;
            x = 0.25 * s;
            y = (m01 + m10) / s;
            z = (m02 + m20) / s;
        } else if (m11 > m22) {
            double s = 2.0 * Math.sqrt(1.0 + m11 - m00 - m22);
            w = (m02 - m20) / s;
            x = (m01 + m10) / s;
            y = 0.25 * s;
            z = (m12 + m21) / s;
        } else {
            double s = 2.0 * Math.sqrt(1.0 + m22 - m00 - m11);
            w = (m10 - m01) / s;
            x = (m02 + m20) / s;
            y = (m12 + m21) / s;
            z = 0.25 * s;
        }
        return w < 0 ? new double[] {-w, -x, -y, -z} : new double[] {w, x, y, z};
    }
}
