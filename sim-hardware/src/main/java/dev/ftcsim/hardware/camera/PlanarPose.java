package dev.ftcsim.hardware.camera;

import dev.ftcsim.contracts.world.Vec3;
import java.util.ArrayList;
import java.util.List;

/**
 * The poses of a flat square that fit four observed corners, in closed form: Infinitesimal Plane-based Pose
 * Estimation (Collins and Bartoli, 2014), the method behind OpenCV's {@code SOLVEPNP_IPPE_SQUARE}. A flat target seen
 * from a distance has two poses that explain the image almost equally well, with the plane tilted one way or the
 * other, and this returns both. They are the starting points for {@link PoseSolver}, which decides between them by
 * reprojection error, as a tag detector does.
 *
 * <p>The square's frame has +X to the right and +Y up as seen from the front and +Z out of the face, so its corners
 * are, counter-clockwise from the front: (-h, -h), (h, -h), (h, h), (-h, h).
 */
final class PlanarPose {

    private PlanarPose() {}

    /**
     * @param pixels the four corners in the image as {@code u0, v0, u1, v1, ...}, in the order above
     * @param half   half the side of the square, in metres
     * @return the poses of the square's frame in the camera's frame, the tilt towards the camera first and its mirror
     *         second, or none if the corners are degenerate; a square seen head-on has one
     */
    static List<Rigid> candidates(Pinhole camera, double[] pixels, double half) {
        double[][] model = {{-half, -half}, {half, -half}, {half, half}, {-half, half}};
        double[][] system = new double[8][9];
        for (int i = 0; i < 4; i++) {
            double x = model[i][0];
            double y = model[i][1];
            double u = (pixels[2 * i] - camera.cx()) / camera.fx();
            double v = (pixels[2 * i + 1] - camera.cy()) / camera.fy();
            system[2 * i] = new double[] {x, y, 1, 0, 0, 0, -u * x, -u * y, u};
            system[2 * i + 1] = new double[] {0, 0, 0, x, y, 1, -v * x, -v * y, v};
        }
        double[] h = Linear.solve(system);
        if (h == null) {
            return List.of();
        }
        double h13 = h[2];
        double h23 = h[5];
        // The warp's Jacobian at the square's centre.
        double j00 = h[0] - h[6] * h13;
        double j01 = h[1] - h[7] * h13;
        double j10 = h[3] - h[6] * h23;
        double j11 = h[4] - h[7] * h23;

        double norm = Math.sqrt(h13 * h13 + h23 * h23 + 1.0);
        Vec3 toCentre = new Vec3(h13 / norm, h23 / norm, 1.0 / norm);
        Mat3 rv = rotationFromZ(toCentre);
        Mat3 q = rv.transpose();
        // The Jacobian as the camera would see it with the centre of the square moved onto its optical axis.
        double a00 = (q.m00 * j00 + q.m01 * j10) / norm;
        double a01 = (q.m00 * j01 + q.m01 * j11) / norm;
        double a10 = (q.m10 * j00 + q.m11 * j10) / norm;
        double a11 = (q.m10 * j01 + q.m11 * j11) / norm;

        // A = R22 / depth, and the singular values of R22 are 1 and the cosine of the tilt of the plane.
        double g00 = a00 * a00 + a01 * a01;
        double g01 = a00 * a10 + a01 * a11;
        double g11 = a10 * a10 + a11 * a11;
        double mean = 0.5 * (g00 + g11);
        double radius = Math.sqrt(0.25 * (g00 - g11) * (g00 - g11) + g01 * g01);
        double s1 = Math.sqrt(mean + radius);
        double s2 = Math.sqrt(Math.max(0.0, mean - radius));
        if (s1 < 1e-12) {
            return List.of();
        }
        double phi = 0.5 * StrictMath.atan2(2.0 * g01, g00 - g11);
        double minorX = -StrictMath.sin(phi);
        double minorY = StrictMath.cos(phi);
        double depth = 1.0 / s1;
        double r00 = a00 * depth;
        double r10 = a10 * depth;

        double cosTilt = s2 / s1;
        double sideways = Math.sqrt(Math.max(0.0, 1.0 - cosTilt * cosTilt));
        double nz = -cosTilt;
        if (cosTilt < 1e-9) {
            return List.of();
        }
        List<Rigid> poses = new ArrayList<>(2);
        for (int sign = 1; sign >= -1; sign -= 2) {
            Vec3 normal = new Vec3(sign * sideways * minorX, sign * sideways * minorY, nz);
            Vec3 xAxis = new Vec3(r00, r10, -(r00 * normal.x() + r10 * normal.y()) / nz);
            xAxis = xAxis.minus(normal.times(xAxis.dot(normal)));
            xAxis = xAxis.times(1.0 / xAxis.length());
            Vec3 yAxis = normal.cross(xAxis);
            Mat3 aligned = Mat3.columns(xAxis, yAxis, normal);
            poses.add(new Rigid(rv.times(aligned), toCentre.times(depth)));
            if (sideways < 1e-9) {
                break;
            }
        }
        return poses;
    }

    /** The shortest rotation that carries +Z onto the unit vector {@code d}. */
    private static Mat3 rotationFromZ(Vec3 d) {
        double s = Math.sqrt(d.x() * d.x() + d.y() * d.y());
        if (s < 1e-12) {
            return Mat3.IDENTITY;
        }
        double c = d.z();
        double kx = -d.y() / s;
        double ky = d.x() / s;
        double k = 1.0 - c;
        return new Mat3(
                c + kx * kx * k, kx * ky * k, ky * s,
                ky * kx * k, c + ky * ky * k, -kx * s,
                -ky * s, kx * s, c);
    }
}
