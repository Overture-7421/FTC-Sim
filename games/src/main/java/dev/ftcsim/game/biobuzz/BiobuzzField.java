package dev.ftcsim.game.biobuzz;

import static dev.ftcsim.game.biobuzz.Solids.in;

import dev.ftcsim.contracts.field.BodyKind;
import dev.ftcsim.contracts.field.FieldDescription;
import dev.ftcsim.contracts.field.FieldPart;
import dev.ftcsim.contracts.field.FieldTag;
import dev.ftcsim.contracts.field.HumanAction;
import dev.ftcsim.contracts.field.Pose3;
import dev.ftcsim.contracts.field.Scenery;
import dev.ftcsim.contracts.field.Shape;
import dev.ftcsim.contracts.field.StartPose;
import dev.ftcsim.field.Field;
import dev.ftcsim.field.FieldSetup;
import dev.ftcsim.physics.PhysicsWorld;
import dev.ftcsim.physics.PieceSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The BIOBUZZ field of the 2026-27 season (competition manual TU09 and the official CAD): perimeter, FLOWERS,
 * HIVE frame, 40 POLLEN, 16 NECTAR, two tipping HIVES with the AprilTag clusters 30-45 on their CELLS, and the rules
 * that score them. It holds nothing that changes; {@link #start} builds one run of it in an empty physics world.
 * {@code docs/fields/biobuzz.md} lists the sources, the dimensions and what is estimated.
 */
public final class BiobuzzField implements Field {

    public static final String LOAD_NECTAR = "load-nectar";
    public static final String LOAD_OPPONENT_NECTAR = "load-opponent-nectar";
    public static final String RETURN_POLLEN = "return-pollen";

    private static final int POLLEN_RGB = 0xF2C500;
    private static final int NECTAR_RED_RGB = 0xC62828;
    private static final int NECTAR_BLUE_RGB = 0x1565C0;

    private final FieldDescription description = describe();
    private final Map<Integer, PieceSpec> pieceSpecs = new TreeMap<>(Map.of(
            BiobuzzIds.KIND_POLLEN, PieceModel.POLLEN,
            BiobuzzIds.KIND_NECTAR_RED, PieceModel.NECTAR,
            BiobuzzIds.KIND_NECTAR_BLUE, PieceModel.NECTAR));

    @Override
    public FieldDescription description() {
        return description;
    }

    @Override
    public Map<Integer, PieceSpec> pieceSpecs() {
        return pieceSpecs;
    }

    @Override
    public BiobuzzRun start(PhysicsWorld world, FieldSetup setup) {
        return new BiobuzzRun(world, setup);
    }

    private static FieldDescription describe() {
        List<Scenery> scenery = new ArrayList<>();
        for (FieldGeometry.Group group : FieldGeometry.colliderGroups()) {
            group.solids().stream().filter(Solid::visible).map(Solid::scenery).forEach(scenery::add);
        }
        FieldGeometry.markings().stream().map(Solid::scenery).forEach(scenery::add);

        List<BodyKind> kinds = List.of(
                new BodyKind(BiobuzzIds.KIND_POLLEN, "POLLEN", List.of(ball(PieceModel.POLLEN_RADIUS_M, POLLEN_RGB))),
                new BodyKind(BiobuzzIds.KIND_NECTAR_RED, "red NECTAR", List.of(ball(PieceModel.NECTAR_RADIUS_M, NECTAR_RED_RGB))),
                new BodyKind(BiobuzzIds.KIND_NECTAR_BLUE, "blue NECTAR", List.of(ball(PieceModel.NECTAR_RADIUS_M, NECTAR_BLUE_RGB))),
                new BodyKind(BiobuzzIds.KIND_HIVE_RED, "red HIVE", HiveModel.visualParts(FieldGeometry.RED_RGB)),
                new BodyKind(BiobuzzIds.KIND_HIVE_BLUE, "blue HIVE", HiveModel.visualParts(FieldGeometry.BLUE_RGB)));

        List<FieldTag> tags = new ArrayList<>();
        tags.addAll(HiveModel.tags(BiobuzzIds.HIVE_RED, 30, false));
        tags.addAll(HiveModel.tags(BiobuzzIds.HIVE_RED, 34, true));
        tags.addAll(HiveModel.tags(BiobuzzIds.HIVE_BLUE, 38, true));
        tags.addAll(HiveModel.tags(BiobuzzIds.HIVE_BLUE, 42, false));

        return new FieldDescription(
                "biobuzz", "BIOBUZZ presented by RTX (2026-27)", scenery, kinds, tags, startPoses(),
                List.of(
                        new HumanAction(LOAD_NECTAR, "Human enters a NECTAR of the robot's alliance through its LOADING ZONE"),
                        new HumanAction(LOAD_OPPONENT_NECTAR, "Human enters a NECTAR of the other alliance through its LOADING ZONE"),
                        new HumanAction(RETURN_POLLEN, "Field staff put a POLLEN that left the field back in")));
    }

    private static FieldPart ball(double radius, int rgb) {
        return new FieldPart(new Shape.Sphere(radius), Pose3.IDENTITY, rgb);
    }

    /**
     * Where a robot may start under G304: on its own alliance's side, touching its own wall, facing the field, outside
     * the LOADING ZONE and clear of every FLOWER. The centre is 0.2336 m from the wall, which suits a robot of the
     * largest start size, 18 in. long.
     */
    private static List<StartPose> startPoses() {
        double fromWall = FieldGeometry.HALF_M - in(9.2);
        List<StartPose> poses = new ArrayList<>();
        double[] x = {in(46.0), 0.0, in(-58.0)};
        String[] redSide = {"audience side", "centre", "scoring side"};
        String[] blueSide = {"scoring side", "centre", "audience side"};
        for (int i = 0; i < x.length; i++) {
            poses.add(new StartPose("RED wall, " + redSide[i], x[i], -fromWall, Math.PI / 2));
        }
        // The field is the same when turned half a turn about its centre, which takes the red places to the blue ones.
        for (int i = 0; i < x.length; i++) {
            poses.add(new StartPose("BLUE wall, " + blueSide[i], -x[i], fromWall, -Math.PI / 2));
        }
        return poses;
    }
}
