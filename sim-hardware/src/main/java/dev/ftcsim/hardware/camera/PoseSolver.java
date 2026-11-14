package dev.ftcsim.hardware.camera;

import dev.ftcsim.contracts.world.Vec3;
import java.util.List;

/**
 * Finds where a robot is from where the corners of tags of known place appeared in the camera's image: the corners'
 * reprojection error is minimised over the robot's pose by Levenberg-Marquardt, with the Jacobian worked out
 * analytically. Every tag's four corners count, so with tags in different planes the pose is unique, as with
 * Limelight's MegaTag; one flat tag leaves two poses, and {@link #fit} returns the one that explains the pixels best.
 *
 * <p>The search is deterministic: fixed starting points from {@link PlanarPose}, no random restarts, a fixed cap on
 * iterations. The unknown is the pose of the robot's frame in the field; the camera's own pose in the robot's frame
 * is given.
 */
final class PoseSolver {

    /** One tag: its corners where the camera believes them to be in the field, and where they appeared. */
    record Observation(double[] fieldCorners, double[] pixels) {

        Vec3 corner(int index) {
            return new Vec3(fieldCorners[3 * index], fieldCorners[3 * index + 1], fieldCorners[3 * index + 2]);
        }
    }

    /**
     * @param robot   the pose of the robot in the field
     * @param cost    the sum of the squared reprojection errors, in pixels squared
     * @param sigma   one standard deviation of x, y, z in metres and of roll, pitch, yaw about the field's axes in
     *                degrees, that the pixel noise {@code pixelSigma} leaves in the pose
     */
    record Fit(Rigid robot, double cost, double[] sigma) {}

    private static final int MAX_ITERATIONS = 60;
    private static final int MAX_DAMPING_TRIES = 14;
    private static final double MIN_DEPTH = 1e-3;
    private static final double UNKNOWN_SIGMA = 1e3;

    private final Pinhole camera;
    private final Rigid cameraInRobot;

    PoseSolver(Pinhole camera, Rigid cameraInRobot) {
        this.camera = camera;
        this.cameraInRobot = cameraInRobot;
    }

    /**
     * The best pose over every starting point that the two largest tags suggest, or null if nothing converged.
     *
     * @param observations the tags that were seen, largest first; at least one
     * @param fieldFromTag the field pose of the tag each observation belongs to, in the same order, which fixes the
     *                     starting points
     * @param halfSides    half the side of each tag
     */
    Fit fit(List<Observation> observations, List<Rigid> fieldFromTag, List<Double> halfSides, double pixelSigma) {
        Fit best = null;
        int seeds = Math.min(2, observations.size());
        for (int i = 0; i < seeds; i++) {
            for (Rigid tagInCamera : PlanarPose.candidates(camera, observations.get(i).pixels(), halfSides.get(i))) {
                Rigid cameraInField = fieldFromTag.get(i).compose(tagInCamera.inverse());
                Rigid start = cameraInField.compose(cameraInRobot.inverse());
                Fit fit = refine(observations, start.rotation(), start.translation(), true, pixelSigma);
                if (fit != null && (best == null || fit.cost() < best.cost())) {
                    best = fit;
                }
            }
        }
        return best;
    }

    /**
     * The robot's position when its rotation is already known, as MegaTag 2 knows it from the robot's gyro: a linear
     * least-squares estimate refined by the same minimisation.
     */
    Fit fitPosition(List<Observation> observations, Mat3 robotRotation, double pixelSigma) {
        Vec3 start = linearPosition(observations, robotRotation);
        return start == null ? null : refine(observations, robotRotation, start, false, pixelSigma);
    }

