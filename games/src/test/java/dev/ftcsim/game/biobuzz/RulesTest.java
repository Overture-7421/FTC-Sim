package dev.ftcsim.game.biobuzz;

import static dev.ftcsim.game.biobuzz.Solids.in;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.field.Alliance;
import dev.ftcsim.contracts.field.MatchPhase;
import dev.ftcsim.contracts.field.Score;
import dev.ftcsim.contracts.snapshot.BodyState;
import dev.ftcsim.contracts.world.BodyPose;
import dev.ftcsim.field.EntityIds;
import dev.ftcsim.physics.MecanumConfig;
import dev.ftcsim.physics.Vector3;
import org.junit.jupiter.api.Test;

/** The scoring rules of section 10.5 and the human rules of G426 and G427, played in real time on the whole field. */
class RulesTest {

    private static final int STEPS_PER_SECOND = 240;
    private static final int POLLEN = BiobuzzIds.KIND_POLLEN;
    private static final int NECTAR_RED = BiobuzzIds.KIND_NECTAR_RED;
    private static final int NECTAR_BLUE = BiobuzzIds.KIND_NECTAR_BLUE;
    private static final FlowerModel.Site SITE = FlowerModel.SITES.get(3);

    private static long nanos(double seconds) {
        return Math.round(seconds * 1e9);
    }

    private static Score.Line line(Arena arena, String label) {
        return arena.run.score().lines().stream().filter(l -> l.label().equals(label)).findFirst().orElseThrow();
    }

    private static void emptyFlower4(Arena arena) {
        for (int id = BiobuzzIds.POLLEN_FIRST + 12; id < BiobuzzIds.POLLEN_FIRST + 16; id++) {
            arena.world.removePiece(id);
        }
    }

    private static void dropIntoFlower4(Arena arena, int kind) {
        arena.spawn(kind, new Vector3(in(SITE.axisX()), in(SITE.axisY()), in(23.0)), Vector3.ZERO);
        arena.steps(360);
    }

    private static long piecesOfKind(Arena arena, int kind) {
        return arena.world.snapshot().bodies().stream().filter(b -> b.kind() == kind).count();
    }

    /** A red arena whose LOADING ZONES are cleared of POLLEN, with the robot set down where a start pose would not. */
    private static Arena arenaWithRobot(MecanumConfig config, double x, double y, double yaw) {
        return arenaWithRobot(new Arena(5, Alliance.RED), config, x, y, yaw);
    }

    private static Arena arenaWithRobot(Arena arena, MecanumConfig config, double x, double y, double yaw) {
        for (int id = BiobuzzIds.POLLEN_FIRST + 24; id < BiobuzzIds.POLLEN_FIRST + 40; id++) {
            if (arena.body(id) != null) {
                arena.world.removePiece(id);
            }
        }
        arena.world.addMecanumChassis(EntityIds.CHASSIS, 3, config, new Vector3(x, y, 0), yaw);
        return arena;
    }

    /** Tips the red HIVE by loading its upward audience CELL with eight POLLEN, and waits for it to land. */
    private static void tipRedHive(Arena arena) {
        Arena.Loader loader = arena.new Loader(true, true, true);
        for (int i = 0; i < 3; i++) {
            loader.place(POLLEN);
        }
        arena.steps(1500);
    }

