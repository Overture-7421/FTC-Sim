package dev.ftcsim.scenarios.opmodes;

import java.util.Locale;

/**
 * Works out how to shoot into the CELL of an alliance's HIVE that faces up: where the turret must point, how steeply (the hood),
 * how fast (the flywheels) and whether the ball will go in at all.
 *
 * <p>A CELL is a small house open only at the far end of the HIVE's arm, and a ball must come down into it, so the shot is a lob:
 * from 55 degrees up when the robot is far to 85 degrees when it is close. For each launch angle the hood can make, the speed that
 * puts the ball through a point in the CELL's mouth is worked out, and the ball is flown against the CELL's floor, back, sides and
 * roof (the ball's radius counts) to see that it goes in. A shot counts only when it still goes in with the turret a little off
 * ({@link #HEADING_ERROR_DEGREES}), the flywheels a little fast or slow ({@link #SPEED_ERROR}) and the hood a little off
 * ({@link #ELEVATION_ERROR_DEGREES}). Of the angles that pass, the one in the middle of the longest run of them is chosen.
 *
 * <p>The launch numbers are tuning for the robot's shooter (the efficiency of its wheels, the hood's angles), measured once; a team
 * replaces them and the formulas with a table of its own. Nothing here knows about the simulator.
 *
 * <p>Written for Java 8, like the other files a robot project takes.
 */
public final class BiobuzzShotSolver {
    /** Why a shot is not possible from where the robot is. */
    public enum Reason {
        NONE("ok"),
        WRONG_SIDE("the CELL that faces up opens on the other side of the HIVE: drive round to it"),
        TOO_CLOSE("too close to the CELL: back away"),
        OUT_OF_RANGE("too far for the flywheels: drive closer"),
        NO_ARC("no throw gets in from here: move a little to the side or nearer");

        private final String text;

        Reason(String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }
    }

    /** Where to aim and how hard to shoot. */
    public static final class Solution {
        public final boolean feasible;
        public final Reason reason;
        /** Degrees left of the robot's front. */
        public final double bearingDegrees;
        public final double distanceMeters;
        public final double elevationDegrees;
        /** The hood servo's position for that elevation. */
        public final double hoodPosition;
        public final double flywheelTicksPerSecond;
        /** How many degrees wide the run of working launch angles around this one is. */
        public final double marginDegrees;
        public final double targetX;
        public final double targetY;
        public final double targetZ;

        Solution(boolean feasible, Reason reason, double bearing, double distance, double elevation, double hood, double ticks,
                double margin, double[] target) {
            this.feasible = feasible;
            this.reason = reason;
            this.bearingDegrees = bearing;
            this.distanceMeters = distance;
            this.elevationDegrees = elevation;
            this.hoodPosition = hood;
            this.flywheelTicksPerSecond = ticks;
            this.marginDegrees = margin;
            this.targetX = target[0];
            this.targetY = target[1];
            this.targetZ = target[2];
        }

        /** A line for telemetry. */
        public String describe() {
            if (!feasible) {
                return String.format(Locale.ROOT, "no shot: %s (bearing %.1f, distance %.2f m)", reason.text(), bearingDegrees, distanceMeters);
            }
            return String.format(Locale.ROOT, "bearing %.1f distance %.2f m elevation %.0f hood %.2f flywheels %.0f margin +-%.0f deg",
                    bearingDegrees, distanceMeters, elevationDegrees, hoodPosition, flywheelTicksPerSecond, marginDegrees / 2.0);
        }
    }

