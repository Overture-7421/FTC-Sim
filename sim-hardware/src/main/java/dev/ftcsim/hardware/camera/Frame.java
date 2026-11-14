package dev.ftcsim.hardware.camera;

import java.util.List;

/**
 * What one frame of an AprilTag pipeline came to: the tags it found, the robot poses its tags give, and when it was
 * taken and finished. Everything a Limelight reports about the frame is here; {@link LimelightJson} writes it out.
 *
 * @param captureNanos    the middle of the exposure of the middle row of the sensor
 * @param startNanos      when the pipeline began: {@code captureNanos} plus the capture latency
 * @param completionNanos when the result became available: {@code startNanos} plus the targeting latency
 * @param pipeline        the pipeline the frame went through
 * @param fiducial        whether that pipeline looks for AprilTags; the others are not simulated and find nothing
 * @param tags            the tags found, the largest first
 * @param botpose         MegaTag 1: x, y, z in metres and roll, pitch, yaw in degrees, or zeros without a mapped tag
 * @param tagCount        how many mapped tags the pose was fitted to
 * @param botposeOrb      MegaTag 2, which takes the robot's yaw from robot code; zeros without one
 */
record Frame(
        long index,
        long captureNanos,
        long startNanos,
        long completionNanos,
        double captureLatencyMillis,
        double targetingLatencyMillis,
        int pipeline,
        boolean fiducial,
        boolean sendCorners,
        int imageWidth,
        int imageHeight,
        List<Reading> tags,
        double[] botpose,
        double[] stdevMt1,
        int tagCount,
        double spanMeters,
        double averageDistanceMeters,
        double averageAreaPercent,
        double[] botposeOrb,
        double[] stdevMt2) {

    /**
     * One tag as the pipeline reports it.
     *
     * @param pixels         the four corners as found, {@code u0, v0, ..., u3, v3}
     * @param area           the quadrilateral's share of the image, in 0..1
     * @param cameraInTarget six numbers each: x, y, z and the roll, pitch and yaw of the pose, in the frame the name says
     */
    record Reading(
            int id,
            boolean mapped,
            double[] pixels,
            double centreU,
            double centreV,
            double txDegrees,
            double tyDegrees,
            double area,
            double[] cameraInTarget,
            double[] robotInField,
            double[] robotInTarget,
            double[] targetInCamera,
            double[] targetInRobot) {}
}
