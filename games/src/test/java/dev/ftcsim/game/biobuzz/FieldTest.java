package dev.ftcsim.game.biobuzz;

import static dev.ftcsim.game.biobuzz.Solids.in;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.field.Alliance;
import dev.ftcsim.contracts.field.BodyKind;
import dev.ftcsim.contracts.field.FieldDescription;
import dev.ftcsim.contracts.field.FieldPart;
import dev.ftcsim.contracts.field.FieldTag;
import dev.ftcsim.contracts.field.MatchPhase;
import dev.ftcsim.contracts.field.Pose3;
import dev.ftcsim.contracts.field.Shape;
import dev.ftcsim.contracts.field.StartPose;
import dev.ftcsim.contracts.snapshot.BodyState;
import dev.ftcsim.contracts.world.BodyPose;
import dev.ftcsim.contracts.world.Vec3;
import dev.ftcsim.field.EntityIds;
import dev.ftcsim.physics.MecanumChassis;
import dev.ftcsim.physics.MecanumConfig;
import dev.ftcsim.physics.ShapePart;
import dev.ftcsim.physics.Vector3;
import dev.ftcsim.physics.WheelPosition;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** The field as a whole: what it describes, that its seed fixes the layout, that it loses nothing, and what it costs. */
class FieldTest {

    private static final BiobuzzField FIELD = new BiobuzzField();
    private static final int STEPS_PER_SECOND = 240;
    private static final double BUDGET_MS = 1000.0 / STEPS_PER_SECOND;

    private static long piecesInWorld(Arena arena) {
        return arena.world.snapshot().bodies().stream().filter(b -> Assessment.isPiece(b.kind())).count();
    }

    private static long piecesAnywhere(Arena arena) {
        long inReserve = arena.run.piecesInReserve(BiobuzzIds.KIND_POLLEN)
                + arena.run.piecesInReserve(BiobuzzIds.KIND_NECTAR_RED)
                + arena.run.piecesInReserve(BiobuzzIds.KIND_NECTAR_BLUE);
        return piecesInWorld(arena) + inReserve;
    }

    @Test
    void theDescriptionNamesEveryBodyAndEveryTagOfTheWorld() {
        FieldDescription description = FIELD.description();
        try (Arena arena = new Arena(1, Alliance.RED)) {
            Set<Integer> declared = new HashSet<>();
            description.bodyKinds().forEach(k -> declared.add(k.id()));
            Set<Integer> entities = new HashSet<>();
            for (BodyState body : arena.world.snapshot().bodies()) {
                assertTrue(declared.contains(body.kind()), "kind " + body.kind() + " has no appearance");
                assertTrue(body.entityId() >= EntityIds.FIELD_BASE, "entity " + body.entityId() + " is below the field's ids");
                entities.add(body.entityId());
            }
            assertTrue(FIELD.pieceSpecs().keySet().stream().allMatch(declared::contains));
            for (BodyKind kind : description.bodyKinds()) {
                assertFalse(kind.parts().isEmpty(), kind.name() + " would be invisible");
            }
            assertFalse(description.scenery().isEmpty());
            assertEquals(description.scenery().size(), description.scenery().stream().map(s -> s.name()).distinct().count(),
                    "scenery names are unique");

            Set<Integer> tagIds = new TreeSet<>();
            for (FieldTag tag : description.tags()) {
                assertTrue(entities.contains(tag.hostEntityId()), "tag " + tag.id() + " is on a body that exists");
                assertTrue(tag.hostEntityId() == BiobuzzIds.HIVE_RED || tag.hostEntityId() == BiobuzzIds.HIVE_BLUE);
                assertEquals(in(3.25), tag.sizeMeters(), 1e-9);
                assertTrue(tagIds.add(tag.id()), "tag " + tag.id() + " appears once");
            }
            assertEquals(java.util.stream.IntStream.rangeClosed(30, 45).boxed().toList(), List.copyOf(tagIds));
            assertEquals(Set.of(BiobuzzIds.HIVE_RED), hostsOf(description, 30, 37));
            assertEquals(Set.of(BiobuzzIds.HIVE_BLUE), hostsOf(description, 38, 45));
        }
    }

