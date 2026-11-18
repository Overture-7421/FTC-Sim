package dev.ftcsim.game.biobuzz;

import static dev.ftcsim.game.biobuzz.Solids.in;

import dev.ftcsim.contracts.field.FieldPart;
import dev.ftcsim.contracts.field.FieldTag;
import dev.ftcsim.contracts.field.Pose3;
import dev.ftcsim.contracts.field.Shape;
import dev.ftcsim.physics.PivotSpec;
import dev.ftcsim.physics.ShapePart;
import dev.ftcsim.physics.Vector3;
import java.util.ArrayList;
import java.util.List;

/**
 * The geometry, mass and tags of one HIVE: two CELLS on the ends of an arm that tips about a horizontal pivot on the
 * frame. Dimensions come from the official CAD ({@code docs/fields/biobuzz.md} lists them); the mass, inertia,
 * bearing friction and damper are estimates, calibrated against the acceptance checklist.
 *
 * <p>The HIVE body has its own frame, whose origin is the middle of the pivot axis. At angle zero the arm is
 * horizontal, +X points to the audience, +Y runs along the pivot axis and +Z is up. A rotation about +Y by a
 * positive angle lowers the audience end, so the audience CELL faces up at the negative stop.
 */
final class HiveModel {

    /** Height of the pivot axis above the tiles. */
    static final double PIVOT_HEIGHT_M = in(43.95);
    /** Distance of each pivot from the centre line of the field; the red HIVE is on the negative side. */
    static final double PIVOT_OFFSET_M = in(12.74);

    /** Angle of the arm at either stop: about 60 degrees of travel between the two stable states. */
    static final double STOP_RADIANS = Math.toRadians(30.0);
    /** From this far from a stop the damper takes the speed out of the arm; reaching it completes a TIP. */
    static final double DAMPER_ZONE_RADIANS = Math.toRadians(6.0);

    // A CELL in "beam" inches: d along the arm from the pivot, n across it (up at angle zero), x along the pivot.
    static final double BACK = 9.44;
    static final double FRONT = 21.46;
    static final double FLOOR = -1.46;
    /** The height of the ridge: a CELL is a house, with upright sides up to {@link #EAVE} and a gabled roof above. */
    static final double CEILING = 12.50;
    /** Where the sides end and the roof starts: 7.61 in above the CELL floor, from figure 9-11 of the manual. */
    static final double EAVE = FLOOR + 7.61;
    static final double HALF_WIDTH = 10.0;

    /** The height of the inside of the roof at {@code lateral} inches from the middle of the CELL. */
    static double roofInches(double lateral) {
        double a = Math.min(Math.abs(lateral), HALF_WIDTH);
        return CEILING - a * (CEILING - EAVE) / HALF_WIDTH;
    }
    /** The solids are thickened outwards, so that the inside of the CELL keeps its CAD size. */
    private static final double WALL = 0.40;

    /** Estimated mass of one HIVE with its ballast washers. */
    static final double MASS_KG = 1.5;
    /** Estimated moment of inertia about the pivot axis. */
    static final double INERTIA_ABOUT_PIVOT = 0.27;
    /**
     * How far the centre of mass sits above the pivot when the arm is level. This is the calibrated quantity: it sets
     * the load that tips the HIVE, which the checklist fixes between seven and eight POLLEN.
     */
    static final double CENTER_OF_MASS_HEIGHT_M = 0.0585;
    static final double BEARING_FRICTION_NM = 0.004;
    static final double DAMPER_NMS_PER_RAD = 2.0;
    static final float SURFACE_FRICTION = 0.35f;
    static final float SURFACE_RESTITUTION = 0.1f;

    private static final int SKIN_RGB = 0xCFD8DC;
    private static final double SKIN = 0.03;
    private static final float SKIN_ALPHA = 0.25f;
    private static final int STICKER_RGB = 0xFAFAFA;

    // The AprilTag cluster: SDK 12 member offsets in inches along the sticker, tag size and where the row sits.
    private static final double TAG_SIZE_M = in(3.25);
    private static final double[] TAG_OFFSETS = {-6.50, -2.75, 2.75, 6.50};
    /** The tag row is 7.1874 in behind the front of the CELL (figure 9-15), on the outside of the floor. */
    private static final double TAG_ROW_D = FRONT - 7.1874;
    private static final double STICKER_N = -1.50;

    private HiveModel() {}

    /** Where the pivot of the HIVE of the given colour is. */
    static Vector3 pivot(boolean red) {
        return new Vector3(0, red ? -PIVOT_OFFSET_M : PIVOT_OFFSET_M, PIVOT_HEIGHT_M);
    }

    /** A point given in the HIVE frame, in the field frame, when the arm stands at {@code angle}. */
    static Vector3 toField(boolean red, double angle, double x, double y, double z) {
        Vector3 p = pivot(red);
        double c = StrictMath.cos(angle);
        double s = StrictMath.sin(angle);
        return new Vector3(p.x() + x * c + z * s, p.y() + y, p.z() - x * s + z * c);
    }

