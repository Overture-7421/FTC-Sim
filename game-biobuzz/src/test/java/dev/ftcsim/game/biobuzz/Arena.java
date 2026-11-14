package dev.ftcsim.game.biobuzz;

import static dev.ftcsim.game.biobuzz.Solids.in;

import dev.ftcsim.contracts.field.Alliance;
import dev.ftcsim.contracts.snapshot.BodyState;
import dev.ftcsim.field.FieldSetup;
import dev.ftcsim.physics.PhysicsWorld;
import dev.ftcsim.physics.Vector3;
import java.nio.file.Path;

/** A real physics world with the BIOBUZZ field in it, and the little helpers the tests need to play on it. */
final class Arena implements AutoCloseable {

    static final Path NATIVES = Path.of("build", "jolt-natives");

    final PhysicsWorld world = PhysicsWorld.create(NATIVES);
    final BiobuzzRun run;
    private int nextExtra = 5000;
    private long[] stepNanos;
    private int timedSteps;

    Arena(long seed, Alliance alliance) {
        this(new FieldSetup(seed, alliance));
    }

    /** The same, for a robot whose outline, as the rules see it, is a square of the given side. */
    Arena(long seed, Alliance alliance, double outlineMeters) {
        this(new FieldSetup(seed, alliance, outlineMeters, outlineMeters));
    }

    private Arena(FieldSetup setup) {
        run = new BiobuzzField().start(world, setup);
    }

    @Override
    public void close() {
        world.close();
    }

    void steps(int count) {
        for (int i = 0; i < count; i++) {
            long start = stepNanos == null ? 0 : System.nanoTime();
            world.step();
            run.afterStep(world.snapshot());
            if (stepNanos != null && timedSteps < stepNanos.length) {
                stepNanos[timedSteps++] = System.nanoTime() - start;
            }
        }
    }

    /** From now on, records how long each step takes, the physics and the rules after it, for up to {@code capacity} steps. */
    void timeSteps(int capacity) {
        stepNanos = new long[capacity];
        timedSteps = 0;
    }

    /** The recorded step durations in nanoseconds, in the order they ran. */
    long[] timings() {
        return java.util.Arrays.copyOf(stepNanos, timedSteps);
    }

    BodyState body(int id) {
        return world.snapshot().bodies().stream().filter(b -> b.entityId() == id).findFirst().orElse(null);
    }

    /** Puts one more piece into the world, under an id the field does not use. */
    int spawn(int kind, Vector3 position, Vector3 velocity) {
        int id = nextExtra++;
        world.spawnPiece(id, kind, PieceModel.specOf(kind), position, velocity);
        return id;
    }

    double hiveDegrees(boolean red) {
        return Math.toDegrees(Assessment.hiveAngle(body(red ? BiobuzzIds.HIVE_RED : BiobuzzIds.HIVE_BLUE)));
    }

    /** Loads one CELL the way the field staff do for the acceptance checklist: by hand, or by tossing. */
    final class Loader {
        private final boolean red;
        private final double sign;
        private final double direction;
        private final java.util.List<Double> centers = new java.util.ArrayList<>();
        private double rowEdge;

        /**
         * @param audienceCell     which CELL of the HIVE is loaded; it must be the one facing up
         * @param keepStagedNectar leave the three NECTAR a match starts with in the CELL, or take them out
         */
        Loader(boolean red, boolean audienceCell, boolean keepStagedNectar) {
            this.red = red;
            this.sign = audienceCell ? 1 : -1;
            this.direction = audienceCell ? 1 : -1;
            boolean staged = red == audienceCell;
            int first = red ? BiobuzzIds.NECTAR_RED_FIRST : BiobuzzIds.NECTAR_BLUE_FIRST;
            if (staged && keepStagedNectar) {
                double r = PieceModel.NECTAR_RADIUS_M / 0.0254;
                rowEdge = -direction * (HiveModel.HALF_WIDTH - r - 0.05 - 2 * (2 * r + 0.02)) + direction * r;
            } else {
                if (staged) {
                    for (int k = 0; k < 3; k++) {
                        world.removePiece(first + k);
                    }
                }
                rowEdge = -direction * HiveModel.HALF_WIDTH;
            }
        }

        private double angle() {
            return Assessment.hiveAngle(body(red ? BiobuzzIds.HIVE_RED : BiobuzzIds.HIVE_BLUE));
        }

        private Vector3 at(double d, double n, double lateral) {
            return HiveModel.toField(red, angle(), sign * in(d), in(lateral), in(n));
        }

        /** Sets the next piece down gently against the back wall, beside the last one, and lets it settle. */
        void place(int kind) {
            double r = PieceModel.radiusOf(kind) / 0.0254;
            double lateral = rowEdge + direction * (r + 0.03);
            spawn(kind, at(HiveModel.BACK + r + 0.05, HiveModel.FLOOR + r + 0.15, lateral), Vector3.ZERO);
            rowEdge = lateral + direction * r;
            centers.add(lateral);
            steps(90);
        }

        /** The place midway between the {@code first}-th and the next piece set down, where a piece nestles on top. */
        double cleftAbove(int first) {
            return 0.5 * (centers.get(first) + centers.get(first + 1));
        }

        /** Sets a piece down gently in the cleft above two pieces of the first row, which starts the second row. */
        void placeOnTop(int kind, double lateral) {
            double r = PieceModel.radiusOf(kind) / 0.0254;
            spawn(kind, at(HiveModel.BACK + r + 0.05, HiveModel.FLOOR + 2.43 + r + 0.3, lateral), Vector3.ZERO);
            steps(120);
        }

        /** Throws a piece into the CELL through its opening, at the speed of a gentle toss. */
        void toss(int kind, double speed, double lateral) {
            double a = angle();
            Vector3 outward = new Vector3(sign * Math.cos(a), 0, -sign * Math.sin(a));
            spawn(kind, at(HiveModel.FRONT - 1.0, HiveModel.FLOOR + 6.0, lateral), outward.times(-speed));
            steps(180);
        }
    }
}
