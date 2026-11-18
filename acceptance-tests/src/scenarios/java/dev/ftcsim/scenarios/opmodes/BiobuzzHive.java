package dev.ftcsim.scenarios.opmodes;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where the BIOBUZZ field's HIVEs, their AprilTags and their CELLs are, for a robot program. The SDK's tag library gives only
 * placeholder poses for these tags, because every tag is fixed to a HIVE and the HIVE tips; a team writes its own map. This one is
 * the HIVEs from the manual (section 9.6, figures 9-7, 9-11 and 9-15, and 10.3), in the field frame: X towards the audience, Y
 * towards the blue wall, Z up, metres.
 *
 * <p>A HIVE rests on one of its two stops, 30 degrees from level, so one of its CELLs faces up (the {@link Tilt}). Only that CELL
 * can be loaded to tip the HIVE, and a TIP changes which one it is: the tags move with the arm, so the map is given per tilt.
 *
 * <p>Written for Java 8, like the other files a robot project takes.
 */
public final class BiobuzzHive {
    /** The two alliances: the red HIVE is on the negative Y side of the field. */
    public enum Alliance {
        RED(-1), BLUE(1);

        private final int sign;

        Alliance(int sign) {
            this.sign = sign;
        }

        /** The side of the field's centre line the alliance's HIVE is on. */
        public int sign() {
            return sign;
        }

        public Alliance other() {
            return this == RED ? BLUE : RED;
        }
    }

    /** Which end of a HIVE's arm is up: the audience end or the scoring end. */
    public enum Tilt {
        /** The arm is down at its scoring end (the red HIVE's start). */
        AUDIENCE_UP(+1, -Math.toRadians(30.0)),
        /** The arm is down at its audience end (the blue HIVE's start). */
        SCORING_UP(-1, Math.toRadians(30.0));

        private final int side;
        private final double angle;

        Tilt(int side, double angle) {
            this.side = side;
            this.angle = angle;
        }

        /** +1 when the CELL that faces up is on the audience side (positive X), -1 on the scoring side. */
        public int side() {
            return side;
        }

        /** The arm's angle: a positive angle lowers the audience end. */
        public double angle() {
            return angle;
        }

        public Tilt other() {
            return this == AUDIENCE_UP ? SCORING_UP : AUDIENCE_UP;
        }
    }

    /** A tag's centre in the field. The tags face down. */
    public static final class Tag {
        private final double x;
        private final double y;
        private final double z;