    private static Set<Integer> hostsOf(FieldDescription description, int firstTag, int lastTag) {
        Set<Integer> hosts = new HashSet<>();
        description.tags().stream().filter(t -> t.id() >= firstTag && t.id() <= lastTag).forEach(t -> hosts.add(t.hostEntityId()));
        return hosts;
    }

    @Test
    void hiveCellsHaveTranslucentSkinsOnEveryClosedFace() {
        List<FieldPart> skins = HiveModel.visualParts(FieldGeometry.RED_RGB).stream()
                .filter(part -> part.alpha() > 0f && part.alpha() < 1f).toList();
        double middleD = in((HiveModel.BACK + HiveModel.FRONT) / 2);
        for (int sign : new int[] {1, -1}) {
            List<double[]> faces = new ArrayList<>();
            faces.add(new double[] {sign * middleD, 0, in(HiveModel.FLOOR)});
            for (double lateral : new double[] {-1, 1}) {
                faces.add(new double[] {sign * middleD, lateral * in(HiveModel.HALF_WIDTH), in(HiveModel.EAVE / 2)});
            }
            // Roof and gable: a skin just outside the inside of the house outline at several widths
            for (double a = -9.5; a <= 9.5; a += 1.9) {
                faces.add(new double[] {sign * middleD, in(a), in(HiveModel.roofInches(a) + 0.01)});
                faces.add(new double[] {sign * in(HiveModel.BACK - 0.01), in(a), in(HiveModel.roofInches(a) - 0.7)});
            }
            for (double[] face : faces) {
                assertTrue(skins.stream().anyMatch(part -> touches(part, face)),
                        "every closed CELL face has a visible translucent skin: " + Arrays.toString(face));
            }
            assertFalse(skins.stream().anyMatch(part ->
                    touches(part, new double[] {sign * in(HiveModel.FRONT), 0, in(HiveModel.EAVE / 2)})),
                    "the entry stays open");
        }
    }

    @Test
    void hiveCellsAreHouseShapedNotBoxes() {
        // Above the eave, the corners of the old rectangle are free of both skins and solid walls.
        double lateral = in(HiveModel.HALF_WIDTH - 0.5);
        double n = in((HiveModel.EAVE + HiveModel.CEILING) / 2 + 1.5);
        double d = in((HiveModel.BACK + HiveModel.FRONT) / 2);
        for (FieldPart part : HiveModel.visualParts(FieldGeometry.RED_RGB)) {
            assertFalse(touches(part, new double[] {d, lateral, n}), "the roof corner is empty space");
            assertFalse(touches(part, new double[] {d, -lateral, n}), "the roof corner is empty space");
        }
        for (ShapePart part : HiveModel.collisionParts()) {
            assertFalse(solidContains(part, new double[] {d, lateral, n}), "the roof corner is empty space");
            assertFalse(solidContains(part, new double[] {d, -lateral, n}), "the roof corner is empty space");
        }
        assertTrue(HiveModel.roofInches(HiveModel.HALF_WIDTH) < HiveModel.roofInches(0.0), "the ridge is in the middle");
        assertEquals(HiveModel.CEILING, HiveModel.roofInches(0.0), 1e-12);
    }

    @Test
    void aPieceDroppedOnTheRoofRollsOffAndNeverEntersTheCell() {
        try (Arena arena = new Arena(1, Alliance.RED)) {
            // Over the red HIVE's audience CELL, between the ridge and the eave.
            double lateral = 5.0;
            int id = arena.spawn(BiobuzzIds.KIND_POLLEN, HiveModel.toField(true, -HiveModel.STOP_RADIANS,
                    in(15.0), in(lateral), in(HiveModel.roofInches(lateral) + 3.0)), dev.ftcsim.physics.Vector3.ZERO);
            for (int i = 0; i < 600; i++) {
                arena.steps(1);
                double angle = Assessment.hiveAngle(arena.body(BiobuzzIds.HIVE_RED));
                BodyState piece = arena.body(id);
                double[] local = HiveModel.toHive(true, angle, piece.x(), piece.y(), piece.z());
                boolean inside = local[0] > in(HiveModel.BACK) && local[0] < in(HiveModel.FRONT)
                        && Math.abs(local[1]) < in(HiveModel.HALF_WIDTH) && local[2] < in(HiveModel.roofInches(local[1] / 0.0254) - 0.1)
                        && local[2] > in(HiveModel.FLOOR);
                assertFalse(inside, "the roof is solid: piece at " + Arrays.toString(local));
            }
        }
    }