    @Test
    void aFlowerBelongsToTheAllianceWhoseNectarIsOnTopAndTheBottomBonusToTheOneWhoseNectarIsLowest() {
        // From the bottom: a POLLEN in the pocket, red NECTAR, POLLEN, blue NECTAR. The blue one is on top.
        for (Alliance robot : Alliance.values()) {
            try (Arena arena = new Arena(5, robot)) {
                arena.steps(120);
                emptyFlower4(arena);
                dropIntoFlower4(arena, POLLEN);
                dropIntoFlower4(arena, NECTAR_RED);
                dropIntoFlower4(arena, POLLEN);
                dropIntoFlower4(arena, NECTAR_BLUE);
                arena.steps(600);

                // The POLLEN in the pocket is below the scoring volume; the other three pieces are in it.
                if (robot == Alliance.RED) {
                    assertEquals(0, line(arena, "POLLEN and NECTAR in owned FLOWER").count(), "blue owns it");
                    assertEquals(1, line(arena, "FLOWER bottom NECTAR bonus").count(), "but the lowest NECTAR is red");
                    assertEquals(5, line(arena, "FLOWER bottom NECTAR bonus").points());
                } else {
                    assertEquals(3, line(arena, "POLLEN and NECTAR in owned FLOWER").count(), "blue owns it");
                    assertEquals(6, line(arena, "POLLEN and NECTAR in owned FLOWER").points());
                    assertEquals(0, line(arena, "FLOWER bottom NECTAR bonus").count());
                }
                if (robot == Alliance.RED) {
                    dropIntoFlower4(arena, NECTAR_RED);
                    arena.steps(600);
                    assertEquals(4, line(arena, "POLLEN and NECTAR in owned FLOWER").count(), "a red NECTAR on top turns it red");
                    assertEquals(8, line(arena, "POLLEN and NECTAR in owned FLOWER").points());
                    assertEquals(1, line(arena, "FLOWER bottom NECTAR bonus").count());
                }
            }
        }
    }

    @Test
    void aGardenScoresEverythingPartlyInsideItForItsOwnAllianceWhateverTheColour() {
        try (Arena arena = new Arena(5, Alliance.RED)) {
            arena.steps(240);
            assertEquals(4, line(arena, "POLLEN and NECTAR in GARDEN").count(), "the four staged POLLEN");

            // A NECTAR of the other colour, and a POLLEN whose edge just reaches the tape, both count.
            arena.spawn(NECTAR_BLUE, new Vector3(in(68.4), in(-56.0), in(2.5)), Vector3.ZERO);
            arena.spawn(POLLEN, new Vector3(in(66.9), in(-49.0), in(2.0)), Vector3.ZERO);
            arena.steps(480);
            assertEquals(6, line(arena, "POLLEN and NECTAR in GARDEN").count());
            assertEquals(6, line(arena, "POLLEN and NECTAR in GARDEN").points());

            // One that stops 1 cm short of the 2 in wide tape does not.
            arena.spawn(POLLEN, new Vector3(in(66.3), in(-60.0), in(2.0)), Vector3.ZERO);
            arena.steps(480);
            assertEquals(6, line(arena, "POLLEN and NECTAR in GARDEN").count());
        }
        try (Arena arena = new Arena(5, Alliance.BLUE)) {
            arena.steps(240);
            assertEquals(4, line(arena, "POLLEN and NECTAR in GARDEN").count(), "the blue GARDEN has its own four");
        }
    }

    @Test
    void theRobotLeavesTheWallAndParksOnlyWhenItIsAwayFromEveryWallAndPartlyInItsLoadingZone() {
        MecanumConfig config = MecanumConfig.generic18Inch();
        double halfLength = config.lengthM() / 2;
        double zoneX = in(-35.255);

        try (Arena arena = arenaWithRobot(
                config, zoneX, -(FieldGeometry.HALF_M - 0.01 - halfLength), Math.PI / 2)) {
            arena.steps(240);
            assertEquals(1, line(arena, "LEAVE").count(), "a centimetre off the wall in practice counts at once");
            assertEquals(1, line(arena, "TELEOP PARK").count(), "its rear half is in the LOADING ZONE");
        }
        try (Arena arena = arenaWithRobot(
                config, zoneX, -(FieldGeometry.HALF_M - 0.001 - halfLength), Math.PI / 2)) {
            arena.steps(240);
            assertEquals(0, line(arena, "LEAVE").count(), "touching the wall is not leaving it");
            assertEquals(1, line(arena, "TELEOP PARK").count());
        }
        try (Arena arena = arenaWithRobot(config, 0, 0, 0.3)) {
            arena.steps(240);
            assertEquals(1, line(arena, "LEAVE").count());
            assertEquals(0, line(arena, "TELEOP PARK").count(), "the middle of the field is not the LOADING ZONE");
        }
        try (Arena arena = arenaWithRobot(config, in(35.255), FieldGeometry.HALF_M - 0.01 - halfLength, -Math.PI / 2)) {
            arena.steps(240);
            assertEquals(0, line(arena, "TELEOP PARK").count(), "the other alliance's LOADING ZONE does not count");
        }
    }

