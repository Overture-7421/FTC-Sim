package dev.ftcsim.acceptance;

import dev.ftcsim.contracts.field.FieldDescription;
import dev.ftcsim.contracts.field.MatchPhase;
import dev.ftcsim.contracts.field.Score;
import dev.ftcsim.contracts.snapshot.WorldSnapshot;
import dev.ftcsim.field.Field;
import dev.ftcsim.field.FieldRun;
import dev.ftcsim.field.FieldSetup;
import dev.ftcsim.physics.PhysicsWorld;
import dev.ftcsim.physics.PieceSpec;
import dev.ftcsim.physics.Vector3;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * A floor with a long ramp on it: a slab tilted by {@link #ANGLE_DEGREES} whose low end meets the floor
 * {@link #FOOT_X} metres in front of a robot that starts at the origin facing forward, and which rises as far as the
 * floor goes, so that a robot driving forward climbs it for as long as a test needs.
 */
final class RampField implements Field {
    static final double ANGLE_DEGREES = 15.0;
    static final double FOOT_X = 0.9;

    private static final double HALF_LENGTH = 2.6;
    private static final double HALF_WIDTH = 1.0;
    private static final double HALF_THICKNESS = 0.15;

    @Override
    public FieldDescription description() {
        return new FieldDescription("ramp", "Ramp field", List.of(), List.of(), List.of(), List.of(), List.of());
    }

    @Override
    public Map<Integer, PieceSpec> pieceSpecs() {
        return Map.of();
    }

    @Override
    public FieldRun start(PhysicsWorld world, FieldSetup setup) {
        world.addStaticBox(new Vector3(0, 0, -0.5), new Vector3(6, 6, 0.5));
        // The slab's top face passes through the foot of the ramp and rises along the slab's length; the slab is turned
        // about the Y axis by minus the ramp angle, which lifts its +X end.
        double angle = Math.toRadians(ANGLE_DEGREES);
        double centreX = FOOT_X + HALF_LENGTH * Math.cos(angle) + HALF_THICKNESS * Math.sin(angle);
        double centreZ = HALF_LENGTH * Math.sin(angle) - HALF_THICKNESS * Math.cos(angle);
        world.addStaticBox(
                new Vector3(centreX, 0, centreZ), new Vector3(HALF_LENGTH, HALF_WIDTH, HALF_THICKNESS), Vector3.Y, -angle);
        return new FieldRun() {
            @Override
            public void afterStep(WorldSnapshot snapshot) {}

            @Override
            public void enterPhase(MatchPhase phase, long timeNanos) {}

            @Override
            public void perform(String humanActionId) {}

            @Override
            public OptionalInt takeFromReserve(int kind) {
                return OptionalInt.empty();
            }

            @Override
            public Score score() {
                return new Score(List.of());
            }
        };
    }
}
