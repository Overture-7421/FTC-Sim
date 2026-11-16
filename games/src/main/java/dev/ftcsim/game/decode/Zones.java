package dev.ftcsim.game.decode;

import dev.ftcsim.contracts.field.Alliance;
import java.util.List;

/** The tape and zones of the field that the scoring looks at, and how a robot or a ball sits on them. */
final class Zones {

    /** The robot, or a strip of tape, as a rectangle on the floor. */
    record Footprint(double x, double y, double yaw, double halfLength, double halfWidth) {

        double localX(double px, double py) {
            return (px - x) * StrictMath.cos(yaw) + (py - y) * StrictMath.sin(yaw);
        }

        double localY(double px, double py) {
            return -(px - x) * StrictMath.sin(yaw) + (py - y) * StrictMath.cos(yaw);
        }

        /** The four corners as {x, y}. */
        double[][] corners() {
            double cos = StrictMath.cos(yaw);
            double sin = StrictMath.sin(yaw);
            double[][] corners = new double[4][];
            int i = 0;
            for (int along : new int[] {-1, 1}) {
                for (int across : new int[] {-1, 1}) {
                    double lx = along * halfLength;
                    double ly = across * halfWidth;
                    corners[i++] = new double[] {x + lx * cos - ly * sin, y + lx * sin + ly * cos};
                }
            }
            return corners;
        }
    }

    // One inch of tape: a robot is over a line when it covers any of the tape, not only its centre line.
    private static final double TAPE_HALF_WIDTH = 0.0127;
    // The tile in the base zone is bounded by the outside edge of its tape; a robot at the limit of 18 in needs a little room.
    private static final double BASE_ROOM = 0.0254;
    private static final double BASE_MIN_X = 0.7295;
    private static final double BASE_MAX_X = 1.1867;
    private static final double DEPOT_HALF_WIDTH = 0.0129;
    static final double DEPOT_HALF_LENGTH = 0.3809;
    static final double DEPOT_X = -1.4938;
    static final double DEPOT_Y = -1.3924;

    // The back launch line runs from each goal-side corner to the middle of the field, the front one from the audience
    // wall to a point in front of the middle; the depots count as launch lines too.
    private static final List<double[]> LAUNCH_LINES = launchLines();

    private Zones() {}

    private static List<double[]> launchLines() {
        double w = Dimensions.WALL;
        double tx = Dimensions.frontTangent().x() * DEPOT_HALF_LENGTH;
        double ty = Dimensions.frontTangent().y() * DEPOT_HALF_LENGTH;
        return List.of(
                new double[] {-w, -w, 0, 0},
                new double[] {-w, w, 0, 0},
                new double[] {1.1957, 0, 1.7867, -0.59},
                new double[] {1.1957, 0, 1.7867, 0.59},
                new double[] {DEPOT_X - tx, DEPOT_Y - ty, DEPOT_X + tx, DEPOT_Y + ty},
                new double[] {DEPOT_X - tx, -(DEPOT_Y - ty), DEPOT_X + tx, -(DEPOT_Y + ty)});
    }

    /** Whether any part of the robot is over a launch line. */
    static boolean isOverLaunchLine(Footprint robot) {
        for (double[] line : LAUNCH_LINES) {
            if (segmentTouches(line, robot)) {
                return true;
            }
        }
        return false;
    }

    // Clips the segment to the robot's rectangle, grown by half the tape's width.
    private static boolean segmentTouches(double[] line, Footprint box) {
        double ax = box.localX(line[0], line[1]);
        double ay = box.localY(line[0], line[1]);
        double dx = box.localX(line[2], line[3]) - ax;
        double dy = box.localY(line[2], line[3]) - ay;
        double hx = box.halfLength() + TAPE_HALF_WIDTH;
        double hy = box.halfWidth() + TAPE_HALF_WIDTH;
        double[] p = {-dx, dx, -dy, dy};
        double[] q = {ax + hx, hx - ax, ay + hy, hy - ay};
        double first = 0;
        double last = 1;
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0) {
                if (q[i] < 0) {
                    return false;
                }
            } else if (p[i] < 0) {
                first = Math.max(first, q[i] / p[i]);
            } else {
                last = Math.min(last, q[i] / p[i]);
            }
        }
        return first <= last;
    }

    /** 0 when the robot is not on its alliance's base, 1 when it is partly on it and 2 when it is fully on it. */
    static int baseStatus(Footprint robot, Alliance alliance) {
        double minY = alliance == Alliance.BLUE ? 0.6104 : -1.0660;
        double maxY = alliance == Alliance.BLUE ? 1.0676 : -0.6088;
        boolean inside = true;
        for (double[] corner : robot.corners()) {
            inside &= corner[0] >= BASE_MIN_X - BASE_ROOM && corner[0] <= BASE_MAX_X + BASE_ROOM
                    && corner[1] >= minY - BASE_ROOM && corner[1] <= maxY + BASE_ROOM;
        }
        if (inside) {
            return 2;
        }
        return overlaps(robot, BASE_MIN_X, BASE_MAX_X, minY, maxY) ? 1 : 0;
    }

    // Separating axis test between the robot and an axis-aligned rectangle: the four edge directions are all there is.
    private static boolean overlaps(Footprint robot, double minX, double maxX, double minY, double maxY) {
        double[][] zone = {{minX, minY}, {minX, maxY}, {maxX, minY}, {maxX, maxY}};
        double cos = StrictMath.cos(robot.yaw());
        double sin = StrictMath.sin(robot.yaw());
        double[][] axes = {{1, 0}, {0, 1}, {cos, sin}, {-sin, cos}};
        for (double[] axis : axes) {
            double[] robotRange = project(robot.corners(), axis);
            double[] zoneRange = project(zone, axis);
            if (robotRange[1] < zoneRange[0] || zoneRange[1] < robotRange[0]) {
                return false;
            }
        }
        return true;
    }

    private static double[] project(double[][] points, double[] axis) {
        double low = Double.MAX_VALUE;
        double high = -Double.MAX_VALUE;
        for (double[] point : points) {
            double along = point[0] * axis[0] + point[1] * axis[1];
            low = Math.min(low, along);
            high = Math.max(high, along);
        }
        return new double[] {low, high};
    }

    /** Whether a ball resting at (x, y) with the given radius is over the depot of the given alliance. */
    static boolean isOverDepot(double x, double y, double radius, Alliance alliance) {
        double blueY = alliance == Alliance.BLUE ? y : -y;
        Footprint tape = new Footprint(
                DEPOT_X, DEPOT_Y, Dimensions.FRONT_NORMAL_YAW - 0.5 * Math.PI, DEPOT_HALF_LENGTH, DEPOT_HALF_WIDTH);
        double beyondEnds = Math.max(Math.abs(tape.localX(x, blueY)) - tape.halfLength(), 0);
        double beyondSides = Math.max(Math.abs(tape.localY(x, blueY)) - tape.halfWidth(), 0);
        return beyondEnds * beyondEnds + beyondSides * beyondSides <= radius * radius;
    }
}
