package dev.ftcsim.hardware.camera;

import dev.ftcsim.contracts.world.Vec3;

/**
 * An ideal pinhole camera whose image has been undistorted: pixel {@code (0, 0)} is the top-left corner of the image,
 * x grows to the right and y downwards, and the camera looks along +Z of its own frame (X right, Y down, as the
 * Limelight's camera space has it).
 */
record Pinhole(int width, int height, double fx, double fy, double cx, double cy) {

    /**
     * A camera with the given fields of view, as a datasheet gives them: the angle between the left and right edges
     * of the image, and between the top and bottom edges. The principal point is the centre of the image.
     */
    static Pinhole fromFieldOfView(int width, int height, double horizontalDegrees, double verticalDegrees) {
        double fx = 0.5 * width / StrictMath.tan(StrictMath.toRadians(0.5 * horizontalDegrees));
        double fy = 0.5 * height / StrictMath.tan(StrictMath.toRadians(0.5 * verticalDegrees));
        return new Pinhole(width, height, fx, fy, 0.5 * width, 0.5 * height);
    }

    /** Where a point in front of the camera lands in the image, as {@code {u, v}}. The point must have positive depth. */
    double[] project(Vec3 inCamera) {
        return new double[] {fx * inCamera.x() / inCamera.z() + cx, fy * inCamera.y() / inCamera.z() + cy};
    }

    /** The direction of the ray through a pixel, in the camera frame, with depth 1. */
    Vec3 ray(double u, double v) {
        return new Vec3((u - cx) / fx, (v - cy) / fy, 1.0);
    }

    boolean contains(double u, double v, double margin) {
        return u >= margin && u <= width - margin && v >= margin && v <= height - margin;
    }

    /** The horizontal angle of a pixel from the principal point, in degrees; positive to the right. */
    double horizontalDegrees(double u) {
        return StrictMath.toDegrees(StrictMath.atan((u - cx) / fx));
    }

    /** The vertical angle of a pixel from the principal point, in degrees; positive upwards. */
    double verticalDegrees(double v) {
        return -StrictMath.toDegrees(StrictMath.atan((v - cy) / fy));
    }
}
