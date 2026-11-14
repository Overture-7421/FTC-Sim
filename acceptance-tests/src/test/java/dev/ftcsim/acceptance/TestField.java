package dev.ftcsim.acceptance;

import dev.ftcsim.contracts.field.Alliance;
import dev.ftcsim.contracts.field.BodyKind;
import dev.ftcsim.contracts.field.FieldDescription;
import dev.ftcsim.contracts.field.FieldPart;
import dev.ftcsim.contracts.field.MatchPhase;
import dev.ftcsim.contracts.field.Pose3;
import dev.ftcsim.contracts.field.Score;
import dev.ftcsim.contracts.field.Shape;
import dev.ftcsim.contracts.snapshot.WorldSnapshot;
import dev.ftcsim.field.Field;
import dev.ftcsim.field.FieldRun;
import dev.ftcsim.field.FieldSetup;
import dev.ftcsim.field.support.PieceReserve;
import dev.ftcsim.physics.PhysicsWorld;
import dev.ftcsim.physics.PieceSpec;
import dev.ftcsim.physics.Vector3;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * The smallest field that exercises everything a session asks of one: a floor, one kind of piece, a few of them
 * lying in front of a robot standing at the origin, and a reserve of two more.
 */
final class TestField implements Field {
    static final int PIECE_KIND = 100;
    static final float RADIUS = 0.0635f;
    static final PieceSpec PIECE = new PieceSpec(RADIUS, 0.07f, 0.5f, 0.2f, 0.02f);

    /** The ids of the pieces lying on the floor at the start, then those in reserve. */
    static final List<Integer> ON_FLOOR = List.of(1000, 1001, 1002);
    static final List<Integer> IN_RESERVE = List.of(1003, 1004);

    /** A phase the session told the field it had entered, and the simulated instant it said so. */
    record PhaseEntry(MatchPhase phase, long timeNanos) {}

    private final List<PhaseEntry> phases = Collections.synchronizedList(new ArrayList<>());

    /** Every {@code enterPhase} call so far, oldest first; opening the session makes the first, PRACTICE at 0. */
    List<PhaseEntry> phases() {
        synchronized (phases) {
            return List.copyOf(phases);
        }
    }

    @Override
    public FieldDescription description() {
        BodyKind piece = new BodyKind(PIECE_KIND, "ball", List.of(new FieldPart(new Shape.Sphere(RADIUS), Pose3.IDENTITY, 0x7e57c2)));
        return new FieldDescription("test", "Test field", List.of(), List.of(piece), List.of(), List.of(), List.of());
    }

    @Override
    public Map<Integer, PieceSpec> pieceSpecs() {
        return Map.of(PIECE_KIND, PIECE);
    }

    @Override
    public FieldRun start(PhysicsWorld world, FieldSetup setup) {
        world.addStaticBox(new Vector3(0, 0, -0.5), new Vector3(6, 6, 0.5));
        double[] ys = {-0.08, 0.0, 0.08};
        for (int i = 0; i < ON_FLOOR.size(); i++) {
            world.spawnPiece(ON_FLOOR.get(i), PIECE_KIND, PIECE, new Vector3(0.30, ys[i], RADIUS), Vector3.ZERO);
        }
        PieceReserve reserve = new PieceReserve();
        IN_RESERVE.forEach(id -> reserve.add(PIECE_KIND, id));
        return new FieldRun() {
            @Override
            public void afterStep(WorldSnapshot snapshot) {}

            @Override
            public void enterPhase(MatchPhase phase, long timeNanos) {
                phases.add(new PhaseEntry(phase, timeNanos));
            }

            @Override
            public void perform(String humanActionId) {}

            @Override
            public OptionalInt takeFromReserve(int kind) {
                return reserve.take(kind);
            }

            @Override
            public Score score() {
                return new Score(List.of());
            }
        };
    }

    static FieldSetup setup() {
        return new FieldSetup(1, Alliance.RED);
    }
}
