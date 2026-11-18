package dev.ftcsim.game.biobuzz;

import static dev.ftcsim.game.biobuzz.Solids.in;

import dev.ftcsim.contracts.snapshot.BodyState;
import dev.ftcsim.contracts.snapshot.WorldSnapshot;
import dev.ftcsim.field.EntityIds;
import java.util.ArrayList;
import java.util.List;

/**
 * What the pieces and the robot are doing right now that scores for one alliance, read from a snapshot: pieces in the
 * upward CELL of its HIVE, in the FLOWERS and GARDEN, and whether the robot has left the wall and is in the LOADING
 * ZONE. A piece is counted in one place only, in the order CELL, FLOWER, GARDEN. Nothing here remembers anything, so
 * a piece that rests or bounces can never be counted twice.
 *
 * @param cellPieces      POLLEN and NECTAR inside the upward CELL
 * @param ownedFlowers    FLOWERS whose top-most NECTAR is of this alliance's colour
 * @param ownedFlowerPieces every POLLEN and NECTAR in those FLOWERS
 * @param bottomNectar    FLOWERS whose bottom-most NECTAR is of this alliance's colour
 * @param gardenPieces    POLLEN and NECTAR at least partly in this alliance's GARDEN
 * @param parked          the robot is at least partly in this alliance's LOADING ZONE
 * @param leftWall        the robot touches no perimeter wall
 */
