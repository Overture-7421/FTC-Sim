package dev.ftcsim.game.decode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.field.Alliance;
import dev.ftcsim.physics.Vector3;
import org.junit.jupiter.api.Test;

/** One ball at a time dropped or thrown into a goal: every one finds its way down the plate and the lane to the SQUARE. */
class GoalFlowTest {

    private static final double GRID_STEP_M = 0.04;
    private static final double DROP_HEIGHT_M = 1.2;
    private static final double SECONDS = 5;

    /** Plays the ball in for the goal of the alliance, which is the blue goal's mirror image, and counts what was classified. */
    private static int classified(Alliance alliance, Vector3 from, Vector3 velocity) {
        double side = alliance == Alliance.RED ? -1 : 1;
        try (Harness h = new Harness(1, alliance)) {
            h.seconds(0.2);
            h.reshoot(1000, new Vector3(from.x(), side * from.y(), from.z()), new Vector3(velocity.x(), side * velocity.y(), velocity.z()));
            h.seconds(SECONDS);
            return h.run.score().lines().stream()
                    .filter(line -> line.label().endsWith("CLASSIFIED"))
                    .mapToInt(line -> line.count())
                    .sum();
        }
    }

    @Test
    void aBallDroppedOverAnyPointOfTheOpeningOfEitherGoalReachesTheSquare() {
        Vector3 tangent = Dimensions.frontTangent();
        Vector3 normal = Dimensions.frontNormal();
        int tried = 0;
        for (Alliance alliance : Alliance.values()) {
            for (double x = -1.76; x <= -1.2; x += GRID_STEP_M) {
                for (double y = -1.76; y <= -1.2; y += GRID_STEP_M) {
                    double across = (x - Dimensions.FRONT_PANEL_X) * normal.x() + (y - Dimensions.FRONT_PANEL_Y) * normal.y();
                    double along = (x - Dimensions.FRONT_PANEL_X) * tangent.x() + (y - Dimensions.FRONT_PANEL_Y) * tangent.y();
                    // Inside the opening: behind the front panel, which is 0.69 m long, and clear of it by a ball's radius.
                    if (across < -0.07 && Math.abs(along) < 0.33) {
                        assertEquals(1, classified(alliance, new Vector3(x, y, DROP_HEIGHT_M), Vector3.ZERO),
                                alliance + " goal, dropped at " + x + ", " + y);
                        tried++;
                    }
                }
            }
        }
        assertTrue(tried >= 150, "the drops cover the opening of both goals: " + tried);
    }

    @Test
    void aBallThrownAtTheBackboardsDropsIntoEitherGoalAndReachesTheSquare() {
        int tried = 0;
        for (Alliance alliance : Alliance.values()) {
            for (double z = 1.0; z <= 1.3; z += 0.1) {
                for (double along = -1.72; along <= -1.3; along += 0.07) {
                    // Against the end backboard, which faces +X, and against the side backboard, which faces +Y.
                    assertEquals(1, classified(alliance, new Vector3(-1.72, along, z), new Vector3(-2.0, 0, -1.0)),
                            alliance + " goal, thrown at the end board from " + along + ", " + z);
                    assertEquals(1, classified(alliance, new Vector3(along, -1.72, z), new Vector3(0, -2.0, -1.0)),
                            alliance + " goal, thrown at the side board from " + along + ", " + z);
                    tried += 2;
                }
            }
        }
        assertTrue(tried >= 60, "the throws cover both boards of both goals: " + tried);
    }
}
