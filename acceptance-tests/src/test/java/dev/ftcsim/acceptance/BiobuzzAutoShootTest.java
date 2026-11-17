package dev.ftcsim.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.scenarios.opmodes.BiobuzzAutoShoot;
import dev.ftcsim.scenarios.opmodes.BiobuzzAutoShoot.Command;
import dev.ftcsim.scenarios.opmodes.BiobuzzAutoShoot.Solution;
import dev.ftcsim.scenarios.opmodes.BiobuzzAutoShoot.State;
import dev.ftcsim.scenarios.opmodes.BiobuzzHive;
import dev.ftcsim.scenarios.opmodes.BiobuzzPoseEstimator.Pose;
import org.junit.jupiter.api.Test;

/** The BIOBUZZ stub's automatic shot on its own: the aim and the speed it asks for, and when it opens the stopper. */
class BiobuzzAutoShootTest {
    private static final Pose POSE = new Pose(-1.2, -0.4, 0.3);
    private static final double DT = 0.02;

    /** Flies a ball from the opening at the speed the flywheels would give it and returns how close it passes to the CELL. */
    private static double missDistance(Pose pose, Solution solution) {
        double rpmFactor = 2.0 * Math.PI / 28.0;
        double motorRadPerSecond = solution.flywheelTicksPerSecond * rpmFactor;
        double speed = 0.49 * motorRadPerSecond / (43.0 / 30.0) * 0.0381;
        double theta = Math.toRadians(45.0 + 1800.0 / (730.0 / 24.0) * solution.hoodPosition);
        double heading = pose.heading() + Math.toRadians(solution.bearingDegrees);
        double axisX = pose.x() + 0.089 * Math.cos(pose.heading());
        double axisY = pose.y() + 0.089 * Math.sin(pose.heading());
        double x = axisX + 0.111 * Math.cos(heading);
        double y = axisY + 0.111 * Math.sin(heading);
        double z = 0.4074;
        double vx = speed * Math.cos(theta) * Math.cos(heading);
        double vy = speed * Math.cos(theta) * Math.sin(heading);
        double vz = speed * Math.sin(theta);
        BiobuzzHive.Tag cell = BiobuzzHive.nearestBlueCell(pose.x());
        double best = Double.MAX_VALUE;
        for (int i = 0; i < 4000; i++) {
            best = Math.min(best, Math.sqrt(Math.pow(x - cell.x(), 2) + Math.pow(y - cell.y(), 2) + Math.pow(z - cell.z(), 2)));
            x += vx * 0.001;
            y += vy * 0.001;
            z += vz * 0.001;
            vz -= 9.81 * 0.001;
        }
        return best;
    }

    @Test
    void theBearingAndTheSpeedItAsksForPutTheBallOnTheCellForEveryDistance() {
        for (double x : new double[] {-1.6, -1.2, -0.9, 0.9, 1.4}) {
            for (double y : new double[] {-1.4, -0.6, 0.0}) {
                Pose pose = new Pose(x, y, 0.4);
                Solution solution = BiobuzzAutoShoot.solve(pose);
                if (!solution.reachable) {
                    continue;
                }
                assertTrue(missDistance(pose, solution) < 0.03, "from " + x + ", " + y + " the ball misses by " + missDistance(pose, solution));
            }
        }
        assertTrue(BiobuzzAutoShoot.solve(new Pose(-1.2, -0.6, 0.0)).reachable, "a usual shot is in reach");
    }

    @Test
    void aShortShotRaisesTheHoodToItsStopAndALongerOneLessAndNeitherAsksForMoreThanTheHoodHas() {
        Solution near = BiobuzzAutoShoot.solve(new Pose(-0.9, 0.0, 0.0));
        Solution middle = BiobuzzAutoShoot.solve(new Pose(-1.1, -0.25, 0.0));
        Solution far = BiobuzzAutoShoot.solve(new Pose(-1.6, -1.4, 0.0));
        assertTrue(near.reachable && middle.reachable && far.reachable);
        assertEquals(BiobuzzAutoShoot.HOOD_MAX_POSITION, near.hoodPosition, 1e-9, "a steep shot needs the hood at its stop, 24 degrees up");
        assertEquals(24.0 / (1800.0 / (730.0 / 24.0)), BiobuzzAutoShoot.HOOD_MAX_POSITION, 1e-12, "which is position 0.41 of a five-turn servo");
        assertTrue(far.hoodPosition > 0.0 && far.hoodPosition < middle.hoodPosition && middle.hoodPosition < near.hoodPosition,"a longer shot wants the hood lower: " + far.hoodPosition + " " + middle.hoodPosition);
        for (double x : new double[] {-1.6, -1.2, -0.9, 0.9, 1.4}) {
            Solution each = BiobuzzAutoShoot.solve(new Pose(x, -0.6, 0.4));
            assertTrue(each.hoodPosition >= 0.0 && each.hoodPosition <= BiobuzzAutoShoot.HOOD_MAX_POSITION, "hood " + each.hoodPosition);
        }
        assertFalse(BiobuzzAutoShoot.solve(new Pose(0.4, 0.31, 0.0)).reachable, "standing at the CELL: there is no arc");
    }