    /** The inverse of {@link #toField}: the HIVE-frame coordinates of a point of the field. */
    static double[] toHive(boolean red, double angle, double x, double y, double z) {
        Vector3 p = pivot(red);
        double dx = x - p.x();
        double dz = z - p.z();
        double c = StrictMath.cos(angle);
        double s = StrictMath.sin(angle);
        return new double[] {dx * c - dz * s, y - p.y(), dx * s + dz * c};
    }

    /** The slope of each half of the roof, from the eave up to the ridge. */
    private static final double ROOF_RADIANS = Math.atan2(CEILING - EAVE, HALF_WIDTH);
    private static final double ROOF_LENGTH = Math.hypot(HALF_WIDTH, CEILING - EAVE);
    /** How wide each step of the gable's collision wall is. */
    private static final double GABLE_STEP = 2.0;


    private static List<ShapePart> collisionCuboids(int sign) {
        double w = HALF_WIDTH + WALL;
        List<ShapePart> parts = new ArrayList<>();
        parts.add(box(sign, BACK - WALL, FRONT, FLOOR - WALL, FLOOR, -w, w));
        parts.add(box(sign, BACK - WALL, BACK, FLOOR - WALL, EAVE, -w, w));
        parts.add(box(sign, BACK, FRONT, FLOOR, EAVE, -w, -HALF_WIDTH));
        parts.add(box(sign, BACK, FRONT, FLOOR, EAVE, HALF_WIDTH, w));
        // The gable of the back wall, in steps that stay just clear of the roof's outer face
        for (int side : new int[] {-1, 1}) {
            for (double a = 0; a < HALF_WIDTH; a += GABLE_STEP) {
                double top = roofInches(a);
                double y0 = Math.min(side * a, side * (a + GABLE_STEP));
                double y1 = Math.max(side * a, side * (a + GABLE_STEP));
                parts.add(box(sign, BACK - WALL, BACK, EAVE, top, y0, y1));
            }
        }
        for (int side : new int[] {-1, 1}) {
            parts.add(roofSlab(sign, side, BACK - WALL, FRONT, 0, WALL, WALL));
        }
        return parts;
    }

    /** The solids both CELLS collide with, in the HIVE frame. */
    static List<ShapePart> collisionParts() {
        List<ShapePart> parts = new ArrayList<>();
        for (int sign : new int[] {1, -1}) {
            parts.addAll(collisionCuboids(sign));
        }
        return parts;
    }

    private static ShapePart box(int sign, double d0, double d1, double n0, double n1, double x0, double x1) {
        return new ShapePart.Box(
                new Vector3(sign * in(d0 + d1) / 2, in(x0 + x1) / 2, in(n0 + n1) / 2),
                new Vector3(in(d1 - d0) / 2, in(x1 - x0) / 2, in(n1 - n0) / 2));
    }

    /**
     * One half of the roof as a collision slab lying along its slope, from {@code inner} to {@code outer} inches outside
     * the inner face and {@code extend} longer at both ends.
     */
    private static ShapePart roofSlab(int sign, int side, double d0, double d1, double inner, double outer, double extend) {
        double[] c = roofCenter(side, inner, outer);
        return new ShapePart.Box(
                new Vector3(sign * in(d0 + d1) / 2, in(c[0]), in(c[1])),
                new Vector3(in(d1 - d0) / 2, in(ROOF_LENGTH + 2 * extend) / 2, in(outer - inner) / 2),
                Vector3.X, -side * ROOF_RADIANS);
    }

    /** The y and n, in inches, of the middle of a roof slab between two offsets from the inner face. */
    private static double[] roofCenter(int side, double inner, double outer) {
        double offset = (inner + outer) / 2;
        return new double[] {
                side * (HALF_WIDTH / 2 + offset * Math.sin(ROOF_RADIANS)),
                (EAVE + CEILING) / 2 + offset * Math.cos(ROOF_RADIANS)};
    }

    private static FieldPart roofPart(
            int sign, int side, double d0, double d1, double inner, double outer, double extend, int rgb, float alpha) {
        double[] c = roofCenter(side, inner, outer);
        return new FieldPart(
                new Shape.Box(in(d1 - d0) / 2, in(ROOF_LENGTH + 2 * extend) / 2, in(outer - inner) / 2),
                Pose3.turned(sign * in(d0 + d1) / 2, in(c[0]), in(c[1]), 1, 0, 0, -side * ROOF_RADIANS),
                rgb, alpha);
    }

