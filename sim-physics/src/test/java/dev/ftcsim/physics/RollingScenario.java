package dev.ftcsim.physics;

import dev.ftcsim.contracts.world.BodyPose;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * A closed arena where 36 pieces with rolling resistance are kicked about, roll down a slope, are taken out and fired
 * back in, and are carried by hand and let go, among a few pieces without rolling resistance. Everything follows from
 * the step counter and one seeded generator, so a run is a pure function of the build under test. It is the scenario
 * of {@link RollingDeterminismTest}, which also starts {@link #main} in fresh JVMs.
 */
public final class RollingScenario {

    static final int STEPS = 2400;
    static final int FIRST_ID = 100;
    static final int PIECES = 36;

    private static final float COEFFICIENT = 0.06f;
    // The three game pieces with rolling resistance, and a fourth whose roll is only damped by the engine.
    private static final PieceSpec[] SPECS = {
        new PieceSpec(0.06275f, 0.0748f, 0.5f, 0.35f, 0.05f, COEFFICIENT),
        new PieceSpec(0.03556f, 0.02495f, 0.5f, 0.3f, 0.05f, COEFFICIENT),
        new PieceSpec(0.04597f, 0.04128f, 0.5f, 0.3f, 0.05f, COEFFICIENT),
        new PieceSpec(0.06275f, 0.0748f, 0.5f, 0.35f, 0.5f),
    };
    private static final double ARENA_HALF = 3.2;
    private static final double WALL_HALF_THICKNESS = 0.05;
    private static final double WALL_HALF_HEIGHT = 0.5;
    private static final double SLOPE_TILT = 0.14;
    private static final double SLOPE_HALF_LENGTH = 0.7;
    private static final double SLOPE_HALF_THICKNESS = 0.05;
    private static final double SLOPE_X = -0.6;
    private static final double SLOPE_Y = 1.8;
    private static final int GRID_PIECES = 30;
    private static final int TAKEN_OUT_FIRST = 0;
    private static final int TAKEN_OUT_COUNT = 4;
    private static final int TAKE_OUT_STEP = 300;
    private static final int FIRE_BACK_STEP = 420;
    private static final int[] CARRIED = {10, 11};
    private static final int CARRY_STEP = 600;
    private static final int LET_GO_STEP = 840;
    private static final double CARRY_SPEED = 0.5;

    private final PhysicsWorld world;
    private final SplittableRandom random = new SplittableRandom(20250625);
    private final Vector3[] carriedFrom = new Vector3[CARRIED.length];
    private final int[] carriedTowards = new int[CARRIED.length];
    private int step;

    RollingScenario(PhysicsWorld world) {
        this.world = world;
        buildArena();
        spawnPieces();
    }

    /** Hash of the world after every {@link GoldenScenario#HASH_INTERVAL} steps of a complete run. */
    static List<String> run(PhysicsWorld world) {
        RollingScenario scenario = new RollingScenario(world);
        List<String> hashes = new ArrayList<>();
        for (int i = 1; i <= STEPS; i++) {
            scenario.step();
            if (i % GoldenScenario.HASH_INTERVAL == 0) {
                hashes.add(world.stateHash());
            }
        }
        return hashes;
    }

    public static void main(String[] args) {
        String dllSha256 = args.length > 0 ? args[0] : TestNatives.RELEASE_SHA256;
        int workerThreads = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        try (PhysicsWorld world = PhysicsWorld.create(Path.of(System.getProperty("ftcsim.nativeDirectory")), dllSha256, workerThreads)) {
            GoldenHashes.lines(run(world)).forEach(System.out::println);
        }
    }

    void step() {
        if (step == TAKE_OUT_STEP) {
            for (int i = TAKEN_OUT_FIRST; i < TAKEN_OUT_FIRST + TAKEN_OUT_COUNT; i++) {
                world.removePiece(FIRST_ID + i);
            }
        }
        if (step == FIRE_BACK_STEP) {
            for (int i = TAKEN_OUT_FIRST; i < TAKEN_OUT_FIRST + TAKEN_OUT_COUNT; i++) {
                double side = i % 2 == 0 ? 1 : -1;
                world.spawnPiece(FIRST_ID + i, 1 + i % SPECS.length, SPECS[i % SPECS.length],
                        new Vector3(side * 2.8, -side * (2.8 - 0.2 * i), 0.3), new Vector3(-side * 2.0, side * 2.0, 0));
            }
        }
        if (step == CARRY_STEP) {
            for (int i = 0; i < CARRIED.length; i++) {
                world.holdPiece(FIRST_ID + CARRIED[i]);
                BodyPose pose = world.bodyPose(FIRST_ID + CARRIED[i]);
                carriedFrom[i] = new Vector3(pose.x(), pose.y(), pose.z());
                // Towards the middle of the arena, because a held piece does not stop at a wall.
                carriedTowards[i] = pose.x() > 0 ? -1 : 1;
            }
        }
        if (step > CARRY_STEP && step <= LET_GO_STEP) {
            double carried = CARRY_SPEED * (step - CARRY_STEP) / PhysicsWorld.STEPS_PER_SECOND;
            for (int i = 0; i < CARRIED.length; i++) {
                world.moveHeldPiece(
                        FIRST_ID + CARRIED[i], carriedFrom[i].plus(new Vector3(carriedTowards[i] * carried, 0, 0)), Vector3.Y, 0);
            }
        }
        if (step == LET_GO_STEP) {
            for (int i = 0; i < CARRIED.length; i++) {
                world.releasePiece(FIRST_ID + CARRIED[i], new Vector3(carriedTowards[i] * CARRY_SPEED, 0, 0), Vector3.ZERO);
            }
        }
        world.step();
        step++;
    }

    /** How many pieces lie perfectly still, which is what an engine that put them to sleep reports. */
    int piecesAtRest(boolean withRollingResistance) {
        int resting = 0;
        for (int i = 0; i < PIECES; i++) {
            BodyPose pose = world.bodyPose(FIRST_ID + i);
            boolean resists = SPECS[i % SPECS.length].rollingResistance() > 0;
            if (resists == withRollingResistance && pose.vx() == 0.0 && pose.vy() == 0.0 && pose.vz() == 0.0
                    && pose.wx() == 0.0 && pose.wy() == 0.0 && pose.wz() == 0.0) {
                resting++;
            }
        }
        return resting;
    }

    /** How many pieces have a rolling resistance. */
    static int piecesWithRollingResistance() {
        int count = 0;
        for (int i = 0; i < PIECES; i++) {
            count += SPECS[i % SPECS.length].rollingResistance() > 0 ? 1 : 0;
        }
        return count;
    }

    /** Whether every piece is still inside the closed arena. */
    boolean allPiecesInside() {
        for (int i = 0; i < PIECES; i++) {
            BodyPose pose = world.bodyPose(FIRST_ID + i);
            if (Math.abs(pose.x()) > ARENA_HALF || Math.abs(pose.y()) > ARENA_HALF || pose.z() < -0.01 || pose.z() > 2 * WALL_HALF_HEIGHT) {
                return false;
            }
        }
        return true;
    }

    private void buildArena() {
        world.addStaticBox(new Vector3(0, 0, -0.5), new Vector3(ARENA_HALF, ARENA_HALF, 0.5));
        double edge = ARENA_HALF + WALL_HALF_THICKNESS;
        world.addStaticBox(new Vector3(edge, 0, WALL_HALF_HEIGHT), new Vector3(WALL_HALF_THICKNESS, edge, WALL_HALF_HEIGHT));
        world.addStaticBox(new Vector3(-edge, 0, WALL_HALF_HEIGHT), new Vector3(WALL_HALF_THICKNESS, edge, WALL_HALF_HEIGHT));
        world.addStaticBox(new Vector3(0, edge, WALL_HALF_HEIGHT), new Vector3(edge, WALL_HALF_THICKNESS, WALL_HALF_HEIGHT));
        world.addStaticBox(new Vector3(0, -edge, WALL_HALF_HEIGHT), new Vector3(edge, WALL_HALF_THICKNESS, WALL_HALF_HEIGHT));
        world.addStaticBox(
                new Vector3(SLOPE_X, SLOPE_Y, slopeCenterZ()),
                new Vector3(SLOPE_HALF_LENGTH, 0.5, SLOPE_HALF_THICKNESS),
                Vector3.Y, SLOPE_TILT);
    }

    // The low end of the slope, which falls away towards +X, just touches the floor.
    private static double slopeCenterZ() {
        return SLOPE_HALF_LENGTH * StrictMath.sin(SLOPE_TILT) - SLOPE_HALF_THICKNESS * StrictMath.cos(SLOPE_TILT);
    }

    private void spawnPieces() {
        for (int i = 0; i < GRID_PIECES; i++) {
            PieceSpec spec = SPECS[i % SPECS.length];
            double x = -1.25 + 0.5 * (i % 6) + jitter();
            double y = -2.4 + 0.5 * (i / 6) + jitter();
            double speed = 0.3 + 3.2 * unit();
            double heading = 2 * Math.PI * unit();
            world.spawnPiece(FIRST_ID + i, 1 + i % SPECS.length, spec, new Vector3(x, y, spec.radiusM() + 0.002),
                    new Vector3(speed * StrictMath.cos(heading), speed * StrictMath.sin(heading), 0));
        }
        double sin = StrictMath.sin(SLOPE_TILT);
        double cos = StrictMath.cos(SLOPE_TILT);
        for (int i = GRID_PIECES; i < PIECES; i++) {
            PieceSpec spec = SPECS[i % SPECS.length];
            double along = -0.5 + 0.2 * (i - GRID_PIECES);
            double lift = SLOPE_HALF_THICKNESS + spec.radiusM() + 0.002;
            world.spawnPiece(FIRST_ID + i, 1 + i % SPECS.length, spec,
                    new Vector3(SLOPE_X + along * cos + lift * sin, SLOPE_Y, slopeCenterZ() - along * sin + lift * cos),
                    Vector3.ZERO);
        }
    }

    private double unit() {
        return (random.nextLong() >>> 11) * 0x1.0p-53;
    }

    private double jitter() {
        return 0.06 * (unit() - 0.5);
    }
}
