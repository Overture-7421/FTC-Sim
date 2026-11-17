package dev.ftcsim.hardware.camera;

import static dev.ftcsim.hardware.camera.CameraWorld.MS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.field.FieldTag;
import dev.ftcsim.contracts.field.Pose3;
import dev.ftcsim.contracts.hardware.CameraWire;
import dev.ftcsim.contracts.world.BodyPose;
import dev.ftcsim.contracts.world.RayView;
import dev.ftcsim.contracts.world.SurfaceHit;
import dev.ftcsim.contracts.world.Vec3;
import dev.ftcsim.hardware.device.TurretView;
import dev.ftcsim.hardware.device.scripted.ScriptedBody;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.LongFunction;
import org.junit.jupiter.api.Test;

class LimelightModelTest {

    private static final List<FieldTag> GOALS = List.of(CameraWorld.blueGoal(), CameraWorld.redGoal());

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> fiducials(Map<String, Object> results) {
        return (List<Map<String, Object>>) results.get("Fiducial");
    }

    private static double number(Map<String, Object> results, String key) {
        return (Double) results.get(key);
    }

    private static double[] array(Map<String, Object> results, String key) {
        return Json.numbers(results.get(key));
    }

    /** When the camera finished the frame in a reply, in simulated nanoseconds, as its own timestamp says. */
    private static long completionOf(Map<String, Object> results) {
        return Math.round(number(results, "ts") * 1e6) - LimelightJson.BOOT_AGE_NANOS;
    }

    private static long captureOf(Map<String, Object> results) {
        return completionOf(results) - Math.round((number(results, "cl") + number(results, "tl")) * 1e6);
    }

    @Test
    void aTagInViewGivesAValidResultWhoseBotposeIsNearTheTruth() {
        ScriptedBody body = CameraWorld.parkedFacing(CameraWorld.blueGoal(), 1.5, 0.2, 0.05);
        CameraWorld world = new CameraWorld(body, CameraWorld.OPEN_SPACE, GOALS, CameraWorld.settings("decode"), 7);
        world.rig.runTo(300 * MS);

        Map<String, Object> results = world.results(300 * MS);

        assertEquals(1.0, number(results, "v"));
        assertEquals(1.0, number(results, "botpose_tagcount"));
        assertEquals(0.0, number(results, "pID"));
        assertEquals(20.0, (Double) fiducials(results).get(0).get("fID"));
        double[] botpose = array(results, "botpose");
        BodyPose truth = body.at(300 * MS);
        assertEquals(truth.x(), botpose[0], 0.05, "x");
        assertEquals(truth.y(), botpose[1], 0.05, "y");
        assertEquals(StrictMath.toDegrees(truth.yaw()), botpose[5], 2.0, "yaw");
        double[] fromThisTag = array(fiducials(results).get(0), "t6r_fs");
        assertEquals(botpose[0], fromThisTag[0], 0.01, "the pose from the only tag is the pose");
        assertEquals(botpose[1], fromThisTag[1], 0.01);
        assertEquals(botpose[5], fromThisTag[5], 0.5);
        assertTrue(number(results, "tx") != 0.0 && number(results, "ta") > 0.0);
    }

    @Test
    void thePoseErrorIsTheSensorsPixelNoiseAndRoundingAndGrowsWithRange() {
        double[] meanError = new double[3];
        double[] ranges = {1.5, 2.5, 3.5};
        for (int r = 0; r < ranges.length; r++) {
            int n = 0;
            for (int k = 0; k < 30; k++) {
                ScriptedBody body = CameraWorld.parkedFacing(CameraWorld.blueGoal(), ranges[r], -0.4 + 0.03 * k, 0.1);
                CameraWorld world = new CameraWorld(body, CameraWorld.OPEN_SPACE, GOALS, CameraWorld.settings("decode"), 100 + k);
                world.rig.runTo(200 * MS);
                Map<String, Object> results = world.results(200 * MS);
                if (number(results, "botpose_tagcount") == 1.0) {
                    double[] botpose = array(results, "botpose");
                    BodyPose truth = body.at(200 * MS);
                    meanError[r] += Math.hypot(botpose[0] - truth.x(), botpose[1] - truth.y());
                    n++;
                }
            }
            assertTrue(n >= 25, "the tag is seen from " + ranges[r] + " m");
            meanError[r] /= n;
        }

        assertTrue(meanError[0] < meanError[1] && meanError[1] < meanError[2], "error grows with range: " + List.of(meanError[0], meanError[1], meanError[2]));
        assertTrue(meanError[2] > 3.0 * meanError[0], "and not by a little: " + meanError[0] + " to " + meanError[2]);
        assertTrue(meanError[0] < 0.04 && meanError[2] < 0.5, "and stays what a 640x480 camera gives: " + meanError[0] + ", " + meanError[2]);
    }

