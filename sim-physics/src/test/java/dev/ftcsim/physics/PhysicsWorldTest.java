package dev.ftcsim.physics;

import static dev.ftcsim.physics.TestWorlds.body;
import static dev.ftcsim.physics.TestWorlds.steps;
import static dev.ftcsim.physics.TestWorlds.worldWithFloor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.snapshot.BodyState;
import dev.ftcsim.contracts.snapshot.WorldSnapshot;
import org.junit.jupiter.api.Test;

class PhysicsWorldTest {

    private static final PieceSpec FOAM_BALL = new PieceSpec(0.0455f, 0.06f, 0.5f, 0.5f, 0.5f);
    private static final int KIND_BALL = 7;

    @Test
    void startsOnThePinnedSinglePrecisionDeterministicBuild() {
        try (PhysicsWorld world = TestNatives.newWorld()) {
            String info = world.engineInfo();
            assertTrue(info.contains("Cross Platform Deterministic"), info);
            assertTrue(info.contains("Single precision"), info);
            assertTrue(info.contains(TestNatives.RELEASE_SHA256), info);
        }
    }

    @Test
    void refusesANativeLibraryThatDoesNotMatchThePin() {
        IllegalStateException e = assertThrows(
                IllegalStateException.class,
                () -> PhysicsWorld.create(TestNatives.directory(), "0".repeat(64), 0));
        assertTrue(e.getMessage().contains(TestNatives.RELEASE_SHA256), e.getMessage());
    }

    @Test
    void droppedPieceBouncesWithItsRestitutionAndComesToRest() {
        try (PhysicsWorld world = worldWithFloor()) {
            world.spawnPiece(1, KIND_BALL, FOAM_BALL, new Vector3(0, 0, 1.0), Vector3.ZERO);
            double peak = 0;
            boolean bounced = false;
            float previous = 1.0f;
            for (int i = 0; i < 480; i++) {
                world.step();
                float z = body(world, 1).z();
                bounced |= z > previous && previous < FOAM_BALL.radiusM() + 0.02f;
                if (bounced) {
                    peak = Math.max(peak, z - FOAM_BALL.radiusM());
                }
                previous = z;
            }
            // v = sqrt(2 g h) = 4.43 m/s before impact, 0.5 of it afterwards: a rebound of about 25 cm.
            assertEquals(0.25 * (1.0 - FOAM_BALL.radiusM()), peak, 0.05);
            steps(world, 480);
            assertEquals(FOAM_BALL.radiusM(), body(world, 1).z(), 0.003);
        }
    }

    @Test
    void fastPieceBouncesOffAThinWallInsteadOfTunnelling() {
        try (PhysicsWorld world = worldWithFloor()) {
            world.addStaticBox(new Vector3(1.0, 0, 0.3), new Vector3(0.005, 1, 0.3));
            world.spawnPiece(1, KIND_BALL, FOAM_BALL, new Vector3(0, 0, 0.1), new Vector3(15, 0, 0));
            steps(world, 240);
            assertTrue(body(world, 1).x() < 1.0f, "ball passed the wall: x=" + body(world, 1).x());
        }
    }

    @Test
    void aSurfaceOfItsOwnRestitutionBouncesAPieceHigherThanThePieceAloneWould() {
        PieceSpec dull = new PieceSpec(0.0455f, 0.06f, 0.5f, 0.1f, 0f);
        try (PhysicsWorld world = TestNatives.newWorld()) {
            // The engine takes the larger restitution of the two bodies: the default plate leaves the piece its own 0.1.
            world.addStaticBox(new Vector3(-1, 0, -0.5), new Vector3(0.5, 0.5, 0.5));
            world.addStaticBox(new Vector3(1, 0, -0.5), new Vector3(0.5, 0.5, 0.5), Vector3.Y, 0.0, 0.5f, 0.6f);
            world.spawnPiece(1, KIND_BALL, dull, new Vector3(-1, 0, 1.0), Vector3.ZERO);
            world.spawnPiece(2, KIND_BALL, dull, new Vector3(1, 0, 1.0), Vector3.ZERO);
            double[] rebound = new double[2];
            boolean[] bounced = new boolean[2];
            float[] previous = {1.0f, 1.0f};
            for (int i = 0; i < 240; i++) {
                world.step();
                for (int ball = 0; ball < 2; ball++) {
                    float z = body(world, ball + 1).z();
                    bounced[ball] |= z > previous[ball] && previous[ball] < dull.radiusM() + 0.02f;
                    if (bounced[ball]) {
                        rebound[ball] = Math.max(rebound[ball], z - dull.radiusM());
                    }
                    previous[ball] = z;
                }
            }
            // The speed after the impact is the restitution times the speed before it, so the rebound is its square.
            double fall = 1.0 - dull.radiusM();
            assertEquals(0.01 * fall, rebound[0], 0.01);
            assertEquals(0.36 * fall, rebound[1], 0.05);
        }
    }

