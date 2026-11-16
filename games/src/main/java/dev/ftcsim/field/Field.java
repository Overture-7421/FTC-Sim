package dev.ftcsim.field;

import dev.ftcsim.contracts.field.FieldDescription;
import dev.ftcsim.physics.PhysicsWorld;
import dev.ftcsim.physics.PieceSpec;
import java.util.Map;

/**
 * One game's field: its geometry, its pieces and its rules. A field is stateless; everything that changes during a
 * run lives in the {@link FieldRun} it starts, so one field can be run again and again from the same seed.
 */
public interface Field {

    /** What presentation and sensors need to know about the field, which never changes. */
    FieldDescription description();

    /**
     * The physical properties of every kind of game piece, by kind. A mechanism that takes a piece out of the world
     * and later puts it back needs them.
     */
    Map<Integer, PieceSpec> pieceSpecs();

    /**
     * Builds the field in an empty world: scenery, moving structures and every piece in its starting place, using
     * entity ids from {@link EntityIds#FIELD_BASE} upwards. The same world and setup always give the same field.
     */
    FieldRun start(PhysicsWorld world, FieldSetup setup);
}