record Assessment(
        int cellPieces, int ownedFlowers, int ownedFlowerPieces, int bottomNectar, int gardenPieces, boolean parked, boolean leftWall) {

    /** The arm has to be this far from level for a CELL to count as facing up. */
    private static final double UP_RADIANS = Math.toRadians(1.0);
    /** Closer to a wall than this counts as touching it. */
    private static final double TOUCH_M = 0.005;

    static boolean isPiece(int kind) {
        return kind == BiobuzzIds.KIND_POLLEN || kind == BiobuzzIds.KIND_NECTAR_RED || kind == BiobuzzIds.KIND_NECTAR_BLUE;
    }

    /** Angle of a HIVE body about the pivot axis, from its orientation. */
    static double hiveAngle(BodyState hive) {
        double angle = 2.0 * StrictMath.atan2(hive.qy(), hive.qw());
        if (angle > Math.PI) {
            angle -= 2.0 * Math.PI;
        } else if (angle < -Math.PI) {
            angle += 2.0 * Math.PI;
        }
        return angle;
    }

    static Assessment of(WorldSnapshot snapshot, boolean red, double robotLength, double robotWidth) {
        List<BodyState> pieces = new ArrayList<>();
        BodyState robot = null;
        BodyState hive = null;
        int hiveId = red ? BiobuzzIds.HIVE_RED : BiobuzzIds.HIVE_BLUE;
        for (BodyState body : snapshot.bodies()) {
            if (isPiece(body.kind())) {
                pieces.add(body);
            } else if (body.entityId() == EntityIds.CHASSIS) {
                robot = body;
            } else if (body.entityId() == hiveId) {
                hive = body;
            }
        }
        boolean[] counted = new boolean[pieces.size()];
        int cell = hive == null ? 0 : piecesInCell(pieces, counted, hive, red);

        int owned = 0;
        int ownedPieces = 0;
        int bottom = 0;
        int kindOfMine = red ? BiobuzzIds.KIND_NECTAR_RED : BiobuzzIds.KIND_NECTAR_BLUE;
        for (FlowerModel.Site site : FlowerModel.SITES) {
            List<Integer> inside = new ArrayList<>();
            for (int i = 0; i < pieces.size(); i++) {
                if (!counted[i] && inFlowerVolume(pieces.get(i), site)) {
                    inside.add(i);
                }
            }
            int top = -1;
            int low = -1;
            for (int i : inside) {
                BodyState piece = pieces.get(i);
                if (piece.kind() == BiobuzzIds.KIND_POLLEN) {
                    continue;
                }
                if (top < 0 || piece.z() > pieces.get(top).z()) {
                    top = i;
                }
                if (low < 0 || piece.z() < pieces.get(low).z()) {
                    low = i;
                }
            }
            for (int i : inside) {
                counted[i] = true;
            }
            if (top >= 0 && pieces.get(top).kind() == kindOfMine) {
                owned++;
                ownedPieces += inside.size();
            }
            if (low >= 0 && pieces.get(low).kind() == kindOfMine) {
                bottom++;
            }
        }

        FieldGeometry.Zone garden = red ? FieldGeometry.RED_GARDEN : FieldGeometry.BLUE_GARDEN;
        int gardenCount = 0;
        for (int i = 0; i < pieces.size(); i++) {
            BodyState piece = pieces.get(i);
            if (!counted[i] && garden.overlapsCircle(piece.x(), piece.y(), PieceModel.radiusOf(piece.kind()))) {
                gardenCount++;
            }
        }

        boolean parked = false;
        boolean left = false;
        if (robot != null) {
            double yaw = StrictMath.atan2(
                    2.0 * (robot.qw() * robot.qz() + robot.qx() * robot.qy()),
                    1.0 - 2.0 * (robot.qy() * robot.qy() + robot.qz() * robot.qz()));
            FieldGeometry.Zone zone = red ? FieldGeometry.RED_LOADING_ZONE : FieldGeometry.BLUE_LOADING_ZONE;
            parked = overlaps(zone, robot.x(), robot.y(), yaw, robotLength / 2, robotWidth / 2);
            double c = Math.abs(StrictMath.cos(yaw));
            double s = Math.abs(StrictMath.sin(yaw));
            double reachX = c * robotLength / 2 + s * robotWidth / 2;
            double reachY = s * robotLength / 2 + c * robotWidth / 2;
            left = Math.abs(robot.x()) + reachX < FieldGeometry.HALF_M - TOUCH_M
                    && Math.abs(robot.y()) + reachY < FieldGeometry.HALF_M - TOUCH_M;
        }
        return new Assessment(cell, owned, ownedPieces, bottom, gardenCount, parked, left);
    }

    private static int piecesInCell(List<BodyState> pieces, boolean[] counted, BodyState hive, boolean red) {
        double angle = hiveAngle(hive);
        double side;
        if (angle < -UP_RADIANS) {
            side = 1.0;
        } else if (angle > UP_RADIANS) {
            side = -1.0;
        } else {
            return 0;
        }
        int inside = 0;
        for (int i = 0; i < pieces.size(); i++) {
            BodyState piece = pieces.get(i);
            double[] local = HiveModel.toHive(red, angle, piece.x(), piece.y(), piece.z());
            double d = side * local[0];
            if (d >= in(HiveModel.BACK) && d <= in(HiveModel.FRONT)
                    && Math.abs(local[1]) <= in(HiveModel.HALF_WIDTH)
                    && local[2] >= in(HiveModel.FLOOR) && local[2] <= in(HiveModel.roofInches(local[1] / 0.0254))) {
                counted[i] = true;
                inside++;
            }
        }
        return inside;
    }

    private static boolean inFlowerVolume(BodyState piece, FlowerModel.Site site) {
        double dx = piece.x() - in(site.axisX());
        double dy = piece.y() - in(site.axisY());
        double r = PieceModel.radiusOf(piece.kind());
        return dx * dx + dy * dy < FlowerModel.COLUMN_RADIUS_M * FlowerModel.COLUMN_RADIUS_M
                && piece.z() + r > FlowerModel.SCORING_BOTTOM_M && piece.z() - r < FlowerModel.SCORING_TOP_M;
    }

    /** Whether a rectangle turned by {@code yaw} about its centre overlaps an axis-aligned zone. */
    static boolean overlaps(FieldGeometry.Zone zone, double x, double y, double yaw, double halfLength, double halfWidth) {
        double c = StrictMath.cos(yaw);
        double s = StrictMath.sin(yaw);
        double zx = 0.5 * (zone.xMin() + zone.xMax());
        double zy = 0.5 * (zone.yMin() + zone.yMax());
        double hx = 0.5 * (zone.xMax() - zone.xMin());
        double hy = 0.5 * (zone.yMax() - zone.yMin());
        double reachX = Math.abs(c) * halfLength + Math.abs(s) * halfWidth;
        double reachY = Math.abs(s) * halfLength + Math.abs(c) * halfWidth;
        if (Math.abs(zx - x) > hx + reachX || Math.abs(zy - y) > hy + reachY) {
            return false;
        }
        double alongLength = (zx - x) * c + (zy - y) * s;
        double alongWidth = -(zx - x) * s + (zy - y) * c;
        return Math.abs(alongLength) <= halfLength + hx * Math.abs(c) + hy * Math.abs(s)
                && Math.abs(alongWidth) <= halfWidth + hx * Math.abs(s) + hy * Math.abs(c);
    }
}
