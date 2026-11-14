package dev.ftcsim.physics;

import static dev.ftcsim.physics.TestWorlds.steps;
import static dev.ftcsim.physics.TestWorlds.worldWithFloor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PivotTest {

    private static final PieceSpec BALL = new PieceSpec(0.0355f, 0.025f, 0.5f, 0.2f, 1.0f);
    private static final double STOP = 0.5;
    private static final int TRAY = 100;

    /** A tray 0.6 m long whose centre of mass sits 3 cm above the axis, so that it rests on whichever stop it is nearer. */
    private static PivotSpec tray(double initialAngle) {
        List<ShapePart> parts = List.of(
                new ShapePart.Box(new Vector3(0, 0, 0), new Vector3(0.3, 0.1, 0.005)),
                new ShapePart.Box(new Vector3(-0.295, 0, 0.03), new Vector3(0.005, 0.1, 0.03)),
                new ShapePart.Box(new Vector3(0.295, 0, 0.03), new Vector3(0.005, 0.1, 0.03)),
                new ShapePart.Box(new Vector3(0, -0.095, 0.03), new Vector3(0.3, 0.005, 0.03)),
                new ShapePart.Box(new Vector3(0, 0.095, 0.03), new Vector3(0.3, 0.005, 0.03)));
        return new PivotSpec(
                new Vector3(0, 0, 0.6), Vector3.Y, parts, 1.0, new Vector3(0, 0, 0.03), new Vector3(0.01, 0.05, 0.05),
                initialAngle, -STOP, STOP, 0.002, Math.toRadians(8), 1.5, 0.4f, 0.1f);
    }

    @Test
    void aPivotStartsAtItsAngleAndRestsAgainstTheStopItIsPressedOnto() {
        try (PhysicsWorld world = worldWithFloor()) {
            Pivot pivot = world.addPivot(TRAY, 7, tray(STOP));
            assertEquals(STOP, pivot.angleRadians(), 1e-4, "the angle has the sign of a right-hand turn about +Y");
            steps(world, 480);
            assertEquals(STOP, pivot.angleRadians(), 2e-3);
            assertEquals(0.0, pivot.angularVelocityRadiansPerSecond(), 1e-2);
        }
    }

    @Test
    void aLightLoadLeavesItAloneAndAHeavierOneTipsItOverWithoutBouncing() {
        try (PhysicsWorld world = worldWithFloor()) {
            Pivot pivot = world.addPivot(TRAY, 7, tray(STOP));
            // At +STOP the +X end is down; a ball in the raised -X corner pushes the tray towards the other stop.
            for (int i = 0; i < 4; i++) {
                world.spawnPiece(i, 1, BALL, new Vector3(-0.25, -0.06 + 0.04 * i, 0.6 + 0.0355 + 0.15), Vector3.ZERO);
            }
            steps(world, 720);
            assertEquals(STOP, pivot.angleRadians(), 2e-3, "four balls weigh less than the bias of the tray");

            for (int i = 4; i < 12; i++) {
                world.spawnPiece(i, 1, BALL, new Vector3(-0.25 + 0.01 * (i % 3), -0.06 + 0.025 * (i - 4), 0.6 + 0.4 + 0.1 * (i - 4)), Vector3.ZERO);
            }
            double lastAngle = STOP;
            double leastAngle = STOP;
            boolean arrived = false;
            double farthestBack = 0.0;
            for (int i = 0; i < 960; i++) {
                world.step();
                double angle = pivot.angleRadians();
                leastAngle = Math.min(leastAngle, angle);
                if (angle < -STOP + 1e-3) {
                    arrived = true;
                }
                if (arrived) {
                    farthestBack = Math.max(farthestBack, angle - lastAngle);
                }
                lastAngle = angle;
            }
            assertTrue(arrived, "the tray reached the far stop; the least angle was " + leastAngle);
            assertTrue(farthestBack < 0.005, "the damper left no bounce: " + farthestBack);
            assertEquals(-STOP, pivot.angleRadians(), 2e-3);
        }
    }
}
