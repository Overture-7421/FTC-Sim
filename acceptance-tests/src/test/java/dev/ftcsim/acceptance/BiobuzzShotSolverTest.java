package dev.ftcsim.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.scenarios.opmodes.BiobuzzHive;
import dev.ftcsim.scenarios.opmodes.BiobuzzHive.Alliance;
import dev.ftcsim.scenarios.opmodes.BiobuzzHive.Tilt;
import dev.ftcsim.scenarios.opmodes.BiobuzzPoseEstimator.Pose;
import dev.ftcsim.scenarios.opmodes.BiobuzzShotSolver;
import dev.ftcsim.scenarios.opmodes.BiobuzzShotSolver.Reason;
import dev.ftcsim.scenarios.opmodes.BiobuzzShotSolver.Solution;
import org.junit.jupiter.api.Test;

/** The BIOBUZZ shot solver on its own: where it aims, how steeply and how fast, and when it says there is no shot. */
class BiobuzzShotSolverTest {
    /** A robot at {@code (x, y)} facing the red HIVE's middle, which is how a driver stands. */
    private static Pose facingRedHive(double x, double y) {
        return new Pose(x, y, Math.atan2(-0.3236 - y, -x));
    }

    private static Solution red(double x, double y) {
        return BiobuzzShotSolver.solve(facingRedHive(x, y), Alliance.RED, Tilt.AUDIENCE_UP, Double.NaN);
    }

    /** Flies a ball from the opening at the speed the flywheels would give it, as a plain integration, and returns how near it passes the aim. */
    private static double missDistance(Pose pose, Solution solution) {
        double rimMetersPerSecond = solution.flywheelTicksPerSecond * 2.0 * Math.PI / 28.0 / (43.0 / 30.0) * 0.0381;
        double speed = 0.49 * rimMetersPerSecond;
        double theta = Math.toRadians(solution.elevationDegrees);
        double heading = pose.heading() + Math.toRadians(solution.bearingDegrees);
        // Where the ball leaves, worked out here from the CAD's wheels and hood rather than taken from the solver.
        double ahead = 0.162 - 0.0737 * Math.sin(theta);
        double x = pose.x() + 0.089 * Math.cos(pose.heading()) + (ahead - 0.089) * Math.cos(heading);
        double y = pose.y() + 0.089 * Math.sin(pose.heading()) + (ahead - 0.089) * Math.sin(heading);
        double z = 0.1824 + 0.2146 + 0.0737 * Math.cos(theta);
        double vx = speed * Math.cos(theta) * Math.cos(heading);
        double vy = speed * Math.cos(theta) * Math.sin(heading);
        double vz = speed * Math.sin(theta);
        double best = Double.MAX_VALUE;
        for (int i = 0; i < 4000; i++) {
            best = Math.min(best, Math.sqrt(Math.pow(x - solution.targetX, 2) + Math.pow(y - solution.targetY, 2) + Math.pow(z - solution.targetZ, 2)));
            x += vx * 0.001;
            y += vy * 0.001;
            z += vz * 0.001;
            vz -= 9.81 * 0.001;
        }
        return best;
    }

    @Test
    void theBearingTheElevationAndTheSpeedItAsksForPutTheBallOnTheCellsMouthFromNearAndFromFar() {
        for (double[] place : new double[][] {{1.0, -0.8}, {1.2, -0.3}, {1.4, -1.0}, {1.5, -1.5}, {1.7, -1.0}}) {
            Pose pose = facingRedHive(place[0], place[1]);
            Solution solution = BiobuzzShotSolver.solve(pose, Alliance.RED, Tilt.AUDIENCE_UP, Double.NaN);
            assertTrue(solution.feasible, "a shot from " + place[0] + ", " + place[1] + ": " + solution.describe());
            assertTrue(missDistance(pose, solution) < 0.03, "from " + place[0] + ", " + place[1] + " the ball misses by " + missDistance(pose, solution));
            assertTrue(solution.hoodPosition >= 0.0 && solution.hoodPosition <= BiobuzzShotSolver.HOOD_MAX_POSITION, "hood " + solution.hoodPosition);
            assertTrue(solution.flywheelTicksPerSecond <= 0.95 * 2130.0, "within what the flywheels can do: " + solution.flywheelTicksPerSecond);
            assertTrue(solution.marginDegrees >= 1.0, "with some room either side of the elevation");
        }
    }

    @Test
    void aNearShotIsASteeperLobThanAFarOneAndTheHoodPositionIsWhereThatAngleIs() {
        Solution near = red(1.0, -0.8);
        Solution middle = red(1.4, -1.0);
        Solution far = red(1.7, -1.0);
        assertTrue(near.feasible && middle.feasible && far.feasible);
        assertTrue(near.elevationDegrees > middle.elevationDegrees && middle.elevationDegrees > far.elevationDegrees,
                near.elevationDegrees + " " + middle.elevationDegrees + " " + far.elevationDegrees);
        assertTrue(near.elevationDegrees > 70.0, "a lob that comes down into a CELL that is open at the end of its arm: " + near.elevationDegrees);
        assertEquals(45.0 + near.hoodPosition * 1800.0 / (730.0 / 24.0), near.elevationDegrees, 1e-9, "the hood servo's five turns behind the 730:24 gears");
        assertEquals(44.0 / (1800.0 / (730.0 / 24.0)), BiobuzzShotSolver.HOOD_MAX_POSITION, 1e-12, "the hood's stop is position 0.74 of the servo");
    }

