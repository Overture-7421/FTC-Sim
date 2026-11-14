package dev.ftcsim.game.decode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.field.Alliance;
import dev.ftcsim.contracts.field.MatchPhase;
import dev.ftcsim.contracts.field.Score;
import dev.ftcsim.contracts.snapshot.BodyState;
import dev.ftcsim.field.EntityIds;
import dev.ftcsim.physics.MecanumChassis;
import dev.ftcsim.physics.MecanumConfig;
import dev.ftcsim.physics.PhysicsWorld;
import dev.ftcsim.physics.Vector3;
import dev.ftcsim.physics.WheelPosition;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;

class DecodeScoringTest {

    private static Vector3 aboveGoal(Alliance goal, int n) {
        double y = -1.56 - 0.02 * (n % 4);
        return new Vector3(-1.62 + 0.03 * (n % 3), goal == Alliance.RED ? -y : y, 1.2);
    }

    private static Score.Line line(Score score, String label) {
        return score.lines().stream().filter(l -> l.label().equals(label)).findFirst().orElseThrow();
    }

    private static long nanos(Harness h) {
        return h.world.snapshot().simTimeNanos();
    }

    /** Puts the next artifact from the field into the goal, one after another, and returns the ids in the order they went in. */
    private static List<Integer> dropFieldArtifacts(Harness h, Alliance goal, int count, int firstId) {
        List<Integer> ids = new ArrayList<>();
        for (int n = 0; n < count; n++) {
            h.reshoot(firstId + n, aboveGoal(goal, n), Vector3.ZERO);
            ids.add(firstId + n);
            h.seconds(0.7);
        }
        return ids;
    }

    @Test
    void aBallDroppedIntoEitherGoalIsCountedOnceAndRidesTheRampToTheGate() {
        for (Alliance alliance : Alliance.values()) {
            try (Harness h = new Harness(1, alliance)) {
                h.seconds(0.5);
                int id = h.shootFromReserve(Kinds.PURPLE, aboveGoal(alliance, 0), Vector3.ZERO);
                h.seconds(3);

                assertEquals(1, line(h.run.score(), "TELEOP CLASSIFIED").count());
                assertEquals(0, line(h.run.score(), "TELEOP OVERFLOW").count());
                BodyState ball = h.body(id);
                Vector3 slot = RampGeometry.point(RampGeometry.FIRST_SLOT, 0, alliance == Alliance.RED);
                assertEquals(slot.x(), ball.x(), 0.003);
                assertEquals(slot.y(), ball.y(), 0.003);
                assertEquals(slot.z(), ball.z(), 0.003);
                // Nothing counts a second time while the ball waits at the gate.
                h.seconds(3);
                assertEquals(1, line(h.run.score(), "TELEOP CLASSIFIED").count());
            }
        }
    }

    @Test
    void nineBallsFillTheRampAndTheTenthOverflowsOverTheGateAndAwayFromTheRamp() {
        try (Harness h = new Harness(4, Alliance.BLUE)) {
            h.seconds(0.5);
            List<Integer> ids = dropFieldArtifacts(h, Alliance.BLUE, 9, 1000);
            h.seconds(3);
            for (int i = 0; i < 9; i++) {
                BodyState ball = h.body(ids.get(i));
                Vector3 slot = RampGeometry.point(RampGeometry.FIRST_SLOT - i * RampGeometry.SLOT, 0, false);
                assertEquals(slot.x(), ball.x(), 0.005, "ball " + i + " is in slot " + (i + 1));
                assertEquals(slot.z(), ball.z(), 0.005);
            }
            assertEquals(27, line(h.run.score(), "TELEOP CLASSIFIED").points());
            assertEquals(0, line(h.run.score(), "TELEOP OVERFLOW").count());

            h.reshoot(1009, aboveGoal(Alliance.BLUE, 9), Vector3.ZERO);
            h.seconds(2.8);
            Score score = h.run.score();
            assertEquals(9, line(score, "TELEOP CLASSIFIED").count());
            assertEquals(1, line(score, "TELEOP OVERFLOW").count());
            assertEquals(1, line(score, "TELEOP OVERFLOW").points());
            BodyState overflow = h.body(1009);
            assertTrue(overflow.x() > 0.3 && overflow.z() < 0.15, "the overflow ball rolled over the stack and left the ramp");
            assertEquals(-1.69, overflow.y(), 0.1, "it is in the lane along the wall");
            assertEquals(9, h.artifacts().stream().filter(DecodeScoringTest::isOnBlueRamp).count(), "the stack was not disturbed");
        }
    }

