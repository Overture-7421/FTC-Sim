package dev.ftcsim.hardware.camera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.world.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

class PoseSolverTest {

    private static final double EXACT = 1e-6;

    private static void assertPose(Rigid truth, Rigid found, double tolerance) {
        assertEquals(0.0, TestScene.distance(truth.translation(), found.translation()), tolerance, "position");
        Mat3 difference = truth.rotation().transpose().times(found.rotation());
        assertEquals(0.0, Math.abs(difference.yaw()) + Math.abs(difference.pitch()) + Math.abs(difference.roll()), tolerance, "rotation");
    }

    @Test
    void anIdealCameraRecoversTheCornersItWasShownFromTheirPixels() {
        Rigid robot = TestScene.robotFacing(TestScene.blueGoal(), 2.0, 0.3, 0.1);
        double[] pixels = TestScene.pixels(robot, TestScene.blueGoal());
        Rigid fieldToCamera = TestScene.cameraInField(robot).inverse();

        double[] corners = TestScene.blueGoal().fieldCorners(Rigid.IDENTITY);
        for (int i = 0; i < 4; i++) {
            Vec3 inCamera = fieldToCamera.apply(new Vec3(corners[3 * i], corners[3 * i + 1], corners[3 * i + 2]));
            Vec3 ray = TestScene.CAMERA.ray(pixels[2 * i], pixels[2 * i + 1]);
            Vec3 back = ray.times(inCamera.z());
            assertEquals(0.0, TestScene.distance(inCamera, back), 1e-9, "the pixel's ray through the true depth hits the corner");
        }
    }

    @Test
    void theClosedFormSolutionContainsTheTruePoseOfASingleTag() {
        SceneTag tag = TestScene.blueGoal();
        Rigid robot = TestScene.robotFacing(tag, 1.7, -0.2, 0.15);
        double[] pixels = TestScene.pixels(robot, tag);
        Rigid truth = TestScene.cameraInField(robot).inverse().compose(tag.inHost());

        List<Rigid> candidates = PlanarPose.candidates(TestScene.CAMERA, pixels, 0.5 * TestScene.TAG_SIZE);

        assertEquals(2, candidates.size(), "a tilted square has two poses");
        double nearest = Double.MAX_VALUE;
        for (Rigid candidate : candidates) {
            nearest = Math.min(nearest, TestScene.distance(candidate.translation(), truth.translation()));
        }
        assertEquals(0.0, nearest, 1e-9, "one of them is exact");
    }

    @Test
    void fittingTheCornersOfOneTagGivesBackTheExactRobotPose() {
        SceneTag tag = TestScene.blueGoal();
        for (double distance : new double[] {1.0, 1.5, 3.0, 4.5}) {
            for (double sideways : new double[] {-0.6, 0.0, 0.5}) {
                Rigid robot = TestScene.robotFacing(tag, distance, sideways, 0.05);
                PoseSolver.Observation seen = TestScene.observe(robot, tag);

                PoseSolver.Fit fit = TestScene.solver().fit(
                        List.of(seen), List.of(tag.inHost()), List.of(0.5 * TestScene.TAG_SIZE), 0.3);

                assertNotNull(fit);
                assertPose(robot, fit.robot(), EXACT);
                assertEquals(0.0, fit.cost(), 1e-9);
            }
        }
    }

    @Test
    void twoTagsInDifferentPlanesAreFittedTogetherToTheExactPose() {
        SceneTag blue = TestScene.blueGoal();
        SceneTag red = TestScene.facing(24, -1.4826, 1.4133, 0.7493, StrictMath.toRadians(-54.0));
        Rigid robot = TestScene.robot(-0.4, 0.0, 0.1, StrictMath.toRadians(180.0 + 5.0));
        // From here the robot looks back at both goals' tags along -X.
        PoseSolver.Observation a = TestScene.observe(robot, blue);
        PoseSolver.Observation b = TestScene.observe(robot, red);

        PoseSolver.Fit fit = TestScene.solver().fit(
                List.of(a, b),
                List.of(blue.inHost(), red.inHost()),
                List.of(0.5 * TestScene.TAG_SIZE, 0.5 * TestScene.TAG_SIZE),
                0.3);

        assertNotNull(fit);
        assertPose(robot, fit.robot(), EXACT);
    }

    @Test
    void theAnalyticJacobianAgreesWithDifferencesOfTheResiduals() {
        SceneTag tag = TestScene.blueGoal();
        Rigid robot = TestScene.robotFacing(tag, 2.2, 0.2, 0.1);
        List<PoseSolver.Observation> seen = List.of(TestScene.observe(robot, tag));
        // A pose that is not the truth, so that the residuals are not zero.
        Mat3 rotation = Mat3.rotZ(robot.rotation().yaw() + 0.02).times(Mat3.rotY(0.01));
        Vec3 position = robot.translation().plus(new Vec3(0.03, -0.02, 0.01));
        PoseSolver solver = TestScene.solver();

        double[][] analytic = solver.jacobian(seen, rotation, position, true);

        double step = 1e-6;
        for (int p = 0; p < 6; p++) {
            double[] delta = new double[6];
            delta[p] = step;
            double[] plus = solver.residuals(seen, shifted(rotation, delta, 1), shiftedPosition(position, delta, 1));
            double[] minus = solver.residuals(seen, shifted(rotation, delta, -1), shiftedPosition(position, delta, -1));
            for (int row = 0; row < plus.length; row++) {
                assertEquals((plus[row] - minus[row]) / (2 * step), analytic[row][p], 1e-4 * Math.max(1.0, Math.abs(analytic[row][p])),
                        "row " + row + " parameter " + p);
            }
        }
    }