    /** Whether a point of the HIVE frame is within a visual box, which may be turned about the HIVE's X axis. */
    private static boolean touches(FieldPart part, double[] point) {
        Pose3 pose = part.local();
        if (part.shape() instanceof Shape.Wedge wedge) {
            double t = (point[2] - pose.z() + wedge.halfZ()) / (2 * wedge.halfZ());
            return Math.abs(point[0] - pose.x()) <= wedge.halfX() + 1e-8 && t >= -1e-8 && t <= 1 + 1e-8
                    && Math.abs(point[1] - pose.y()) <= wedge.halfY() * (1 - t) + 1e-8;
        }
        Shape.Box box = (Shape.Box) part.shape();
        double angle = 2 * Math.atan2(pose.qx(), pose.qw());
        return insideTurned(point, new double[] {pose.x(), pose.y(), pose.z()},
                new double[] {box.halfX(), box.halfY(), box.halfZ()}, angle);
    }

    private static boolean solidContains(ShapePart part, double[] point) {
        ShapePart.Box box = (ShapePart.Box) part;
        return insideTurned(point, new double[] {box.center().x(), box.center().y(), box.center().z()},
                new double[] {box.halfExtents().x(), box.halfExtents().y(), box.halfExtents().z()}, box.angleRadians());
    }

    private static boolean insideTurned(double[] point, double[] center, double[] half, double angleAboutX) {
        double dy = point[1] - center[1];
        double dz = point[2] - center[2];
        double c = Math.cos(angleAboutX);
        double s = Math.sin(angleAboutX);
        double y = dy * c + dz * s;
        double z = -dy * s + dz * c;
        return Math.abs(point[0] - center[0]) <= half[0] + 1e-8 && Math.abs(y) <= half[1] + 1e-8
                && Math.abs(z) <= half[2] + 1e-8;
    }

    @Test
    void hiveFloorSkinsDoNotShareExposedTopFacesWithTheColouredRibs() {
        List<FieldPart> parts = HiveModel.visualParts(FieldGeometry.RED_RGB);
        for (FieldPart skin : parts) {
            if (!(skin.shape() instanceof Shape.Box skinBox)) {
                continue;
            }
            if (skin.rgb() != 0xCFD8DC || skinBox.halfZ() > in(0.1) || skin.local().qx() != 0.0) {
                continue;
            }
            for (FieldPart rib : parts) {
                if (!(rib.shape() instanceof Shape.Box ribBox)) {
                    continue;
                }
                if (rib.rgb() == FieldGeometry.RED_RGB && rib.local().qx() == 0.0) {
                    boolean overlapX = Math.abs(skin.local().x() - rib.local().x())
                            < skinBox.halfX() + ribBox.halfX() - 1e-8;
                    boolean overlapY = Math.abs(skin.local().y() - rib.local().y())
                            < skinBox.halfY() + ribBox.halfY() - 1e-8;
                    boolean sameTop = Math.abs(skin.local().z() + skinBox.halfZ()
                            - rib.local().z() - ribBox.halfZ()) < 1e-8;
                    assertFalse(overlapX && overlapY && sameTop, "the skin and rib must not fight for the same pixels");
                }
            }
        }
    }

    @Test
    void hiveTagBackingsSitBehindTheTagsRatherThanIntersectingThem() {
        List<FieldPart> backings = HiveModel.visualParts(FieldGeometry.RED_RGB).stream()
                .filter(part -> part.rgb() == 0xFAFAFA).toList();
        for (boolean audience : new boolean[] {false, true}) {
            for (FieldTag tag : HiveModel.tags(BiobuzzIds.HIVE_RED, audience ? 34 : 30, audience)) {
                FieldPart backing = backings.stream()
                        .filter(part -> Math.signum(part.local().x()) == Math.signum(tag.local().x()))
                        .findFirst().orElseThrow();
                Shape.Box box = (Shape.Box) backing.shape();
                assertTrue(backing.local().z() - box.halfZ() >= tag.local().z() - 1e-8,
                        "the white backing must be behind the outward-facing tag, not through its black back face");
                assertTrue(backing.local().z() + box.halfZ() > tag.local().z() + 1e-5,
                        "the backing hides the tag's back face from inside the CELL");
            }
        }
    }