    @Test
    void patternPointsFollowTheOrderTheBallsArrivedInAndTheColourOfTheMotif() {
        try (Harness h = new Harness(11, Alliance.BLUE)) {
            h.seconds(0.5);
            Motif motif = Motif.valueOf(h.run.motif());
            Deque<Integer> purple = new ArrayDeque<>();
            Deque<Integer> green = new ArrayDeque<>();
            for (BodyState piece : h.artifacts()) {
                (piece.kind() == Kinds.GREEN ? green : purple).add(piece.entityId());
            }
            // The first six balls have the colour of their index and the last three do not.
            for (int index = 1; index <= 9; index++) {
                int wanted = motif.kindAtIndex(index);
                int send = index <= 6 ? wanted : (wanted == Kinds.GREEN ? Kinds.PURPLE : Kinds.GREEN);
                int id = (send == Kinds.GREEN ? green : purple).poll();
                h.reshoot(id, aboveGoal(Alliance.BLUE, index), Vector3.ZERO);
                h.seconds(0.7);
            }
            h.seconds(3);
            assertEquals(6, line(h.run.score(), "TELEOP PATTERN").count());
            assertEquals(12, line(h.run.score(), "TELEOP PATTERN").points());
        }
    }

    @Test
    void aBallThatBouncesOutOfTheGoalAndFallsBackInIsCountedOnce() {
        try (Harness h = new Harness(8, Alliance.BLUE)) {
            h.seconds(0.2);
            // Thrown straight up from inside the goal, it leaves through the open top and comes back down into it.
            h.reshoot(1000, new Vector3(-1.62, -1.62, 0.9), new Vector3(0, 0, 6));
            double highest = 0;
            for (int i = 0; i < 360; i++) {
                h.step(1);
                highest = Math.max(highest, h.body(1000).z());
            }
            assertTrue(highest > 2.0, "the ball flew out of the goal first");
            h.seconds(2);
            assertEquals(1, line(h.run.score(), "TELEOP CLASSIFIED").count());
            assertEquals(0, line(h.run.score(), "TELEOP OVERFLOW").count());
            assertTrue(isOnBlueRamp(h.body(1000)));
        }
    }

    @Test
    void aBallThatDidNotPassThroughTheGoalIsNotCountedAndBouncingShotsCountOncePerBall() {
        try (Harness h = new Harness(6, Alliance.BLUE)) {
            h.seconds(0.5);
            // Straight onto the top of the ramp from the side, without entering the goal.
            h.reshoot(1000, new Vector3(-1.15, -1.70, 0.7), Vector3.ZERO);
            h.seconds(1);
            assertEquals(0, line(h.run.score(), "TELEOP CLASSIFIED").count());
            assertEquals(0, line(h.run.score(), "TELEOP OVERFLOW").count());
            // It rolls down the ramp's bare floor and would knock the gate open, so it is taken away.
            h.world.removePiece(1000);

            // Shots from across the field, some into the open top, some into the backboards and walls first.
            double[][] from = {{-0.3, -0.9, 0.5}, {0.2, -0.4, 0.6}, {-0.8, -0.2, 0.5}, {0.6, -1.2, 0.5}, {-0.2, -1.5, 0.5}, {-1.0, 0.3, 0.6}};
            double[][] to = {{-1.55, -1.55, 1.15}, {-1.45, -1.65, 1.2}, {-1.65, -1.45, 1.15}, {-1.7, -1.7, 1.3}, {-1.6, -1.7, 1.2}, {-1.5, -1.6, 1.1}};
            double[] seconds = {0.5, 0.35, 0.3, 0.25, 0.4, 0.3};
            for (int i = 0; i < from.length; i++) {
                Vector3 start = new Vector3(from[i][0], from[i][1], from[i][2]);
                h.reshoot(1001 + i, start, ballistic(start, new Vector3(to[i][0], to[i][1], to[i][2]), seconds[i]));
                h.seconds(0.6);
            }
            h.seconds(6);

            Score score = h.run.score();
            int counted = line(score, "TELEOP CLASSIFIED").count() + line(score, "TELEOP OVERFLOW").count();
            long onTheRamp = h.artifacts().stream().filter(DecodeScoringTest::isOnBlueRamp).count();
            assertTrue(counted >= 4, "most of the shots went in, but only " + counted);
            assertEquals(onTheRamp, counted, "every ball on the ramp was counted once, and nothing else was");
            assertEquals(Layout.PIECE_COUNT - 1, h.run.artifactsAccountedFor(h.snapshot()), "no artifact appeared or vanished but the one taken away");
        }
    }