    @Test
    void aSurfaceOfItsOwnFrictionGripsAPieceSlidingOverItMoreOrLess() {
        PieceSpec ball = new PieceSpec(0.0455f, 0.06f, 0.5f, 0f, 0f);
        try (PhysicsWorld world = TestNatives.newWorld()) {
            world.addStaticBox(new Vector3(0, -2, -0.5), new Vector3(5, 0.5, 0.5), Vector3.Y, 0.0, 0.02f, 0f);
            world.addStaticBox(new Vector3(0, 2, -0.5), new Vector3(5, 0.5, 0.5), Vector3.Y, 0.0, 0.9f, 0f);
            world.spawnPiece(1, KIND_BALL, ball, new Vector3(-4, -2, 0.0455), new Vector3(2, 0, 0));
            world.spawnPiece(2, KIND_BALL, ball, new Vector3(-4, 2, 0.0455), new Vector3(2, 0, 0));
            steps(world, 72);
            // The engine combines the frictions as the square root of their product: 0.1 and 0.67, which slow the slide
            // by 0.98 and 6.6 m/s^2. The grippy surface has turned it into a roll, which keeps 5/7 of the speed.
            assertEquals(2.0 - 0.98 * 0.3, world.bodyPose(1).vx(), 0.08);
            assertEquals(2.0 * 5 / 7, world.bodyPose(2).vx(), 0.08);
        }
    }

    @Test
    void removedPieceLeavesTheSnapshotAndMayReenterUnderTheSameId() {
        try (PhysicsWorld world = worldWithFloor()) {
            world.spawnPiece(5, KIND_BALL, FOAM_BALL, new Vector3(0, 0, 0.5), Vector3.ZERO);
            world.spawnPiece(3, KIND_BALL, FOAM_BALL, new Vector3(1, 0, 0.5), Vector3.ZERO);
            assertThrows(IllegalArgumentException.class,
                    () -> world.spawnPiece(5, KIND_BALL, FOAM_BALL, Vector3.ZERO, Vector3.ZERO));

            world.removePiece(5);
            assertFalse(world.hasPiece(5));
            assertEquals(1, world.snapshot().bodies().size());
            assertThrows(IllegalArgumentException.class, () -> world.removePiece(5));

            world.spawnPiece(5, KIND_BALL, FOAM_BALL, new Vector3(0, 1, 0.5), new Vector3(0, 0, 3));
            assertTrue(world.hasPiece(5));
            assertTrue(body(world, 5).y() > 0.9f);
        }
    }

    @Test
    void snapshotsAreOrderedByEntityIdAndTimedByTheStepCount() {
        try (PhysicsWorld world = worldWithFloor()) {
            for (int id : new int[] {9, 2, 40, 7}) {
                world.spawnPiece(id, KIND_BALL, FOAM_BALL, new Vector3(id * 0.1, 0, 0.5), Vector3.ZERO);
            }
            steps(world, 240);
            WorldSnapshot snapshot = world.snapshot();
            assertEquals(240, snapshot.stepIndex());
            assertEquals(1_000_000_000L, snapshot.simTimeNanos());
            assertEquals(java.util.List.of(2, 7, 9, 40), snapshot.bodies().stream().map(BodyState::entityId).toList());
            assertEquals(KIND_BALL, snapshot.bodies().get(0).kind());
        }
    }

    @Test
    void identicalRunsProduceIdenticalStateHashes() {
        assertEquals(hashAfterSettling(), hashAfterSettling());
    }

    private static String hashAfterSettling() {
        try (PhysicsWorld world = worldWithFloor()) {
            for (int i = 0; i < 20; i++) {
                world.spawnPiece(i, KIND_BALL, FOAM_BALL, new Vector3(0.05 * (i % 5), 0.03 * (i / 5), 0.2 + 0.1 * i), Vector3.ZERO);
            }
            steps(world, 300);
            return world.stateHash();
        }
    }
}