    @Test
    void itSaysWhyThereIsNoShotWhenThereIsNone() {
        assertEquals(Reason.WRONG_SIDE, red(-1.2, -0.8).reason, "the CELL that faces up opens to the audience side");
        assertEquals(Reason.WRONG_SIDE, red(0.0, -1.5).reason, "under the arm is not the side either");
        assertEquals(Reason.TOO_CLOSE, red(0.7, -1.0).reason, "no lob this steep gets in from so near");
        assertEquals(Reason.OUT_OF_RANGE, red(3.8, -1.0).reason, "the flywheels are not fast enough from beyond the field");
        for (double[] place : new double[][] {{-1.2, -0.8}, {0.7, -1.0}, {3.8, -1.0}}) {
            Solution solution = red(place[0], place[1]);
            assertFalse(solution.feasible);
            assertTrue(solution.describe().startsWith("no shot"), solution.describe());
        }
    }

    @Test
    void afterTheTipTheOtherCellIsTheOneAndTheRobotMustBeOnItsSide() {
        Pose audienceSide = facingRedHive(1.4, -1.0);
        Solution tipped = BiobuzzShotSolver.solve(audienceSide, Alliance.RED, Tilt.SCORING_UP, Double.NaN);
        assertEquals(Reason.WRONG_SIDE, tipped.reason);
        Solution scoringSide = BiobuzzShotSolver.solve(facingRedHive(-1.4, -1.0), Alliance.RED, Tilt.SCORING_UP, Double.NaN);
        assertTrue(scoringSide.feasible, scoringSide.describe());
    }

    @Test
    void theBlueHiveIsTheRedOneTurnedHalfAWayRoundSoItsShotsAreTheSame() {
        for (double[] place : new double[][] {{1.0, -0.8}, {1.4, -1.0}, {1.7, -1.0}}) {
            Pose redPose = facingRedHive(place[0], place[1]);
            Pose bluePose = new Pose(-redPose.x(), -redPose.y(), redPose.heading() + Math.PI);
            Solution forRed = BiobuzzShotSolver.solve(redPose, Alliance.RED, BiobuzzHive.startTilt(Alliance.RED), Double.NaN);
            Solution forBlue = BiobuzzShotSolver.solve(bluePose, Alliance.BLUE, BiobuzzHive.startTilt(Alliance.BLUE), Double.NaN);
            assertTrue(forRed.feasible && forBlue.feasible);
            assertEquals(forRed.bearingDegrees, forBlue.bearingDegrees, 1e-6);
            assertEquals(forRed.distanceMeters, forBlue.distanceMeters, 1e-9);
            assertEquals(forRed.hoodPosition, forBlue.hoodPosition, 1e-9);
            assertEquals(forRed.flywheelTicksPerSecond, forBlue.flywheelTicksPerSecond, 1e-6);
        }
    }

    @Test
    void aHoodThatStillWorksIsKeptSoThatTheSlowServoDoesNotChaseTheEstimate() {
        Solution first = red(1.4, -1.0);
        Solution moved = BiobuzzShotSolver.solve(facingRedHive(1.43, -1.0), Alliance.RED, Tilt.AUDIENCE_UP, first.hoodPosition);
        assertTrue(moved.feasible);
        assertEquals(first.hoodPosition, moved.hoodPosition, 0.0, "the same position, though the best has moved a little");
        Solution far = BiobuzzShotSolver.solve(facingRedHive(1.7, -1.0), Alliance.RED, Tilt.AUDIENCE_UP, red(1.0, -0.8).hoodPosition);
        assertTrue(far.feasible);
        assertTrue(far.hoodPosition < red(1.0, -0.8).hoodPosition - 0.1, "but one that no longer works is replaced");
    }

    @Test
    void theTurretsBearingIsTheDirectionOfTheMouthFromItsAxisWhateverTheRobotsHeading() {
        for (double heading : new double[] {0.0, 1.0, 2.5, -2.0}) {
            Pose pose = new Pose(1.4, -1.0, heading);
            double axisX = 1.4 + 0.089 * Math.cos(heading);
            double axisY = -1.0 + 0.089 * Math.sin(heading);
            double[] aim = BiobuzzHive.upCell(Alliance.RED, Tilt.AUDIENCE_UP).aimPoint();
            double expected = Math.toDegrees(Math.atan2(aim[1] - axisY, aim[0] - axisX) - heading);
            assertEquals(0.0, Math.IEEEremainder(BiobuzzShotSolver.bearingDegrees(pose, Alliance.RED, Tilt.AUDIENCE_UP) - expected, 360.0), 1e-9);
        }
    }
}