    /** The velocity that takes a ball from one point to another in the given time under gravity alone. */
    private static Vector3 ballistic(Vector3 from, Vector3 to, double seconds) {
        return new Vector3(
                (to.x() - from.x()) / seconds,
                (to.y() - from.y()) / seconds,
                (to.z() - from.z() + 0.5 * 9.81 * seconds * seconds) / seconds);
    }

    private static boolean isOnBlueRamp(BodyState ball) {
        return Math.abs(ball.y() + 1.69265) < 0.02 && ball.x() > -1.1 && ball.x() < 0.0 && ball.z() > 0.15;
    }

    @Test
    void aRobotThatPushesTheLeverHoldsTheGateOpenAndTheBallsRollOutInOrder() {
        try (Harness h = new Harness(1, Alliance.BLUE)) {
            h.seconds(0.5);
            List<Integer> ids = dropFieldArtifacts(h, Alliance.BLUE, 4, 1000);
            h.seconds(2);
            assertEquals(4, line(h.run.score(), "TELEOP CLASSIFIED").count());
            assertEquals(0.0, h.run.gateAngleRadians(Alliance.BLUE), 1e-3, "the gate is closed under gravity");

            MecanumChassis robot = h.world.addMecanumChassis(
                    EntityIds.CHASSIS, 1, MecanumConfig.generic18Inch(), new Vector3(0.18, -1.05, 0), -Math.PI / 2);
            h.seconds(0.5);
            for (WheelPosition wheel : WheelPosition.values()) {
                robot.setWheelCommand(wheel, 0.5);
            }
            double widest = 0;
            int[] leftAtStep = {-1, -1, -1, -1};
            for (int step = 0; step < 720; step++) {
                h.step(1);
                widest = Math.max(widest, h.run.gateAngleRadians(Alliance.BLUE));
                for (int i = 0; i < ids.size(); i++) {
                    if (leftAtStep[i] < 0 && h.body(ids.get(i)).x() > 0.06) {
                        leftAtStep[i] = step;
                    }
                }
                if (step == 240) {
                    for (WheelPosition wheel : WheelPosition.values()) {
                        robot.setWheelCommand(wheel, -0.6);
                    }
                }
            }
            assertTrue(widest > RampGeometry.GATE_OPEN_ANGLE, "the robot opened the gate to " + widest);
            assertTrue(leftAtStep[0] > 0 && leftAtStep[1] > leftAtStep[0], "the ball nearest the gate left first");
            assertTrue(leftAtStep[1] > 0, "and the next one followed while the gate was held open");

            h.seconds(2);
            assertEquals(0.0, h.run.gateAngleRadians(Alliance.BLUE), 0.02, "the gate closed again once the robot let go");
            // Whatever is left on the ramp is held by the closed gate in the first slots.
            List<BodyState> left = new ArrayList<>();
            for (int id : ids) {
                if (isOnBlueRamp(h.body(id))) {
                    left.add(h.body(id));
                }
            }
            for (int i = 0; i < left.size(); i++) {
                Vector3 slot = RampGeometry.point(RampGeometry.FIRST_SLOT - i * RampGeometry.SLOT, 0, false);
                assertEquals(slot.x(), left.get(i).x(), 0.005);
            }
            assertEquals(Layout.PIECE_COUNT, h.run.artifactsAccountedFor(h.snapshot()));
        }
    }

