package dev.ftcsim.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.field.Alliance;
import dev.ftcsim.contracts.field.FieldTag;
import dev.ftcsim.contracts.world.BodyView;
import dev.ftcsim.contracts.world.RayView;
import dev.ftcsim.contracts.world.SurfaceHit;
import dev.ftcsim.contracts.world.Vec3;
import dev.ftcsim.field.FieldSetup;
import dev.ftcsim.game.decode.DecodeField;
import dev.ftcsim.hardware.device.scripted.ScriptedBody;
import dev.ftcsim.physics.PhysicsWorld;
import dev.ftcsim.physics.RayHit;
import dev.ftcsim.physics.Vector3;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The SDK's own {@code Limelight3A} driver, used the way Hornet's {@code Camera} subsystem uses it, against the camera
 * model, with the camera set up as the fixed robot sets it up and the real DECODE tags and geometry in
 * front of it. The robot stands where the test puts it, so the truth that the camera estimates is known.
 */
@Timeout(300)
class LimelightHostTest {
    private static final long MS = 1_000_000L;
    private static final long[] SLICES = {Long.MAX_VALUE / 4, 1 * MS, 7 * MS, 33 * MS};
    private static final double INCH = 0.0254;
    private static final List<FieldTag> DECODE_TAGS = new DecodeField().description().tags();

    /** What a run of one OpMode looked like from the robot program and from the simulator. */
    private record Run(Map<String, List<Double>> series, List<String> problems, String telemetry) {}

    /** The real DECODE field in the physics world, for rays and for the bodies tags are stuck on. */
    private static final class FieldRays implements RayView, AutoCloseable {
        private final PhysicsWorld world;

        FieldRays() throws IOException {
            world = PhysicsWorld.create(Path.of(System.getProperty("ftcsim.nativeDirectory")));
            new DecodeField().start(world, new FieldSetup(1, Alliance.BLUE));
            world.step();
        }

        @Override
        public SurfaceHit cast(Vec3 origin, Vec3 direction, double maxMeters) {
            RayHit hit = world.castRay(
                    new Vector3(origin.x(), origin.y(), origin.z()), new Vector3(direction.x(), direction.y(), direction.z()), maxMeters, -1);
            return hit == null ? null : new SurfaceHit(hit.distanceMeters(), hit.kind(), hit.entityId(), 0x808080);
        }

        BodyView entity(int entityId) {
            return () -> world.bodyPose(entityId);
        }

        @Override
        public void close() {
            world.close();
        }
    }

    private static FieldTag tag(int id) {
        return DECODE_TAGS.stream().filter(candidate -> candidate.id() == id).findFirst().orElseThrow();
    }

    /** The camera of the fixed DECODE robot mapped as Hornet: the same mount and pipeline the simulator gives a robot. */
    private static Map<String, Object> hornetCamera() {
        return HornetMapping.spec().limelight().settings();
    }

    private static TestRobot.Builder robot(ScriptedBody chassis, FieldRays field) throws IOException {
        return robot(chassis, field, field);
    }

    /** The robot on the DECODE field, whose rays meet {@code rays} instead of the bare field. */
    private static TestRobot.Builder robot(ScriptedBody chassis, FieldRays field, RayView rays) throws IOException {
        return TestRobot.builder()
                .chassis(chassis)
                .rays(rays)
                .entities(field::entity)
                .fieldTags(DECODE_TAGS)
                .gameMap("decode", DECODE_TAGS)
                .limelight(hornetCamera());
    }

    /** The direction out of the face of a tag: its local +Z. */
    private static Vec3 normalOf(FieldTag tag) {
        double w = tag.local().qw();
        double x = tag.local().qx();
        double y = tag.local().qy();
        double z = tag.local().qz();
        return new Vec3(2 * (x * z + w * y), 2 * (y * z - w * x), 1 - 2 * (x * x + y * y));
    }

