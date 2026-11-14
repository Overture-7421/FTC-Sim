package dev.ftcsim.physics;

import static dev.ftcsim.physics.TestWorlds.body;
import static dev.ftcsim.physics.TestWorlds.steps;
import static dev.ftcsim.physics.TestWorlds.worldWithFloor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.snapshot.BodyState;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class HiveAndGateTest {

    private static final PieceSpec BALL = new PieceSpec(0.0455f, 0.06f, 0.5f, 0.5f, 0.5f);
    private static final double LIMIT = Math.toRadians(30);

    @Test
    void hiveCarriesItsPiecesBetweenTheTwoEndStops() {
        try (PhysicsWorld world = worldWithFloor()) {
            Hive hive = world.addHive(100, 9, new HiveSpec(new Vector3(0, 0, 0.3), 0.6, 0.4, 0.06, LIMIT, Math.toRadians(120)));
            for (int i = 0; i < 5; i++) {
                world.spawnPiece(i, 1, BALL, new Vector3(-0.2 + 0.1 * i, 0, 0.5), Vector3.ZERO);
            }
            steps(world, 360);
            assertEquals(Hive.State.AT_NEGATIVE, hive.state());
            assertEquals(-LIMIT, hive.angleRadians());
            assertEquals(List.of(0, 1, 2, 3, 4), piecesOnTray(world));

            hive.tipPositive();
            world.step();
            assertEquals(Hive.State.MOVING_TO_POSITIVE, hive.state());
            steps(world, 240);
            assertEquals(Hive.State.AT_POSITIVE, hive.state());
            assertEquals(LIMIT, hive.angleRadians());
            assertEquals(List.of(0, 1, 2, 3, 4), piecesOnTray(world));
            // The +X end is down, so the pieces have slid towards it and rest above the tilted tray.
            assertTrue(body(world, 4).z() < body(world, 0).z() - 0.15);

            hive.tipNegative();
            steps(world, 360);
            assertEquals(Hive.State.AT_NEGATIVE, hive.state());
            assertEquals(List.of(0, 1, 2, 3, 4), piecesOnTray(world));
            assertTrue(body(world, 0).z() < body(world, 4).z() - 0.15);
        }
    }

    // Pieces that are still inside the footprint of the tray, in ascending id order.
    private static List<Integer> piecesOnTray(PhysicsWorld world) {
        List<Integer> ids = new ArrayList<>();
        for (int id = 0; id < 5; id++) {
            BodyState piece = body(world, id);
            if (Math.abs(piece.x()) < 0.3 && Math.abs(piece.y()) < 0.2 && piece.z() > 0.2 && piece.z() < 0.5) {
                ids.add(id);
            }
        }
        return ids;
    }

    @Test
    void gateOpensUnderAPushedPieceAndClosesAgainByGravity() {
        try (PhysicsWorld world = worldWithFloor()) {
            Gate gate = world.addGate(
                    200, 8, new GateSpec(new Vector3(0, 0, 0.12), 0.3, 0.10, 0.01, 0.15, Math.toRadians(80), 2.0));
            steps(world, 240);
            assertEquals(0.0, gate.angleRadians(), 1e-3);

            world.spawnPiece(1, 1, BALL, new Vector3(-0.6, 0, 0.0455), new Vector3(1.5, 0, 0));
            double widest = 0;
            for (int i = 0; i < 960; i++) {
                world.step();
                widest = Math.max(widest, gate.angleRadians());
            }
            assertTrue(Math.toDegrees(widest) > 20, "the gate opened to " + Math.toDegrees(widest) + " degrees");
            assertTrue(widest < Math.toRadians(80) + 1e-3, "the gate passed its stop");
            assertEquals(0.0, gate.angleRadians(), Math.toRadians(1.0));
        }
    }
}
