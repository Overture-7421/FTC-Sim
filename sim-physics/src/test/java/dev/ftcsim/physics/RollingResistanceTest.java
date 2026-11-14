package dev.ftcsim.physics;

import static dev.ftcsim.physics.TestWorlds.steps;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.world.BodyPose;
import org.junit.jupiter.api.Test;

class RollingResistanceTest {

    private static final float RADIUS = 0.06275f;
    private static final int SECOND = PhysicsWorld.STEPS_PER_SECOND;
    // A ball that is launched without spin slides until it rolls at 5/7 of its speed, then slows at 5/7 of c * g.
    private static final double ROLLING_SHARE = 5.0 / 7.0;
    private static final double GRAVITY = PhysicsWorld.GRAVITY;

    private static PieceSpec ball(float damping, float coefficient) {
        return new PieceSpec(RADIUS, 0.0748f, 0.5f, 0.35f, damping, coefficient);
    }

    private static PhysicsWorld wideFloor() {
        PhysicsWorld world = TestNatives.newWorld();
        world.addStaticBox(new Vector3(0, 0, -0.5), new Vector3(60, 60, 0.5));
        return world;
    }

    private static double speed(PhysicsWorld world, int id) {
        BodyPose pose = world.bodyPose(id);
        return Math.sqrt(pose.vx() * pose.vx() + pose.vy() * pose.vy() + pose.vz() * pose.vz());
    }

    private record Stop(double seconds, double meters) {}

    /** Launches a ball along +X on the wide floor and steps until it first moves slower than 1 mm/s. */
    private static Stop rollOut(PhysicsWorld world, float coefficient, double launchSpeed) {
        world.spawnPiece(1, 1, ball(0f, coefficient), new Vector3(-30, 0, RADIUS), new Vector3(launchSpeed, 0, 0));
        for (int step = 1; step <= 30 * SECOND; step++) {
            world.step();
            if (speed(world, 1) < 1e-3) {
                return new Stop(step / (double) SECOND, world.bodyPose(1).x() + 30);
            }
        }
        throw new AssertionError("the ball did not stop within 30 s, speed " + speed(world, 1));
    }

    @Test
    void aBallStopsAfterTheTimeAndDistanceThatItsCoefficientImplies() {
        double coefficient = 0.08;
        double launch = 1.5;
        try (PhysicsWorld world = wideFloor()) {
            Stop stop = rollOut(world, (float) coefficient, launch);
            double expectedSeconds = launch / (coefficient * GRAVITY);
            double expectedMeters = ROLLING_SHARE * launch * launch / (2 * coefficient * GRAVITY);
            assertEquals(expectedSeconds, stop.seconds(), 0.1 * expectedSeconds);
            assertEquals(expectedMeters, stop.meters(), 0.1 * expectedMeters);

            steps(world, 3 * SECOND);
            double stoppedAt = world.bodyPose(1).x();
            steps(world, 3 * SECOND);
            assertEquals(stoppedAt, world.bodyPose(1).x(), 0.0, "an asleep ball stays exactly where it is");
            assertEquals(0.0, speed(world, 1), 0.0);
        }
    }

    @Test
    void aBallTwiceAsFastRollsTwiceAsLongAndFourTimesAsFar() {
        Stop slow;
        Stop fast;
        try (PhysicsWorld world = wideFloor()) {
            slow = rollOut(world, 0.06f, 1.0);
        }
        try (PhysicsWorld world = wideFloor()) {
            fast = rollOut(world, 0.06f, 2.0);
        }
        double seconds = fast.seconds() / slow.seconds();
        double meters = fast.meters() / slow.meters();
        assertTrue(seconds > 1.8 && seconds < 2.2, "time ratio " + seconds);
        assertTrue(meters > 3.4 && meters < 4.4, "distance ratio " + meters);
    }

    @Test
    void aResistanceSmallerThanTheEnginesStaleContactsStillStopsABall() {
        // The lever of 0.5 mm is shorter than the 1 mm by which the engine's reused contact points can drive a roll.
        try (PhysicsWorld world = wideFloor()) {
            Stop stop = rollOut(world, 0.008f, 0.5);
            double expectedSeconds = 0.5 / (0.008 * GRAVITY);
            assertEquals(expectedSeconds, stop.seconds(), 0.2 * expectedSeconds);
            steps(world, 3 * SECOND);
            assertEquals(0.0, speed(world, 1), 0.0);
        }
    }