    /**
     * How the HIVE looks, in the HIVE frame: a house-shaped CELL, whose floor, sides, gabled back and two roof slopes
     * are clear skins with only the entry open.
     */
    static List<FieldPart> visualParts(int ribRgb) {
        List<FieldPart> parts = new ArrayList<>();
        for (int sign : new int[] {1, -1}) {
            parts.add(skin(sign, BACK - SKIN, BACK, FLOOR - SKIN, EAVE, -HALF_WIDTH - SKIN, HALF_WIDTH + SKIN));
            // The gable above the eave is one triangular skin
            parts.add(new FieldPart(
                    new Shape.Wedge(in(SKIN) / 2, in(HALF_WIDTH), in(CEILING - EAVE) / 2),
                    Pose3.at(sign * in(BACK - SKIN / 2), 0, in((EAVE + CEILING) / 2)), SKIN_RGB, SKIN_ALPHA));
            parts.add(skin(sign, BACK, FRONT, FLOOR - SKIN, FLOOR, -HALF_WIDTH, HALF_WIDTH));
            parts.add(skin(sign, BACK, FRONT, FLOOR, EAVE, -HALF_WIDTH - SKIN, -HALF_WIDTH));
            parts.add(skin(sign, BACK, FRONT, FLOOR, EAVE, HALF_WIDTH, HALF_WIDTH + SKIN));
            for (int side : new int[] {-1, 1}) {
                parts.add(roofPart(sign, side, BACK, FRONT, 0, SKIN, SKIN, SKIN_RGB, SKIN_ALPHA));
            }
            parts.add(visual(sign, 11.52, 16.52, STICKER_N, FLOOR - SKIN, -8.5, 8.5, STICKER_RGB));
            for (double d : new double[] {BACK, FRONT - 2.0}) {
                double d1 = d + 2.0;
                parts.add(visual(sign, d, d1, FLOOR - 1.5, FLOOR - SKIN, -HALF_WIDTH - 0.5, HALF_WIDTH + 0.5, ribRgb));
                parts.add(visual(sign, d, d1, FLOOR, EAVE, -HALF_WIDTH - 0.5, -HALF_WIDTH - SKIN, ribRgb));
                parts.add(visual(sign, d, d1, FLOOR, EAVE, HALF_WIDTH + SKIN, HALF_WIDTH + 0.5, ribRgb));
                for (int side : new int[] {-1, 1}) {
                    parts.add(roofPart(sign, side, d, d1, SKIN, 0.6, 0.5, ribRgb, 1f));
                }
            }
            parts.add(visual(sign, BACK - 4.7, FRONT, -2.71, -1.71, -0.5, 0.5, ribRgb));
        }
        return parts;
    }

    private static FieldPart visual(int sign, double d0, double d1, double n0, double n1, double x0, double x1, int rgb) {
        return visual(sign, d0, d1, n0, n1, x0, x1, rgb, 1f);
    }

    private static FieldPart skin(int sign, double d0, double d1, double n0, double n1, double x0, double x1) {
        return visual(sign, d0, d1, n0, n1, x0, x1, SKIN_RGB, SKIN_ALPHA);
    }

    private static FieldPart visual(
            int sign, double d0, double d1, double n0, double n1, double x0, double x1, int rgb, float alpha) {
        return new FieldPart(
                new Shape.Box(in(d1 - d0) / 2, in(x1 - x0) / 2, in(n1 - n0) / 2),
                Pose3.at(sign * in(d0 + d1) / 2, in(x0 + x1) / 2, in(n0 + n1) / 2),
                rgb, alpha);
    }

    /** The HIVE as a pivoting body, resting on the stop that leaves the given CELL facing up. */
    static PivotSpec spec(boolean red, boolean audienceCellUp) {
        return spec(red, audienceCellUp, CENTER_OF_MASS_HEIGHT_M);
    }

    /** As {@link #spec(boolean, boolean)} with another centre of mass height; the calibration varies it. */
    static PivotSpec spec(boolean red, boolean audienceCellUp, double centerOfMassHeight) {
        double inertiaAboutCenter = INERTIA_ABOUT_PIVOT - MASS_KG * centerOfMassHeight * centerOfMassHeight;
        return new PivotSpec(
                pivot(red), Vector3.Y, collisionParts(), MASS_KG, new Vector3(0, 0, centerOfMassHeight),
                new Vector3(0.06, inertiaAboutCenter, 0.27),
                audienceCellUp ? -STOP_RADIANS : STOP_RADIANS, -STOP_RADIANS, STOP_RADIANS,
                BEARING_FRICTION_NM, DAMPER_ZONE_RADIANS, DAMPER_NMS_PER_RAD, SURFACE_FRICTION, SURFACE_RESTITUTION);
    }

    /**
     * The four tags of the cluster on one CELL, attached to the HIVE body. Tag {@code first + i} sits at the SDK
     * member offset {@code TAG_OFFSETS[i]} along the sticker, as seen by a camera looking up at it; the sticker's
     * bottom edge is towards the middle of the field. The tag frame has +Z out of the face, +Y up the sticker.
     */
    static List<FieldTag> tags(int hostEntityId, int firstId, boolean audienceCell) {
        double half = Math.sqrt(0.5);
        double side = audienceCell ? 1.0 : -1.0;
        List<FieldTag> tags = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Pose3 pose = new Pose3(side * in(TAG_ROW_D), side * in(TAG_OFFSETS[i]), in(STICKER_N), 0.0, half, side * half, 0.0);
            tags.add(new FieldTag(firstId + i, TAG_SIZE_M, hostEntityId, pose));
        }
        return tags;
    }
}
