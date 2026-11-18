package dev.ftcsim.acceptance;

import dev.ftcsim.scenarios.opmodes.BiobuzzHive;
import dev.ftcsim.scenarios.opmodes.BiobuzzPoseEstimator;
import dev.ftcsim.scenarios.opmodes.BiobuzzPoseEstimator.Pose;
import dev.ftcsim.scenarios.opmodes.BiobuzzPoseEstimator.Sighting;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What the Limelight on the turret would report of the HIVE tags, made by working the other way round from the estimator (from the
 * tags' places, the camera's mount and the turret's angle), so that a mistake in one direction of the frame change shows up.
 */
final class BiobuzzSightings {
    private static final double BODY_CENTRE_HEIGHT = 0.1824;
    private static final double CAMERA_Z = 0.256;

    private BiobuzzSightings() {}

    /** What the camera sees of a tag, as X right, Y down, Z ahead, from a robot at {@code truth} with the turret at {@code turret}. */
    static Sighting see(Map<Integer, BiobuzzHive.Tag> tags, int id, Pose truth, double turret) {
        BiobuzzHive.Tag tag = tags.get(id);
        double c = Math.cos(-truth.heading());
        double s = Math.sin(-truth.heading());
        double rx = c * (tag.x() - truth.x()) - s * (tag.y() - truth.y());
        double ry = s * (tag.x() - truth.x()) + c * (tag.y() - truth.y());
        double rz = tag.z() - BODY_CENTRE_HEIGHT;
        // Undo the turret's turn about its axis.
        double dx = rx - BiobuzzPoseEstimator.TURRET_AXIS_X;
        double dy = ry - BiobuzzPoseEstimator.TURRET_AXIS_Y;
        double tc = Math.cos(-turret);
        double ts = Math.sin(-turret);
        double px = BiobuzzPoseEstimator.TURRET_AXIS_X + dx * tc - dy * ts;
        double py = BiobuzzPoseEstimator.TURRET_AXIS_Y + dx * ts + dy * tc;
        // From the camera, in the robot's axes; then undo the camera's upward tilt.
        double forward = px - BiobuzzPoseEstimator.CAMERA_X;
        double left = py - BiobuzzPoseEstimator.CAMERA_Y;
        double up = rz - CAMERA_Z;
        double pitch = BiobuzzPoseEstimator.CAMERA_PITCH_RADIANS;
        double along = forward * Math.cos(pitch) + up * Math.sin(pitch);
        double above = -forward * Math.sin(pitch) + up * Math.cos(pitch);
        return new Sighting(id, -left, -above, along);
    }

    static List<Sighting> cluster(Map<Integer, BiobuzzHive.Tag> tags, Pose truth, double turret, int... ids) {
        List<Sighting> sightings = new ArrayList<>();
        for (int id : ids) {
            sightings.add(see(tags, id, truth, turret));
        }
        return sightings;
    }
}
