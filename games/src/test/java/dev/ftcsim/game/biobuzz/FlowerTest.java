package dev.ftcsim.game.biobuzz;

import static dev.ftcsim.game.biobuzz.Solids.in;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.field.Alliance;
import dev.ftcsim.contracts.snapshot.BodyState;
import dev.ftcsim.physics.Vector3;
import org.junit.jupiter.api.Test;

/**
 * The FLOWER operation tests of the Field Acceptance Checklist (2.8 to 2.11): what stays in, what comes out of the
 * retrieval opening, and what can never get through it.
 */
class FlowerTest {

    /** The FLOWER on the red wall nearest the audience: axis at X = 23.39 in, Y = -69.52 in, the wall behind it at -Y. */
    private static final FlowerModel.Site SITE = FlowerModel.SITES.get(3);
    private static final int FIRST_STARTING_POLLEN = BiobuzzIds.POLLEN_FIRST + 12;

    private static void emptyTheFlower(Arena arena) {
        for (int id = FIRST_STARTING_POLLEN; id < FIRST_STARTING_POLLEN + 4; id++) {
            arena.world.removePiece(id);
        }
    }

    /** The height of the piece's centre above the tiles in inches, and its distance from the axis of the FLOWER. */
    private static double[] where(Arena arena, int id) {
        BodyState b = arena.body(id);
        double dx = b.x() - in(SITE.axisX());
        double dy = b.y() - in(SITE.axisY());
        return new double[] {b.z() / 0.0254, Math.hypot(dx, dy) / 0.0254};
    }

    private static int drop(Arena arena, int kind) {
        int id = arena.spawn(kind, new Vector3(in(SITE.axisX()), in(SITE.axisY()), in(23.0)), Vector3.ZERO);
        arena.steps(360);
        return id;
    }

    /**
     * Throws a POLLEN along the floor of the retrieval opening, in the direction {@code a} towards the wall and
     * {@code b} along it, as a hand or an intake roller sweeps through it.
     */
    private static int sweep(Arena arena, double a, double b, double height, double speed) {
        double length = Math.hypot(a, b);
        double wx = SITE.wallX();
        double wy = SITE.wallY();
        Vector3 direction = new Vector3(
                (a * wx - b * wy) / length, (a * wy + b * wx) / length, 0.0);
        Vector3 start = new Vector3(in(SITE.axisX()), in(SITE.axisY()), in(height)).minus(direction.times(in(6.0)));
        int id = arena.spawn(BiobuzzIds.KIND_POLLEN, start, direction.times(speed));
        arena.steps(300);
        return id;
    }

    @Test
    void oneLiftUnderTheStackReleasesAllFourPollenEvenWithoutAnIntake() {
        try (Arena arena = new Arena(1, Alliance.RED)) {
            arena.steps(120);
            double axisX = in(SITE.axisX());
            double axisY = in(SITE.axisY());
            // Hold the lowered plate under the wall side of the bottom ball, then raise it once.
            int support = 9000;
            arena.world.addFixedBox(support, 0, new Vector3(axisX, axisY + 0.340, 0.1794),
                    new Vector3(0.01, 0.01, 0.01), -Math.PI / 2);
            var plate = arena.world.addHingedPlate(support, new Vector3(0.015, 0.212, 0.004),
                    new Vector3(0.0225, 0, -0.1034), new Vector3(0.3311, 0, -0.0700), 0);
            for (int step = 1; step <= 600; step++) {
                plate.accept(Math.toRadians(4.0) * Math.min(step / 14.0, 1.0));
                arena.steps(1);
            }
            for (int id = FIRST_STARTING_POLLEN; id < FIRST_STARTING_POLLEN + 4; id++) {
                double[] position = where(arena, id);
                assertTrue(position[1] > 2.44 && position[0] < 2,
                        "POLLEN " + id + " should be on the floor outside the FLOWER: " + arena.body(id));
            }
        }
    }