    @Test
    void theTagsRideOnTheirHivesAndFaceDownWhicheverEndIsUp() {
        // The stickers are on the outside of the floor of each CELL: a tag's +Z, out of its face, points down at
        // both stops, and when the HIVE tips the tags of one end drop by as much as those of the other rise.
        try (Arena arena = new Arena(1, Alliance.RED)) {
            List<FieldTag> tags = FIELD.description().tags();
            Vec3[] before = tagPositions(arena, tags);
            for (FieldTag tag : tags) {
                Vec3 normal = faceNormal(arena.world.bodyPose(tag.hostEntityId()), tag);
                assertTrue(normal.z() < -0.8, "the face of tag " + tag.id() + " points down: " + normal);
            }

            Arena.Loader loader = arena.new Loader(true, true, true);
            for (int i = 0; i < 3; i++) {
                loader.place(BiobuzzIds.KIND_POLLEN);
            }
            arena.steps(1500);
            assertEquals(1, arena.run.tips().size());
            Vec3[] after = tagPositions(arena, tags);

            double row = in(14.27);
            for (int i = 0; i < tags.size(); i++) {
                FieldTag tag = tags.get(i);
                double dropped = before[i].z() - after[i].z();
                if (tag.hostEntityId() == BiobuzzIds.HIVE_BLUE) {
                    assertEquals(0.0, dropped, 1e-3, "tag " + tag.id() + " is on the HIVE that did not move");
                } else {
                    boolean audienceEnd = tag.id() >= 34;
                    assertEquals(audienceEnd ? row : -row, dropped, 0.015, "tag " + tag.id() + " went with its CELL");
                }
            }
        }
    }

    private static Vec3[] tagPositions(Arena arena, List<FieldTag> tags) {
        Vec3[] positions = new Vec3[tags.size()];
        for (int i = 0; i < tags.size(); i++) {
            FieldTag tag = tags.get(i);
            positions[i] = arena.world.bodyPose(tag.hostEntityId()).pointToField(new Vec3(tag.local().x(), tag.local().y(), tag.local().z()));
        }
        return positions;
    }

    /** The direction out of the face of a tag, given the pose of the body it is stuck on. */
    private static Vec3 faceNormal(BodyPose host, FieldTag tag) {
        Pose3 l = tag.local();
        BodyPose tagInHost = new BodyPose(0, l.x(), l.y(), l.z(), l.qw(), l.qx(), l.qy(), l.qz(), 0, 0, 0, 0, 0, 0);
        return host.directionToField(tagInHost.directionToField(new Vec3(0, 0, 1)));
    }

    @Test
    void everyStartPoseIsInsideTheFieldAgainstItsOwnWallAndClearOfEveryPiece() {
        List<StartPose> poses = FIELD.description().startPoses();
        assertEquals(6, poses.size());
        try (Arena arena = new Arena(1, Alliance.RED)) {
            for (StartPose pose : poses) {
                boolean red = pose.name().startsWith("RED");
                assertEquals(red ? -1 : 1, Math.signum(pose.y()), pose.name());
                assertEquals(FieldGeometry.HALF_M - in(9.2), Math.abs(pose.y()), 1e-9, pose.name());
                assertEquals(red ? Math.PI / 2 : -Math.PI / 2, pose.headingRadians(), 1e-9, "faces the field");
                // An 18 in square robot, turned to face the field, covers 9 in either side of the pose.
                for (BodyState body : arena.world.snapshot().bodies()) {
                    if (Assessment.isPiece(body.kind())) {
                        boolean overlaps = Math.abs(body.x() - pose.x()) < in(9.0) + PieceModel.radiusOf(body.kind())
                                && Math.abs(body.y() - pose.y()) < in(9.0) + PieceModel.radiusOf(body.kind());
                        assertFalse(overlaps, pose.name() + " would stand on piece " + body.entityId());
                    }
                }
            }
        }
    }

