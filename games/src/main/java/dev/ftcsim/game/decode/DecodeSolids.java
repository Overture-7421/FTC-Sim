package dev.ftcsim.game.decode;

import static dev.ftcsim.game.decode.Dimensions.PANEL_HEIGHT;
import static dev.ftcsim.game.decode.Dimensions.PANEL_THICKNESS;
import static dev.ftcsim.game.decode.Dimensions.WALL;

import dev.ftcsim.physics.Vector3;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleUnaryOperator;

/**
 * Everything solid on the DECODE field that never moves: floor, perimeter, the two goals with the surfaces that guide a
 * ball to the archway, and the walls and floor of the ramps. All of it is boxes, so what is drawn is what is collided
 * with. Blue is built first and red is its mirror image.
 */
final class DecodeSolids {

    static final int BLUE = 0x2D5FD3;
    static final int RED = 0xD33A2F;
    static final int GLASS = 0xBFD6E4;
    static final int FLOOR = 0x6B6F73;
    static final int STEEL = 0x9AA0A6;

    private static final double THIN = PANEL_THICKNESS;
    private static final double SLAB = 0.02;
    private static final int PLATE_STRIPS = 20;
    private static final int BACKBOARD_STEPS = 4;
    private static final int WALL_STEPS = 3;

    // The archway plate stands in the plane of the goal's open end, which is also where the rails stop being physical.
    private static final double ARCH_X = -1.2300;
    private static final double LINTEL_BOTTOM = 0.7810;
    // The goal's internal ramp (CAD part am-5721) is a plate in the shape of a right triangle: one leg along the end
    // wall, one along PLATE_EDGE_Y, the hypotenuse on the inside face of the front panel. It is tilted about the X axis
    // so that its top face z = PLATE_HEIGHT_AT_ZERO + tan(15 degrees) * y falls towards the side wall, and balls roll off
    // its low edge into the lane along that wall.
    private static final double PLATE_EDGE_Y = -1.6162;
    private static final double PLATE_HEIGHT_AT_ZERO = 1.2136;
    private static final double PLATE_END_X = -1.2741;
    // The lane is the top of the classifier's rails (CAD parts am-5725): two tubes of 1 in square section, turned a
    // quarter so that a ridge points up, 4.5 in apart, falling 15 degrees along +X from the end wall. A ball rests on the
    // two ridges and stays in the middle of the lane. The axis of a tube passes through (RAIL_AXIS_X, RAIL_AXIS_Z).
    private static final double[] RAIL_Y = {-1.6368, -1.7511};
    private static final double RAIL_HALF_SIDE = 0.0127;
    private static final double RAIL_AXIS_X = -1.4326;
    private static final double RAIL_AXIS_Z = 0.6736;
    private static final double RAIL_START_X = -1.7761;
    private static final double RAIL_END_X = -1.2;
    // Where the field-side wall of the ramp stands, and where the ramp's floor and the wall end before the gate arm
    // sweeps by.
    private static final double RAMP_WALL_INNER_Y = -1.6123;
    private static final double RAMP_WALL_OUTER_Y = -1.6023;
    private static final double RAMP_END_X = -0.01;

    private DecodeSolids() {}

    /** All solids, blue first and then red, ready to be added to the world in this order. */
    static List<Part> all() {
        List<Part> all = new ArrayList<>(perimeter());
        List<Part> blue = blueGoal();
        all.addAll(blue);
        for (Part part : blue) {
            Part red = part.mirrored(part.name().replace("blue", "red"));
            all.add(part.rgb() == BLUE ? red.recolored(RED) : red);
        }
        return all;
    }

    private static List<Part> perimeter() {
        double outer = WALL + Dimensions.WALL_THICKNESS;
        double h = Dimensions.WALL_HEIGHT;
        return List.of(
                Part.bounds("floor", -3, 3, -3, 3, -0.2, 0, FLOOR),
                Part.bounds("wall-goal-end", -outer, -WALL, -outer, outer, 0, h, GLASS),
                Part.bounds("wall-audience-end", WALL, outer, -outer, outer, 0, h, GLASS),
                Part.bounds("wall-minus-y", -WALL, WALL, -outer, -WALL, 0, h, GLASS),
                Part.bounds("wall-plus-y", -WALL, WALL, WALL, outer, 0, h, GLASS));
    }

    private static List<Part> blueGoal() {
        double inner = -WALL + THIN;
        List<Part> parts = new ArrayList<>();
        parts.add(Part.bounds("blue-goal-side-wall", -WALL, -1.1063, -WALL, inner, 0, PANEL_HEIGHT, BLUE));
        parts.add(Part.bounds("blue-goal-end-wall", -WALL, inner, inner, -1.0971, 0, PANEL_HEIGHT, BLUE));
        parts.add(new Part(
                "blue-goal-front-panel",
                Dimensions.frontPanelCenter(),
                new Vector3(0.5 * Dimensions.FRONT_PANEL_LENGTH, 0.5 * THIN, 0.5 * PANEL_HEIGHT),
                Orientation.yaw(Dimensions.FRONT_NORMAL_YAW - 0.5 * Math.PI),
                BLUE));
        parts.add(Part.bounds(
                "blue-goal-lintel", ARCH_X - 0.005, ARCH_X + 0.005, inner, -1.6114, LINTEL_BOTTOM, PANEL_HEIGHT, BLUE));
        addBackboard(parts, "blue-goal-side-backboard", -WALL, -1.1244, -WALL, inner, true);
        addBackboard(parts, "blue-goal-end-backboard", inner, -1.1152, -WALL, inner, false);
        addInternalRamp(parts);
        addRails(parts);
        parts.add(slab("blue-ramp-floor", ARCH_X + 0.0025, RAMP_END_X, inner, RAMP_WALL_INNER_Y, RampGeometry::rampTop));
        addRampWall(parts);
        parts.replaceAll(part -> part.madeOf(Material.PLASTIC));
        return parts;
    }