    /**
     * Where a ball leaves, from the CAD's shooter: it rides round the two wheels, whose axle is {@code WHEEL_AXLE_X} ahead of the
     * robot's centre and {@code WHEEL_AXLE_HEIGHT_M} above the floor, and is let go where the hood's end is, on the circle of
     * {@code EXIT_RADIUS_M} (the wheel's radius and the ball's) about the axle at the launch angle: low and forward for a flat
     * launch, back over the axle for a steep one. See {@link #exitAhead} and {@link #exitHeight}.
     */
    static final double TURRET_AXIS_M = BiobuzzPoseEstimator.TURRET_AXIS_X;
    static final double WHEEL_AXLE_X = 0.162;
    static final double WHEEL_AXLE_HEIGHT_M = 0.1824 + 0.2146;
    static final double EXIT_RADIUS_M = 0.0737;
    /** The shooter: the share of the wheels' rim speed a ball leaves with, a 3 in wheel behind a 43:30 belt, a 28 count encoder. */
    static final double EFFICIENCY = 0.49;
    static final double WHEEL_RADIUS_M = 0.0381;
    static final double BELT_RATIO = 43.0 / 30.0;
    static final double TICKS_PER_TURN = 28.0;
    /** The flywheels' speed at full power, measured on the robot (the viscous load of the wheels keeps it well under the motor's 2800). */
    static final double MOTOR_MAX_TICKS_PER_SECOND = 2130.0;
    /** The shot may ask for this share of the top speed, which leaves the flywheels room to recover between balls. */
    static final double MAX_SHARE_OF_TOP_SPEED = 0.95;
    /**
     * The launch angle is {@code BASE} degrees with the hood servo at 0 and rises with the servo: the servo has five turns (1800
     * degrees) behind 730:24 gears, so a whole turn of it is 59.2 degrees of hood, and the hood's stop holds it at 44 degrees, which is
     * position 0.74. The code keeps its commands under that.
     */
    static final double ELEVATION_BASE_DEGREES = 45.0;
    /** The lob the idle flywheel speed is worked out for: the middle of the steep lobs that work. */
    static final double NOMINAL_ELEVATION_DEGREES = 65.0;
    static final double HOOD_DEGREES_PER_POSITION = 1800.0 / (730.0 / 24.0);
    static final double HOOD_RANGE_DEGREES = 44.0;
    public static final double HOOD_MAX_POSITION = HOOD_RANGE_DEGREES / HOOD_DEGREES_PER_POSITION;

    /** How far off the turret, the flywheels and the hood may be, and the shot still has to go in. */
    static final double HEADING_ERROR_DEGREES = 1.5;
    static final double SPEED_ERROR = 0.03;
    static final double ELEVATION_ERROR_DEGREES = 0.7;
    /** A new launch angle replaces the old one only if the old one has left the working range or is this far from the best. */
    static final double KEEP_ELEVATION_DEGREES = 5.0;
    /** The ball is a POLLEN: 1.40 in in radius. */
    private static final double BALL_RADIUS_M = 1.40 * BiobuzzHive.INCH;
    private static final double GRAVITY = 9.81;
    private static final double STEP_SECONDS = 0.004;
    private static final int MAX_STEPS = 600;
    /** Nearer than this to the foot of the CELL along the arm, the robot is on the wrong side of the HIVE. */
    private static final double WRONG_SIDE_M = 0.35;
    private static final double TOO_CLOSE_M = 0.9;

    private BiobuzzShotSolver() {}

    /**
     * Where to aim and how hard to shoot from {@code pose} at the CELL of {@code alliance}'s HIVE that faces up.
     *
     * @param previousHood the hood position in use, or NaN: it is kept while it still works, so that the servo, which is slow, is not
     *                     chasing small changes of the estimate
     */
    public static Solution solve(
            BiobuzzPoseEstimator.Pose pose, BiobuzzHive.Alliance alliance, BiobuzzHive.Tilt tilt, double previousHood) {
        BiobuzzHive.Cell cell = BiobuzzHive.upCell(alliance, tilt);
        double[] aim = cell.aimPoint();
        double axisX = pose.x() + Math.cos(pose.heading()) * TURRET_AXIS_M;
        double axisY = pose.y() + Math.sin(pose.heading()) * TURRET_AXIS_M;
        double absolute = Math.atan2(aim[1] - axisY, aim[0] - axisX);
        double bearing = Math.toDegrees(normalize(absolute - pose.heading()));
        double distance = Math.hypot(aim[0] - axisX, aim[1] - axisY);

        int low = (int) ELEVATION_BASE_DEGREES;
        int high = (int) (ELEVATION_BASE_DEGREES + HOOD_RANGE_DEGREES);
        boolean[] works = new boolean[high + 1];
        boolean anySpeed = false;
        for (int degrees = low; degrees <= high; degrees++) {
            double theta = Math.toRadians(degrees);
            double muzzleX = axisX + exitAhead(theta) * Math.cos(absolute);
            double muzzleY = axisY + exitAhead(theta) * Math.sin(absolute);
            double speed = speedFor(Math.hypot(aim[0] - muzzleX, aim[1] - muzzleY), aim[2] - exitHeight(theta), theta);
            if (Double.isNaN(speed) || !withinFlywheels(speed * (1.0 + SPEED_ERROR))) {
                continue;
            }
            anySpeed = true;
            works[degrees] = goesInAlways(cell, muzzleX, muzzleY, exitHeight(theta), absolute, speed, theta);
        }
        int bestStart = -1;
        int bestLength = 0;
        for (int degrees = low; degrees <= high; degrees++) {
            if (!works[degrees]) {
                continue;
            }
            int end = degrees;
            while (end + 1 <= high && works[end + 1]) {
                end++;
            }
            if (end - degrees + 1 > bestLength) {
                bestLength = end - degrees + 1;
                bestStart = degrees;
            }
            degrees = end;
        }
        if (bestLength == 0) {
            double robotAlong = cell.toCell(axisX, axisY, BiobuzzHive.PIVOT_Z)[0];
            Reason reason = robotAlong < WRONG_SIDE_M ? Reason.WRONG_SIDE
                    : !anySpeed ? Reason.OUT_OF_RANGE : distance < TOO_CLOSE_M ? Reason.TOO_CLOSE : Reason.NO_ARC;
            return new Solution(false, reason, bearing, distance, Double.NaN, Double.NaN, 0.0, 0.0, aim);
        }
        double elevation = bestStart + 0.5 * (bestLength - 1);
        if (!Double.isNaN(previousHood)) {
            double previous = ELEVATION_BASE_DEGREES + previousHood * HOOD_DEGREES_PER_POSITION;
            int below = (int) Math.floor(previous);
            int above = (int) Math.ceil(previous);
            boolean stillWorks = below >= low && above <= high && works[below] && works[above]
                    && Math.abs(previous - elevation) <= KEEP_ELEVATION_DEGREES;
            if (stillWorks) {
                elevation = previous;
            }
        }
        double theta = Math.toRadians(elevation);
        double finalX = axisX + exitAhead(theta) * Math.cos(absolute);
        double finalY = axisY + exitAhead(theta) * Math.sin(absolute);
        double speed = speedFor(Math.hypot(aim[0] - finalX, aim[1] - finalY), aim[2] - exitHeight(theta), theta);
        double hood = (elevation - ELEVATION_BASE_DEGREES) / HOOD_DEGREES_PER_POSITION;
        return new Solution(true, Reason.NONE, bearing, distance, elevation, Math.max(0.0, Math.min(HOOD_MAX_POSITION, hood)),
                ticksFor(speed), bestLength, aim);
    }

