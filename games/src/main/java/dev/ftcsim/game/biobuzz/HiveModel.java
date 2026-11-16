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
    static final double CEILING = 12.50;
    static final double HALF_WIDTH = 10.0;
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

    /** The audience CELL has sign +1 and the scoring CELL sign -1, which mirrors the arm about the pivot. */
    private record Cuboid(double d0, double d1, double n0, double n1, double x0, double x1) {}

    private static List<Cuboid> collisionCuboids() {
        double w = HALF_WIDTH + WALL;
        return List.of(
                new Cuboid(BACK - WALL, FRONT, FLOOR - WALL, FLOOR, -w, w),
                new Cuboid(BACK - WALL, BACK, FLOOR - WALL, CEILING + WALL, -w, w),
                new Cuboid(BACK, FRONT, FLOOR, CEILING, -w, -HALF_WIDTH),
                new Cuboid(BACK, FRONT, FLOOR, CEILING, HALF_WIDTH, w),
                new Cuboid(BACK - WALL, FRONT, CEILING, CEILING + WALL, -w, w));
    }

    /** The solids both CELLS collide with, in the HIVE frame. */
    static List<ShapePart> collisionParts() {
        List<ShapePart> parts = new ArrayList<>();
        for (int sign : new int[] {1, -1}) {
            for (Cuboid c : collisionCuboids()) {
                parts.add(new ShapePart.Box(
                        new Vector3(sign * in(c.d0() + c.d1()) / 2, in(c.x0() + c.x1()) / 2, in(c.n0() + c.n1()) / 2),
                        new Vector3(in(c.d1() - c.d0()) / 2, in(c.x1() - c.x0()) / 2, in(c.n1() - c.n0()) / 2)));
            }
        }
        return parts;
    }

    /** How the HIVE looks, in the HIVE frame: clear skins left open so that the pieces inside stay visible. */
    static List<FieldPart> visualParts(int ribRgb) {
        List<FieldPart> parts = new ArrayList<>();
        for (int sign : new int[] {1, -1}) {
            parts.add(visual(sign, BACK - 0.06, BACK, FLOOR, CEILING, -HALF_WIDTH, HALF_WIDTH, SKIN_RGB));
            parts.add(visual(sign, BACK, FRONT, FLOOR - 0.06, FLOOR, -HALF_WIDTH, HALF_WIDTH, SKIN_RGB));
            parts.add(visual(sign, 11.52, 16.52, STICKER_N - 0.03, STICKER_N, -8.5, 8.5, STICKER_RGB));
            for (double d : new double[] {BACK, FRONT - 2.0}) {
                double d1 = d + 2.0;
                parts.add(visual(sign, d, d1, FLOOR - 1.5, FLOOR, -HALF_WIDTH - 0.5, HALF_WIDTH + 0.5, ribRgb));
                parts.add(visual(sign, d, d1, CEILING, CEILING + 0.6, -HALF_WIDTH - 0.5, HALF_WIDTH + 0.5, ribRgb));
                parts.add(visual(sign, d, d1, FLOOR, CEILING, -HALF_WIDTH - 0.5, -HALF_WIDTH, ribRgb));
                parts.add(visual(sign, d, d1, FLOOR, CEILING, HALF_WIDTH, HALF_WIDTH + 0.5, ribRgb));
            }
            parts.add(visual(sign, BACK - 4.7, FRONT, -2.71, -1.71, -0.5, 0.5, ribRgb));
        }
        return parts;
    }

    private static FieldPart visual(int sign, double d0, double d1, double n0, double n1, double x0, double x1, int rgb) {
        return new FieldPart(
                new Shape.Box(in(d1 - d0) / 2, in(x1 - x0) / 2, in(n1 - n0) / 2),
                Pose3.at(sign * in(d0 + d1) / 2, in(x0 + x1) / 2, in(n0 + n1) / 2),
                rgb);
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