    // A stepped board above the goal wall; it is as tall as the goal's corner at its start and as the wall at its end.
    private static void addBackboard(
            List<Part> parts, String name, double from, double to, double wallMin, double wallMax, boolean alongX) {
        double length = (to - from) / BACKBOARD_STEPS;
        for (int i = 0; i < BACKBOARD_STEPS; i++) {
            double top = Dimensions.BACKBOARD_TOP - (Dimensions.BACKBOARD_TOP - PANEL_HEIGHT) * i / BACKBOARD_STEPS;
            double a = from + i * length;
            double b = a + length;
            parts.add(alongX
                    ? Part.bounds(name + "-" + i, a, b, wallMin, wallMax, PANEL_HEIGHT, top, BLUE)
                    : Part.bounds(name + "-" + i, wallMin, wallMax, a, b, PANEL_HEIGHT, top, BLUE));
        }
    }

    private static void addRails(List<Part> parts) {
        double middleX = 0.5 * (RAIL_START_X + RAIL_END_X);
        double middleZ = RAIL_AXIS_Z - RampGeometry.TAN * (middleX - RAIL_AXIS_X);
        Orientation along = Orientation.aboutY(RampGeometry.SLOPE).times(Orientation.aboutAxis(Vector3.X, 0.25 * Math.PI));
        for (int i = 0; i < RAIL_Y.length; i++) {
            parts.add(new Part(
                    "blue-goal-rail-" + i,
                    new Vector3(middleX, RAIL_Y[i], middleZ),
                    new Vector3(0.5 * (RAIL_END_X - RAIL_START_X) / RampGeometry.COS, RAIL_HALF_SIDE, RAIL_HALF_SIDE),
                    along,
                    STEEL));
        }
    }

    // The plate in thin strips along x, each as long as the plate is wide there; the stair steps along the front panel
    // stay inside the panel's thickness.
    private static void addInternalRamp(List<Part> parts) {
        double inner = -WALL + THIN;
        double width = (PLATE_END_X - inner) / PLATE_STRIPS;
        Vector3 tangent = Dimensions.frontTangent();
        Vector3 normal = Dimensions.frontNormal();
        double faceX = Dimensions.FRONT_PANEL_X - normal.x() * 0.5 * THIN;
        double faceY = Dimensions.FRONT_PANEL_Y - normal.y() * 0.5 * THIN;
        for (int i = 0; i < PLATE_STRIPS; i++) {
            double a = inner + i * width;
            double b = a + width;
            double top = faceY + (b - faceX) * tangent.y() / tangent.x();
            parts.add(plateStrip("blue-goal-plate-" + i, a, b, PLATE_EDGE_Y, top));
        }
    }

    // A thin plate whose top face lies in the plane of the goal's internal ramp and covers x from a to b, y from y0 to y1.
    private static Part plateStrip(String name, double a, double b, double y0, double y1) {
        double middleY = 0.5 * (y0 + y1);
        Vector3 up = new Vector3(0, -RampGeometry.SIN, RampGeometry.COS);
        Vector3 top = new Vector3(0.5 * (a + b), middleY, PLATE_HEIGHT_AT_ZERO + RampGeometry.TAN * middleY);
        return new Part(
                name,
                top.minus(up.times(0.5 * SLAB)),
                new Vector3(0.5 * (b - a), 0.5 * (y1 - y0) / RampGeometry.COS, 0.5 * SLAB),
                Orientation.aboutAxis(Vector3.X, RampGeometry.SLOPE),
                BLUE);
    }

    // The wall between the ramp and the field, as high as the panel it models at the middle of each step.
    private static void addRampWall(List<Part> parts) {
        double[][] runs = {{-1.2221, -0.6314, 0.5046, 0.3856}, {-0.6314, RAMP_END_X, 0.3856, 0.2874}};
        for (int run = 0; run < runs.length; run++) {
            double length = (runs[run][1] - runs[run][0]) / WALL_STEPS;
            for (int i = 0; i < WALL_STEPS; i++) {
                double a = runs[run][0] + i * length;
                double middle = (i + 0.5) / WALL_STEPS;
                double top = runs[run][2] + (runs[run][3] - runs[run][2]) * middle;
                parts.add(Part.bounds(
                        "blue-ramp-wall-" + run + "-" + i, a, a + length, RAMP_WALL_INNER_Y, RAMP_WALL_OUTER_Y, 0, top, STEEL));
            }
        }
    }

    // A thin plate along the 15 degree slope whose top surface lies in the given surface and covers x from a to b.
    private static Part slab(String name, double a, double b, double y0, double y1, DoubleUnaryOperator surface) {
        double middle = 0.5 * (a + b);
        Vector3 up = new Vector3(RampGeometry.SIN, 0, RampGeometry.COS);
        Vector3 top = new Vector3(middle, 0.5 * (y0 + y1), surface.applyAsDouble(middle));
        return new Part(
                name,
                top.minus(up.times(0.5 * SLAB)),
                new Vector3(0.5 * (b - a) / RampGeometry.COS, 0.5 * (y1 - y0), 0.5 * SLAB),
                Orientation.aboutY(RampGeometry.SLOPE),
                STEEL);
    }
}