    @Test
    void aTagTheCamerasMapDoesNotKnowIsFoundButGivesNoBotpose() {
        // Tag 30 is on the field; the camera's map of "decode" is of tags 20 and 24 only.
        FieldTag stranger = CameraWorld.tag(30, -1.4826, -1.4133, 0.7493, StrictMath.toRadians(54.0), -1);
        ScriptedBody body = CameraWorld.parkedFacing(stranger, 1.5, 0.0, 0.0);
        CameraWorld world = new CameraWorld(body, CameraWorld.OPEN_SPACE, List.of(stranger), CameraWorld.settings("none"), 3);
        world.rig.runTo(300 * MS);

        Map<String, Object> results = world.results(300 * MS);

        assertEquals(1.0, number(results, "v"), "a target is valid whether or not the map knows it");
        assertEquals(0.0, number(results, "botpose_tagcount"));
        for (double value : array(results, "botpose")) {
            assertEquals(0.0, value);
        }
        Map<String, Object> tag = fiducials(results).get(0);
        for (double value : Json.numbers(tag.get("t6r_fs"))) {
            assertEquals(0.0, value, "no robot pose without a place for the tag");
        }
        assertEquals(1.5 + 0.0, Json.numbers(tag.get("t6t_cs"))[2], 0.25, "but the camera knows how far away it is");
    }

    @Test
    void aCameraOnTheTurretSeesWhereTheTurretPointsAndOneOnTheChassisSeesWhereTheChassisPoints() {
        TurretView turnedAround = new TurretView() {
            @Override
            public double yawRadians() {
                return Math.PI;
            }

            @Override
            public double yawRateRadPerSec() {
                return 0.0;
            }
        };
        ScriptedBody body = CameraWorld.parkedFacing(CameraWorld.blueGoal(), 1.5, 0.0, Math.PI);
        Map<String, Object> onTurret = CameraWorld.settings("decode");
        onTurret.put("mount.on", "turret");

        CameraWorld riding = new CameraWorld(body, CameraWorld.OPEN_SPACE, GOALS, onTurret, 3, Map.of(), turnedAround);
        riding.rig.runTo(300 * MS);
        CameraWorld fixed = new CameraWorld(body, CameraWorld.OPEN_SPACE, GOALS, CameraWorld.settings("decode"), 3, Map.of(), turnedAround);
        fixed.rig.runTo(300 * MS);

        assertEquals(1.0, number(riding.results(300 * MS), "v"), "the chassis faces away but the turret, and the camera on it, faces the tag");
        assertEquals(0.0, number(fixed.results(300 * MS), "v"), "a camera fixed to the chassis does not turn with the turret");
        Map<String, Object> bad = CameraWorld.settings("decode");
        bad.put("mount.on", "elsewhere");
        assertThrows(IllegalArgumentException.class, () -> new CameraWorld(body, CameraWorld.OPEN_SPACE, GOALS, bad, 3));
    }

    @Test
    void facingAwayFromEveryTagTheResultIsInvalid() {
        ScriptedBody body = CameraWorld.parkedFacing(CameraWorld.blueGoal(), 1.5, 0.0, Math.PI);
        CameraWorld world = new CameraWorld(body, CameraWorld.OPEN_SPACE, GOALS, CameraWorld.settings("decode"), 3);
        world.rig.runTo(300 * MS);

        Map<String, Object> results = world.results(300 * MS);

        assertEquals(0.0, number(results, "v"));
        assertEquals(0.0, number(results, "tx"));
        assertEquals(0.0, number(results, "botpose_tagcount"));
        assertTrue(fiducials(results).isEmpty());
    }

