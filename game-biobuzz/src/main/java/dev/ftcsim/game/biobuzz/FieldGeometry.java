package dev.ftcsim.game.biobuzz;

import static dev.ftcsim.game.biobuzz.Solids.in;

import dev.ftcsim.contracts.field.Pose3;
import dev.ftcsim.contracts.field.Shape;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything that never moves: the floor, the perimeter, the FLOWERS, the frame and the tape. Field frame: origin at
 * the middle of the field on the tiles, +X towards the audience, +Y towards the blue alliance wall, +Z up; so the
 * red alliance wall is at negative Y, as the red ALLIANCE AREA is on the left from the audience.
 *
 * <p>The inside of the perimeter is the CAD's 141.04 in, not the nominal 144 in of the manual: the tiles are
 * trimmed to fit and the tape lines, the FLOWERS and the staged pieces are all dimensioned in the CAD frame.
 */
final class FieldGeometry {

    /** Distance from the middle of the field to the inside face of each wall. */
    static final double HALF_M = in(70.52);
    static final double WALL_THICKNESS_M = in(1.13);
    static final double WALL_HEIGHT_M = in(11.83);

    private static final int TILE_RGB = 0x5B5F66;
    private static final int WALL_RGB = 0xCFD8DC;
    static final int RED_RGB = 0xE53935;
    static final int BLUE_RGB = 0x1E88E5;

    private FieldGeometry() {}

    /** An axis-aligned region of the floor in the field frame, in metres. */
    record Zone(double xMin, double xMax, double yMin, double yMax) {
        boolean overlapsCircle(double x, double y, double radius) {
            double dx = Math.max(Math.max(xMin - x, 0), x - xMax);
            double dy = Math.max(Math.max(yMin - y, 0), y - yMax);
            return dx * dx + dy * dy <= radius * radius;
        }

        boolean containsCenter(double x, double y) {
            return x >= xMin && x <= xMax && y >= yMin && y <= yMax;
        }
    }

    /** The red LOADING ZONE is against the red wall at negative Y, the blue one against the blue wall. */
    static final Zone RED_LOADING_ZONE = new Zone(in(-46.60), in(-23.91), -HALF_M, in(-59.10));
    static final Zone BLUE_LOADING_ZONE = new Zone(in(23.91), in(46.60), in(59.10), HALF_M);
    /** The red GARDEN lies along the audience wall in the red corner, the blue one along the far wall in the blue corner. */
    static final Zone RED_GARDEN = new Zone(in(68.10), in(70.10), in(-70.10), in(-47.41));
    static final Zone BLUE_GARDEN = new Zone(in(-70.10), in(-68.10), in(47.41), in(70.10));

    /** Solids that collide and are made one body in the physics world, with how they grip and bounce a piece. */
    record Group(List<Solid> solids, float friction, float restitution) {}

    /** Groups of solids that collide, each made one body in the physics world. */
    static List<Group> colliderGroups() {
        List<Group> groups = new ArrayList<>();
        groups.add(new Group(
                List.of(new Solid("floor", new Shape.Box(HALF_M + 0.5, HALF_M + 0.5, 0.25), Pose3.at(0, 0, -0.25), TILE_RGB, false, true)),
                0.8f, 0.0f));
        groups.add(new Group(walls(), 0.4f, 0.0f));
        for (int i = 0; i < FlowerModel.SITES.size(); i++) {
            groups.add(new Group(FlowerModel.solids(i + 1, FlowerModel.SITES.get(i)), 0.35f, 0.0f));
        }
        groups.add(new Group(FrameModel.solids(), 0.35f, 0.0f));
        return groups;
    }

    private static List<Solid> walls() {
        double centre = HALF_M + WALL_THICKNESS_M / 2;
        double length = HALF_M + WALL_THICKNESS_M;
        List<Solid> walls = new ArrayList<>();
        for (int sign : new int[] {-1, 1}) {
            String side = sign > 0 ? "+" : "-";
            walls.add(new Solid(
                    "perimeter wall " + side + "X", new Shape.Box(WALL_THICKNESS_M / 2, length, WALL_HEIGHT_M / 2),
                    Pose3.at(sign * centre, 0, WALL_HEIGHT_M / 2), WALL_RGB, true, true));
            walls.add(new Solid(
                    "perimeter wall " + side + "Y", new Shape.Box(HALF_M, WALL_THICKNESS_M / 2, WALL_HEIGHT_M / 2),
                    Pose3.at(0, sign * centre, WALL_HEIGHT_M / 2), WALL_RGB, true, true));
        }
        return walls;
    }

    /** What is drawn but has no thickness worth colliding with: the tiles and the tape. */
    static List<Solid> markings() {
        List<Solid> marks = new ArrayList<>();
        marks.add(new Solid("tiles", new Shape.Box(HALF_M, HALF_M, 0.0075), Pose3.at(0, 0, -0.0075), TILE_RGB, true, false));
        loadingZone(marks, "red", -1, RED_RGB);
        loadingZone(marks, "blue", 1, BLUE_RGB);
        garden(marks, "red", 68.10, -70.10, -47.41, RED_RGB);
        garden(marks, "blue", -68.10, 47.41, 70.10, BLUE_RGB);
        return marks;
    }

    /** Two 11 in sides and the 20.69 in line that joins them, 1 in tape, outside edges as in the CAD. */
    private static void loadingZone(List<Solid> marks, String colour, int side, int rgb) {
        double inner = 59.10;
        double outerEdge = 70.10;
        double x0 = side > 0 ? 23.91 : -46.60;
        double x1 = x0 + 22.69;
        marks.add(tape("LOADING ZONE " + colour + " side 1", x0 + 0.5, side * (inner + outerEdge) / 2, 1.0, outerEdge - inner, rgb));
        marks.add(tape("LOADING ZONE " + colour + " side 2", x1 - 0.5, side * (inner + outerEdge) / 2, 1.0, outerEdge - inner, rgb));
        marks.add(tape("LOADING ZONE " + colour + " front", (x0 + x1) / 2, side * (inner + 0.5), x1 - x0 - 2.0, 1.0, rgb));
    }

    /** The 2 in wide GARDEN tape, as two 1 in strips along the wall. */
    private static void garden(List<Solid> marks, String colour, double xEdge, double yFrom, double yTo, int rgb) {
        double sign = xEdge > 0 ? 1 : -1;
        double yMid = (yFrom + yTo) / 2;
        double length = Math.abs(yTo - yFrom);
        marks.add(tape("GARDEN " + colour + " inner strip", sign * (Math.abs(xEdge) + 0.5), yMid, 1.0, length, rgb));
        marks.add(tape("GARDEN " + colour + " outer strip", sign * (Math.abs(xEdge) + 1.5), yMid, 1.0, length, rgb));
    }

    private static Solid tape(String name, double cx, double cy, double sizeX, double sizeY, int rgb) {
        return new Solid(
                name, new Shape.Box(in(sizeX) / 2, in(sizeY) / 2, 0.0003), Pose3.at(in(cx), in(cy), 0.0003), rgb, true, false);
    }
}
