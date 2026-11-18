package dev.ftcsim.game.biobuzz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.field.Alliance;
import dev.ftcsim.contracts.field.MatchPhase;
import dev.ftcsim.physics.Vector3;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The HIVE acceptance tests of the Field Acceptance Checklist (2.3 to 2.6) and of the calibration table of the Event
 * Field Setup Guide (12.3), played on both HIVES of the whole field: what tips them and what does not.
 */
class HiveTest {

    private static final int POLLEN = BiobuzzIds.KIND_POLLEN;

    private record Scenario(String name, boolean keepsStagedNectar, boolean tips, int piecesInCell, Consumer<Arena.Loader> load) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static void pollen(Arena.Loader loader, int count) {
        for (int i = 0; i < count; i++) {
            loader.place(POLLEN);
        }
    }

    private static Stream<Arguments> scenarios() {
        // The first row holds seven POLLEN across the 20 in CELL; the next one lies in the cleft between two of them.
        Scenario[] all = {
            new Scenario("checklist 2.5: seven POLLEN set down: no tip", false, false, 7, l -> pollen(l, 7)),
            new Scenario("checklist 2.6: an eighth POLLEN tossed in: tip", false, true, 0, l -> {
                pollen(l, 7);
                l.toss(POLLEN, 1.5, 4.0);
            }),
            new Scenario("checklist 2.3: three NECTAR and two POLLEN set down: no tip", true, false, 5, l -> pollen(l, 2)),
            new Scenario("checklist 2.4: a third POLLEN tossed in: tip", true, true, 0, l -> {
                pollen(l, 2);
                l.toss(POLLEN, 1.5, 4.0);
            }),
            new Scenario("setup guide: a seventh POLLEN tossed on six: no tip", false, false, 7, l -> {
                pollen(l, 6);
                l.toss(POLLEN, 1.5, 4.0);
            }),
            new Scenario("setup guide: a second POLLEN tossed on three NECTAR and one: no tip", true, false, 5, l -> {
                pollen(l, 1);
                l.toss(POLLEN, 1.5, 4.0);
            }),
            new Scenario("setup guide: an eighth POLLEN set down gently: tip", false, true, 0, l -> {
                pollen(l, 7);
                l.placeOnTop(POLLEN, l.cleftAbove(1));
            }),
            new Scenario("setup guide: a third POLLEN set down gently on three NECTAR and two: tip", true, true, 0, l -> pollen(l, 3)),
        };
        return Stream.of(all).flatMap(s -> Stream.of(Arguments.of(s, true), Arguments.of(s, false)));
    }

    @ParameterizedTest(name = "{0} (red HIVE: {1})")
    @MethodSource("scenarios")
    void aHiveTipsOnTheLoadsTheChecklistSaysAndOnNoOther(Scenario scenario, boolean red) {
        try (Arena arena = new Arena(7, red ? Alliance.RED : Alliance.BLUE)) {
            arena.steps(240);
            Arena.Loader loader = arena.new Loader(red, red, scenario.keepsStagedNectar());
            scenario.load().accept(loader);
            arena.steps(720);

            long tips = arena.run.tips().stream().filter(t -> (t.hive() == Alliance.RED) == red).count();
            double degrees = arena.hiveDegrees(red);
            double home = red ? -30.0 : 30.0;
            if (scenario.tips()) {
                assertEquals(1, tips, "tips");
                assertEquals(-home, degrees, 1.5, "the HIVE rests on the other stop");
            } else {
                assertEquals(0, tips, "tips");
                assertEquals(home, degrees, 2.0, "the HIVE stays on its stop");
                assertEquals(scenario.piecesInCell(), arena.run.score().lines().get(4).count(), "pieces held by the CELL");
            }
        }
    }

    @Test
    void aTippedCellSpillsItsLoadOntoTheFloorBesideTheHiveAndTheTipCountsOnce() {
        try (Arena arena = new Arena(7, Alliance.RED)) {
            arena.steps(240);
            Arena.Loader loader = arena.new Loader(true, true, false);
            pollen(loader, 7);
            loader.toss(POLLEN, 1.5, 4.0);
            arena.steps(1200);

            assertEquals(1, arena.run.tips().size());
            BiobuzzRun.TipEvent tip = arena.run.tips().get(0);
            assertEquals(Alliance.RED, tip.hive());
            assertEquals(MatchPhase.PRACTICE, tip.phase());
            assertEquals(1, tip.sequence());
            int spilled = 0;
            for (int id = 5000; id < 5008; id++) {
                var piece = arena.body(id);
                assertTrue(piece.z() < 0.1, "piece " + id + " is on the floor, not in the CELL or hanging: " + piece.z());
                assertTrue(piece.x() > 0.2, "piece " + id + " fell on the audience side, where the CELL pointed: " + piece.x());
                spilled++;
            }
            assertEquals(8, spilled);
            assertEquals(0, arena.run.score().lines().get(4).count(), "nothing is left in the CELL that is now facing down");

            arena.steps(2400);
            assertEquals(1, arena.run.tips().size(), "pieces settling on the floor do not tip anything again");
            assertEquals(1, arena.run.score().lines().get(3).count());
            assertEquals(20, arena.run.score().lines().get(3).points());
        }
    }