    @Test
    void autoAndTeleopArtifactsAreScoredApartAndTheEndOfTheMatchSettles() {
        try (Harness h = new Harness(2, Alliance.BLUE)) {
            h.world.addMecanumChassis(
                    EntityIds.CHASSIS, 1, MecanumConfig.generic18Inch(), new Vector3(0.5, 1.3, 0), 0);
            h.run.enterPhase(MatchPhase.AUTONOMOUS, nanos(h));
            dropFieldArtifacts(h, Alliance.BLUE, 2, 1000);
            h.seconds(2);
            Score auto = h.run.score();
            assertEquals(2, line(auto, "AUTO CLASSIFIED").count());
            assertEquals(6, line(auto, "AUTO CLASSIFIED").points());
            assertEquals(3, line(auto, "LEAVE").points(), "the robot is clear of every launch line");
            assertEquals(0, line(auto, "TELEOP CLASSIFIED").count());

            h.seconds(30);
            h.run.enterPhase(MatchPhase.TELEOP, nanos(h));
            h.reshoot(1002, aboveGoal(Alliance.BLUE, 2), Vector3.ZERO);
            h.seconds(2);
            Score teleop = h.run.score();
            assertEquals(2, line(teleop, "AUTO CLASSIFIED").count());
            assertEquals(1, line(teleop, "TELEOP CLASSIFIED").count());
            assertEquals(3, line(teleop, "LEAVE").points());
            assertEquals(line(auto, "AUTO PATTERN").count(), line(teleop, "AUTO PATTERN").count(), "latched at the start of TELEOP");

            // A ball is still on its way into the goal when the match ends: it counts, and then the score is final.
            h.reshoot(1003, aboveGoal(Alliance.BLUE, 3), Vector3.ZERO);
            h.seconds(0.4);
            h.run.enterPhase(MatchPhase.ENDED, nanos(h));
            assertFalse(h.run.isSettled());
            int stepsToSettle = 0;
            while (!h.run.isSettled() && stepsToSettle < 8 * PhysicsWorld.STEPS_PER_SECOND) {
                h.step(1);
                stepsToSettle++;
            }
            assertTrue(stepsToSettle < 3 * PhysicsWorld.STEPS_PER_SECOND,
                    "the pieces came to rest by themselves, not at the 5 s limit: " + stepsToSettle + " steps");
            Score end = h.run.score();
            assertEquals(2, line(end, "TELEOP CLASSIFIED").count());
            h.seconds(3);
            assertEquals(end, h.run.score());
        }
    }

    @Test
    void aWholeScenarioReplaysBitForBit() {
        String first = replay();
        System.out.println("replay " + first);
        assertEquals(first, replay());
    }

    // Balls into the goal, a robot that opens the gate and backs off, phases, a shot at the end.
    private static String replay() {
        try (Harness h = new Harness(21, Alliance.BLUE)) {
            MecanumChassis robot = h.world.addMecanumChassis(
                    EntityIds.CHASSIS, 1, MecanumConfig.generic18Inch(), new Vector3(0.18, -1.05, 0), -Math.PI / 2);
            h.seconds(0.5);
            h.run.enterPhase(MatchPhase.AUTONOMOUS, nanos(h));
            dropFieldArtifacts(h, Alliance.BLUE, 5, 1000);
            h.run.enterPhase(MatchPhase.TELEOP, nanos(h));
            for (WheelPosition wheel : WheelPosition.values()) {
                robot.setWheelCommand(wheel, 0.5);
            }
            h.seconds(1.2);
            for (WheelPosition wheel : WheelPosition.values()) {
                robot.setWheelCommand(wheel, -0.6);
            }
            h.reshoot(1006, aboveGoal(Alliance.BLUE, 6), Vector3.ZERO);
            h.seconds(2);
            h.run.perform(DecodeDescription.LOAD_GREEN);
            h.run.enterPhase(MatchPhase.ENDED, nanos(h));
            h.seconds(6);
            return h.world.stateHash() + " " + h.run.score();
        }
    }

