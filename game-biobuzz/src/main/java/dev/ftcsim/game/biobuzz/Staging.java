package dev.ftcsim.game.biobuzz;

import static dev.ftcsim.game.biobuzz.Solids.in;

import dev.ftcsim.contracts.field.Alliance;
import dev.ftcsim.physics.Vector3;
import java.util.ArrayList;
import java.util.List;

/**
 * Where every piece starts, as section 10.3.1 of the manual and the setup guide stage them: four POLLEN in each
 * FLOWER and in each GARDEN, three NECTAR in the upward CELL of each HIVE, the rest of the NECTAR with the humans.
 * The robot's own four preloads are held back for the robot to take. A robot that is not on the field leaves its four
 * preloads in the middle of its LOADING ZONE against the wall, so the other three robots' sixteen minus four POLLEN are
 * there: four in the robot's own zone and eight in the other. Placement varies a little with the seed, as
 * volunteers' does.
 */
final class Staging {

    /** A piece that starts in the world. */
    record Placement(int id, int kind, double x, double y, double z) {}

    final List<Placement> inWorld = new ArrayList<>();
    /** Pieces the robot may take as its preloads, in the order they are handed out. */
    final List<Integer> robotPollen = new ArrayList<>();
    final List<Integer> redNectarWithHuman = new ArrayList<>();
    final List<Integer> blueNectarWithHuman = new ArrayList<>();

    private static final double PITCH = 2.82;
    private static final double AGAINST_WALL = 70.52 - 1.4 - 0.02;
    /** Height of a POLLEN centre when it rests on the tiles, with a hair of clearance. */
    private static final double RESTING = 1.42;

    Staging(Alliance robot, long seed) {
        SeededNoise noise = new SeededNoise(seed);
        int id = BiobuzzIds.POLLEN_FIRST;
        for (FlowerModel.Site site : FlowerModel.SITES) {
            for (double height : FlowerModel.START_HEIGHTS) {
                pollen(id++, site.axisX() + noise.signed(0.06), site.axisY() + noise.signed(0.06), height);
            }
        }
        garden(id, 1, -1, noise);
        garden(id + 4, -1, 1, noise);
        id += 8;
        zone(id, robot == Alliance.RED, false, noise);
        zone(id + 8, robot == Alliance.BLUE, true, noise);
        cellNectar(true);
        cellNectar(false);
        for (int k = 3; k < BiobuzzIds.NECTAR_PER_ALLIANCE; k++) {
            redNectarWithHuman.add(BiobuzzIds.NECTAR_RED_FIRST + k);
            blueNectarWithHuman.add(BiobuzzIds.NECTAR_BLUE_FIRST + k);
        }
    }

    private void pollen(int id, double xInches, double yInches, double zInches) {
        inWorld.add(new Placement(id, BiobuzzIds.KIND_POLLEN, in(xInches), in(yInches), in(zInches)));
    }

    /** Four POLLEN in a line from the corner, along the wall: {@code wallX} is the side of the wall the garden is on. */
    private void garden(int firstId, int wallX, int cornerY, SeededNoise noise) {
        for (int k = 0; k < 4; k++) {
            pollen(
                    firstId + k, wallX * AGAINST_WALL, cornerY * (AGAINST_WALL - k * PITCH) + noise.signed(0.08), RESTING);
        }
    }

    /**
     * The sixteen preloads of one alliance's LOADING ZONE ids {@code firstId..firstId+7}: when its robot is present
     * the first four go to the zone and the other four to the robot, otherwise all eight lie in the zone.
     */
    private void zone(int firstId, boolean robotPresent, boolean blue, SeededNoise noise) {
        int count = robotPresent ? 4 : 8;
        double centre = (blue ? 1 : -1) * (23.91 + 46.60) / 2;
        for (int k = 0; k < count; k++) {
            pollen(firstId + k, centre + (k - (count - 1) / 2.0) * PITCH + noise.signed(0.08), (blue ? 1 : -1) * AGAINST_WALL, RESTING);
        }
        if (robotPresent) {
            for (int k = 4; k < 8; k++) {
                robotPollen.add(firstId + k);
            }
        }
    }

    /** Three NECTAR on the floor of the upward CELL, against its back wall and the side towards its alliance area. */
    private void cellNectar(boolean red) {
        double r = PieceModel.NECTAR_RADIUS_M / 0.0254;
        double angle = red ? -HiveModel.STOP_RADIANS : HiveModel.STOP_RADIANS;
        double sign = red ? 1 : -1;
        int first = red ? BiobuzzIds.NECTAR_RED_FIRST : BiobuzzIds.NECTAR_BLUE_FIRST;
        int kind = red ? BiobuzzIds.KIND_NECTAR_RED : BiobuzzIds.KIND_NECTAR_BLUE;
        for (int k = 0; k < 3; k++) {
            double lateral = (red ? -1 : 1) * (HiveModel.HALF_WIDTH - r - 0.05 - k * (2 * r + 0.02));
            Vector3 p = HiveModel.toField(
                    red, angle, sign * in(HiveModel.BACK + r + 0.03), in(lateral), in(HiveModel.FLOOR + r + 0.03));
            inWorld.add(new Placement(first + k, kind, p.x(), p.y(), p.z()));
        }
    }
}
