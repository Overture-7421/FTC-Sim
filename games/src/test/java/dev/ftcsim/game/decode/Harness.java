package dev.ftcsim.game.decode;

import dev.ftcsim.contracts.field.Alliance;
import dev.ftcsim.contracts.snapshot.BodyState;
import dev.ftcsim.contracts.snapshot.WorldSnapshot;
import dev.ftcsim.field.FieldSetup;
import dev.ftcsim.physics.PhysicsWorld;
import dev.ftcsim.physics.Vector3;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;

/** A DECODE field in a real physics world, stepped the way the host steps it, with helpers to play pieces into it. */
final class Harness implements AutoCloseable {

    static final Path NATIVES = Path.of("build", "jolt-natives-game-decode");

    final PhysicsWorld world;
    final DecodeRun run;
    final DecodeField field = new DecodeField();

    Harness(long seed, Alliance alliance) {
        this(new FieldSetup(seed, alliance));
    }

    Harness(FieldSetup setup) {
        world = PhysicsWorld.create(NATIVES);
        run = field.start(world, setup);
    }

    void step(int count) {
        for (int i = 0; i < count; i++) {
            world.step();
            run.afterStep(world.snapshot());
        }
    }

    void seconds(double seconds) {
        step((int) Math.round(seconds * PhysicsWorld.STEPS_PER_SECOND));
    }

    BodyState body(int id) {
        return world.snapshot().bodies().stream().filter(b -> b.entityId() == id).findFirst().orElseThrow();
    }

    List<BodyState> artifacts() {
        return world.snapshot().bodies().stream().filter(b -> Kinds.isArtifact(b.kind())).toList();
    }

    /** What a shooter does: a piece the robot holds is released into the world with a position and a velocity. */
    int shootFromReserve(int kind, Vector3 position, Vector3 velocity) {
        OptionalInt id = run.takeFromReserve(kind);
        int piece = id.orElseThrow();
        world.spawnPiece(piece, kind, DecodeField.ARTIFACT, position, velocity);
        return piece;
    }

    /** The same for a piece that was on the field: an intake takes it out of the world and a shooter puts it back. */
    void reshoot(int id, Vector3 position, Vector3 velocity) {
        int kind = body(id).kind();
        world.removePiece(id);
        world.spawnPiece(id, kind, DecodeField.ARTIFACT, position, velocity);
    }

    WorldSnapshot snapshot() {
        return world.snapshot();
    }

    @Override
    public void close() {
        world.close();
    }
}