    /** A chassis standing {@code distance} metres in front of the tag along its normal, looking at it. */
    private static ScriptedBody standingBefore(FieldTag tag, double distance, double seconds) {
        Vec3 normal = normalOf(tag);
        double heading = StrictMath.atan2(-normal.y(), -normal.x());
        return ScriptedBody.startingAt(
                        tag.local().x() + normal.x() * distance, tag.local().y() + normal.y() * distance, heading)
                .stay(seconds)
                .build();
    }

    private static void advanceInSlices(VirtualSession session, long total, long slice) {
        long end = session.now() + total;
        while (session.now() < end) {
            session.kernel.runUntil(Math.min(end, session.now() + slice));
        }
    }

    private static Run run(TestRobot.Builder builder, String opMode, long initNanos, long runNanos, long slice) throws Exception {
        try (VirtualSession session = new VirtualSession(builder)) {
            session.init(opMode);
            advanceInSlices(session, initNanos, slice);
            session.start();
            advanceInSlices(session, runNanos, slice);
            List<String> problems = new ArrayList<>(session.kernel.diagnostics().problems());
            problems.addAll(session.robot.cameras.problems());
            Run run = new Run(session.world.run("dev.ftcsim.scenarios.ReadSeries"), problems, session.telemetry().toString());
            session.stop();
            advanceInSlices(session, 100 * MS, slice);
            return run;
        }
    }

    private static Run run(TestRobot.Builder builder, String opMode, long runNanos) throws Exception {
        return run(builder, opMode, 500 * MS, runNanos, Long.MAX_VALUE / 4);
    }

