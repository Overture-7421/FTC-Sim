package dev.ftcsim.game.decode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.field.Alliance;
import dev.ftcsim.contracts.field.FieldDescription;
import dev.ftcsim.contracts.field.FieldTag;
import dev.ftcsim.contracts.field.Pose3;
import dev.ftcsim.contracts.field.Scenery;
import dev.ftcsim.contracts.field.Shape;
import dev.ftcsim.contracts.field.StartPose;
import dev.ftcsim.contracts.snapshot.BodyState;
import dev.ftcsim.contracts.world.BodyPose;
import dev.ftcsim.field.EntityIds;
import dev.ftcsim.physics.MecanumChassis;
import dev.ftcsim.physics.MecanumConfig;
import dev.ftcsim.physics.PhysicsWorld;
import dev.ftcsim.physics.Vector3;
import dev.ftcsim.physics.WheelPosition;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class DecodeFieldTest {

    private static final FieldDescription DESCRIPTION = new DecodeField().description();

    private static List<String> reserveAndLayoutAfterAShot(long seed) {
        try (Harness h = new Harness(seed, Alliance.BLUE)) {
            h.shootFromReserve(Kinds.PURPLE, new Vector3(-1.6, -1.55, 1.2), Vector3.ZERO);
            return h.artifacts().stream()
                    .map(b -> String.format("%d kind %d at %.3f %.3f", b.entityId(), b.kind(), b.x(), b.y()))
                    .toList();
        }
    }

    private static long count(List<BodyState> bodies, int kind) {
        return bodies.stream().filter(b -> b.kind() == kind).count();
    }

    private static Orientation orientation(Pose3 pose) {
        return new Orientation(pose.qw(), pose.qx(), pose.qy(), pose.qz());
    }

    @Test
    void theSameSeedBuildsTheSameFieldAndAnotherSeedDiffers() {
        List<String> first = reserveAndLayoutAfterAShot(7);
        assertEquals(first, reserveAndLayoutAfterAShot(7));
        assertNotEquals(first, reserveAndLayoutAfterAShot(8));
    }

    @Test
    void thirtySixArtifactsOfTwentyFourPurpleAndTwelveGreenAreAccountedFor() {
        try (Harness h = new Harness(3, Alliance.RED)) {
            List<BodyState> onField = h.artifacts();
            assertEquals(24, onField.size());
            assertEquals(16, count(onField, Kinds.PURPLE));
            assertEquals(8, count(onField, Kinds.GREEN));
            assertEquals(Layout.PIECE_COUNT, h.run.artifactsAccountedFor(h.snapshot()));

            // The robot's alliance keeps 4 purple and 2 green artifacts in its alliance area, which complete the 24 and 12.
            Set<Integer> ids = new HashSet<>(onField.stream().map(BodyState::entityId).toList());
            for (int kind : new int[] {Kinds.PURPLE, Kinds.PURPLE, Kinds.PURPLE, Kinds.PURPLE, Kinds.GREEN, Kinds.GREEN}) {
                int id = h.run.takeFromReserve(kind).orElseThrow();
                assertTrue(ids.add(id), "each artifact has its own id");
            }
            assertTrue(h.run.takeFromReserve(Kinds.PURPLE).isEmpty());
            assertTrue(h.run.takeFromReserve(Kinds.GREEN).isEmpty());
            assertTrue(Collections.min(ids) >= EntityIds.FIELD_BASE);
        }
    }

    @Test
    void artifactsStartOnTheSpikeMarksAndInTheLoadingZonesInTheOfficialColours() {
        try (Harness h = new Harness(5, Alliance.BLUE)) {
            // Spike mark nearest the audience on the +Y side, from the middle of the field outwards: GPP.
            int[] kinds = {Kinds.GREEN, Kinds.PURPLE, Kinds.PURPLE};
            double[] y = {1.073, 1.200, 1.327};
            for (int i = 0; i < 3; i++) {
                BodyState ball = h.body(1000 + i);
                assertEquals(kinds[i], ball.kind());
                assertEquals(0.8961, ball.x(), 0.005);
                assertEquals(y[i], ball.y(), 0.005);
            }
            // Loading zone in the +Y corner: PGP along the perimeter.
            int[] loading = {Kinds.PURPLE, Kinds.GREEN, Kinds.PURPLE};
            for (int i = 0; i < 3; i++) {
                BodyState ball = h.body(1018 + i);
                assertEquals(loading[i], ball.kind());
                assertEquals(1.461 + 0.127 * i, ball.x(), 0.005);
                assertEquals(1.7215, ball.y(), 0.005);
            }
        }
    }

    @Test
    void theGoalTagsSitOnTheOutsideOfTheGoalFrontPanels() {
        for (int tagId : new int[] {20, 24}) {
            FieldTag tag = DESCRIPTION.tags().stream().filter(t -> t.id() == tagId).findFirst().orElseThrow();
            assertEquals(-1, tag.hostEntityId());
            String name = tagId == 20 ? "blue-goal-front-panel" : "red-goal-front-panel";
            Scenery panel = DESCRIPTION.scenery().stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow();
            Shape.Box box = (Shape.Box) panel.part().shape();
            Orientation rotation = orientation(panel.pose());
            Vector3 fromPanel = new Vector3(tag.local().x() - panel.pose().x(), tag.local().y() - panel.pose().y(), tag.local().z() - panel.pose().z());
            double along = fromPanel.dot(rotation.rotate(Vector3.X));
            double across = fromPanel.dot(rotation.rotate(Vector3.Y));
            double up = fromPanel.dot(rotation.rotate(Vector3.Z));
            double half = 0.5 * tag.sizeMeters();
            assertEquals(box.halfY(), Math.abs(across), 0.003, "the tag lies on a face of the panel");
            assertTrue(Math.abs(along) + half <= box.halfX(), "the tag is within the width of the panel");
            assertTrue(Math.abs(up) + half <= box.halfZ(), "the tag is within the height of the panel");
            Vector3 facing = orientation(tag.local()).rotate(Vector3.Z);
            assertTrue(facing.dot(fromPanel) > 0, "the tag faces away from the panel");
            assertEquals(0.7493, tag.local().z(), 1e-4);
            assertEquals(-1.4827, tag.local().x(), 1e-3);
            assertEquals(tagId == 20 ? -1.4133 : 1.4133, tag.local().y(), 1e-3);
        }
    }

    @Test
    void theObeliskTurnsTheTagOfThePatternTowardsTheFieldWhateverTheSeed() {
        Set<Integer> shown = new HashSet<>();
        for (long seed = 0; seed < 12; seed++) {
            try (Harness h = new Harness(seed, Alliance.BLUE)) {
                BodyState obelisk = h.body(Obelisk.ENTITY_ID);
                Orientation host = new Orientation(obelisk.qw(), obelisk.qx(), obelisk.qy(), obelisk.qz());
                List<Integer> facingField = new ArrayList<>();
                for (FieldTag tag : DESCRIPTION.tags()) {
                    if (tag.hostEntityId() == Obelisk.ENTITY_ID) {
                        Vector3 normal = host.rotate(orientation(tag.local()).rotate(Vector3.Z));
                        if (normal.x() > 0.99) {
                            facingField.add(tag.id());
                        }
                    }
                }
                assertEquals(List.of(Motif.valueOf(h.run.motif()).tagId()), facingField);
                shown.addAll(facingField);
            }
        }
        assertEquals(Set.of(21, 22, 23), shown);
    }

    @Test
    void aRobotStandsStillAtEveryStartPose() {
        for (StartPose pose : DESCRIPTION.startPoses()) {
            Alliance alliance = pose.name().startsWith("BLUE") ? Alliance.BLUE : Alliance.RED;
            try (Harness h = new Harness(2, alliance)) {
                h.world.addMecanumChassis(
                        EntityIds.CHASSIS, 1, MecanumConfig.generic18Inch(), new Vector3(pose.x(), pose.y(), 0), pose.headingRadians());
                h.seconds(1.5);
                BodyState robot = h.body(EntityIds.CHASSIS);
                assertEquals(pose.x(), robot.x(), 0.02, pose.name());
                assertEquals(pose.y(), robot.y(), 0.02, pose.name());
                assertEquals(alliance == Alliance.BLUE, pose.y() < 0, pose.name() + " is on its alliance's half");
                assertFalse(Math.abs(pose.x()) > Dimensions.WALL || Math.abs(pose.y()) > Dimensions.WALL);
            }
        }
    }

    @Test
    void aStepWithTheWholeFieldInMotionCostsFarLessThanTheBudgetOf4Point17Milliseconds() {
        try (Harness h = new Harness(1, Alliance.BLUE)) {
            MecanumChassis robot = h.world.addMecanumChassis(
                    EntityIds.CHASSIS, 1, MecanumConfig.generic18Inch(), new Vector3(1.5, -0.3, 0), Math.PI);
            for (WheelPosition wheel : WheelPosition.values()) {
                robot.setWheelCommand(wheel, 0.6);
            }
            h.seconds(1);
            // Every artifact on the field is kicked into motion, and the reserve is shot at the goal.
            SplittableRandom random = new SplittableRandom(9);
            for (BodyState piece : h.artifacts()) {
                double heading = random.nextDouble() * 2 * Math.PI;
                h.reshoot(piece.entityId(), new Vector3(piece.x(), piece.y(), 0.3), new Vector3(3 * Math.cos(heading), 3 * Math.sin(heading), 1));
            }
            for (int n = 0; n < 6; n++) {
                h.shootFromReserve(n % 3 == 2 ? Kinds.GREEN : Kinds.PURPLE, new Vector3(-1.62 + 0.03 * (n % 3), -1.56, 1.2 + 0.2 * n), Vector3.ZERO);
            }
            int steps = 2400;
            long[] nanos = new long[steps];
            for (int i = 0; i < steps; i++) {
                long start = System.nanoTime();
                h.world.step();
                h.run.afterStep(h.world.snapshot());
                nanos[i] = System.nanoTime() - start;
            }
            long[] busy = Arrays.copyOfRange(nanos, 0, PhysicsWorld.STEPS_PER_SECOND);
            Arrays.sort(busy);
            Arrays.sort(nanos);
            double busyP50 = busy[busy.length / 2] / 1e6;
            double busyP99 = busy[busy.length * 99 / 100] / 1e6;
            System.out.printf("DECODE field, step + afterStep + snapshot, first second after kicking 36 artifacts: "
                    + "p50 %.3f ms, p99 %.3f ms, max %.3f ms; over 10 s: p50 %.3f ms, p99 %.3f ms, max %.3f ms%n",
                    busyP50, busyP99, busy[busy.length - 1] / 1e6,
                    nanos[steps / 2] / 1e6, nanos[steps * 99 / 100] / 1e6, nanos[steps - 1] / 1e6);
            assertTrue(busyP50 < 2.0, "median step cost " + busyP50 + " ms");
            assertTrue(busyP99 < 4.17, "99th percentile step cost " + busyP99 + " ms");
        }
    }

    @Test
    void anArtifactRolledAcrossTheTilesComesToRestAndStaysThere() {
        // Without rolling resistance the engine leaves such a ball crawling at 0.1 to 0.25 m/s for the whole match.
        double[] speeds = {0.3, 0.6, 1.0, 2.0, 4.0};
        try (Harness h = new Harness(1, Alliance.BLUE)) {
            h.seconds(1);
            List<BodyState> onTheField = h.artifacts();
            int[] ids = new int[speeds.length];
            for (int i = 0; i < speeds.length; i++) {
                ids[i] = onTheField.get(i).entityId();
                h.reshoot(ids[i], new Vector3(-0.6, -0.8 + 0.4 * i, Dimensions.ARTIFACT_RADIUS), new Vector3(speeds[i], 0, 0));
            }
            h.seconds(12);
            double[] x = new double[ids.length];
            for (int i = 0; i < ids.length; i++) {
                BodyPose pose = h.world.bodyPose(ids[i]);
                assertEquals(0.0, Math.sqrt(pose.vx() * pose.vx() + pose.vy() * pose.vy() + pose.vz() * pose.vz()), 1e-3,
                        "artifact " + ids[i] + " rolled at " + speeds[i] + " m/s is at rest");
                x[i] = pose.x();
            }
            h.seconds(8);
            for (int i = 0; i < ids.length; i++) {
                assertEquals(x[i], h.world.bodyPose(ids[i]).x(), 1e-6, "artifact " + ids[i] + " stays where it stopped");
            }
        }
    }
}
