package dev.ftcsim.scenarios.opmodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A pose estimator for the BIOBUZZ robot: the Pinpoint's odometry, which is smooth and drifts, corrected by the Limelight's 3D
 * targets, which are noisy and do not drift. The odometry moves the estimate every loop; each time the camera sees AprilTags
 * whose place on the field is known, the estimate is pulled towards the pose they give. The pull follows Hornet's {@code Camera}
 * subsystem: a fixed share of the difference, bigger for the position when the tags are near (the pixels of a close tag
 * say more about where the robot is) and bigger for the heading when they are far.
 *
 * <p>Hornet takes the camera's own {@code botpose}. This robot's field has none (every tag is on a HIVE that tips, so the
 * SDK has no map), so the pose is worked out here: each tag's 3D position in the camera frame goes to the robot frame through the
 * camera's mount and the turret's angle, and the robot's pose is the one that puts those points on the tags of {@link BiobuzzHive}.
 * With two tags or more both the position and the heading come from them; with one only the position does, using the estimate's
 * heading.
 *
 * <p>The estimate is the odometry turned and moved by a correction (so the Pinpoint itself is never written to). Until the camera
 * has seen two tags together the estimate has no pose, unless it was given a start pose. A fix that lands more than
 * {@link #MAX_RESIDUAL_M} off the tags (a tipped HIVE has moved them), or far from the estimate, is ignored.
 *
 * <p>Nothing here knows about the simulator: the same file runs on the real robot after the camera's mount is measured.
 */
public final class BiobuzzPoseEstimator {
    /** A pose on the field: metres and radians, counter-clockwise from the field's X axis. */
    public static final class Pose {
        private final double x;
        private final double y;
        private final double heading;

        public Pose(double x, double y, double heading) {
            this.x = x;
            this.y = y;
            this.heading = heading;
        }

        public double x() {
            return x;
        }

        public double y() {
            return y;
        }

        public double heading() {
            return heading;
        }
    }

    /** A tag the camera sees: its id and where its centre is in the camera's frame (X right, Y down, Z ahead), metres. */
    public static final class Sighting {
        private final int id;
        private final double x;
        private final double y;
        private final double z;

        public Sighting(int id, double x, double y, double z) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public int id() {
            return id;
        }

        public double x() {
            return x;
        }

        public double y() {
            return y;
        }

        public double z() {
            return z;
        }
    }

    /** The calibrated Limelight mount, with the turret facing forward (robot frame, metres). */
    public static final double CAMERA_X = (3.522 + 5.434666889764) * 0.0254;
    public static final double CAMERA_Y = 0.0;
    public static final double CAMERA_PITCH_RADIANS = Math.toRadians(41.0);
    /** The camera's calibrated height above the floor. */
    public static final double CAMERA_HEIGHT_M = 16.96944346457 * 0.0254;
    /** The turret's axis; the camera turns about it with the turret. */
    public static final double TURRET_AXIS_X = 3.522 * 0.0254;
    public static final double TURRET_AXIS_Y = 0.0;

    /** The blend of a fix, as Hornet's {@code Camera} has it: tags nearer than this are "close". */
    public static final double CLOSE_DISTANCE_M = 3.0;
    public static final double CLOSE_POSITION_BLEND = 0.6;
    public static final double CLOSE_HEADING_BLEND = 0.6;
    public static final double FAR_POSITION_BLEND = 0.4;
    public static final double FAR_HEADING_BLEND = 0.9;
    /** The tags may sit this far (root mean square) from where the fix puts them before the fix is thrown away. */
    public static final double MAX_RESIDUAL_M = 0.12;
    /** A fix this far from the estimate is thrown away, once the estimate has a pose. */
    public static final double MAX_JUMP_M = 1.0;
    public static final double MAX_HEADING_JUMP_RADIANS = Math.toRadians(30.0);
    /** Tags closer together than this say nothing about the heading. */
    private static final double MIN_SPREAD_M = 0.05;
    /** This many different frames in a row that agree with one another, though not with the estimate, replace the estimate. */
    private static final int RECOVERY_FRAMES = 8;
    private static final double RECOVERY_AGREEMENT_M = 0.15;
    private static final double RECOVERY_AGREEMENT_RADIANS = Math.toRadians(5.0);

    private double odoX;
    private double odoY;
    private double odoHeading;
    private boolean haveOdometry;
    private boolean havePose;
    private boolean confirmed;
    private double correctionTurn;
    private double correctionX;
    private double correctionY;
    private int fixes;
    private Map<Integer, BiobuzzHive.Tag> tagMap = BiobuzzHive.TAGS;
    private int rejectedRun;
    private double rejectedX;
    private double rejectedY;
    private double rejectedHeading;

    /** @param start where the robot starts on the field, exactly, or null to wait for the camera */
    public BiobuzzPoseEstimator(Pose start) {
        this(start, false);
    }

    /**
     * @param start       where the robot starts on the field, or null to wait for the camera
     * @param approximate the start is only roughly right (the robot may stand anywhere on its wall): the first fix from two tags or
     *                    more replaces it whatever it says, and until then the estimate is not {@link #confirmed}
     */
    public BiobuzzPoseEstimator(Pose start, boolean approximate) {
        if (start != null) {
            havePose = true;
            confirmed = !approximate;
            correctionTurn = start.heading();
            correctionX = start.x();
            correctionY = start.y();
        }
    }

    /** The tags the camera is matched with; change it when a HIVE tips and its tags move. */
    public void setTags(Map<Integer, BiobuzzHive.Tag> tags) {
        this.tagMap = tags;
    }

    /** Whether the camera has placed the robot: false while the estimate is only the start the program was given. */
    public boolean confirmed() {
        return confirmed;
    }

    /** Takes the Pinpoint's pose, in its own frame: metres and radians. Call it every loop, before {@link #correct}. */
    public void update(double x, double y, double heading) {
        if (!haveOdometry && havePose) {
            // A start pose was given: the odometry's first reading is the robot at that pose.
            correctionTurn -= heading;
            double c = Math.cos(correctionTurn);
            double s = Math.sin(correctionTurn);
            correctionX -= c * x - s * y;
            correctionY -= s * x + c * y;
        }
        haveOdometry = true;
        odoX = x;
        odoY = y;
        odoHeading = heading;
    }

    /** The estimate, or null while the robot has not been placed. */
    public Pose pose() {
        if (!havePose || !haveOdometry) {
            return null;
        }
        double c = Math.cos(correctionTurn);
        double s = Math.sin(correctionTurn);
        return new Pose(
                correctionX + c * odoX - s * odoY, correctionY + s * odoX + c * odoY, normalize(odoHeading + correctionTurn));
    }

    /** How many times the camera has corrected the estimate. */
    public int fixes() {
        return fixes;
    }

    /**
     * Lets what the camera sees correct the estimate.
     *
     * @param sightings    the tags in this frame
     * @param turretRadians where the turret was when the frame was taken, counter-clockwise from the robot's front
     * @return whether the estimate changed
     */
    public boolean correct(List<Sighting> sightings, double turretRadians) {
        if (!haveOdometry) {
            return false;
        }
        List<double[]> seen = new ArrayList<>();
        List<double[]> known = new ArrayList<>();
        for (Sighting sighting : sightings) {
            BiobuzzHive.Tag tag = tagMap.get(sighting.id());
            if (tag != null) {
                seen.add(inRobot(sighting, turretRadians));
                known.add(new double[] {tag.x(), tag.y()});
            }
        }
        if (seen.isEmpty()) {
            return false;
        }
        Pose current = pose();
        double heading;
        double x;
        double y;
        boolean headingKnown = seen.size() >= 2 && spread(known) >= MIN_SPREAD_M;
        if (headingKnown) {
            double[] meanSeen = mean(seen);
            double[] meanKnown = mean(known);
            double dot = 0.0;
            double cross = 0.0;
            for (int i = 0; i < seen.size(); i++) {
                double rx = seen.get(i)[0] - meanSeen[0];
                double ry = seen.get(i)[1] - meanSeen[1];
                double tx = known.get(i)[0] - meanKnown[0];
                double ty = known.get(i)[1] - meanKnown[1];
                dot += rx * tx + ry * ty;
                cross += rx * ty - ry * tx;
            }
            heading = Math.atan2(cross, dot);
            double c = Math.cos(heading);
            double s = Math.sin(heading);
            x = meanKnown[0] - (c * meanSeen[0] - s * meanSeen[1]);
            y = meanKnown[1] - (s * meanSeen[0] + c * meanSeen[1]);
        } else if (current != null) {
            heading = current.heading();
            double[] meanSeen = mean(seen);
            double[] meanKnown = mean(known);
            double c = Math.cos(heading);
            double s = Math.sin(heading);
            x = meanKnown[0] - (c * meanSeen[0] - s * meanSeen[1]);
            y = meanKnown[1] - (s * meanSeen[0] + c * meanSeen[1]);
        } else {
            return false;
        }

        double squares = 0.0;
        double c = Math.cos(heading);
        double s = Math.sin(heading);
        double nearest = Double.MAX_VALUE;
        for (int i = 0; i < seen.size(); i++) {
            double px = x + c * seen.get(i)[0] - s * seen.get(i)[1];
            double py = y + s * seen.get(i)[0] + c * seen.get(i)[1];
            squares += Math.pow(px - known.get(i)[0], 2) + Math.pow(py - known.get(i)[1], 2);
            nearest = Math.min(nearest, Math.hypot(known.get(i)[0] - x, known.get(i)[1] - y));
        }
        if (Math.sqrt(squares / seen.size()) > MAX_RESIDUAL_M) {
            return false;
        }

        if (current == null || !confirmed) {
            // The camera places the robot for the first time. An approximate start gives way to the first fix of two tags or more.
            if (!headingKnown) {
                return false;
            }
            place(new Pose(x, y, heading));
            confirmed = true;
        } else {
            if (Math.hypot(x - current.x(), y - current.y()) > MAX_JUMP_M
                    || (headingKnown && Math.abs(normalize(heading - current.heading())) > MAX_HEADING_JUMP_RADIANS)) {
                return headingKnown && recover(x, y, heading);
            }
            rejectedRun = 0;
            boolean close = nearest < CLOSE_DISTANCE_M;
            double positionBlend = close ? CLOSE_POSITION_BLEND : FAR_POSITION_BLEND;
            double headingBlend = close ? CLOSE_HEADING_BLEND : FAR_HEADING_BLEND;
            place(new Pose(
                    current.x() + positionBlend * (x - current.x()),
                    current.y() + positionBlend * (y - current.y()),
                    headingKnown ? normalize(current.heading() + headingBlend * normalize(heading - current.heading())) : current.heading()));
        }
        fixes++;
        return true;
    }

    /**
     * A fix too far from the estimate is ignored, but when several different frames in a row agree with one another the estimate is
     * what is wrong (the robot was pushed, or the odometry slipped): the last of them replaces it.
     */
    private boolean recover(double x, double y, double heading) {
        boolean sameAsLast = rejectedRun > 0 && Math.hypot(x - rejectedX, y - rejectedY) < RECOVERY_AGREEMENT_M
                && Math.abs(normalize(heading - rejectedHeading)) < RECOVERY_AGREEMENT_RADIANS;
        if (!sameAsLast) {
            rejectedRun = 1;
        } else if (x != rejectedX || y != rejectedY || heading != rejectedHeading) {
            rejectedRun++;
        }
        rejectedX = x;
        rejectedY = y;
        rejectedHeading = heading;
        if (rejectedRun < RECOVERY_FRAMES) {
            return false;
        }
        rejectedRun = 0;
        place(new Pose(x, y, heading));
        fixes++;
        return true;
    }

    /** Where a tag the camera sees is in the robot's frame, horizontally, with the turret at the given angle. */
    static double[] inRobot(Sighting tag, double turretRadians) {
        double cos = Math.cos(CAMERA_PITCH_RADIANS);
        double sin = Math.sin(CAMERA_PITCH_RADIANS);
        // Camera X right, Y down, Z ahead; the camera looks forward and tilted up by its pitch.
        double forward = tag.z() * cos + tag.y() * sin + CAMERA_X;
        double left = -tag.x() + CAMERA_Y;
        double dx = forward - TURRET_AXIS_X;
        double dy = left - TURRET_AXIS_Y;
        double c = Math.cos(turretRadians);
        double s = Math.sin(turretRadians);
        return new double[] {TURRET_AXIS_X + dx * c - dy * s, TURRET_AXIS_Y + dx * s + dy * c};
    }

    private void place(Pose pose) {
        havePose = true;
        correctionTurn = normalize(pose.heading() - odoHeading);
        double c = Math.cos(correctionTurn);
        double s = Math.sin(correctionTurn);
        correctionX = pose.x() - (c * odoX - s * odoY);
        correctionY = pose.y() - (s * odoX + c * odoY);
    }

    private static double[] mean(List<double[]> points) {
        double x = 0.0;
        double y = 0.0;
        for (double[] point : points) {
            x += point[0];
            y += point[1];
        }
        return new double[] {x / points.size(), y / points.size()};
    }

    private static double spread(List<double[]> points) {
        double widest = 0.0;
        for (double[] a : points) {
            for (double[] b : points) {
                widest = Math.max(widest, Math.hypot(a[0] - b[0], a[1] - b[1]));
            }
        }
        return widest;
    }

    private static double normalize(double angle) {
        return Math.atan2(Math.sin(angle), Math.cos(angle));
    }
}