    @Test
    void aHumanPlayerFeedsTheLoadingZoneFromTheReserveOnlyDuringTeleop() {
        try (Harness h = new Harness(3, Alliance.RED)) {
            h.run.enterPhase(MatchPhase.AUTONOMOUS, nanos(h));
            h.run.perform(DecodeDescription.LOAD_PURPLE);
            assertEquals(24, h.artifacts().size(), "people do not handle artifacts during AUTO");

            h.run.enterPhase(MatchPhase.TELEOP, nanos(h));
            h.run.perform(DecodeDescription.LOAD_PURPLE);
            h.run.perform(DecodeDescription.LOAD_GREEN);
            h.seconds(0.5);
            List<BodyState> artifacts = h.artifacts();
            assertEquals(26, artifacts.size());
            long fed = artifacts.stream().filter(b -> b.entityId() >= 1030).count();
            assertEquals(2, fed, "the red alliance area pieces are the ones that were fed");
            for (BodyState piece : artifacts) {
                if (piece.entityId() >= 1030) {
                    assertTrue(piece.x() > 1.2 && piece.y() < -1.2, "in the red loading zone, in the -Y corner at +X");
                }
            }
            assertThrows(IllegalArgumentException.class, () -> h.run.perform("shoot-the-moon"));
        }
    }

    @Test
    void artifactsThatLeaveTheFieldAreHandedBackToTheHumanPlayer() {
        try (Harness h = new Harness(3, Alliance.BLUE)) {
            h.seconds(0.2);
            int before = h.run.artifactsAccountedFor(h.snapshot());
            h.reshoot(1000, new Vector3(1.6, 0.0, 0.5), new Vector3(6, 0, 3));
            h.seconds(4);
            assertFalse(h.world.hasPiece(1000), "the piece was taken out of the world");
            assertEquals(before, h.run.artifactsAccountedFor(h.snapshot()), "and kept by the field");
            int fromReserve = 0;
            while (h.run.takeFromReserve(Kinds.GREEN).isPresent()) {
                fromReserve++;
            }
            assertEquals(3, fromReserve, "the green piece is in the blue human player's reserve with the other two");
        }
    }

    @Test
    void artifactsOverTheDepotScoreForTheOwnerWhoeverPutThemThere() {
        try (Harness h = new Harness(3, Alliance.BLUE)) {
            Vector3 normal = Dimensions.frontNormal();
            // Against the foot of the blue goal's front panel, on the depot tape; and one well away from it.
            h.reshoot(1000, new Vector3(Dimensions.FRONT_PANEL_X + 0.0677 * normal.x(), Dimensions.FRONT_PANEL_Y + 0.0677 * normal.y(), 0.1), Vector3.ZERO);
            h.reshoot(1001, new Vector3(Dimensions.FRONT_PANEL_X + 0.4 * normal.x(), Dimensions.FRONT_PANEL_Y + 0.4 * normal.y(), 0.1), Vector3.ZERO);
            h.seconds(2);
            assertEquals(1, line(h.run.score(), "DEPOT").count());
            assertEquals(1, line(h.run.score(), "DEPOT").points());
        }
    }

    @Test
    void theRobotScoresBaseByHowMuchOfItIsOnItsBaseZone() {
        int[] expectedPoints = {10, 5, 0};
        double[] x = {0.958, 1.17, 0.2};
        for (int i = 0; i < x.length; i++) {
            try (Harness h = new Harness(3, Alliance.BLUE)) {
                h.world.addMecanumChassis(
                        EntityIds.CHASSIS, 1, MecanumConfig.generic18Inch(), new Vector3(x[i], 0.839, 0), 0);
                h.seconds(1);
                Score score = h.run.score();
                int base = line(score, "BASE PARTIAL").points() + line(score, "BASE FULL").points();
                assertEquals(expectedPoints[i], base, "robot at x = " + x[i]);
            }
        }
    }
}