    @Test
    void twoPollenAndANectarStackInTheOrderTheyWereLoadedAndNothingFallsOutOfTheBottom() {
        try (Arena arena = new Arena(3, Alliance.RED)) {
            arena.steps(120);
            emptyTheFlower(arena);
            int first = drop(arena, BiobuzzIds.KIND_POLLEN);
            int second = drop(arena, BiobuzzIds.KIND_POLLEN);
            int nectar = drop(arena, BiobuzzIds.KIND_NECTAR_RED);
            arena.steps(1200);

            double[] a = where(arena, first);
            double[] b = where(arena, second);
            double[] n = where(arena, nectar);
            assertEquals(1.4, a[0], 0.2, "the first POLLEN rests on the tiles in the pocket of the bottom ring");
            assertEquals(4.2, b[0], 0.3, "the second rests on the first");
            assertEquals(7.3, n[0], 0.4, "the NECTAR rests on the second");
            assertTrue(a[1] < 1.0 && b[1] < 1.0 && n[1] < 1.0, "all three stay in the column");
        }
    }

    @Test
    void aNectarDroppedInFromTheTopSitsInTheMiddleRingAndNeverReachesThePocketBelow() {
        try (Arena arena = new Arena(3, Alliance.RED)) {
            arena.steps(120);
            emptyTheFlower(arena);
            int nectar = drop(arena, BiobuzzIds.KIND_NECTAR_BLUE);
            arena.steps(600);
            double[] n = where(arena, nectar);
            assertTrue(n[0] > 4.0 && n[0] < 5.5, "the seat of the middle ring is narrower than a NECTAR: z = " + n[0]);

            int pollen = drop(arena, BiobuzzIds.KIND_POLLEN);
            assertTrue(where(arena, pollen)[0] > n[0] + 2.0, "a POLLEN dropped after it stays on top of it");
        }
    }

    /**
     * Strikes the piece in the pocket with a barrage of POLLEN thrown through the retrieval opening from every side
     * that is open, at different heights and speeds, the way a hand or an intake roller would knock at it.
     *
     * @return the number of throws that it took to knock it more than 4.5 in from the axis, or -1 if it never left
     */
    private static int barrage(Arena arena, int target) {
        double[][] directions = {{0, 1}, {0, -1}, {1, 1}, {1, -1}, {1, 0}};
        int throwNumber = 0;
        for (double[] direction : directions) {
            for (double height : new double[] {2.0, 2.2}) {
                for (double speed : new double[] {4.0, 6.0}) {
                    throwNumber++;
                    int hand = sweep(arena, direction[0], direction[1], height, speed);
                    arena.world.removePiece(hand);
                    arena.steps(120);
                    if (where(arena, target)[1] > 4.5) {
                        return throwNumber;
                    }
                }
            }
        }
        return -1;
    }

    @Test
    void aPollenInThePocketStaysUnderANudgeButComesOutOfTheRetrievalOpeningWhenKnockedHardEnough() {
        try (Arena arena = new Arena(3, Alliance.RED)) {
            arena.steps(120);
            emptyTheFlower(arena);
            int pollen = drop(arena, BiobuzzIds.KIND_POLLEN);
            assertTrue(where(arena, pollen)[0] < 1.6, "it sits in the pocket");

            int nudge = sweep(arena, 0, 1, 2.1, 1.0);
            arena.world.removePiece(nudge);
            arena.steps(240);
            assertTrue(where(arena, pollen)[1] < 2.0, "the lip of the bottom ring holds a POLLEN that is only nudged");

            assertTrue(barrage(arena, pollen) > 0, "a POLLEN in the pocket can be knocked out through the retrieval opening");
        }
    }

    @Test
    void aNectarInThePocketCannotBeKnockedOutOfTheRetrievalOpeningByTheSameBarrage() {
        try (Arena arena = new Arena(3, Alliance.RED)) {
            arena.steps(120);
            emptyTheFlower(arena);
            int nectar = arena.spawn(
                    BiobuzzIds.KIND_NECTAR_RED, new Vector3(in(SITE.axisX()), in(SITE.axisY()), in(2.0)), Vector3.ZERO);
            arena.steps(240);
            assertEquals(1.82, where(arena, nectar)[0], 0.15, "it lies in the pocket, which is not deep enough to hold it");

            assertEquals(-1, barrage(arena, nectar), "the NECTAR (3.62 in) does not fit past the lip under the 3.51 in opening");
            double[] n = where(arena, nectar);
            assertTrue(n[1] < 3.0 && n[0] < 4.0, "and it is still in the opening: " + n[1] + " in from the axis, " + n[0] + " in up");
        }
    }
}