    @Test
    void aBodyBetweenTheCameraAndTheTagHidesIt() {
        ScriptedBody body = CameraWorld.parkedFacing(CameraWorld.blueGoal(), 2.0, 0.0, 0.0);
        Rigid robot = TestScene.robotFacing(SceneTag.of(CameraWorld.blueGoal()), 2.0, 0.0, 0.0);
        Vec3 camera = robot.compose(TestScene.MOUNT.cameraInRobot()).translation();
        Vec3 tag = SceneTag.of(CameraWorld.blueGoal()).inHost().translation();
        Vec3 halfway = camera.plus(tag.minus(camera).times(0.5));
        CameraWorld world = new CameraWorld(body, CameraWorld.ball(halfway, 0.12), GOALS, CameraWorld.settings("decode"), 3);
        world.rig.runTo(300 * MS);

        assertEquals(0.0, number(world.results(300 * MS), "v"));
    }

    @Test
    void aSurfaceTheTagIsStuckOnDoesNotHideIt() {
        ScriptedBody body = CameraWorld.parkedFacing(CameraWorld.blueGoal(), 2.0, 0.0, 0.0);
        Vec3 tag = SceneTag.of(CameraWorld.blueGoal()).inHost().translation();
        // A ball whose surface is a centimetre in front of the tag, like a panel the tag is printed on.
        Vec3 behind = SceneTag.of(CameraWorld.blueGoal()).inHost().rotation().column(2).times(-0.19).plus(tag);
        CameraWorld world = new CameraWorld(body, CameraWorld.ball(behind, 0.2), GOALS, CameraWorld.settings("decode"), 3);
        world.rig.runTo(300 * MS);

        assertEquals(1.0, number(world.results(300 * MS), "v"));
    }

