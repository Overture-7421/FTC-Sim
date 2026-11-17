package dev.ftcsim.scenarios.opmodes;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where the BIOBUZZ field's HIVEs and their AprilTags are, for a robot program. The SDK's tag library gives only placeholder
 * poses for these tags, because every tag is fixed to a HIVE and the HIVE tips; a team writes its own map. This one is the
 * HIVEs as they stand at the start of a match (the manual's section 9.6, figure 9-15 and 10.3), in the field frame:
 * X towards the audience, Y towards the blue wall, Z up, metres.
 */
public final class BiobuzzHive {
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

    private static final double INCH = 0.0254;
    /** Each HIVE's pivot is 12.74 in either side of the field's centre line (the red HIVE on the negative side) and 43.95 in up. */
    private static final double PIVOT_Y = 12.74 * INCH;
    private static final double PIVOT_Z = 43.95 * INCH;
    /** A CELL runs 9.44 to 21.46 in from the pivot along the arm and from 1.46 in below it to 12.50 in above it. */
    private static final double CELL_BACK = 9.44 * INCH;
    private static final double CELL_FRONT = 21.46 * INCH;
    private static final double CELL_FLOOR = -1.46 * INCH;
    private static final double CELL_CEILING = 12.50 * INCH;
    /** The row of four tags sits 7.1874 in behind the front of the CELL, on the outside of its floor, 1.5 in below the arm. */
    private static final double TAG_ROW = CELL_FRONT - 7.1874 * INCH;
    private static final double TAG_BELOW = -1.5 * INCH;
    private static final double[] TAG_OFFSETS = {-6.50 * INCH, -2.75 * INCH, 2.75 * INCH, 6.50 * INCH};
    /** The arm stops 30 degrees from level. */
    private static final double TIP = Math.toRadians(30.0);

    /**
     * Tags 30 to 45 by id, with each HIVE as it stands at the start of a match (CM 10.3: the CELL with the NECTAR faces up, the red
     * audience CELL and the blue scoring CELL, so each arm rests on a stop 30 degrees from level): red HIVE 30 to 33 on the scoring
     * side and 34 to 37 on the audience side, blue HIVE 38 to 41 and 42 to 45. A TIP puts the tags elsewhere, up to 7 cm sideways.
     */
    public static final Map<Integer, Tag> TAGS = tags();

    private BiobuzzHive() {}

    private static Map<Integer, Tag> tags() {
        Map<Integer, Tag> tags = new LinkedHashMap<>();
        addCluster(tags, 30, -PIVOT_Y, -1, -TIP);
        addCluster(tags, 34, -PIVOT_Y, 1, -TIP);
        addCluster(tags, 38, PIVOT_Y, 1, TIP);
        addCluster(tags, 42, PIVOT_Y, -1, TIP);
        return Collections.unmodifiableMap(tags);
    }

    /** A positive angle lowers the audience end of the arm. */
    private static void addCluster(Map<Integer, Tag> tags, int firstId, double pivotY, int side, double angle) {
        for (int i = 0; i < TAG_OFFSETS.length; i++) {
            double along = side * TAG_ROW;
            tags.put(firstId + i, new Tag(
                    along * Math.cos(angle) + TAG_BELOW * Math.sin(angle), pivotY + side * TAG_OFFSETS[i],
                    PIVOT_Z - along * Math.sin(angle) + TAG_BELOW * Math.cos(angle)));
        }
    }

    /**
     * The middle of the blue HIVE's CELL on the audience side ({@code towardsAudience}) or the scoring side, with the arm tipped
     * so that this side is down, which is where the CELL is when it is the one the robot has loaded.
     */
    public static Tag blueCell(boolean towardsAudience) {
        double along = 0.5 * (CELL_BACK + CELL_FRONT);
        double above = 0.5 * (CELL_FLOOR + CELL_CEILING);
        double side = towardsAudience ? 1.0 : -1.0;
        double x = side * (along * Math.cos(TIP) + above * Math.sin(TIP));
        double z = PIVOT_Z - along * Math.sin(TIP) + above * Math.cos(TIP);
        return new Tag(x, PIVOT_Y, z);
    }

    /** The blue CELL nearer to a robot standing at field X {@code robotX}. */
    public static Tag nearestBlueCell(double robotX) {
        return blueCell(robotX >= 0.0);
    }
}