    private Vec3 linearPosition(List<Observation> observations, Mat3 robotRotation) {
        Mat3 cameraRotation = robotRotation.times(cameraInRobot.rotation());
        Mat3 toCamera = cameraRotation.transpose();
        double[][] normal = new double[3][4];
        for (Observation observation : observations) {
            for (int i = 0; i < 4; i++) {
                Vec3 a = toCamera.times(observation.corner(i));
                double du = observation.pixels()[2 * i] - camera.cx();
                double dv = observation.pixels()[2 * i + 1] - camera.cy();
                accumulate(normal, new double[] {camera.fx(), 0, -du}, camera.fx() * a.x() - du * a.z());
                accumulate(normal, new double[] {0, camera.fy(), -dv}, camera.fy() * a.y() - dv * a.z());
            }
        }
        double[] b = Linear.solve(normal);
        if (b == null) {
            return null;
        }
        Vec3 cameraPosition = cameraRotation.times(new Vec3(b[0], b[1], b[2]));
        return cameraPosition.minus(robotRotation.times(cameraInRobot.translation()));
    }

    private static void accumulate(double[][] normal, double[] row, double rhs) {
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                normal[i][j] += row[i] * row[j];
            }
            normal[i][3] += row[i] * rhs;
        }
    }

    /** Where the camera would see each corner, minus where it did, or null if a corner is not in front of the camera. */
    double[] residuals(List<Observation> observations, Mat3 robotRotation, Vec3 robotPosition) {
        Rigid fieldToCamera = cameraInField(robotRotation, robotPosition).inverse();
        double[] residuals = new double[8 * observations.size()];
        int k = 0;
        for (Observation observation : observations) {
            for (int i = 0; i < 4; i++) {
                Vec3 inCamera = fieldToCamera.apply(observation.corner(i));
                if (inCamera.z() < MIN_DEPTH) {
                    return null;
                }
                double[] pixel = camera.project(inCamera);
                residuals[k++] = pixel[0] - observation.pixels()[2 * i];
                residuals[k++] = pixel[1] - observation.pixels()[2 * i + 1];
            }
        }
        return residuals;
    }

    private Rigid cameraInField(Mat3 robotRotation, Vec3 robotPosition) {
        return new Rigid(robotRotation, robotPosition).compose(cameraInRobot);
    }

    /** The Jacobian of {@link #residuals} with respect to the rotation (about the field's axes) and the position. */
    double[][] jacobian(List<Observation> observations, Mat3 robotRotation, Vec3 robotPosition, boolean freeRotation) {
        Rigid cameraPose = cameraInField(robotRotation, robotPosition);
        Mat3 toCamera = cameraPose.rotation().transpose();
        Rigid fieldToCamera = cameraPose.inverse();
        int parameters = freeRotation ? 6 : 3;
        double[][] jacobian = new double[8 * observations.size()][parameters];
        int row = 0;
        for (Observation observation : observations) {
            for (int i = 0; i < 4; i++) {
                Vec3 corner = observation.corner(i);
                Vec3 inCamera = fieldToCamera.apply(corner);
                double z = inCamera.z();
                double[] dU = {camera.fx() / z, 0, -camera.fx() * inCamera.x() / (z * z)};
                double[] dV = {0, camera.fy() / z, -camera.fy() * inCamera.y() / (z * z)};
                Vec3 w = corner.minus(robotPosition);
                for (int p = 0; p < parameters; p++) {
                    Vec3 dX = p < parameters - 3
                            ? toCamera.times(w.cross(unit(p)))
                            : toCamera.times(unit(p - (parameters - 3))).times(-1.0);
                    jacobian[row][p] = dU[0] * dX.x() + dU[1] * dX.y() + dU[2] * dX.z();
                    jacobian[row + 1][p] = dV[0] * dX.x() + dV[1] * dX.y() + dV[2] * dX.z();
                }
                row += 2;
            }
        }
        return jacobian;
    }

    private static Vec3 unit(int axis) {
        return new Vec3(axis == 0 ? 1 : 0, axis == 1 ? 1 : 0, axis == 2 ? 1 : 0);
    }

    private Fit refine(List<Observation> observations, Mat3 rotation, Vec3 position, boolean freeRotation, double pixelSigma) {
        int n = freeRotation ? 6 : 3;
        Mat3 r = rotation;
        Vec3 t = position;
        double[] residuals = residuals(observations, r, t);
        if (residuals == null) {
            return null;
        }
        double cost = dot(residuals, residuals);
        double lambda = 1e-3;
        double[][] jacobian = null;
        for (int iteration = 0; iteration < MAX_ITERATIONS && cost > 1e-24; iteration++) {
            jacobian = jacobian(observations, r, t, freeRotation);
            double[][] normal = new double[n][n + 1];
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    double sum = 0;
                    for (double[] row : jacobian) {
                        sum += row[i] * row[j];
                    }
                    normal[i][j] = sum;
                }
                double gradient = 0;
                for (int k = 0; k < residuals.length; k++) {
                    gradient += jacobian[k][i] * residuals[k];
                }
                normal[i][n] = -gradient;
            }
            boolean improved = false;
            double stepSize = 0;
            for (int attempt = 0; attempt < MAX_DAMPING_TRIES && !improved; attempt++) {
                double[][] damped = new double[n][];
                for (int i = 0; i < n; i++) {
                    damped[i] = normal[i].clone();
                    damped[i][i] += lambda * Math.max(normal[i][i], 1e-12);
                }
                double[] step = Linear.solve(damped);
                if (step != null) {
                    Mat3 candidateRotation = freeRotation ? Mat3.fromRotationVector(step[0], step[1], step[2]).times(r) : r;
                    int offset = freeRotation ? 3 : 0;
                    Vec3 candidatePosition = t.plus(new Vec3(step[offset], step[offset + 1], step[offset + 2]));
                    double[] candidate = residuals(observations, candidateRotation, candidatePosition);
                    double candidateCost = candidate == null ? Double.POSITIVE_INFINITY : dot(candidate, candidate);
                    if (candidateCost < cost) {
                        improved = true;
                        stepSize = 0;
                        for (double component : step) {
                            stepSize = Math.max(stepSize, Math.abs(component));
                        }
                        r = candidateRotation;
                        t = candidatePosition;
                        residuals = candidate;
                        cost = candidateCost;
                        lambda = Math.max(lambda / 3.0, 1e-15);
                    }
                }
                if (!improved) {
                    lambda *= 4.0;
                }
            }
            if (!improved || stepSize < 1e-13) {
                break;
            }
        }
        return new Fit(new Rigid(r, t), cost, sigma(observations, r, t, freeRotation, pixelSigma));
    }

    private double[] sigma(List<Observation> observations, Mat3 r, Vec3 t, boolean freeRotation, double pixelSigma) {
        int n = freeRotation ? 6 : 3;
        double[][] jacobian = jacobian(observations, r, t, freeRotation);
        double[][] normal = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                double sum = 0;
                for (double[] row : jacobian) {
                    sum += row[i] * row[j];
                }
                normal[i][j] = sum;
            }
        }
        double[][] covariance = Linear.invert(normal);
        double[] sigma = new double[6];
        for (int axis = 0; axis < 6; axis++) {
            double variance = Double.NaN;
            if (covariance != null) {
                int parameter = freeRotation ? (axis < 3 ? axis + 3 : axis - 3) : (axis < 3 ? axis : -1);
                variance = parameter < 0 ? 0.0 : covariance[parameter][parameter];
            }
            double deviation = Double.isNaN(variance) || variance < 0 ? UNKNOWN_SIGMA : pixelSigma * Math.sqrt(variance);
            sigma[axis] = axis < 3 ? Math.min(deviation, UNKNOWN_SIGMA) : Math.min(StrictMath.toDegrees(deviation), UNKNOWN_SIGMA);
        }
        return sigma;
    }

    private static double dot(double[] a, double[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }
}