        public Tag(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
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

    static final double INCH = 0.0254;
    /** Each HIVE's pivot is 12.74 in either side of the field's centre line (the red HIVE on the negative side) and 43.95 in up. */
    static final double PIVOT_Y = 12.74 * INCH;
    static final double PIVOT_Z = 43.95 * INCH;
    /** A CELL runs 9.44 to 21.46 in from the pivot along the arm and from 1.46 in below it to 12.50 in above it. */
    static final double CELL_BACK = 9.44 * INCH;
    static final double CELL_FRONT = 21.46 * INCH;
    static final double CELL_FLOOR = -1.46 * INCH;
    static final double CELL_CEILING = 12.50 * INCH;
    /** The sides stand up to 7.61 in above the floor, and the roof of the house rises from there to the ridge. */
    static final double CELL_EAVE = CELL_FLOOR + 7.61 * INCH;
    static final double CELL_HALF_WIDTH = 10.0 * INCH;
    /** The walls and floor are about this thick, outside the CELL's inside dimensions. */
    static final double CELL_WALL = 0.40 * INCH;
    /** The row of four tags sits 7.1874 in behind the front of the CELL, on the outside of its floor, 1.5 in below the arm. */
    private static final double TAG_ROW = CELL_FRONT - 7.1874 * INCH;
    private static final double TAG_BELOW = -1.5 * INCH;
    private static final double[] TAG_OFFSETS = {-6.50 * INCH, -2.75 * INCH, 2.75 * INCH, 6.50 * INCH};

    /**
     * Tags 30 to 45 by id with both HIVEs as they stand at the start of a match (CM 10.3: the CELL with the NECTAR faces up, the red
     * audience CELL and the blue scoring CELL): red HIVE 30 to 33 on the scoring side and 34 to 37 on the audience side, blue HIVE
     * 38 to 41 and 42 to 45. A TIP puts the tags elsewhere, up to 7 cm sideways; {@link #tags} has them per tilt.
     */
    public static final Map<Integer, Tag> TAGS = tags(startTilt(Alliance.RED), startTilt(Alliance.BLUE));

    private BiobuzzHive() {}

    /** The CELL that faces up at the start of a match: the red HIVE's audience CELL, the blue HIVE's scoring one. */
    public static Tilt startTilt(Alliance alliance) {
        return alliance == Alliance.RED ? Tilt.AUDIENCE_UP : Tilt.SCORING_UP;
    }

    /** The tag map with the red HIVE and the blue HIVE each at its own tilt. */
    public static Map<Integer, Tag> tags(Tilt red, Tilt blue) {
        Map<Integer, Tag> tags = new LinkedHashMap<>();
        addCluster(tags, 30, Alliance.RED, -1, red);
        addCluster(tags, 34, Alliance.RED, 1, red);
        addCluster(tags, 38, Alliance.BLUE, 1, blue);
        addCluster(tags, 42, Alliance.BLUE, -1, blue);
        return Collections.unmodifiableMap(tags);
    }

    /** The tag map when {@code own} stands at {@code tilt} and the other HIVE is at its start. */
    public static Map<Integer, Tag> tags(Alliance own, Tilt tilt) {
        return own == Alliance.RED ? tags(tilt, startTilt(Alliance.BLUE)) : tags(startTilt(Alliance.RED), tilt);
    }

    /** Whether {@code id} is one of the alliance's HIVE's eight tags. */
    public static boolean isOwnTag(Alliance alliance, int id) {
        int first = alliance == Alliance.RED ? 30 : 38;
        return id >= first && id < first + 8;
    }

    private static void addCluster(Map<Integer, Tag> tags, int firstId, Alliance alliance, int side, Tilt tilt) {
        double angle = tilt.angle();
        for (int i = 0; i < TAG_OFFSETS.length; i++) {
            double along = side * TAG_ROW;
            tags.put(firstId + i, new Tag(
                    along * Math.cos(angle) + TAG_BELOW * Math.sin(angle), alliance.sign() * PIVOT_Y + side * TAG_OFFSETS[i],
                    PIVOT_Z - along * Math.sin(angle) + TAG_BELOW * Math.cos(angle)));
        }
    }

    /** The CELL of the alliance's HIVE that faces up when the arm is at {@code tilt}. */
    public static Cell upCell(Alliance alliance, Tilt tilt) {
        return new Cell(alliance, tilt);
    }

    /**
     * One CELL, with the frame its geometry is given in: {@code d} along the arm from the pivot towards the CELL's open end, {@code l}
     * across it (along the pivot axis, 0 in the middle) and {@code n} up from the arm's axis, perpendicular to it. The CELL is a
     * house: a floor, a back wall, two side walls up to the eave and a gabled roof, and it is open only at its far end.
     */
    public static final class Cell {
        private final double pivotY;
        private final int side;
        private final double cos;
        private final double sin;

        Cell(Alliance alliance, Tilt tilt) {
            this.pivotY = alliance.sign() * PIVOT_Y;
            this.side = tilt.side();
            this.cos = Math.cos(tilt.angle());
            this.sin = Math.sin(tilt.angle());
        }

        /** +1 when this CELL is on the audience side of the HIVE, -1 on the scoring side. */
        public int side() {
            return side;
        }

        /** A field point in the CELL's frame: {@code d, l, n}. */
        public double[] toCell(double x, double y, double z) {
            double dz = z - PIVOT_Z;
            return new double[] {side * (x * cos - dz * sin), y - pivotY, x * sin + dz * cos};
        }

        /** A point of the CELL's frame in the field: {@code x, y, z}. */
        public double[] toField(double d, double l, double n) {
            double xh = side * d;
            return new double[] {xh * cos + n * sin, pivotY + l, PIVOT_Z - xh * sin + n * cos};
        }

        /** The height of the inside of the roof {@code lateral} metres from the middle. */
        public static double roof(double lateral) {
            double a = Math.min(Math.abs(lateral), CELL_HALF_WIDTH);
            return CELL_CEILING - a * (CELL_CEILING - CELL_EAVE) / CELL_HALF_WIDTH;
        }

        /** Where a shot is aimed: in the middle of the opening, a little way in and low enough to fall onto the floor. */
        public double[] aimPoint() {
            return toField(CELL_FRONT - 2.0 * INCH, 0.0, CELL_FLOOR + 6.5 * INCH);
        }
    }
}