    @Test
    void theOutlineOfTheRobotThatTheSetupGivesDecidesWhetherItIsPartlyInTheLoadingZone() {
        MecanumConfig config = MecanumConfig.generic18Inch();
        double y = -(FieldGeometry.HALF_M - 0.01 - config.lengthM() / 2);
        // The zone ends at x = -23.91 in. A robot centred at -16 in reaches it with 18 in, but not with 12 in.
        try (Arena arena = arenaWithRobot(new Arena(5, Alliance.RED, in(18.0)), config, in(-16.0), y, Math.PI / 2)) {
            arena.steps(240);
            assertEquals(1, line(arena, "TELEOP PARK").count());
        }
        try (Arena arena = arenaWithRobot(new Arena(5, Alliance.RED, in(12.0)), config, in(-16.0), y, Math.PI / 2)) {
            arena.steps(240);
            assertEquals(0, line(arena, "TELEOP PARK").count());
        }
    }

    @Test
    void leaveAndAutoParkAreJudgedWhereTheRobotIsAtTheEndOfAutoAndTipsDuringTheTransitionCountForAuto() {
        MecanumConfig config = MecanumConfig.generic18Inch();
        try (Arena arena = arenaWithRobot(
                config, in(-35.255), -(FieldGeometry.HALF_M - 0.01 - config.lengthM() / 2), Math.PI / 2)) {
            arena.run.enterPhase(MatchPhase.AUTONOMOUS, 0);
            arena.steps(10 * STEPS_PER_SECOND);
            assertEquals(0, line(arena, "LEAVE").count(), "not assessed before the end of AUTO");
            tipRedHive(arena);
            assertEquals(1, line(arena, "HIVE TIP in AUTO").count());
            assertTrue(arena.world.stepIndex() < 30 * STEPS_PER_SECOND);

            arena.steps((int) (31L * STEPS_PER_SECOND - arena.world.stepIndex()));
            assertEquals(1, line(arena, "LEAVE").count(), "AUTO ended at 30 s with the robot clear of the wall");
            assertEquals(3, line(arena, "LEAVE").points());
            assertEquals(1, line(arena, "AUTO PARK").count(), "and partly in its LOADING ZONE");
            assertEquals(5, line(arena, "AUTO PARK").points());

            // The second TIP is completed in the 8 s between AUTO and TELEOP, so it is assessed as part of AUTO.
            Arena.Loader scoringCell = arena.new Loader(true, false, false);
            for (int i = 0; i < 7; i++) {
                scoringCell.place(POLLEN);
            }
            scoringCell.toss(POLLEN, 1.5, -4.0);
            while (arena.run.tips().size() < 2 && arena.world.stepIndex() < 38 * STEPS_PER_SECOND) {
                arena.steps(10);
            }
            assertEquals(2, arena.run.tips().size());
            long secondTipAt = arena.run.tips().get(1).timeNanos();
            assertTrue(secondTipAt > nanos(30) && secondTipAt < nanos(38), "the second TIP completed during the transition");
            assertEquals(2, line(arena, "HIVE TIP in AUTO").count());

            arena.steps((int) (38L * STEPS_PER_SECOND - arena.world.stepIndex()));
            arena.run.enterPhase(MatchPhase.TELEOP, arena.world.snapshot().simTimeNanos());
            Arena.Loader audienceCell = arena.new Loader(true, true, false);
            for (int i = 0; i < 7; i++) {
                audienceCell.place(POLLEN);
            }
            audienceCell.toss(POLLEN, 1.5, 4.0);
            arena.steps(1500);
            assertEquals(2, line(arena, "HIVE TIP in AUTO").count());
            assertEquals(1, line(arena, "HIVE TIP in TELEOP").count());
            assertEquals(MatchPhase.AUTONOMOUS, arena.run.tips().get(1).phase());
            assertEquals(MatchPhase.TELEOP, arena.run.tips().get(2).phase());
        }
    }

