package dev.ftcsim.scenarios.opmodes;

import java.util.List;

/**
 * Tells which way a HIVE is tipped from the height of its AprilTags. The tags are on the underside of the arm, 0.9 m up on the end
 * that is down and 1.26 m up on the end that is up, so a tag's height above the floor (from where the camera sees it, which needs
 * no pose at all) says which of the two the arm is on. A TIP changes it, and the CELL that faces up is the other one.
 *
 * <p>A frame votes for the tilt the tags it shows agree with; the tilt changes only after several frames in a row vote for the other
 * one, and frames that agree with neither (the arm is swinging, or a tag is misread) vote for nothing.
 *
 * <p>Written for Java 8, like the other files a robot project takes.
 */
public final class BiobuzzHiveTracker {
    /** Frames in a row that must agree on the other tilt before it is believed. */
    public static final int FRAMES_TO_SWITCH = 6;
    /** A frame's tags must be this close (mean, metres) to a tilt's heights to vote for it, and this much closer than the other's. */
    static final double MAX_ERROR_M = 0.12;
    static final double MIN_ADVANTAGE_M = 0.15;

    private final BiobuzzHive.Alliance alliance;
    private BiobuzzHive.Tilt tilt;
    private int votes;
    private double lastSignature = Double.NaN;

    public BiobuzzHiveTracker(BiobuzzHive.Alliance alliance) {
        this.alliance = alliance;
        this.tilt = BiobuzzHive.startTilt(alliance);
    }

    /** The tilt believed now. */
    public BiobuzzHive.Tilt tilt() {
        return tilt;
    }

    /** The height of a tag above the floor, from where the camera sees it (X right, Y down, Z ahead; metres). */
    static double heightOf(BiobuzzPoseEstimator.Sighting tag) {
        double up = tag.z() * Math.sin(BiobuzzPoseEstimator.CAMERA_PITCH_RADIANS) - tag.y() * Math.cos(BiobuzzPoseEstimator.CAMERA_PITCH_RADIANS);
        return BiobuzzPoseEstimator.CAMERA_HEIGHT_M + up;
    }

    /**
     * Looks at one frame.
     *
     * @param sightings the tags of this frame, any HIVE's
     * @return whether the tilt changed
     */
    public boolean update(List<BiobuzzPoseEstimator.Sighting> sightings) {
        double[] error = new double[2];
        int own = 0;
        BiobuzzHive.Tilt[] hypotheses = BiobuzzHive.Tilt.values();
        for (BiobuzzPoseEstimator.Sighting tag : sightings) {
            if (!BiobuzzHive.isOwnTag(alliance, tag.id())) {
                continue;
            }
            own++;
            double height = heightOf(tag);
            for (int i = 0; i < hypotheses.length; i++) {
                error[i] += Math.abs(height - BiobuzzHive.tags(alliance, hypotheses[i]).get(tag.id()).z());
            }
        }
        if (own == 0) {
            return false;
        }
        // The loop runs faster than the camera: the same frame again is not another vote.
        double signature = error[0] + 1000.0 * error[1];
        if (signature == lastSignature) {
            return false;
        }
        lastSignature = signature;
        int best = error[0] <= error[1] ? 0 : 1;
        double bestError = error[best] / own;
        double otherError = error[1 - best] / own;
        if (bestError > MAX_ERROR_M || otherError - bestError < MIN_ADVANTAGE_M) {
            return false;
        }
        if (hypotheses[best] == tilt) {
            votes = 0;
            return false;
        }
        votes++;
        if (votes < FRAMES_TO_SWITCH) {
            return false;
        }
        votes = 0;
        tilt = hypotheses[best];
        return true;
    }
}