    /** Runs the sequence with the robot, the turret and the flywheels exactly where they are asked to be. */
    @Test
    void theStopperOpensAndTheFeedRunsOnlyOnceTheTurretTheFlywheelsAndTheHoodAreWhereTheyShouldBe() {
        BiobuzzAutoShoot shot = new BiobuzzAutoShoot();
        Solution solution = BiobuzzAutoShoot.solve(POSE);
        double now = 0.0;

        Command idle = shot.update(now, POSE, 0.0, 0.0, 0.0);
        assertEquals(State.IDLE, shot.state());
        assertEquals(0.0, idle.flywheelPower + idle.indexerPower + idle.turretPower, 0.0);
        assertFalse(idle.stopperOpen);

        shot.press(now, 0.0);
        // The turret is far off, the flywheels stopped: spin up and turn, with the stopper shut and the indexer still.
        Command first = shot.update(now += DT, POSE, 0.0, 0.0, 0.0);
        assertEquals(State.AIMING, shot.state());
        assertTrue(first.flywheelPower > 0.0 && first.turretPower != 0.0, "it spins up and turns at once");
        assertFalse(first.stopperOpen);
        assertEquals(0.0, first.indexerPower, 0.0);
        assertEquals(Math.signum(solution.bearingDegrees), Math.signum(first.turretPower), 0.0, "towards the CELL");

        // The turret and flywheels get there, the hood is still moving: not ready.
        Command hoodMoving = shot.update(now += DT, POSE, solution.bearingDegrees, solution.flywheelTicksPerSecond, 0.0);
        assertFalse(hoodMoving.stopperOpen);
        for (int i = 0; i < 40; i++) {
            assertFalse(shot.update(now += DT, POSE, solution.bearingDegrees, solution.flywheelTicksPerSecond, 0.0).stopperOpen,
                    "the hood servo needs its second");
        }
        // The robot is turning: still not ready.
        for (int i = 0; i < 20; i++) {
            shot.update(now += DT, POSE, solution.bearingDegrees, solution.flywheelTicksPerSecond, 60.0);
        }
        assertEquals(State.AIMING, shot.state());

        for (int i = 0; i < 150 && shot.state() == State.AIMING; i++) {
            shot.update(now += DT, POSE, solution.bearingDegrees, solution.flywheelTicksPerSecond, 0.0);
        }
        assertEquals(State.FEEDING, shot.state(), shot.status());

        // The stopper opens first and the indexer follows it once it has had time to open.
        Command opening = shot.update(now += DT, POSE, solution.bearingDegrees, solution.flywheelTicksPerSecond, 0.0);
        assertTrue(opening.stopperOpen);
        assertEquals(0.0, opening.indexerPower, 0.0, "the stopper is still opening");
        double fed = 0.0;
        boolean paused = false;
        for (int i = 0; i < 500 && shot.state() == State.FEEDING; i++) {
            boolean sagging = i == 100;
            Command feeding = shot.update(now += DT, POSE, solution.bearingDegrees,
                    sagging ? solution.flywheelTicksPerSecond * 0.8 : solution.flywheelTicksPerSecond, 0.0);
            if (sagging) {
                paused = feeding.indexerPower == 0.0;
            } else if (feeding.indexerPower > 0.0) {
                fed += DT;
            }
        }
        assertTrue(paused, "a shot that dipped the flywheels pauses the feed until they recover");
        assertEquals(State.DONE, shot.state(), shot.status());
        assertEquals(BiobuzzAutoShoot.FEED_SECONDS, fed, 0.1);

        Command done = shot.update(now += DT, POSE, solution.bearingDegrees, solution.flywheelTicksPerSecond, 0.0);
        assertEquals(0.0, done.flywheelPower + done.indexerPower + done.turretPower, 0.0, "everything is off at the end");
        assertFalse(done.stopperOpen);
        assertFalse(shot.active());
    }

    @Test
    void aPressAgainCancelsAndWithoutAPoseOrWhereItCannotReachItGivesUpWithoutOpeningTheStopper() {
        BiobuzzAutoShoot cancelled = new BiobuzzAutoShoot();
        cancelled.press(0.0, 0.0);
        cancelled.update(0.1, POSE, 0.0, 0.0, 0.0);
        cancelled.press(0.2, 0.0);
        assertEquals(State.IDLE, cancelled.state());
        assertTrue(cancelled.status().contains("cancelled"));

        BiobuzzAutoShoot blind = new BiobuzzAutoShoot();
        blind.press(0.0, 0.0);
        Command waiting = blind.update(1.0, null, 0.0, 0.0, 0.0);
        assertEquals(State.AIMING, blind.state());
        assertFalse(waiting.stopperOpen);
        assertTrue(blind.status().contains("pose"), blind.status());
        blind.update(9.0, null, 0.0, 0.0, 0.0);
        assertEquals(State.FAILED, blind.state(), "no pose for too long");

        BiobuzzAutoShoot tooClose = new BiobuzzAutoShoot();
        tooClose.press(0.0, 0.0);
        Command refused = tooClose.update(0.1, new Pose(0.4, 0.31, 0.0), 0.0, 0.0, 0.0);
        assertEquals(State.FAILED, tooClose.state());
        assertTrue(tooClose.status().contains("out of range"), tooClose.status());
        assertFalse(refused.stopperOpen);
    }
}