    @Test
    void withoutARollingResistanceABallStillCrawlsAfterHalfAMinute() {
        try (PhysicsWorld world = wideFloor()) {
            world.spawnPiece(1, 1, ball(0.5f, 0f), new Vector3(-30, 0, RADIUS), new Vector3(1.0, 0, 0));
            steps(world, 30 * SECOND);
            assertTrue(speed(world, 1) > 0.1, "the engine leaves such a ball rolling at " + speed(world, 1));
        }
    }

    /** A plane tilted by an angle about +Y that falls away towards +X, with its top surface through the origin. */
    private record Slope(double radians) {

        Vector3 normal() {
            return new Vector3(Math.sin(radians), 0, Math.cos(radians));
        }

        Vector3 downhill() {
            return new Vector3(Math.cos(radians), 0, -Math.sin(radians));
        }

        PhysicsWorld worldWithBallAtRest(float coefficient) {
            PhysicsWorld world = TestNatives.newWorld();
            world.addStaticBox(normal().times(-0.1), new Vector3(4, 3, 0.1), Vector3.Y, radians);
            world.spawnPiece(1, 1, ball(0f, coefficient), normal().times(RADIUS), Vector3.ZERO);
            return world;
        }

        double speedDownhill(PhysicsWorld world) {
            BodyPose pose = world.bodyPose(1);
            return new Vector3(pose.vx(), pose.vy(), pose.vz()).dot(downhill());
        }
    }

    @Test
    void aBallKeepsRollingDownASlopeSteeperThanItsCoefficient() {
        double coefficient = 0.08;
        Slope slope = new Slope(Math.toRadians(10));
        try (PhysicsWorld world = slope.worldWithBallAtRest((float) coefficient)) {
            steps(world, SECOND);
            double before = slope.speedDownhill(world);
            steps(world, SECOND / 2);
            double acceleration = (slope.speedDownhill(world) - before) / 0.5;
            double expected = ROLLING_SHARE * GRAVITY * (Math.sin(slope.radians()) - coefficient * Math.cos(slope.radians()));
            assertEquals(expected, acceleration, 0.1 * expected);
        }
    }

    @Test
    void aBallStaysPutOnASlopeGentlerThanItsCoefficient() {
        Slope slope = new Slope(Math.toRadians(3));
        try (PhysicsWorld world = slope.worldWithBallAtRest(0.08f)) {
            steps(world, 4 * SECOND);
            BodyPose pose = world.bodyPose(1);
            Vector3 start = slope.normal().times(RADIUS);
            assertTrue(new Vector3(pose.x(), pose.y(), pose.z()).minus(start).length() < 0.01, "the ball rolled away");
            assertEquals(0.0, speed(world, 1), 0.0);
        }
    }

    private static String hashOfAHeldPieceCarriedIntoAnotherBall(float coefficient) {
        try (PhysicsWorld world = wideFloor()) {
            world.spawnPiece(1, 1, ball(0f, coefficient), new Vector3(0, 0, RADIUS), Vector3.ZERO);
            world.spawnPiece(2, 1, ball(0f, 0f), new Vector3(0.8, 0, RADIUS), Vector3.ZERO);
            world.holdPiece(1);
            // Carried at 0.5 m/s and turning as a rolling ball does: both are what the held piece is told to do.
            for (int step = 1; step <= 2 * SECOND; step++) {
                double x = 0.5 * step / SECOND;
                world.moveHeldPiece(1, new Vector3(x, 0, RADIUS), Vector3.Y, x / RADIUS);
                world.step();
            }
            assertTrue(world.isPieceHeld(1));
            assertTrue(world.bodyPose(2).x() > 0.85, "the held piece pushed the other ball");
            return world.stateHash();
        }
    }

    @Test
    void aHeldPieceIsLeftAloneAsIfThereWasNoRollingResistance() {
        assertEquals(hashOfAHeldPieceCarriedIntoAnotherBall(0f), hashOfAHeldPieceCarriedIntoAnotherBall(0.08f));
    }

    @Test
    void theCoefficientOfRollingResistanceCannotBeNegativeOrUndefined() {
        assertThrows(IllegalArgumentException.class, () -> ball(0f, -0.01f));
        assertThrows(IllegalArgumentException.class, () -> ball(0f, Float.NaN));
        assertEquals(0f, new PieceSpec(RADIUS, 0.0748f, 0.5f, 0.35f, 0.5f).rollingResistance(), 0f);
    }
}