    @Test
    void theMatchEndsAfterThirtyEightAndOneHundredTwentySecondsAndTheFinalScoreFreezesWhenEverythingIsAtRest() {
        try (Arena arena = new Arena(5, Alliance.RED)) {
            arena.run.enterPhase(MatchPhase.AUTONOMOUS, 0);
            arena.steps(38 * STEPS_PER_SECOND);
            arena.run.enterPhase(MatchPhase.TELEOP, arena.world.snapshot().simTimeNanos());
            assertFalse(arena.run.oneMinuteLeft());
            arena.steps(59 * STEPS_PER_SECOND);
            assertFalse(arena.run.oneMinuteLeft(), "59 s into TELEOP there are 61 s left");
            arena.steps(2 * STEPS_PER_SECOND);
            assertTrue(arena.run.oneMinuteLeft());

            arena.steps(58 * STEPS_PER_SECOND);
            assertEquals(MatchPhase.TELEOP, arena.run.phase());
            assertFalse(arena.run.isFinal());
            arena.steps(2 * STEPS_PER_SECOND);
            assertEquals(MatchPhase.ENDED, arena.run.phase(), "the buzzer at 120 s of TELEOP");
            arena.steps(2 * STEPS_PER_SECOND);
            assertTrue(arena.run.isFinal(), "everything has come to rest");

            Score frozen = arena.run.score();
            arena.spawn(POLLEN, new Vector3(in(69.0), in(-60.0), in(2.0)), Vector3.ZERO);
            arena.steps(240);
            assertEquals(frozen, arena.run.score(), "pieces that arrive after the final assessment are not counted");
        }
    }

    @Test
    void humansEnterNectarOncePerTipAndFreelyInTheLastMinuteAndRunOutAfterFive() {
        try (Arena arena = new Arena(5, Alliance.RED)) {
            arena.run.enterPhase(MatchPhase.AUTONOMOUS, 0);
            assertEquals(3, piecesOfKind(arena, NECTAR_RED));
            arena.run.perform(BiobuzzField.LOAD_NECTAR);
            assertEquals(3, piecesOfKind(arena, NECTAR_RED), "no tip yet, so no NECTAR may be entered");

            tipRedHive(arena);
            arena.run.perform(BiobuzzField.LOAD_NECTAR);
            assertEquals(4, piecesOfKind(arena, NECTAR_RED), "one tip, one NECTAR");
            assertEquals(4, arena.run.piecesInReserve(NECTAR_RED));
            arena.run.perform(BiobuzzField.LOAD_NECTAR);
            assertEquals(4, piecesOfKind(arena, NECTAR_RED), "the allowance is used up");

            arena.run.perform(BiobuzzField.LOAD_OPPONENT_NECTAR);
            assertEquals(3, piecesOfKind(arena, NECTAR_BLUE), "the blue HIVE has not been tipped, so blue may not enter any");

            arena.run.enterPhase(MatchPhase.TELEOP, arena.world.snapshot().simTimeNanos());
            arena.steps(61 * STEPS_PER_SECOND);
            assertTrue(arena.run.oneMinuteLeft());
            for (int i = 0; i < 6; i++) {
                arena.run.perform(BiobuzzField.LOAD_NECTAR);
            }
            assertEquals(8, piecesOfKind(arena, NECTAR_RED), "the four that were left, and no more");
            assertEquals(0, arena.run.piecesInReserve(NECTAR_RED));
            arena.run.perform(BiobuzzField.LOAD_OPPONENT_NECTAR);
            assertEquals(4, piecesOfKind(arena, NECTAR_BLUE), "in the last minute the other alliance may enter too");
        }
    }