    @Test
    void theSameSeedBuildsTheSameFieldAndAnotherSeedDoesNot() {
        assertEquals(builtField(11), builtField(11));
        assertNotEquals(builtField(11), builtField(12));
    }

    private static List<BodyState> builtField(long seed) {
        try (Arena arena = new Arena(seed, Alliance.RED)) {
            return arena.world.snapshot().bodies();
        }
    }

    @Test
    void loadingTheRedHiveTillItTipsLetsTheSpillRollOutAndScores() {
        try (Arena arena = new Arena(11, Alliance.RED)) {
            arena.run.enterPhase(MatchPhase.AUTONOMOUS, 0);
            arena.steps(240);
            Arena.Loader loader = arena.new Loader(true, true, true);
            for (int i = 0; i < 3; i++) {
                loader.place(BiobuzzIds.KIND_POLLEN);
            }
            arena.steps(900);
            assertEquals(1, arena.run.tips().size());
        }
    }

    @Test
    void fiftySixPiecesAreAlwaysSomewhereAndTheRobotsFourPreloadsAreHandedOutOnce() {
        try (Arena arena = new Arena(3, Alliance.BLUE)) {
            assertEquals(56, piecesAnywhere(arena));
            assertEquals(4, arena.run.piecesInReserve(BiobuzzIds.KIND_POLLEN));
            assertEquals(5, arena.run.piecesInReserve(BiobuzzIds.KIND_NECTAR_RED));
            assertEquals(5, arena.run.piecesInReserve(BiobuzzIds.KIND_NECTAR_BLUE));

            Set<Integer> preloads = new TreeSet<>();
            for (int i = 0; i < 4; i++) {
                OptionalInt id = arena.run.takeFromReserve(BiobuzzIds.KIND_POLLEN);
                assertTrue(id.isPresent());
                assertTrue(preloads.add(id.getAsInt()), "each preload is handed out once");
                assertFalse(arena.world.hasPiece(id.getAsInt()), "and is out of the world until the robot takes it");
            }
            assertTrue(arena.run.takeFromReserve(BiobuzzIds.KIND_POLLEN).isEmpty());
            assertTrue(arena.run.takeFromReserve(BiobuzzIds.KIND_NECTAR_RED).isEmpty(), "NECTAR comes only from the humans");
            assertEquals(0, arena.run.piecesInReserve(BiobuzzIds.KIND_POLLEN));
            preloads.forEach(id -> assertTrue(id >= BiobuzzIds.POLLEN_FIRST && id < BiobuzzIds.POLLEN_FIRST + 40));
        }
    }

    @Test
    void aTippingHiveAndItsSpillLoseNoPiece() {
        try (Arena arena = new Arena(4, Alliance.RED)) {
            arena.run.enterPhase(MatchPhase.TELEOP, 0);
            Arena.Loader loader = arena.new Loader(true, true, true);
            for (int i = 0; i < 3; i++) {
                loader.place(BiobuzzIds.KIND_POLLEN);
            }
            arena.steps(1500);
            assertEquals(1, arena.run.tips().size());
            assertEquals(59, piecesAnywhere(arena), "56 and the three spawned for the load");
        }
    }

    @Test
    void aPieceThatRollsOutOnTheTilesStopsAndStaysWhereItStopped() {
        // Without its rolling resistance the engine leaves a damped ball crawling for ever at a few cm/s.
        try (Arena arena = new Arena(5, Alliance.RED)) {
            arena.steps(240);
            int pollen = arena.spawn(BiobuzzIds.KIND_POLLEN, new Vector3(in(-40), in(-20), in(1.41)), new Vector3(1.0, 0, 0));
            int nectar = arena.spawn(BiobuzzIds.KIND_NECTAR_RED, new Vector3(in(-40), in(0), in(1.82)), new Vector3(0.4, 0, 0));
            arena.steps(6 * STEPS_PER_SECOND);
            BodyState pollenThen = arena.body(pollen);
            BodyState nectarThen = arena.body(nectar);
            assertTrue(pollenThen.x() - in(-40) > in(10) && pollenThen.x() - in(-40) < in(60), "the POLLEN rolled a while, not a lap");
            arena.steps(6 * STEPS_PER_SECOND);
            for (int id : new int[] {pollen, nectar}) {
                BodyPose pose = arena.world.bodyPose(id);
                assertEquals(0.0, Math.sqrt(pose.vx() * pose.vx() + pose.vy() * pose.vy()), 1e-3, "piece " + id + " is at rest");
            }
            assertEquals(pollenThen.x(), arena.body(pollen).x(), 1e-4);
            assertEquals(nectarThen.x(), arena.body(nectar).x(), 1e-4);
        }
    }

