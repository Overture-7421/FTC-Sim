package dev.ftcsim.game.decode;

import dev.ftcsim.contracts.field.FieldDescription;
import dev.ftcsim.field.Field;
import dev.ftcsim.field.FieldSetup;
import dev.ftcsim.physics.PhysicsWorld;
import dev.ftcsim.physics.PieceSpec;
import dev.ftcsim.physics.Pivot;
import dev.ftcsim.physics.Vector3;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * The DECODE (2025-26) field: perimeter, two goals with their classifiers and gates, the OBELISK, 36 ARTIFACTS and the
 * rules for scoring them. See {@code docs/fields/decode.md} for the sources and the simplifications.
 */
public final class DecodeField implements Field {

    // The mass is the published weight of an AndyMark ARTIFACT, a hollow Gopher ResisDent polypropylene ball; friction,
    // bounce, rolling resistance and spin damping are estimates. Polypropylene slides on polypropylene at about 0.25.
    static final PieceSpec ARTIFACT = new PieceSpec(
            (float) Dimensions.ARTIFACT_RADIUS, (float) Dimensions.ARTIFACT_MASS_KG, 0.25f, 0.35f, 0.05f, 0.06f);

    private static final FieldDescription DESCRIPTION = DecodeDescription.build();
    private static final Map<Integer, PieceSpec> PIECE_SPECS = pieceSpecs(Map.of(Kinds.PURPLE, ARTIFACT, Kinds.GREEN, ARTIFACT));

    private static Map<Integer, PieceSpec> pieceSpecs(Map<Integer, PieceSpec> unordered) {
        return Collections.unmodifiableMap(new TreeMap<>(unordered));
    }

    @Override
    public FieldDescription description() {
        return DESCRIPTION;
    }

    @Override
    public Map<Integer, PieceSpec> pieceSpecs() {
        return PIECE_SPECS;
    }

    @Override
    public DecodeRun start(PhysicsWorld world, FieldSetup setup) {
        Layout layout = new Layout(setup.seed());
        DecodeSolids.all().forEach(part -> part.addTo(world));
        Pivot blueGate = world.addPivot(GateArm.entityId(false), GateArm.kind(false), GateArm.spec(false));
        Pivot redGate = world.addPivot(GateArm.entityId(true), GateArm.kind(true), GateArm.spec(true));
        Obelisk.addTo(world, layout.motif());
        for (Layout.Placement piece : layout.onField()) {
            world.spawnPiece(piece.id(), piece.kind(), ARTIFACT, piece.position(), Vector3.ZERO);
        }
        return new DecodeRun(world, setup, layout, blueGate, redGate);
    }
}
