package dev.ftcsim.ui.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.field.FieldDescription;
import dev.ftcsim.contracts.field.FieldPart;
import dev.ftcsim.contracts.field.Pose3;
import dev.ftcsim.contracts.field.Scenery;
import dev.ftcsim.contracts.field.Shape;
import dev.ftcsim.ui.scene.SceneModel;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OrbitCameraTest {
    private static final double FRAME = 1.0 / 60.0;

    private static float[] eyeOf(OrbitCamera camera) {
        float[] eye = new float[3];
        camera.viewProjection(16f / 9f, new float[16], eye);
        return eye;
    }

    @Test
    void theOrbitTargetProjectsToTheCentreOfTheScreenInFrontOfTheCamera() {
        OrbitCamera camera = new OrbitCamera();
        float[] matrix = new float[16];
        camera.viewProjection(16f / 9f, matrix, new float[3]);

        // The camera looks at (0, 0, 0.12); in clip space that point must sit on the view axis, with w > 0.
        float z = 0.12f;
        float x = matrix[12] + matrix[8] * z;
        float y = matrix[13] + matrix[9] * z;
        float w = matrix[15] + matrix[11] * z;
        assertTrue(w > 0f);
        assertEquals(0.0, x / w, 1e-4);
        assertEquals(0.0, y / w, 1e-4);
    }

    @Test
    void pitchAndDistanceAreClampedAndEveryChangeBumpsTheVersion() {
        OrbitCamera camera = new OrbitCamera();
        long start = camera.version();

        camera.orbit(0, 10_000);
        assertEquals(OrbitCamera.MAX_PITCH_DEGREES, camera.pitchDegrees(), 1e-9);
        camera.orbit(0, -10_000);
        assertEquals(OrbitCamera.MIN_PITCH_DEGREES, camera.pitchDegrees(), 1e-9);
        camera.zoom(1000);
        assertEquals(OrbitCamera.MIN_DISTANCE, camera.distance(), 1e-9);
        camera.zoom(-1000);
        assertEquals(OrbitCamera.MAX_DISTANCE, camera.distance(), 1e-9);

        assertEquals(start + 4, camera.version());
    }

    @Test
    void presetsAreLaidOutBehindTheDriversEndOfTheField() {
        OrbitCamera camera = new OrbitCamera();
        camera.setDriverHeading(Math.PI / 2);

        camera.select(OrbitCamera.Preset.DRIVER);
        float[] eye = eyeOf(camera);
        assertEquals(0f, eye[0], 1e-4f);
        assertTrue(eye[1] < -3f, "the driver stands on the -Y side when the field runs towards +Y");
        assertTrue(eye[2] > 1f);

        camera.select(OrbitCamera.Preset.TOP);
        assertEquals(OrbitCamera.MAX_PITCH_DEGREES, camera.pitchDegrees(), 1e-9);
        assertEquals(OrbitCamera.Preset.TOP, camera.preset());
    }

    @Test
    void changingTheDriversHeadingMovesAnActivePreset() {
        OrbitCamera camera = new OrbitCamera();
        camera.select(OrbitCamera.Preset.DRIVER);
        float[] before = eyeOf(camera);

        camera.setDriverHeading(Math.PI);
        float[] after = eyeOf(camera);

        assertTrue(Math.abs(before[0] - after[0]) > 1f);
    }

    @Test
    void draggingLeavesAPresetAndContinuesFromWhereTheCameraStands() {
        OrbitCamera camera = new OrbitCamera();
        camera.select(OrbitCamera.Preset.TOP);
        double pitch = camera.pitchDegrees();

        camera.orbit(10, 0);

        assertEquals(OrbitCamera.Preset.FREE, camera.preset());
        assertEquals(pitch, camera.pitchDegrees(), 1e-9);
    }

    @Test
    void theChaseCameraSettlesBehindTheRobotWhereverItIs() {
        OrbitCamera camera = new OrbitCamera();
        camera.select(OrbitCamera.Preset.CHASE);

        for (int i = 0; i < 300; i++) {
            camera.track(2.0, 3.0, Math.PI / 2, FRAME);
        }

        float[] eye = eyeOf(camera);
        assertEquals(2f, eye[0], 1e-2f);
        assertTrue(eye[1] < 3f - 1f, "behind a robot that faces +Y is on its -Y side: " + eye[1]);
    }

    @Test
    void theChaseCameraTurnsTheShortWayRound() {
        OrbitCamera camera = new OrbitCamera();
        camera.select(OrbitCamera.Preset.CHASE);
        for (int i = 0; i < 300; i++) {
            camera.track(0.0, 0.0, Math.toRadians(170), FRAME);
        }

        camera.track(0.0, 0.0, Math.toRadians(-170), FRAME);

        // Across the 180 degree seam the view moves by a few degrees, not nearly a full turn.
        double yaw = camera.yawDegrees();
        assertTrue(Math.abs(Math.abs(yaw) - 10) < 20, "yaw " + yaw);
    }

    @Test
    void trackingDoesNothingOutsideTheChasePreset() {
        OrbitCamera camera = new OrbitCamera();
        camera.select(OrbitCamera.Preset.TOP);
        long version = camera.version();

        camera.track(5.0, 5.0, 1.0, FRAME);

        assertEquals(version, camera.version());
    }

    @Test
    void anArrivedChaseCameraIsStillAndStopsBumpingTheVersion() {
        OrbitCamera camera = new OrbitCamera();
        camera.select(OrbitCamera.Preset.CHASE);
        for (int i = 0; i < 600; i++) {
            camera.track(1.0, 1.0, 0.5, FRAME);
        }
        long version = camera.version();

        camera.track(1.0, 1.0, 0.5, FRAME);

        assertEquals(version, camera.version());
    }

    @Test
    void theChangeListenerHearsTheUserButNotTheRobotBeingTracked() {
        OrbitCamera camera = new OrbitCamera();
        AtomicInteger heard = new AtomicInteger();
        camera.onChange(heard::incrementAndGet);

        camera.orbit(3, 0);
        camera.zoom(1);
        camera.select(OrbitCamera.Preset.CHASE);
        camera.track(1.0, 1.0, 1.0, FRAME);

        assertEquals(3, heard.get());
    }

    @Test
    void aRobotThatStartsSquareToTheWallsIsWatchedAlongItsHeadingAndACornerStartFallsBackOnTheAudiencesView() {
        assertEquals(Math.PI, OrbitCamera.driverHeadingFor(Math.PI), 1e-12);
        assertEquals(-Math.PI / 2, OrbitCamera.driverHeadingFor(-Math.PI / 2), 1e-12);
        assertEquals(Math.PI / 2, OrbitCamera.driverHeadingFor(Math.toRadians(95)), 1e-12, "a few degrees off square is square");
        assertEquals(0.0, OrbitCamera.driverHeadingFor(Math.toRadians(-4)), 1e-12);

        // A robot in a goal corner faces across the diagonal; behind it is the goal, so the audience's view is used.
        assertEquals(Math.PI, OrbitCamera.driverHeadingFor(Math.toRadians(36)), 1e-12);
        assertEquals(Math.PI, OrbitCamera.driverHeadingFor(Math.toRadians(-45)), 1e-12);
        assertEquals(Math.PI, OrbitCamera.driverHeadingFor(Math.toRadians(135)), 1e-12);
    }

    private static SceneModel sceneWith(Scenery... items) {
        return SceneModel.of(new FieldDescription("t", "T", List.of(items), List.of(), List.of(), List.of(), List.of()), null);
    }

    private static double distanceToTarget(OrbitCamera camera, double targetX, double targetY) {
        float[] eye = eyeOf(camera);
        return Math.sqrt(Math.pow(eye[0] - targetX, 2) + Math.pow(eye[1] - targetY, 2) + Math.pow(eye[2] - 0.12, 2));
    }

    @Test
    void theChaseCameraStandsInFrontOfAGoalThatWouldHideTheRobotButLooksOverALowWall() {
        FieldPart tower = new FieldPart(new Shape.Box(0.3, 0.3, 0.6), Pose3.IDENTITY, 0x2D5FD3);
        FieldPart wall = new FieldPart(new Shape.Box(0.02, 2.0, 0.14), Pose3.IDENTITY, 0xBFD6E4);
        OrbitCamera open = new OrbitCamera();
        open.select(OrbitCamera.Preset.CHASE);
        OrbitCamera blocked = new OrbitCamera();
        blocked.setObstacles(sceneWith(new Scenery("goal", tower, Pose3.at(-2.0, 0.0, 0.6))));
        blocked.select(OrbitCamera.Preset.CHASE);
        OrbitCamera lowWall = new OrbitCamera();
        lowWall.setObstacles(sceneWith(new Scenery("wall", wall, Pose3.at(-1.0, 0.0, 0.14))));
        lowWall.select(OrbitCamera.Preset.CHASE);

        // The robot faces +X at x = -0.4, so the camera would be 1.9 m behind it, inside the tower whose face is at -1.7.
        for (int i = 0; i < 300; i++) {
            open.track(-0.4, 0.0, 0.0, FRAME);
            blocked.track(-0.4, 0.0, 0.0, FRAME);
            lowWall.track(-0.4, 0.0, 0.0, FRAME);
        }

        assertEquals(1.9, distanceToTarget(open, -0.4, 0.0), 1e-3);
        assertTrue(eyeOf(open)[0] < -2.0f);
        assertTrue(eyeOf(blocked)[0] > -1.7f, "the camera stops in front of the goal's face at x = -1.7: " + eyeOf(blocked)[0]);
        assertTrue(Math.abs(eyeOf(blocked)[1]) < 1e-3f, "still straight behind the robot");
        assertTrue(distanceToTarget(blocked, -0.4, 0.0) < 1.5 && distanceToTarget(blocked, -0.4, 0.0) >= 1.1);
        assertEquals(1.9, distanceToTarget(lowWall, -0.4, 0.0), 1e-3, "a wall as low as a robot is looked over");
    }

    @Test
    void aRobotStandingAgainstAGoalIsWatchedFromTheNearestSideThatIsClearAndFromBehindOnceItDrivesAway() {
        // A goal 0.6 m wide behind the robot, whose face is 0.3 m from it: there is no room to stand behind.
        FieldPart tower = new FieldPart(new Shape.Box(0.3, 0.6, 0.6), Pose3.IDENTITY, 0x2D5FD3);
        OrbitCamera camera = new OrbitCamera();
        camera.setObstacles(sceneWith(new Scenery("goal", tower, Pose3.at(-0.6, 0.0, 0.6))));
        camera.select(OrbitCamera.Preset.CHASE);

        for (int i = 0; i < 300; i++) {
            camera.track(0.0, 0.0, 0.0, FRAME);
        }

        float[] eye = eyeOf(camera);
        assertTrue(Math.abs(eye[1]) > 1.2f, "the camera has gone round to the side of the robot: " + Arrays.toString(eye));
        assertEquals(1.9, distanceToTarget(camera, 0.0, 0.0), 1e-3, "and from there nothing is in the way, so it is as far as ever");

        for (int i = 0; i < 300; i++) {
            camera.track(4.0, 0.0, 0.0, FRAME);
        }

        float[] later = eyeOf(camera);
        assertTrue(later[0] < 4.0f - 1.5f && Math.abs(later[1]) < 0.01f, "clear of the goal it is behind the robot again: " + Arrays.toString(later));
    }

    @Test
    void theChaseCameraBacksOffGentlyOnceTheViewIsClearAndThenStopsBumpingTheVersion() {
        FieldPart tower = new FieldPart(new Shape.Box(0.3, 0.3, 0.6), Pose3.IDENTITY, 0x2D5FD3);
        OrbitCamera camera = new OrbitCamera();
        camera.setObstacles(sceneWith(new Scenery("goal", tower, Pose3.at(-2.0, 0.0, 0.6))));
        camera.select(OrbitCamera.Preset.CHASE);
        for (int i = 0; i < 300; i++) {
            camera.track(-0.4, 0.0, 0.0, FRAME);
        }
        double close = distanceToTarget(camera, -0.4, 0.0);

        camera.track(3.0, 0.0, 0.0, FRAME);
        double next = distanceToTarget(camera, 3.0, 0.0);

        assertTrue(next > close && next < 1.9, "one frame after the view cleared the camera has moved out only a little: " + next);
        for (int i = 0; i < 600; i++) {
            camera.track(3.0, 0.0, 0.0, FRAME);
        }
        assertEquals(1.9, distanceToTarget(camera, 3.0, 0.0), 1e-3);
        long version = camera.version();
        camera.track(3.0, 0.0, 0.0, FRAME);
        assertEquals(version, camera.version());
    }

    @Test
    void aRobotStandingInsideSomethingTallIsNotHiddenByIt() {
        FieldPart hall = new FieldPart(new Shape.Box(3.0, 3.0, 0.6), Pose3.IDENTITY, 0x808080);
        OrbitCamera camera = new OrbitCamera();
        camera.setObstacles(sceneWith(new Scenery("hall", hall, Pose3.at(0, 0, 0.6))));
        camera.select(OrbitCamera.Preset.CHASE);

        for (int i = 0; i < 300; i++) {
            camera.track(0.0, 0.0, 0.0, FRAME);
        }

        assertEquals(1.9, distanceToTarget(camera, 0.0, 0.0), 1e-3, "a ray that starts inside a box is not blocked by it");
    }
}