    private static Mat3 shifted(Mat3 rotation, double[] delta, int sign) {
        return Mat3.fromRotationVector(sign * delta[0], sign * delta[1], sign * delta[2]).times(rotation);
    }

    private static Vec3 shiftedPosition(Vec3 position, double[] delta, int sign) {
        return position.plus(new Vec3(sign * delta[3], sign * delta[4], sign * delta[5]));
    }

    @Test
    void knowingTheRotationTheFitFindsTheExactPositionOfOneTag() {
        SceneTag tag = TestScene.blueGoal();
        Rigid robot = TestScene.robotFacing(tag, 2.5, 0.4, -0.1);
        PoseSolver.Observation seen = TestScene.observe(robot, tag);

        PoseSolver.Fit fit = TestScene.solver().fitPosition(List.of(seen), robot.rotation(), 0.3);

        assertNotNull(fit);
        assertEquals(0.0, TestScene.distance(robot.translation(), fit.robot().translation()), EXACT);
        assertTrue(fit.sigma()[3] == 0.0 && fit.sigma()[5] == 0.0, "no rotation is left to be uncertain");
    }

    @Test
    void roundingTheCornersToWholePixelsLeavesAnErrorThatGrowsWithTheSquareOfTheRange() {
        // Seen at 35 degrees, where the tag's two poses are far apart, so that only the rounding is left.
        SceneTag tag = TestScene.blueGoal();
        Vec3 normal = tag.inHost().rotation().column(2);
        Vec3 right = tag.inHost().rotation().column(0);
        double[] rootMeanSquare = new double[3];
        double[] ranges = {0.6, 0.9, 1.35};
        for (int r = 0; r < ranges.length; r++) {
            double sum = 0;
            int n = 0;
            for (int k = 0; k < 40; k++) {
                double distance = ranges[r] * (0.9 + 0.005 * k);
                Vec3 position = tag.inHost().translation().plus(normal.times(distance)).plus(right.times(0.7 * distance));
                double toTag = StrictMath.atan2(
                        tag.inHost().translation().y() - position.y(), tag.inHost().translation().x() - position.x());
                Rigid robot = TestScene.robot(position.x(), position.y(), 0.1, toTag);
                double[] pixels = TestScene.pixels(robot, tag);
                for (int i = 0; i < pixels.length; i++) {
                    pixels[i] = Math.rint(pixels[i]);
                }
                PoseSolver.Observation seen = new PoseSolver.Observation(tag.fieldCorners(Rigid.IDENTITY), pixels);
                PoseSolver.Fit fit = TestScene.solver().fit(List.of(seen), List.of(tag.inHost()), List.of(0.5 * TestScene.TAG_SIZE), 0.3);
                double error = TestScene.distance(robot.translation(), fit.robot().translation());
                sum += error * error;
                n++;
            }
            rootMeanSquare[r] = Math.sqrt(sum / n);
        }

        assertTrue(rootMeanSquare[0] > 0.0 && rootMeanSquare[0] < 0.01, "at 60 cm a pixel is about a millimetre: " + rootMeanSquare[0]);
        assertTrue(rootMeanSquare[1] > 1.3 * rootMeanSquare[0] && rootMeanSquare[1] < 4.0 * rootMeanSquare[0],
                "half as far again, about twice the error: " + rootMeanSquare[0] + " " + rootMeanSquare[1]);
        assertTrue(rootMeanSquare[2] > 1.3 * rootMeanSquare[1] && rootMeanSquare[2] < 4.0 * rootMeanSquare[1],
                "and again: " + rootMeanSquare[1] + " " + rootMeanSquare[2]);
    }

    @Test
    void theStandardDeviationsGrowWithTheDistanceToTheTag() {
        SceneTag tag = TestScene.blueGoal();
        double[] previous = null;
        for (double distance : new double[] {1.0, 2.0, 4.0}) {
            Rigid robot = TestScene.robotFacing(tag, distance, 0.0, 0.1);
            PoseSolver.Fit fit = TestScene.solver().fit(
                    List.of(TestScene.observe(robot, tag)), List.of(tag.inHost()), List.of(0.5 * TestScene.TAG_SIZE), 0.4);
            assertNotNull(fit);
            if (previous != null) {
                assertTrue(fit.sigma()[0] > previous[0] && fit.sigma()[1] > previous[1], "x and y");
                assertTrue(fit.sigma()[5] > previous[5], "yaw");
            }
            previous = fit.sigma();
        }
    }
}
