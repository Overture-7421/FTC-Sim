package dev.ftcsim.game.biobuzz;

import dev.ftcsim.contracts.field.Pose3;
import dev.ftcsim.contracts.field.Shape;

/** Small constructors for the solids the field is made of, so that the geometry reads as a list of dimensions. */
final class Solids {

    /** Inches to metres: the manual and the CAD are in inches. */
    static double in(double inches) {
        return inches * 0.0254;
    }

    private Solids() {}

    /** A box with its edges along the field axes, from its centre and its full sizes in inches. */
    static Solid box(String name, double cx, double cy, double cz, double sx, double sy, double sz, int rgb, boolean collides) {
        return new Solid(
                name, new Shape.Box(in(sx) / 2, in(sy) / 2, in(sz) / 2), Pose3.at(in(cx), in(cy), in(cz)), rgb, true, collides);
    }

    /** A box turned about the vertical axis by {@code yawRadians}. */
    static Solid yawedBox(
            String name, double cx, double cy, double cz, double sx, double sy, double sz, double yawRadians, int rgb, boolean collides) {
        return new Solid(
                name,
                new Shape.Box(in(sx) / 2, in(sy) / 2, in(sz) / 2),
                Pose3.turned(in(cx), in(cy), in(cz), 0, 0, 1, yawRadians),
                rgb, true, collides);
    }

    /** A box whose own axes lie along the three given unit vectors, which must be right-handed. */
    static Solid orientedBox(
            String name, double cx, double cy, double cz, double[] ex, double[] ey, double[] ez,
            double hx, double hy, double hz, int rgb, boolean collides) {
        return new Solid(
                name, new Shape.Box(in(hx), in(hy), in(hz)), orientation(in(cx), in(cy), in(cz), ex, ey, ez), rgb, true, collides);
    }

    /** An upright cylinder from its base height and its height, in inches. */
    static Solid upright(String name, double cx, double cy, double zBottom, double radius, double height, int rgb, boolean collides) {
        return new Solid(
                name, new Shape.Cylinder(in(radius), in(height) / 2), Pose3.at(in(cx), in(cy), in(zBottom + height / 2)),
                rgb, true, collides);
    }

    /** The pose that carries the unit axes of a frame onto the three vectors given as its columns. */
    static Pose3 orientation(double x, double y, double z, double[] ex, double[] ey, double[] ez) {
        double m00 = ex[0];
        double m10 = ex[1];
        double m20 = ex[2];
        double m01 = ey[0];
        double m11 = ey[1];
        double m21 = ey[2];
        double m02 = ez[0];
        double m12 = ez[1];
        double m22 = ez[2];
        double trace = m00 + m11 + m22;
        double qw;
        double qx;
        double qy;
        double qz;
        if (trace > 0) {
            double s = 2.0 * StrictMath.sqrt(trace + 1.0);
            qw = 0.25 * s;
            qx = (m21 - m12) / s;
            qy = (m02 - m20) / s;
            qz = (m10 - m01) / s;
        } else if (m00 > m11 && m00 > m22) {
            double s = 2.0 * StrictMath.sqrt(1.0 + m00 - m11 - m22);
            qw = (m21 - m12) / s;
            qx = 0.25 * s;
            qy = (m01 + m10) / s;
            qz = (m02 + m20) / s;
        } else if (m11 > m22) {
            double s = 2.0 * StrictMath.sqrt(1.0 + m11 - m00 - m22);
            qw = (m02 - m20) / s;
            qx = (m01 + m10) / s;
            qy = 0.25 * s;
            qz = (m12 + m21) / s;
        } else {
            double s = 2.0 * StrictMath.sqrt(1.0 + m22 - m00 - m11);
            qw = (m10 - m01) / s;
            qx = (m02 + m20) / s;
            qy = (m12 + m21) / s;
            qz = 0.25 * s;
        }
        double norm = StrictMath.sqrt(qw * qw + qx * qx + qy * qy + qz * qz);
        return new Pose3(x, y, z, qw / norm, qx / norm, qy / norm, qz / norm);
    }

    static double[] cross(double[] a, double[] b) {
        return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    static double[] unit(double... v) {
        double n = StrictMath.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        return new double[] {v[0] / n, v[1] / n, v[2] / n};
    }
}