    /**
     * A flywheel speed to idle at when there is no shot to be had from {@code pose} (the wrong side of the HIVE, too near, or the pose
     * only the start): the speed for a lob of {@link #NOMINAL_ELEVATION_DEGREES} to the CELL's mouth from here, within what the
     * flywheels give. It is close to what a shot from nearby needs, so the flywheels never have far to go. NaN if there is none.
     */
    public static double nominalTicks(BiobuzzPoseEstimator.Pose pose, BiobuzzHive.Alliance alliance, BiobuzzHive.Tilt tilt) {
        double[] aim = BiobuzzHive.upCell(alliance, tilt).aimPoint();
        double axisX = pose.x() + Math.cos(pose.heading()) * TURRET_AXIS_M;
        double axisY = pose.y() + Math.sin(pose.heading()) * TURRET_AXIS_M;
        double theta = Math.toRadians(NOMINAL_ELEVATION_DEGREES);
        double speed = speedFor(Math.hypot(aim[0] - axisX, aim[1] - axisY), aim[2] - exitHeight(theta), theta);
        if (Double.isNaN(speed)) {
            return Double.NaN;
        }
        return Math.min(ticksFor(speed), MAX_SHARE_OF_TOP_SPEED * MOTOR_MAX_TICKS_PER_SECOND);
    }

    /** Degrees left of the robot's front that the turret must point to put the shot into the up CELL, from {@code pose}. */
    public static double bearingDegrees(BiobuzzPoseEstimator.Pose pose, BiobuzzHive.Alliance alliance, BiobuzzHive.Tilt tilt) {
        double[] aim = BiobuzzHive.upCell(alliance, tilt).aimPoint();
        double axisX = pose.x() + Math.cos(pose.heading()) * TURRET_AXIS_M;
        double axisY = pose.y() + Math.sin(pose.heading()) * TURRET_AXIS_M;
        return Math.toDegrees(normalize(Math.atan2(aim[1] - axisY, aim[0] - axisX) - pose.heading()));
    }

    /** How far ahead of the turret's axis a ball leaves, launched {@code theta} above the horizontal. */
    static double exitAhead(double theta) {
        return WHEEL_AXLE_X - EXIT_RADIUS_M * Math.sin(theta) - TURRET_AXIS_M;
    }

    /** How high above the floor a ball leaves, launched {@code theta} above the horizontal. */
    static double exitHeight(double theta) {
        return WHEEL_AXLE_HEIGHT_M + EXIT_RADIUS_M * Math.cos(theta);
    }

    /** The ball speed that takes a ball from the opening to a point {@code d} away horizontally and {@code rise} higher; NaN if none. */
    static double speedFor(double d, double rise, double theta) {
        double room = d * Math.tan(theta) - rise;
        if (d <= 0.0 || room <= 0.05) {
            return Double.NaN;
        }
        return Math.sqrt(GRAVITY * d * d / (2.0 * Math.cos(theta) * Math.cos(theta) * room));
    }

