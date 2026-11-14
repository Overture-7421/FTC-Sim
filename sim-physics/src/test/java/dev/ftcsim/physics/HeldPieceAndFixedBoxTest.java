package dev.ftcsim.physics;

import static dev.ftcsim.physics.TestWorlds.body;
import static dev.ftcsim.physics.TestWorlds.steps;
import static dev.ftcsim.physics.TestWorlds.worldWithFloor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.snapshot.BodyState;
import org.junit.jupiter.api.Test;

class HeldPieceAndFixedBoxTest {

    private static final PieceSpec BALL = new PieceSpec(0.0627f, 0.0748f, 0.5f, 0.35f, 0.5f);

    @Test
    void aHeldPieceIgnoresGravityAndGoesWhereItIsSent() {
        try (PhysicsWorld world = worldWithFloor()) {
            world.spawnPiece(7, 1, BALL, new Vector3(0, 0, 1.0), Vector3.ZERO);
            world.holdPiece(7);
            steps(world, 60);
            assertEquals(1.0, body(world, 7).z(), 1e-6);

            for (int i = 1; i <= 120; i++) {
                world.moveHeldPiece(7, new Vector3(0.001 * i, 0, 1.0), Vector3.Y, 0.01 * i);
                world.step();
            }
            BodyState held = body(world, 7);
            assertEquals(0.12, held.x(), 1e-4);
            assertEquals(1.0, held.z(), 1e-4);
            assertEquals(Math.sin(0.5 * 1.2), held.qy(), 1e-4);
        }
    }

    @Test
    void aReleasedPieceMovesOnWithItsVelocityAndFallsAgain() {
        try (PhysicsWorld world = worldWithFloor()) {
            world.spawnPiece(7, 1, BALL, new Vector3(0, 0, 1.0), Vector3.ZERO);
            world.holdPiece(7);
            world.step();
            world.releasePiece(7, new Vector3(1.0, 0, 0), Vector3.ZERO);
            steps(world, 24);
            BodyState flying = body(world, 7);
            assertEquals(0.1, flying.x(), 2e-3);
            assertEquals(0.95, flying.z(), 5e-3);

            steps(world, 480);
            assertTrue(body(world, 7).z() < 0.1, "the piece lies on the floor again");
        }
    }

    @Test
    void aPieceThatIsNotHeldCannotBeMovedOrReleased() {
        try (PhysicsWorld world = worldWithFloor()) {
            world.spawnPiece(7, 1, BALL, new Vector3(0, 0, 1.0), Vector3.ZERO);
            assertThrows(IllegalStateException.class, () -> world.moveHeldPiece(7, Vector3.ZERO, Vector3.Y, 0));
            assertThrows(IllegalStateException.class, () -> world.releasePiece(7, Vector3.ZERO, Vector3.ZERO));
            assertThrows(IllegalArgumentException.class, () -> world.holdPiece(99));
        }
    }

    @Test
    void aFixedBoxIsReportedBlocksPiecesAndNeverMoves() {
        try (PhysicsWorld world = worldWithFloor()) {
            world.addFixedBox(50, 5, new Vector3(1, 0, 0.3), new Vector3(0.1, 0.1, 0.3), Math.toRadians(30));
            world.spawnPiece(1, 1, BALL, new Vector3(0, 0, 0.0627), new Vector3(2.0, 0, 0));
            steps(world, 480);

            BodyState box = body(world, 50);
            assertEquals(5, box.kind());
            assertEquals(1.0, box.x(), 1e-6);
            assertEquals(0.3, box.z(), 1e-6);
            assertEquals(Math.toRadians(30), TestWorlds.yaw(box), 1e-5);
            assertTrue(body(world, 1).x() < 0.95, "the piece was stopped by the box");

            RayHit hit = world.castRay(new Vector3(0.2, 0.0, 0.3), Vector3.X, 2.0, 0);
            assertNotNull(hit);
            assertEquals(50, hit.entityId());
        }
    }
}