    private static double[] column(Run run, String series, double fromSeconds, boolean validOnly) {
        List<Double> times = run.series().get("t");
        List<Double> values = run.series().get(series);
        List<Double> valid = run.series().get("valid");
        List<Double> selected = new ArrayList<>();
        for (int i = 0; i < times.size(); i++) {
            if (times.get(i) / 1e9 >= fromSeconds && (!validOnly || valid.get(i) == 1.0)) {
                selected.add(values.get(i));
            }
        }
        return selected.stream().mapToDouble(Double::doubleValue).toArray();
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static double percentile(double[] values, double p) {
        double[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        return sorted[Math.min(sorted.length - 1, (int) Math.floor(p * sorted.length))];
    }

    /** Hornet's convertToPedro of a field pose in inches and radians: the same numbers the OpMode computes for the camera. */
    private static double[] pedro(double xInches, double yInches, double yaw) {
        double heading = yaw - Math.PI / 2;
        return new double[] {yInches + 72, 72 - xInches, StrictMath.atan2(StrictMath.sin(heading), StrictMath.cos(heading))};
    }

    /** How far each valid result is from the truth, in inches, and in degrees of heading, after Hornet's conversion. */
    private record Errors(double[] position, double[] heading) {
        String summary() {
            return String.format(java.util.Locale.ROOT,
                    "%d results: position error median %.2f in, 90th %.2f in, 99th %.2f in, max %.2f in; heading error median %.2f deg, 99th %.2f deg",
                    position.length, median(position), percentile(position, 0.9), percentile(position, 0.99),
                    percentile(position, 1.0), median(heading), percentile(heading, 0.99));
        }
    }

    private static void report(String line) {
        System.out.println("LIMELIGHT> " + line);
    }

    private static Errors errorsAgainst(Run run, ScriptedBody truth, double fromSeconds) {
        double[] t = column(run, "t", fromSeconds, true);
        double[] pedroX = column(run, "pedroX", fromSeconds, true);
        double[] pedroY = column(run, "pedroY", fromSeconds, true);
        double[] pedroHeading = column(run, "pedroHeading", fromSeconds, true);
        double[] position = new double[t.length];
        double[] heading = new double[t.length];
        for (int i = 0; i < t.length; i++) {
            var pose = truth.at(Math.round(t[i]));
            double[] expected = pedro(pose.x() / INCH, pose.y() / INCH, pose.yaw());
            position[i] = Math.hypot(pedroX[i] - expected[0], pedroY[i] - expected[1]);
            heading[i] = Math.abs(Math.toDegrees(StrictMath.atan2(
                    StrictMath.sin(pedroHeading[i] - expected[2]), StrictMath.cos(pedroHeading[i] - expected[2]))));
        }
        return new Errors(position, heading);
    }

    // ---- what Hornet's pose blending would get ------------------------------------------------------------------

    /** A run with the robot standing still {@code distance} metres in front of tag 20, and how far its results are from the truth. */
    private record Parked(Run run, Errors errors) {}

    private static Parked parkedBeforeTag20(FieldRays field, double distance) throws Exception {
        ScriptedBody chassis = standingBefore(tag(20), distance, 30);
        Run run = run(robot(chassis, field), "Limelight Pose", 500 * MS, 6500 * MS, Long.MAX_VALUE / 4);
        return new Parked(run, errorsAgainst(run, chassis, 0.6));
    }

    @Test
    void standingMeterAndAHalfFromTheBlueGoalTagEveryResultIsValidAndTheConvertedPoseIsWithinTwoInches() throws Exception {
        try (FieldRays field = new FieldRays()) {
            Parked parked = parkedBeforeTag20(field, 1.5);
            Run run = parked.run();

            double[] valid = column(run, "valid", 0.6, false);
            assertTrue(valid.length > 500, "the OpMode looped " + valid.length + " times");
            for (double v : valid) {
                assertEquals(1.0, v, "every loop gets a valid result once the camera has had a moment");
            }
            for (double connected : column(run, "connected", 0.6, false)) {
                assertEquals(1.0, connected, "and the driver sees the camera as connected");
            }
            for (double id : column(run, "firstId", 0.6, true)) {
                assertEquals(20.0, id);
            }
            Errors errors = parked.errors();
            report("1.5 m from tag 20, Hornet's camera: " + errors.summary());
            assertTrue(median(errors.position()) < 1.0, "median error " + median(errors.position()) + " in");
            assertTrue(percentile(errors.position(), 0.99) < 2.0, "99th percentile error " + percentile(errors.position(), 0.99) + " in");
            assertTrue(percentile(errors.heading(), 0.99) < 2.0, "heading error " + percentile(errors.heading(), 0.99) + " degrees");
            assertEquals(List.of(), run.problems());
            assertTrue(run.telemetry().contains("Pose"), run.telemetry());
        }
    }

    @Test
    void standingThreeMetresAwayThePoseIsNoisierAndStillNearTheTruth() throws Exception {
        try (FieldRays field = new FieldRays()) {
            Parked far = parkedBeforeTag20(field, 3.0);
            Parked near = parkedBeforeTag20(field, 1.5);

            for (double v : column(far.run(), "valid", 0.6, false)) {
                assertEquals(1.0, v);
            }
            Errors errors = far.errors();
            report("3 m from tag 20, Hornet's camera: " + errors.summary());
            // A single tag seen head-on from 3 m is the hard case: a pixel of the 1280 px image is 4 mm of tag.
            assertTrue(median(errors.position()) < 4.0, "median error " + median(errors.position()) + " in");
            assertTrue(percentile(errors.position(), 0.9) < 8.0, "90th percentile " + percentile(errors.position(), 0.9) + " in");
            assertTrue(median(errors.heading()) < 2.0, "heading " + median(errors.heading()) + " degrees");
            assertTrue(median(errors.position()) > 1.5 * median(near.errors().position()), "farther is noisier");
        }
    }

    @Test
    void theHornetConversionPutsTheFieldOriginAtThePedroCentreFacingDown() {
        double[] centre = pedro(0, 0, 0);

        assertEquals(72, centre[0], 1e-12);
        assertEquals(72, centre[1], 1e-12);
        assertEquals(-Math.PI / 2, centre[2], 1e-12);
        // The blue goal's tag, at (-58.37, -55.64) in, is in the corner of Pedro's field that has x small and y large.
        double[] goal = pedro(-58.3727, -55.6425, 0);
        assertEquals(16.4, goal[0], 0.1);
        assertEquals(130.4, goal[1], 0.1);
    }

    @Test
    void facingAwayFromEveryTagTheResultsAreInvalidButTheCameraIsConnected() throws Exception {
        Vec3 normal = normalOf(tag(20));
        ScriptedBody chassis = ScriptedBody.startingAt(
                        tag(20).local().x() + normal.x() * 1.5, tag(20).local().y() + normal.y() * 1.5, StrictMath.atan2(normal.y(), normal.x()))
                .stay(30)
                .build();
        try (FieldRays rays = new FieldRays()) {
            Run run = run(robot(chassis, rays), "Limelight Pose", 1200 * MS);

            for (double v : column(run, "valid", 0.6, false)) {
                assertEquals(0.0, v, "the camera looks away from the tag");
            }
            for (double connected : column(run, "connected", 0.6, false)) {
                assertEquals(1.0, connected, "while the camera itself is fine");
            }
            assertEquals(List.of(), run.problems());
        }
    }

    @Test
    void aBodyBetweenTheCameraAndTheTagMakesTheResultsInvalid() throws Exception {
        FieldTag goal = tag(20);
        ScriptedBody chassis = standingBefore(goal, 2.0, 30);
        Vec3 normal = normalOf(goal);
        // A ball on the line of sight, half way between the lens and the tag.
        Vec3 blocker = new Vec3(goal.local().x() + normal.x() * 1.0, goal.local().y() + normal.y() * 1.0, 0.5);
        RayView ball = (origin, direction, maxMeters) -> {
            Vec3 toOrigin = origin.minus(blocker);
            double along = toOrigin.dot(direction);
            double discriminant = along * along - (toOrigin.dot(toOrigin) - 0.15 * 0.15);
            double distance = discriminant < 0 ? -1 : -along - Math.sqrt(discriminant);
            return distance >= 0 && distance <= maxMeters ? new SurfaceHit(distance, SurfaceHit.SCENERY, -1, 0x808080) : null;
        };
        try (FieldRays field = new FieldRays()) {
            RayView fieldAndBall = (origin, direction, maxMeters) -> {
                SurfaceHit fixed = field.cast(origin, direction, maxMeters);
                SurfaceHit round = ball.cast(origin, direction, maxMeters);
                return round != null && (fixed == null || round.distanceMeters() < fixed.distanceMeters()) ? round : fixed;
            };

            Run run = run(robot(chassis, field, fieldAndBall), "Limelight Pose", 1200 * MS);
            Run clear = run(robot(chassis, field), "Limelight Pose", 1200 * MS);

            for (double v : column(run, "valid", 0.6, false)) {
                assertEquals(0.0, v, "a ball half way hides the tag");
            }
            for (double v : column(clear, "valid", 0.6, false)) {
                assertEquals(1.0, v, "and without it the same robot sees it");
            }
        }
    }

    @Test
    void aMovingRobotsPoseLagsItByAboutTheCamerasLatency() throws Exception {
        // Drives at 1.6 m/s straight at the goal's tag, from 4.5 m to 2.1 m, a little to its side.
        FieldTag goal = tag(20);
        Vec3 normal = normalOf(goal);
        Vec3 right = new Vec3(-normal.y(), normal.x(), 0);
        Vec3 start = new Vec3(goal.local().x(), goal.local().y(), 0).plus(normal.times(4.5)).plus(right.times(-0.7));
        double heading = StrictMath.atan2(goal.local().y() - start.y(), goal.local().x() - start.x());
        ScriptedBody chassis = ScriptedBody.startingAt(start.x(), start.y(), heading).stay(0.8).move(1.5, 1.6, 0, 0).stay(30).build();
        try (FieldRays rays = new FieldRays()) {
            Run run = run(robot(chassis, rays), "Limelight Pose", 500 * MS, 2300 * MS, Long.MAX_VALUE / 4);

            double[] t = column(run, "t", 1.3, true);
            double[] x = column(run, "x", 1.3, true);
            double[] y = column(run, "y", 1.3, true);
            double sum = 0;
            int n = 0;
            for (int i = 0; i < t.length; i++) {
                if (t[i] / 1e9 > 2.3) {
                    continue;
                }
                var then = chassis.at(Math.round(t[i]));
                double along = (x[i] * INCH - then.x()) * StrictMath.cos(then.yaw()) + (y[i] * INCH - then.y()) * StrictMath.sin(then.yaw());
                sum += along;
                n++;
            }
            assertTrue(n > 50, "valid results during the drive: " + n);
            double lagMillis = -(sum / n) / 1.6 * 1000;
            report("driving at 1.6 m/s from 3.7 m to 2.1 m: " + n + " loops, the pose trails the robot by " + String.format(java.util.Locale.ROOT, "%.0f", lagMillis) + " ms");
            // 56 ms from the shutter to the result, 25 ms on average to the next frame, 5 ms to the next poll, and the reply.
            assertTrue(lagMillis > 60 && lagMillis < 130, "the pose trails the robot by " + lagMillis + " ms");
        }
    }

    @Test
    void aTagTheCamerasMapDoesNotKnowIsFoundButGivesNoPose() throws Exception {
        // The camera sees tag 20 but its pipeline has no map: the DECODE tags on a field the camera was not set up for.
        Map<String, Object> noMap = new java.util.HashMap<>(hornetCamera());
        noMap.put("pipeline0.fieldMap", "none");
        ScriptedBody chassis = standingBefore(tag(20), 1.5, 30);
        try (FieldRays field = new FieldRays()) {
            TestRobot.Builder robot = TestRobot.builder()
                    .chassis(chassis)
                    .rays(field)
                    .entities(field::entity)
                    .fieldTags(DECODE_TAGS)
                    .limelight(noMap);

            Run run = run(robot, "Limelight Tags", 500 * MS, 1500 * MS, Long.MAX_VALUE / 4);

            for (double v : column(run, "valid", 0.6, false)) {
                assertEquals(1.0, v, "a tag was found, so the target is valid");
            }
            for (double id : column(run, "firstId", 0.6, true)) {
                assertEquals(20.0, id);
            }
            for (double count : column(run, "fiducials", 0.6, true)) {
                assertEquals(1.0, count);
            }
            for (double count : column(run, "tagCount", 0.6, true)) {
                assertEquals(0.0, count, "but no tag the camera could place on the field");
            }
            for (double position : column(run, "x", 0.6, true)) {
                assertEquals(0.0, position, "so there is no pose");
            }
            for (double position : column(run, "y", 0.6, true)) {
                assertEquals(0.0, position);
            }
        }
    }

    @Test
    void megaTagTwoTakesTheHeadingTheRobotSendsAndIsAtLeastAsNearTheTruthAtRange() throws Exception {
        ScriptedBody chassis = standingBefore(tag(20), 3.0, 30);
        try (FieldRays field = new FieldRays()) {
            Run run = run(robot(chassis, field), "Limelight Tags", 500 * MS, 6500 * MS, Long.MAX_VALUE / 4);

            double[] t = column(run, "t", 1.0, true);
            double[] x = column(run, "x", 1.0, true);
            double[] y = column(run, "y", 1.0, true);
            double[] mt2x = column(run, "mt2x", 1.0, true);
            double[] mt2y = column(run, "mt2y", 1.0, true);
            double[] mt2Yaw = column(run, "mt2Yaw", 1.0, true);
            double[] sentEver = column(run, "sentHeading", 0.0, false);
            double[] mt1Error = new double[t.length];
            double[] mt2Error = new double[t.length];
            int withMt2 = 0;
            for (int i = 0; i < t.length; i++) {
                var truth = chassis.at(Math.round(t[i]));
                mt1Error[i] = Math.hypot(x[i] - truth.x() / INCH, y[i] - truth.y() / INCH);
                mt2Error[i] = Math.hypot(mt2x[i] - truth.x() / INCH, mt2y[i] - truth.y() / INCH);
                if (mt2x[i] != 0 || mt2y[i] != 0) {
                    withMt2++;
                    double yaw = mt2Yaw[i];
                    assertTrue(java.util.Arrays.stream(sentEver).anyMatch(sent -> Math.abs(Math.IEEEremainder(yaw - sent, 360)) < 1e-3),
                            "MegaTag 2 reports a heading the robot sent: " + yaw);
                }
            }
            report("3 m from tag 20: MegaTag 1 error median " + String.format(java.util.Locale.ROOT, "%.2f", median(mt1Error))
                    + " in, MegaTag 2 error median " + String.format(java.util.Locale.ROOT, "%.2f", median(mt2Error)) + " in, "
                    + withMt2 + " of " + t.length + " results had MegaTag 2");
            assertTrue(withMt2 > t.length * 9 / 10, "MegaTag 2 answers once the camera has been told the heading: " + withMt2 + " of " + t.length);
            assertTrue(median(mt2Error) < 4.0, "MegaTag 2 error " + median(mt2Error) + " in");
            assertTrue(median(mt2Error) <= median(mt1Error) * 1.1, "fixing the heading does not make the position worse");
        }
    }

    // ---- the driver and the camera the robot does not have ------------------------------------------------------

    @Test
    void aRobotWithoutACameraHasAnUnpluggedOneAndTheProblemListSaysSo() throws Exception {
        ScriptedBody chassis = standingBefore(tag(20), 1.5, 30);
        TestRobot.Builder noCamera = TestRobot.builder().chassis(chassis).fieldTags(DECODE_TAGS);

        Run run = run(noCamera, "Limelight Pose", 1500 * MS);

        for (double v : column(run, "valid", 0.0, false)) {
            assertEquals(0.0, v);
        }
        for (double connected : column(run, "connected", 0.0, false)) {
            assertEquals(0.0, connected);
        }
        assertTrue(run.problems().stream().anyMatch(p -> p.contains("\"limelight\"") && p.contains("the robot has no camera")), run.problems().toString());
    }

    @Test
    void theDriversStatesPollRateAndConnectionBehaveAsTheSdkDriversDo() throws Exception {
        ScriptedBody chassis = standingBefore(tag(20), 1.5, 30);
        Run run;
        try (FieldRays rays = new FieldRays()) {
            run = run(robot(chassis, rays), "Limelight Lifecycle", 10, 6000 * MS, Long.MAX_VALUE / 4);
        }
        Map<String, List<Double>> s = run.series();
        java.util.function.Function<String, Double> value = key -> s.get(key).get(0);

        assertEquals(0.0, value.apply("fresh.running"));
        assertEquals(0.0, value.apply("fresh.connected"));
        assertTrue(value.apply("fresh.since") > 1e12, "never answered: the time since the epoch");
        assertEquals(0.0, value.apply("fresh.valid"));
        assertEquals(1.0, value.apply("started.running"));
        assertEquals(1.0, value.apply("started.connected"));
        assertTrue(value.apply("started.since") < 20, "polled every 10 ms: " + value.apply("started.since"));
        assertEquals(1.0, value.apply("started.valid"));
        assertTrue(value.apply("started.staleness") < 20);
        assertEquals(0.0, value.apply("paused.running"));
        assertEquals(0.0, value.apply("paused.connected"), "no answer for 600 ms is not connected");
        assertTrue(value.apply("paused.since") >= 550 && value.apply("paused.since") <= 650, "since " + value.apply("paused.since"));
        assertEquals(1.0, value.apply("paused.valid"), "the last result stays");
        assertTrue(value.apply("paused.staleness") >= 550, "and grows stale: " + value.apply("paused.staleness"));
        assertEquals(1.0, value.apply("resumed.running"));
        assertEquals(1.0, value.apply("resumed.connected"));
        assertTrue(value.apply("resumed.since") < 20, "a new rate set while paused does not change the schedule: " + value.apply("resumed.since"));
        assertTrue(value.apply("rateIgnoredWhileRunning.since") < 20);
        assertEquals(0.0, value.apply("stopped.running"));
        assertEquals(0.0, value.apply("stopped.connected"));
        assertEquals(1.0, value.apply("slowAfter300.running"));
        assertEquals(0.0, value.apply("slowAfter300.connected"), "at 1 Hz the answer is 300 ms old: " + value.apply("slowAfter300.since"));
        assertTrue(value.apply("slowAfter300.since") >= 290);
        assertEquals(1.0, value.apply("slowAfter1100.connected"), "the next poll came at one second: " + value.apply("slowAfter1100.since"));
        assertTrue(value.apply("slowAfter1100.since") < 150);

        assertEquals(1.0, value.apply("switch"));
        assertEquals(1.0, value.apply("reload"));
        assertEquals(1.0, value.apply("orientation"));
        assertEquals(20.0, value.apply("statusFps"), "Hornet's 1280x960 pipeline runs at 20 frames per second");
        assertEquals(0.0, value.apply("statusPipeline"));
        assertEquals(1.0, value.apply("statusType"));
        assertEquals(1.0, value.apply("finalResultNotNull"));
        assertEquals(1.0, value.apply("closed"));
    }

    @Test
    void whatTheRealCameraNeedsItsStorageOrSoftwareForFailsAndIsReported() throws Exception {
        ScriptedBody chassis = standingBefore(tag(20), 1.5, 30);
        Run run;
        try (FieldRays rays = new FieldRays()) {
            run = run(robot(chassis, rays), "Limelight Lifecycle", 10, 6000 * MS, Long.MAX_VALUE / 4);
        }
        Map<String, List<Double>> s = run.series();

        for (String refused : List.of("snapshot", "deleteSnapshots", "pythonEmpty", "pythonTooMany", "python", "uploadPipeline", "uploadPython", "uploadEmptyMap", "calibration")) {
            assertEquals(0.0, s.get(refused).get(0), refused + " is not simulated, so the driver reports failure");
        }
        String problems = String.join("\n", run.problems());
        for (String reported : List.of("/capture-snapshot", "/delete-snapshots", "/update-pythoninputs", "/upload-pipeline", "/upload-python", "/cal-default")) {
            assertTrue(problems.contains(reported), reported + " is in the problem list:\n" + problems);
        }
        assertFalse(problems.contains("/upload-fieldmap"), "the driver refuses an empty map itself, before it asks the camera");
        assertFalse(problems.contains("/pipeline-switch") || problems.contains("/status") || problems.contains("/update-robotorientation"),
                "what the camera does is not a problem:\n" + problems);
    }

    @Test
    void everyPublicMethodOfTheSdksDriverIsReplacedByTheSimulatedOne() throws Exception {
        try (VirtualSession session = new VirtualSession(TestRobot.builder())) {
            List<String> missing = session.world.run("dev.ftcsim.scenarios.LimelightSurface");

            assertEquals(List.of(), missing, "these would open a real socket and start the driver's own thread");
        }
    }

    // ---- slicing of simulated time -----------------------------------------------------------------------------

    @Test
    void theCamerasResultsDoNotDependOnHowSimulatedTimeIsSliced() throws Exception {
        Vec3 normal = normalOf(tag(20));
        double heading = StrictMath.atan2(-normal.y(), -normal.x());
        ScriptedBody chassis = ScriptedBody.startingAt(
                        tag(20).local().x() + normal.x() * 3.5, tag(20).local().y() + normal.y() * 3.5, heading + 0.1)
                .stay(0.7)
                .move(1.0, 1.0, 0.2, 0.1)
                .stay(30)
                .build();
        Run first;
        try (FieldRays rays = new FieldRays()) {
            first = run(robot(chassis, rays), "Limelight Pose", 500 * MS, 1500 * MS, SLICES[0]);
            assertTrue(first.series().get("valid").stream().anyMatch(v -> v == 1.0), "the camera saw something");
            for (int i = 1; i < SLICES.length; i++) {
                Run other = run(robot(chassis, rays), "Limelight Pose", 500 * MS, 1500 * MS, SLICES[i]);
                assertEquals(first.series().keySet(), other.series().keySet());
                for (String key : first.series().keySet()) {
                    assertEquals(first.series().get(key), other.series().get(key), key + " with slices of " + SLICES[i] + " ns");
                }
                assertEquals(first.problems(), other.problems());
            }
        }
    }
}