    @Test
    void theOtherCellOfATippedHiveTipsItBackAndEachTipIsRecordedInOrder() {
        try (Arena arena = new Arena(7, Alliance.RED)) {
            arena.steps(240);
            Arena.Loader first = arena.new Loader(true, true, false);
            pollen(first, 7);
            first.toss(POLLEN, 1.5, 4.0);
            arena.steps(1500);
            assertEquals(30.0, arena.hiveDegrees(true), 1.0);

            Arena.Loader second = arena.new Loader(true, false, false);
            pollen(second, 7);
            second.toss(POLLEN, 1.5, -4.0);
            arena.steps(1500);

            assertEquals(-30.0, arena.hiveDegrees(true), 1.0, "the scoring CELL tipped down and the audience CELL is up again");
            assertEquals(2, arena.run.tips().size());
            assertEquals(1, arena.run.tips().get(0).sequence());
            assertEquals(2, arena.run.tips().get(1).sequence());
            assertTrue(arena.run.tips().get(1).stepIndex() > arena.run.tips().get(0).stepIndex());
        }
    }

    @Test
    void theOtherAlliancesHiveTippingIsNotTheRobotsAlliancesPoints() {
        try (Arena arena = new Arena(7, Alliance.RED)) {
            arena.steps(240);
            Arena.Loader loader = arena.new Loader(false, false, false);
            pollen(loader, 7);
            loader.toss(POLLEN, 1.5, -4.0);
            arena.steps(1500);

            assertEquals(1, arena.run.tips().size());
            assertEquals(Alliance.BLUE, arena.run.tips().get(0).hive());
            assertEquals(0, arena.run.score().lines().get(3).count());
        }
    }

    /** Lobs {@code count} POLLEN from the robot's muzzle height, from {@code (x, y)}, at the middle of a CELL's mouth, 69 degrees up. */
    private static void lob(Arena arena, double x, double y, boolean audienceCell, int count) {
        double angle = Math.toRadians(arena.hiveDegrees(true));
        double side = audienceCell ? 1 : -1;
        Vector3 aim = HiveModel.toField(true, angle, side * Solids.in(19.46), 0.0, Solids.in(5.04));
        double bearing = Math.atan2(aim.y() - y, aim.x() - x);
        double muzzleX = x + 0.2 * Math.cos(bearing);
        double muzzleY = y + 0.2 * Math.sin(bearing);
        double distance = Math.hypot(aim.x() - muzzleX, aim.y() - muzzleY);
        double elevation = Math.toRadians(69.0);
        double room = distance * Math.tan(elevation) - (aim.z() - 0.4074);
        double speed = Math.sqrt(9.81 * distance * distance / (2.0 * Math.cos(elevation) * Math.cos(elevation) * room));
        Vector3 velocity = new Vector3(
                speed * Math.cos(elevation) * Math.cos(bearing), speed * Math.cos(elevation) * Math.sin(bearing), speed * Math.sin(elevation));
        for (int i = 0; i < count; i++) {
            arena.spawn(POLLEN, new Vector3(muzzleX, muzzleY, 0.4074), velocity);
            arena.steps(96);
        }
        arena.steps(600);
    }

    @Test
    void threePollenLobbedSteeplyIntoTheMouthOfTheCellThatFacesUpTipTheHiveAndTheSameLobAtTheCellFacingDownDoesNot() {
        try (Arena arena = new Arena(7, Alliance.RED)) {
            arena.steps(240);
            lob(arena, 1.4, -1.0, true, 3);
            assertEquals(1, arena.run.tips().size(), "the three NECTAR the CELL starts with and three POLLEN are more than it holds");
            assertEquals(Alliance.RED, arena.run.tips().get(0).hive());
        }
        try (Arena arena = new Arena(7, Alliance.RED)) {
            arena.steps(240);
            lob(arena, 1.4, -1.0, false, 3);
            assertEquals(0, arena.run.tips().size(), "a CELL that faces down takes nothing");
        }
    }
}