    /** The flywheels' speed in ticks a second that gives a ball {@code speed}. */
    static double ticksFor(double speed) {
        double rim = speed / EFFICIENCY;
        double motorRadPerSecond = rim * BELT_RATIO / WHEEL_RADIUS_M;
        return motorRadPerSecond * TICKS_PER_TURN / (2.0 * Math.PI);
    }

    /** The speed a ball leaves with when the flywheels turn at {@code ticks} a second. */
    static double speedOf(double ticks) {
        return EFFICIENCY * ticks * (2.0 * Math.PI / TICKS_PER_TURN) / BELT_RATIO * WHEEL_RADIUS_M;
    }

    private static boolean withinFlywheels(double speed) {
        return ticksFor(speed) <= MAX_SHARE_OF_TOP_SPEED * MOTOR_MAX_TICKS_PER_SECOND;
    }

    private static boolean goesInAlways(
            BiobuzzHive.Cell cell, double x, double y, double z0, double heading, double speed, double theta) {
        double headingError = Math.toRadians(HEADING_ERROR_DEGREES);
        double elevationError = Math.toRadians(ELEVATION_ERROR_DEGREES);
        if (!goesIn(cell, x, y, z0, heading, speed, theta)) {
            return false;
        }
        for (int h = -1; h <= 1; h += 2) {
            for (int s = -1; s <= 1; s += 2) {
                for (int e = -1; e <= 1; e += 2) {
                    if (!goesIn(cell, x, y, z0, heading + h * headingError, speed * (1.0 + s * SPEED_ERROR), theta + e * elevationError)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** Whether a ball let go at {@code (x0, y0, z0)}, with no spin and no air drag, gets into the CELL before it touches any part of it. */
    static boolean goesIn(BiobuzzHive.Cell cell, double x0, double y0, double z0, double heading, double speed, double theta) {
        double r = BALL_RADIUS_M;
        double vx = speed * Math.cos(theta) * Math.cos(heading);
        double vy = speed * Math.cos(theta) * Math.sin(heading);
        double vz = speed * Math.sin(theta);
        double slope = Math.atan2(BiobuzzHive.CELL_CEILING - BiobuzzHive.CELL_EAVE, BiobuzzHive.CELL_HALF_WIDTH);
        double roofSkin = r / Math.cos(slope);
        double wall = BiobuzzHive.CELL_WALL;
        for (int i = 1; i <= MAX_STEPS; i++) {
            double t = i * STEP_SECONDS;
            double z = z0 + vz * t - 0.5 * GRAVITY * t * t;
            if (z < 0.7 && vz - GRAVITY * t < 0.0) {
                return false;
            }
            double[] p = cell.toCell(x0 + vx * t, y0 + vy * t, z);
            double d = p[0];
            double lateral = Math.abs(p[1]);
            double n = p[2];
            double top = BiobuzzHive.Cell.roof(p[1]);
            if (d > BiobuzzHive.CELL_BACK + r && d < BiobuzzHive.CELL_FRONT - r && lateral < BiobuzzHive.CELL_HALF_WIDTH - r
                    && n > BiobuzzHive.CELL_FLOOR + r && n < top - roofSkin) {
                return true;
            }
            boolean width = lateral < BiobuzzHive.CELL_HALF_WIDTH + wall + r;
            boolean floor = d > BiobuzzHive.CELL_BACK - wall - r && d < BiobuzzHive.CELL_FRONT + r && width
                    && n > BiobuzzHive.CELL_FLOOR - wall - r && n < BiobuzzHive.CELL_FLOOR + r;
            boolean back = d > BiobuzzHive.CELL_BACK - wall - r && d < BiobuzzHive.CELL_BACK + r && width
                    && n > BiobuzzHive.CELL_FLOOR - wall - r && n < top + wall + r;
            boolean side = d > BiobuzzHive.CELL_BACK - r && d < BiobuzzHive.CELL_FRONT + r
                    && lateral > BiobuzzHive.CELL_HALF_WIDTH - r && lateral < BiobuzzHive.CELL_HALF_WIDTH + wall + r
                    && n > BiobuzzHive.CELL_FLOOR - r && n < BiobuzzHive.CELL_EAVE + r;
            boolean roof = d > BiobuzzHive.CELL_BACK - wall - r && d < BiobuzzHive.CELL_FRONT + r && width
                    && n > top - roofSkin && n < top + wall + r;
            if (floor || back || side || roof) {
                return false;
            }
        }
        return false;
    }

    private static double normalize(double angle) {
        return Math.atan2(Math.sin(angle), Math.cos(angle));
    }
}