    @Test
    void aPieceThatAMechanismHoldsIsNotTouchedByTheRollingResistance() {
        try (Arena arena = new Arena(5, Alliance.RED)) {
            arena.steps(240);
            double radius = PieceModel.POLLEN_RADIUS_M;
            int id = arena.spawn(BiobuzzIds.KIND_POLLEN, new Vector3(in(-40), in(-20), radius), Vector3.ZERO);
            arena.world.holdPiece(id);
            double x = in(-40);
            // Carried along the tiles at 0.1 m/s and turned as a rolling ball turns, as a roller would carry it.
            for (int i = 0; i < 2 * STEPS_PER_SECOND; i++) {
                x += 0.1 / STEPS_PER_SECOND;
                arena.world.moveHeldPiece(id, new Vector3(x, in(-20), radius), Vector3.Y, (x - in(-40)) / radius);
                arena.steps(1);
            }
            BodyState held = arena.body(id);
            assertTrue(arena.world.isPieceHeld(id));
            assertEquals(x, held.x(), 1e-3);
            assertEquals(Math.sin(0.5 * (x - in(-40)) / radius), held.qy(), 1e-4, "it turns exactly as it was told to");
        }
    }

    @Test
    void aStepOfTheWholeFieldWithARobotDrivingAndAHiveTippingCostsAFractionOfItsBudget() {
        MecanumConfig config = MecanumConfig.generic18Inch();
        try (Arena warmUp = new Arena(1, Alliance.RED)) {
            warmUp.steps(2 * STEPS_PER_SECOND);
        }
        try (Arena arena = new Arena(2, Alliance.RED)) {
            StartPose pose = FIELD.description().startPoses().get(1);
            MecanumChassis robot = arena.world.addMecanumChassis(
                    EntityIds.CHASSIS, 3, config, new Vector3(pose.x(), pose.y(), 0), pose.headingRadians());
            arena.timeSteps(20 * STEPS_PER_SECOND);
            arena.run.enterPhase(MatchPhase.AUTONOMOUS, 0);
            for (WheelPosition wheel : WheelPosition.values()) {
                robot.setWheelCommand(wheel, 0.5);
            }
            arena.steps(2 * STEPS_PER_SECOND);
            for (WheelPosition wheel : WheelPosition.values()) {
                robot.setWheelCommand(wheel, 0.0);
            }
            Arena.Loader loader = arena.new Loader(true, true, true);
            for (int i = 0; i < 3; i++) {
                loader.place(BiobuzzIds.KIND_POLLEN);
            }
            arena.steps(1500);
            assertEquals(1, arena.run.tips().size());

            long[] nanos = arena.timings();
            int slowest = 0;
            for (int i = 1; i < nanos.length; i++) {
                if (nanos[i] > nanos[slowest]) {
                    slowest = i;
                }
            }
            Arrays.sort(nanos);
            double mean = Arrays.stream(nanos).average().orElseThrow() / 1e6;
            double p50 = nanos[nanos.length / 2] / 1e6;
            double p95 = nanos[(int) (nanos.length * 0.95)] / 1e6;
            double p99 = nanos[(int) (nanos.length * 0.99)] / 1e6;
            double max = nanos[nanos.length - 1] / 1e6;
            System.out.printf(
                    "STEP COST over %d steps (field + robot + tipping HIVE), ms: mean %.3f p50 %.3f p95 %.3f p99 %.3f max %.3f"
                            + " (step %d); budget %.2f%n",
                    nanos.length, mean, p50, p95, p99, max, slowest, BUDGET_MS);
            assertTrue(mean < BUDGET_MS / 4, "mean step " + mean + " ms");
            assertTrue(p95 < BUDGET_MS / 2, "95th percentile " + p95 + " ms");
        }
    }
}