    @Test
    void aTagOnAMovingBodyIsSeenWhereItsBodyWasWhenTheFrameWasTaken() {
        FieldTag goal = CameraWorld.blueGoal();
        Pose3 at = goal.local();
        // The tag is on body 5, which starts at the goal's place and from 0.2 s slides 0.3 m to the left in a quarter of a second.
        FieldTag hosted = new FieldTag(goal.id(), goal.sizeMeters(), 5, new Pose3(0, 0, 0, at.qw(), at.qx(), at.qy(), at.qz()));
        LongFunction<BodyPose> slide = nanos -> new BodyPose(nanos, at.x(), at.y() + slidOn(nanos), at.z(), 1, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        ScriptedBody body = CameraWorld.parkedFacing(goal, 1.5, 0.0, 0.0);
        CameraWorld world = new CameraWorld(
                body, CameraWorld.OPEN_SPACE, List.of(hosted), CameraWorld.settings("decode"), 3, Map.of(5, slide));

        double leftmost = Double.MAX_VALUE;
        double rightmost = -Double.MAX_VALUE;
        for (long now = 250 * MS; now <= 700 * MS; now += 7 * MS) {
            world.rig.runTo(now);
            Map<String, Object> results = world.results(now);
            assertEquals(1.0, number(results, "v"), "the tag is in view while its body slides, at " + now / 1e6 + " ms");
            assertEquals(0.0, number(results, "botpose_tagcount"), "a tag on a body is in no game's map of fixed tags");
            long capture = captureOf(results);
            FieldTag then = CameraWorld.tag(20, at.x(), at.y() + slidOn(capture), at.z(), StrictMath.toRadians(54.0), -1);
            BodyPose pose = body.at(capture);
            double[] ideal = TestScene.pixels(TestScene.robot(pose.x(), pose.y(), pose.z(), pose.yaw()), SceneTag.of(then));
            double expected = 0.25 * (ideal[0] + ideal[2] + ideal[4] + ideal[6]);
            double found = number(fiducials(results).get(0), "txp");
            assertEquals(expected, found, 1.5, "the tag is where its body was at the capture, " + capture / 1e6 + " ms");
            leftmost = Math.min(leftmost, found);
            rightmost = Math.max(rightmost, found);
        }
        assertTrue(rightmost - leftmost > 60, "the tag moved across the image: " + (rightmost - leftmost) + " px");
    }

    /** How far body 5 has slid by the instant: 1.2 m/s from 0.2 s, stopping at 0.3 m. */
    private static double slidOn(long nanos) {
        return 1.2 * Math.max(0.0, Math.min(0.25, nanos / 1e9 - 0.2));
    }

    @Test
    void theBodyATagIsStuckOnNeverHidesItButAnotherBodyDoes() {
        FieldTag goal = CameraWorld.blueGoal();
        Pose3 at = goal.local();
        FieldTag hosted = new FieldTag(goal.id(), goal.sizeMeters(), 5, new Pose3(0, 0, 0, at.qw(), at.qx(), at.qy(), at.qz()));
        LongFunction<BodyPose> still = nanos -> new BodyPose(nanos, at.x(), at.y(), at.z(), 1, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        ScriptedBody body = CameraWorld.parkedFacing(goal, 2.0, 0.0, 0.0);
        // Every ray meets a surface half way to its target: of body 5, which carries the tag, or of body 6.
        RayView ownBody = (origin, direction, maxMeters) -> new SurfaceHit(0.5 * maxMeters, 100, 5, 0x808080);
        RayView otherBody = (origin, direction, maxMeters) -> new SurfaceHit(0.5 * maxMeters, 100, 6, 0x808080);
        Map<Integer, LongFunction<BodyPose>> hosts = Map.of(5, still);

        CameraWorld carrying = new CameraWorld(body, ownBody, List.of(hosted), CameraWorld.settings("decode"), 3, hosts);
        CameraWorld blocked = new CameraWorld(body, otherBody, List.of(hosted), CameraWorld.settings("decode"), 3, hosts);
        carrying.rig.runTo(300 * MS);
        blocked.rig.runTo(300 * MS);

        assertEquals(1.0, number(carrying.results(300 * MS), "v"), "the hive's own wall is not in front of its tag");
        assertEquals(0.0, number(blocked.results(300 * MS), "v"), "but another body's is");
    }

    @Test
    void aTagOnABodyTheHostDoesNotKnowIsNeverSeenAndTheProblemSaysWhy() {
        FieldTag goal = CameraWorld.blueGoal();
        Pose3 at = goal.local();
        FieldTag hosted = new FieldTag(goal.id(), goal.sizeMeters(), 9, new Pose3(0, 0, 0, at.qw(), at.qx(), at.qy(), at.qz()));
        ScriptedBody body = CameraWorld.parkedFacing(goal, 1.5, 0.0, 0.0);
        CameraWorld world = new CameraWorld(body, CameraWorld.OPEN_SPACE, List.of(hosted), CameraWorld.settings("decode"), 3);
        world.rig.runTo(300 * MS);

        assertEquals(0.0, number(world.results(300 * MS), "v"));
        assertTrue(world.camera.problems().stream().anyMatch(p -> p.contains("entity 9")), world.camera.problems().toString());
    }

    @Test
    void anIdFilterLeavesOutTheTagsItDoesNotListAndTheResultIsInvalidWithoutThem() {
        ScriptedBody body = CameraWorld.parkedFacing(CameraWorld.blueGoal(), 1.5, 0.0, 0.0);
        Map<String, Object> settings = CameraWorld.settings("decode");
        settings.put("pipeline0.ids", "24");
        CameraWorld world = new CameraWorld(body, CameraWorld.OPEN_SPACE, GOALS, settings, 3);
        world.rig.runTo(300 * MS);

        assertEquals(0.0, number(world.results(300 * MS), "v"));
    }

    @Test
    void aResultIsTheNewestOneFinishedByTheInstantAndItsPoseIsWhereTheRobotWasWhenTheFrameWasTaken() {
        // Drives at 1.6 m/s towards the tag from 4.5 m to 2.1 m away, a little off to the side so that the view is not head-on.
        FieldTag tag = CameraWorld.blueGoal();
        Rigid start = TestScene.robotFacing(SceneTag.of(tag), 4.5, 0.7, 0.15);
        Vec3 heading = new Vec3(StrictMath.cos(start.rotation().yaw()), StrictMath.sin(start.rotation().yaw()), 0);
        ScriptedBody body = ScriptedBody.startingAt(start.translation().x(), start.translation().y(), start.rotation().yaw())
                .move(1.5, 1.6, 0, 0)
                .stay(10)
                .build();
        Map<String, Object> settings = CameraWorld.settings("decode");
        settings.put("pipeline0.resolution", "1280x960");
        CameraWorld world = new CameraWorld(body, CameraWorld.OPEN_SPACE, GOALS, settings, 5);

        List<Long> ages = new ArrayList<>();
        double alongCapture = 0;
        double alongRequest = 0;
        int n = 0;
        for (long now = 300 * MS; now <= 1400 * MS; now += 23 * MS) {
            world.rig.runTo(now);
            Map<String, Object> results = world.results(now);
            if (number(results, "botpose_tagcount") != 1.0) {
                continue;
            }
            long capture = captureOf(results);
            double[] botpose = array(results, "botpose");
            BodyPose then = body.at(capture);
            BodyPose current = body.at(now);
            Vec3 found = new Vec3(botpose[0], botpose[1], 0);
            alongCapture += found.minus(new Vec3(then.x(), then.y(), 0)).dot(heading);
            alongRequest += found.minus(new Vec3(current.x(), current.y(), 0)).dot(heading);
            n++;
            ages.add(now - capture);
            assertTrue(completionOf(results) <= now, "a result is delivered once it is finished");
            assertTrue(now - completionOf(results) < 55 * MS, "and the newest one, so at most a frame (50 ms at this resolution) old");
        }
        assertTrue(n >= 35, "the tag is in view for most of the drive: " + n);
        // The pose lags the truth along the direction of travel by the speed times the age, which is some 13 cm.
        assertEquals(0.0, alongCapture / n, 0.02, "on average the pose is where the robot was when the frame was taken");
        assertTrue(alongRequest / n < -0.08, "and not where it is now: " + alongRequest / n);
        long newest = ages.stream().mapToLong(Long::longValue).min().orElseThrow();
        long oldest = ages.stream().mapToLong(Long::longValue).max().orElseThrow();
        assertTrue(newest >= 55 * MS, "even a fresh result describes the world some 57 ms ago: " + newest / 1e6 + " ms");
        assertTrue(oldest <= 115 * MS, "and none is older than latency plus a frame: " + oldest / 1e6 + " ms");
    }

    @Test
    void pipelineSwitchesTakeEffectAfterTheCameraHasHadTimeToSwitch() {
        ScriptedBody body = CameraWorld.parkedFacing(CameraWorld.blueGoal(), 1.5, 0.0, 0.0);
        Map<String, Object> settings = CameraWorld.settings("decode");
        settings.put("pipeline1.type", "apriltag");
        settings.put("pipeline1.fieldMap", "none");
        CameraWorld world = new CameraWorld(body, CameraWorld.OPEN_SPACE, GOALS, settings, 3);
        world.rig.runTo(300 * MS);
        assertEquals(1.0, number(world.results(300 * MS), "botpose_tagcount"));

        CameraWire.Reply reply = world.post("/pipeline-switch?index=1", null, 300 * MS);
        world.rig.runTo(325 * MS);
        Map<String, Object> soon = world.results(325 * MS);
        world.rig.runTo(500 * MS);
        Map<String, Object> later = world.results(500 * MS);

        assertTrue(reply.ok());
        assertEquals(0.0, number(soon, "pID"), "the camera is still working on frames from the old pipeline");
        assertEquals(1.0, number(later, "pID"));
        assertEquals(0.0, number(later, "botpose_tagcount"), "the new pipeline has no map");
        assertEquals(1.0, number(later, "v"), "and still sees the tag");
    }

    @Test
    void aPipelineTheProfileDoesNotDefineFindsNothingAndIsReported() {
        ScriptedBody body = CameraWorld.parkedFacing(CameraWorld.blueGoal(), 1.5, 0.0, 0.0);
        CameraWorld world = new CameraWorld(body, CameraWorld.OPEN_SPACE, GOALS, CameraWorld.settings("decode"), 3);
        world.rig.runTo(300 * MS);

        world.post("/pipeline-switch?index=4", null, 300 * MS);
        world.rig.runTo(600 * MS);
        Map<String, Object> results = world.results(600 * MS);

        assertEquals(4.0, number(results, "pID"));
        assertEquals(0.0, number(results, "v"));
        assertTrue(world.camera.problems().stream().anyMatch(p -> p.contains("pipeline 4") && p.contains("not configured")), world.camera.problems().toString());
        assertEquals(400, world.post("/pipeline-switch?index=10", null, 600 * MS).status());
        assertEquals(400, world.post("/pipeline-switch", null, 600 * MS).status());
    }

    @Test
    void megaTagTwoFindsThePositionFromTheYawRobotCodeSendsAndNotBefore() {
        ScriptedBody body = CameraWorld.parkedFacing(CameraWorld.blueGoal(), 2.0, 0.3, 0.1);
        CameraWorld world = new CameraWorld(body, CameraWorld.OPEN_SPACE, GOALS, CameraWorld.settings("decode"), 11);
        world.rig.runTo(300 * MS);
        for (double value : array(world.results(300 * MS), "botpose_orb")) {
            assertEquals(0.0, value, "no orientation has been sent");
        }

        double yaw = StrictMath.toDegrees(body.at(0).yaw());
        assertTrue(world.post("/update-robotorientation", "[" + yaw + ",0,0,0,0,0]", 300 * MS).ok());
        world.rig.runTo(600 * MS);
        double[] orb = array(world.results(600 * MS), "botpose_orb");

        BodyPose truth = body.at(600 * MS);
        assertEquals(truth.x(), orb[0], 0.05);
        assertEquals(truth.y(), orb[1], 0.05);
        assertEquals(yaw, orb[5], 1e-9);
        assertEquals(400, world.post("/update-robotorientation", "not json", 600 * MS).status());
    }

    @Test
    void whatNeedsTheRealCamerasStorageOrSoftwareIsRefusedAndReported() {
        ScriptedBody body = CameraWorld.parkedFacing(CameraWorld.blueGoal(), 1.5, 0.0, 0.0);
        CameraWorld world = new CameraWorld(body, CameraWorld.OPEN_SPACE, GOALS, CameraWorld.settings("decode"), 3);

        CameraWire.Reply snapshot = world.post("/capture-snapshot?snapname=a", null, 10 * MS);
        world.post("/capture-snapshot?snapname=b", null, 20 * MS);
        CameraWire.Reply calibration = world.get("/cal-default", 30 * MS);
        CameraWire.Reply python = world.post("/update-pythoninputs", "[1,2,3]", 40 * MS);
        CameraWire.Reply nothing = world.get("/nonsense", 50 * MS);

        assertFalse(snapshot.ok());
        assertEquals(501, snapshot.status());
        assertEquals(501, calibration.status());
        assertEquals(501, python.status());
        assertEquals(404, nothing.status());
        List<String> problems = world.camera.problems();
        assertTrue(problems.stream().anyMatch(p -> p.contains("/capture-snapshot") && p.contains("2 times")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("/cal-default")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("/update-pythoninputs")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("/nonsense")), problems.toString());
    }

    @Test
    void beforeAnyFrameHasFinishedTheCameraAnswersWithAnEmptyValidDocument() {
        ScriptedBody body = CameraWorld.parkedFacing(CameraWorld.blueGoal(), 1.5, 0.0, 0.0);
        CameraWorld world = new CameraWorld(body, CameraWorld.OPEN_SPACE, GOALS, CameraWorld.settings("decode"), 3);

        Map<String, Object> results = world.results(1 * MS);

        assertEquals(0.0, number(results, "v"));
        assertTrue(fiducials(results).isEmpty());
        assertEquals(0.0, number(results, "botpose_tagcount"));
    }

    @Test
    void theStatusReportsThePipelineAndTheFrameRate() {
        ScriptedBody body = CameraWorld.parkedFacing(CameraWorld.blueGoal(), 1.5, 0.0, 0.0);
        CameraWorld world = new CameraWorld(body, CameraWorld.OPEN_SPACE, GOALS, CameraWorld.settings("decode"), 3);
        world.rig.runTo(300 * MS);

        Map<String, Object> status = Json.object(world.get("/status", 300 * MS).body());

        assertEquals(50.0, number(status, "fps"));
        assertEquals(0.0, number(status, "pipelineIndex"));
        assertEquals("pipe_fiducial", status.get("pipelineType"));
        assertTrue(number(status, "pipeImgCount") >= 10);
    }

    @Test
    void sameInputsGiveByteIdenticalRepliesWhateverTheSlicingOfTime() {
        List<String> replies = new ArrayList<>();
        for (long slice : new long[] {1_000_000_000L, 1 * MS, 7 * MS, 33 * MS}) {
            Rigid start = TestScene.robotFacing(SceneTag.of(CameraWorld.blueGoal()), 3.0, 0.2, 0.1);
            ScriptedBody body = ScriptedBody.startingAt(start.translation().x(), start.translation().y(), start.rotation().yaw())
                    .move(1.5, 0.8, 0.1, 0.2)
                    .stay(10)
                    .build();
            CameraWorld world = new CameraWorld(body, CameraWorld.OPEN_SPACE, GOALS, CameraWorld.settings("decode"), 99);
            StringBuilder transcript = new StringBuilder();
            long now = 0;
            long nextPoll = 100 * MS;
            while (nextPoll <= 1500 * MS) {
                // Advance in slices, but ask exactly at the polling instants.
                while (now < nextPoll) {
                    now = Math.min(nextPoll, now + slice);
                    world.rig.runTo(now);
                }
                transcript.append(world.camera.get("/results", nextPoll).body()).append('\n');
                nextPoll += 10 * MS;
            }
            replies.add(transcript.toString());
        }

        assertTrue(replies.get(0).length() > 10_000);
        for (int i = 1; i < replies.size(); i++) {
            assertEquals(replies.get(0), replies.get(i), "slicing " + i);
        }
    }

    @Test
    void aDifferentSeedGivesDifferentNoiseButTheSameTags() {
        double[] first = botposesWithSeed(1);
        double[] second = botposesWithSeed(2);

        boolean differ = false;
        boolean jitters = false;
        for (int i = 0; i < first.length; i++) {
            differ |= first[i] != second[i];
            jitters |= first[i] != first[0];
        }
        assertTrue(differ, "another seed, another noise");
        assertTrue(jitters, "and the noise changes from frame to frame");
    }

    private static double[] botposesWithSeed(long seed) {
        ScriptedBody body = CameraWorld.parkedFacing(CameraWorld.blueGoal(), 2.5, 0.0, 0.1);
        CameraWorld world = new CameraWorld(body, CameraWorld.OPEN_SPACE, GOALS, CameraWorld.settings("decode"), seed);
        double[] botposes = new double[20];
        for (int i = 0; i < botposes.length; i++) {
            long now = 200 * MS + 20 * MS * i;
            world.rig.runTo(now);
            botposes[i] = array(world.results(now), "botpose")[0];
        }
        return botposes;
    }

    @Test
    void settingsAreCheckedByName() {
        Map<String, Object> bad = CameraWorld.settings("decode");
        bad.put("pipeline0.type", "colour");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> CameraModels.standard().check("limelight-3a", "limelight", bad))
                .getMessage().contains("pipeline0.type"));

        Map<String, Object> typo = CameraWorld.settings("decode");
        typo.put("mount.zz", 1.0);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> CameraModels.standard().check("limelight-3a", "limelight", typo))
                .getMessage().contains("mount.zz"));

        Map<String, Object> resolution = CameraWorld.settings("decode");
        resolution.put("pipeline0.resolution", "800x600");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> CameraModels.standard().check("limelight-3a", "limelight", resolution))
                .getMessage().contains("640x480"));

        assertThrows(IllegalArgumentException.class, () -> CameraModels.standard().check("nonsuch", "limelight", Map.of()));
    }

    @Test
    void aMapForAGameTheHostLacksIsRejectedWhenTheCameraIsBuilt() {
        ScriptedBody body = CameraWorld.parkedFacing(CameraWorld.blueGoal(), 1.5, 0.0, 0.0);
        Map<String, Object> settings = CameraWorld.settings("biobuzz");

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> new CameraWorld(body, CameraWorld.OPEN_SPACE, GOALS, settings, 3));

        assertTrue(failure.getMessage().contains("pipeline0.fieldMap"), failure.getMessage());
    }

    @Test
    void theRackNamesItsOneCameraAfterTheFirstNameThatAsksAndReportsTheOtherNames() {
        ScriptedBody body = CameraWorld.parkedFacing(CameraWorld.blueGoal(), 1.5, 0.0, 0.0);
        CameraWorld world = new CameraWorld(body, CameraWorld.OPEN_SPACE, GOALS, CameraWorld.settings("decode"), 3);
        CameraRack rack = new CameraRack().plug(world.camera);
        world.rig.runTo(300 * MS);

        assertTrue(rack.connect("cam"), "the first name is the camera's");
        assertTrue(rack.connect("cam"), "and stays its name");
        assertTrue(rack.get("cam", "/results", 300 * MS).ok());
        assertFalse(rack.connect("limelight2"));
        assertFalse(rack.get("limelight2", "/results", 300 * MS).reachable());
        assertEquals(CameraWire.Reply.UNREACHABLE, rack.post("nothing", "/pipeline-switch?index=0", null, 0).status());

        List<String> problems = rack.problems();
        assertTrue(problems.stream().anyMatch(p -> p.contains("\"limelight2\"") && p.contains("one camera, \"cam\"")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("\"nothing\"")), problems.toString());
        assertEquals(2, problems.size());
        assertThrows(IllegalStateException.class, () -> rack.plug(world.camera));
        assertFalse(new CameraRack().connect("limelight"), "a robot without a camera has every name unplugged");
    }
}