    @Test
    void inPracticeHumansMayEnterNectarAtOnceUntilTheirFiveAreGone() {
        try (Arena arena = new Arena(5, Alliance.BLUE)) {
            for (int i = 0; i < 7; i++) {
                arena.run.perform(BiobuzzField.LOAD_NECTAR);
            }
            assertEquals(8, piecesOfKind(arena, NECTAR_BLUE));
            assertEquals(0, arena.run.piecesInReserve(NECTAR_BLUE));
            assertEquals(5, arena.run.piecesInReserve(NECTAR_RED));
        }
    }

    @Test
    void escapedPollenReturnsAutomaticallyWhileEscapedNectarGoesBackToItsHuman() {
        try (Arena arena = new Arena(5, Alliance.RED)) {
            arena.run.enterPhase(MatchPhase.AUTONOMOUS, 0);
            long pollenBefore = piecesOfKind(arena, POLLEN);
            arena.spawn(POLLEN, new Vector3(0, -1.5, 0.3), new Vector3(0, -5.0, 3.0));
            arena.spawn(NECTAR_RED, new Vector3(0.5, -1.5, 0.3), new Vector3(0, -5.0, 3.0));
            arena.steps(2 * STEPS_PER_SECOND);

            assertEquals(pollenBefore + 1, piecesOfKind(arena, POLLEN), "the POLLEN returned without a human action");
            assertEquals(6, arena.run.piecesInReserve(NECTAR_RED), "the NECTAR went back to its humans, who now hold six");

            arena.run.perform(BiobuzzField.LOAD_NECTAR);
            assertEquals(4, piecesOfKind(arena, NECTAR_RED), "a NECTAR that left the field may be entered again whatever the tips");
        }
    }

    @Test
    void escapedPollenReturnsAtTheSameWallOrCornerWithoutLosingOrDuplicatingPieces() {
        double inside = FieldGeometry.HALF_M - 0.20;
        double[][] exits = {
                {2, 0.45, 0.5, inside, 0.45},
                {-2, -0.45, 0.5, -inside, -0.45},
                {0.45, 2, 0.5, 0.45, inside},
                {-0.45, -2, 0.5, -0.45, -inside},
                {2, -2, 0.5, inside, -inside},
                {0.6, 0.5, -0.3, 0.6, 0.5}
        };
        for (MatchPhase phase : MatchPhase.values()) {
            try (Arena arena = new Arena(5, Alliance.RED)) {
                arena.run.enterPhase(phase, 0);
                long before = piecesOfKind(arena, POLLEN);
                int[] ids = new int[exits.length];
                for (int i = 0; i < exits.length; i++) {
                    double[] exit = exits[i];
                    ids[i] = arena.spawn(POLLEN, new Vector3(exit[0], exit[1], exit[2]), Vector3.ZERO);
                }
                arena.steps(1);
                for (int i = 0; i < exits.length; i++) {
                    BodyState returned = arena.body(ids[i]);
                    assertNotNull(returned, phase + ": POLLEN " + ids[i] + " returns automatically");
                    assertEquals(POLLEN, returned.kind());
                    assertEquals(exits[i][3], returned.x(), 1e-5, "returns on the same side in X");
                    assertEquals(exits[i][4], returned.y(), 1e-5, "returns on the same side in Y");
                    assertEquals(0.25, returned.z(), 1e-5, "drops safely inside the wall");
                    BodyPose pose = arena.world.bodyPose(ids[i]);
                    assertEquals(0, pose.vx(), 1e-5);
                    assertEquals(0, pose.vy(), 1e-5);
                    assertEquals(0, pose.vz(), 1e-5);
                }
                arena.steps(2 * STEPS_PER_SECOND);
                assertEquals(before + exits.length, piecesOfKind(arena, POLLEN), "each escaped piece returns exactly once");
            }
        }
    }
}
