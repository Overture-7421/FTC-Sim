package dev.ftcsim.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.world.SurfaceHit;
import dev.ftcsim.hardware.device.scripted.ScriptedBody;
import dev.ftcsim.physics.DcMotor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The real SDK drivers of a goBILDA Pinpoint, a REV Color Sensor V3 and an OctoQuad, run by robot programs exactly as
 * a team writes them, talking over the simulated hub to the device models, with a chassis that follows a script so
 * that the truth the devices measure is known. Every run is repeated with other slicings of simulated time, which
 * must not change a single reading.
 */
@Timeout(300)
class DeviceModelsHostTest {
    private static final long MS = 1_000_000L;
    private static final long[] SLICES = {Long.MAX_VALUE / 4, 1 * MS, 7 * MS, 33 * MS};

    /** What a run of one OpMode looked like from the robot program and from the simulator. */
    private record Run(Map<String, List<Double>> series, Map<String, Integer> counts, List<String> problems, String warnings) {}

    private static void advanceInSlices(VirtualSession session, long total, long slice) {
        long end = session.now() + total;
        while (session.now() < end) {
            session.kernel.runUntil(Math.min(end, session.now() + slice));
        }
    }

    private static Run run(TestRobot.Builder robot, String opMode, long initNanos, long runNanos, long slice, List<String> logOut)
            throws Exception {
        try (VirtualSession session = new VirtualSession(robot)) {
            session.init(opMode);
            advanceInSlices(session, initNanos, slice);
            start(session);
            advanceInSlices(session, runNanos, slice);
            Run run = new Run(
                    session.world.run("dev.ftcsim.scenarios.ReadSeries"),
                    session.world.run("dev.ftcsim.scenarios.ReadProbe"),
                    session.kernel.diagnostics().problems(),
                    session.telemetry().warnings());
            session.stop();
            advanceInSlices(session, 100 * MS, slice);
            logOut.addAll(session.world.log());
            return run;
        }
    }

    /** Presses Start; if the OpMode did not get through init, says why instead of only that the robot is idle. */
    private static void start(VirtualSession session) {
        try {
            session.start();
        } catch (IllegalStateException notInitialised) {
            List<String> log = session.world.log();
            throw new AssertionError(
                    notInitialised.getMessage() + "\nTelemetry: " + session.telemetry() + "\nLog:\n"
                            + String.join("\n", log.subList(Math.max(0, log.size() - 40), log.size())),
                    notInitialised);
        }
    }

    private static double last(Run run, String series) {
        List<Double> values = run.series().get(series);
        return values.get(values.size() - 1);
    }

    /** The values of {@code series} at the loops that ran between two instants, in seconds of simulated time. */
    private static List<Double> between(Run run, String series, double fromSeconds, double toSeconds) {
        List<Double> times = run.series().get("t");
        List<Double> values = run.series().get(series);
        List<Double> selected = new ArrayList<>();
        for (int i = 0; i < times.size(); i++) {
            double seconds = times.get(i) / 1e9;
            if (seconds >= fromSeconds && seconds < toSeconds) {
                selected.add(values.get(i));
            }
        }
        assertFalse(selected.isEmpty(), "no loop of " + series + " ran between " + fromSeconds + " s and " + toSeconds + " s");
        return selected;
    }

    private static void assertAllWithin(String what, double expected, double tolerance, List<Double> values) {
        for (double value : values) {
            assertEquals(expected, value, tolerance, what);
        }
    }

    // ---- goBILDA Pinpoint -------------------------------------------------------------------------------------

    /** Hornet's pods: the forward pod 4.467131 in right of the tracking point, the strafe pod 0.160124 in behind it. */
    private static Map<String, Object> hornetPods() {
        Map<String, Object> settings = new HashMap<>();
        settings.put("xPod.x", 0.02);
        settings.put("xPod.y", -4.467131 * 0.0254);
        settings.put("xPod.ticksPerMetre", 19_894.36789);
        settings.put("xPod.direction", -1);
        settings.put("yPod.x", -0.160124 * 0.0254);
        settings.put("yPod.y", 0.03);
        settings.put("yPod.ticksPerMetre", 19_894.36789);
        settings.put("yPod.direction", -1);
        return settings;
    }

    /** One metre forward, a quarter turn on the spot, half a metre forward again: now along +Y. */
    private static ScriptedBody forwardTurnForward() {
        return ScriptedBody.startingAt(0, 0, 0)
                .stay(1.5)
                .move(2, 0.5, 0, 0)
                .move(1, 0, 0, Math.PI / 2)
                .move(1, 0.5, 0, 0)
                .build();
    }

    private static Run pinpointRun(String opMode, long slice) throws Exception {
        TestRobot.Builder robot = TestRobot.builder()
                .chassis(forwardTurnForward())
                .device("odopods", 1, "gobilda-pinpoint", hornetPods());
        return run(robot, opMode, 1000 * MS, 6000 * MS, slice, new ArrayList<>());
    }

    @Test
    void pedroPathingsPinpointSequenceWorksAndTheChipTracksTheRobot() throws Exception {
        Run run = pinpointRun("Pinpoint Odometry", Long.MAX_VALUE / 4);

        assertEquals(List.of(), run.problems());
        assertEquals(2.0, run.series().get("deviceId").get(0));
        assertEquals(3.0, run.series().get("deviceVersion").get(0));
        assertTrue(run.counts().get("calibrating") > 0, "the first look after the reset finds the chip calibrating");
        assertTrue(run.counts().get("ready") > 0, "and it becomes ready");
        assertEquals(1000.0, last(run, "x"), 1.5);
        assertEquals(500.0, last(run, "y"), 1.5);
        assertEquals(Math.PI / 2, last(run, "heading"), 2e-3);
        assertEquals(667.0, last(run, "loopTime"));
        assertEquals(1.0, last(run, "status"), "READY");
        assertEquals(19_894.37 * (1.5 + 0.113462 * Math.PI / 2), last(run, "encoderX"), 5.0);
        assertEquals(0.0, last(run, "velX"), 1e-3);
    }

    @Test
    void whileTheRobotTurnsInPlaceThePinpointDoesNotMoveBecauseTheOffsetsAreRight() throws Exception {
        Run run = pinpointRun("Pinpoint Odometry", Long.MAX_VALUE / 4);

        assertAllWithin("x during the turn", 1000.0, 1.5, between(run, "x", 3.6, 4.5));
        assertAllWithin("y during the turn", 0.0, 1.5, between(run, "y", 3.6, 4.5));
        List<Double> headings = between(run, "heading", 3.6, 4.5);
        assertTrue(headings.get(headings.size() - 1) - headings.get(0) > 1.0, "the heading did change");
        assertEquals(500.0, between(run, "velY", 4.7, 5.3).get(0), 12.0, "driving along +Y at 0.5 m/s");
    }

    @Test
    void offsetsRobotCodeGotWrongMakeThePoseDriftByWhatTheGeometrySays() throws Exception {
        Run run = pinpointRun("Pinpoint Odometry Without Offsets", Long.MAX_VALUE / 4);

        // The pods are 113.46 mm right of and 4.07 mm behind the tracking point; a quarter turn then moves it (a - b, a + b).
        assertEquals(1000.0 + 117.5, last(run, "x"), 3.0);
        assertEquals(500.0 + 109.4, last(run, "y"), 3.0);
    }

    @Test
    void thePinpointReadingsDoNotDependOnHowSimulatedTimeIsSliced() throws Exception {
        Run first = pinpointRun("Pinpoint Odometry", SLICES[0]);

        for (int i = 1; i < SLICES.length; i++) {
            assertEquals(first, pinpointRun("Pinpoint Odometry", SLICES[i]), "slice of " + SLICES[i] + " ns");
        }
    }

    // ---- REV Color Sensor V3 ---------------------------------------------------------------------------------

    private record Wall(double fromSeconds, double metres, int rgb) {}

    /** What stands in front of the sensor, second by second, a card at distances and then coloured things up close. */
    private static final List<Wall> WALLS = List.of(
            new Wall(1, 0.12, 0xCCCCCC),
            new Wall(2, 0.06, 0xCCCCCC),
            new Wall(3, 0.03, 0xCCCCCC),
            new Wall(4, 0.015, 0xCCCCCC),
            new Wall(5, 0.004, 0xCCCCCC),
            new Wall(6, 0.01, 0xFF0000),
            new Wall(7, 0.01, 0x00FF00),
            new Wall(8, 0.01, 0x0000FF),
            new Wall(9, 0.03, 0x6A3D9A),
            new Wall(10, 0.03, 0x000000));

    private static Run colorRun(long slice) throws Exception {
        ScriptedBody chassis = ScriptedBody.startingAt(0, 0, 0).build();
        TestRobot.Builder robot = TestRobot.builder()
                .chassis(chassis)
                .rays((origin, direction, maxMeters) -> {
                    double seconds = chassis.sample().timeNanos() / 1e9;
                    Wall wall = null;
                    for (Wall candidate : WALLS) {
                        if (seconds >= candidate.fromSeconds()) {
                            wall = candidate;
                        }
                    }
                    boolean inRange = wall != null && seconds < 11 && wall.metres() <= maxMeters;
                    return inRange ? new SurfaceHit(wall.metres(), SurfaceHit.SCENERY, -1, wall.rgb()) : null;
                })
                .device("upSensor", 2, "rev-color-sensor-v3", sensorSettings());
        return run(robot, "Color Range Sensor", 0, 11_500 * MS, slice, new ArrayList<>());
    }

    private static Map<String, Object> sensorSettings() {
        Map<String, Object> settings = new HashMap<>();
        settings.put("x", 0.1);
        settings.put("y", 0.0);
        settings.put("z", 0.1);
        return settings;
    }

    private static double lastBetween(Run run, String series, double fromSeconds, double toSeconds) {
        List<Double> values = between(run, series, fromSeconds, toSeconds);
        return values.get(values.size() - 1);
    }

    @Test
    void theColorRangeSensorHornetAsksForIsTheRevSensorAndReadsTheDistanceTheWorldHas() throws Exception {
        Run run = colorRun(Long.MAX_VALUE / 4);

        assertEquals(List.of(), run.problems());
        assertEquals(1.0, run.series().get("isRevColorSensorV3").get(0));
        assertEquals(15.24, lastBetween(run, "distanceCm", 0.5, 0.9), 0.01, "nothing in front reads the SDK's maximum");
        assertEquals(12.0, lastBetween(run, "distanceCm", 1.4, 2.0), 0.4);
        assertEquals(6.0, lastBetween(run, "distanceCm", 2.4, 3.0), 0.2);
        assertEquals(3.0, lastBetween(run, "distanceCm", 3.4, 4.0), 0.1);
        assertEquals(1.5, lastBetween(run, "distanceCm", 4.4, 5.0), 0.1);
        assertEquals(0.635, lastBetween(run, "distanceCm", 5.4, 6.0), 0.01, "too close saturates at the SDK's minimum of 0.25 in");
        assertEquals(15.24, lastBetween(run, "distanceCm", 10.4, 11.0), 0.01, "a black wall returns nothing");
    }

    @Test
    void hornetsThresholdOfFiveCentimetresSeesAPurpleBallAtThreeCentimetresAndNotAtTwelve() throws Exception {
        Run run = colorRun(Long.MAX_VALUE / 4);

        assertTrue(lastBetween(run, "distanceCm", 9.4, 10.0) < 5.0, "purple at 3 cm reads " + lastBetween(run, "distanceCm", 9.4, 10.0));
        assertTrue(lastBetween(run, "distanceCm", 1.4, 2.0) > 5.0);
    }

    @Test
    void theColourChannelsFollowTheColourOfTheSurface() throws Exception {
        Run run = colorRun(Long.MAX_VALUE / 4);

        assertTrue(lastBetween(run, "red", 6.4, 7.0) > 5 * lastBetween(run, "green", 6.4, 7.0));
        assertTrue(lastBetween(run, "green", 7.4, 8.0) > 5 * lastBetween(run, "red", 7.4, 8.0));
        assertTrue(lastBetween(run, "blue", 8.4, 9.0) > 5 * lastBetween(run, "green", 8.4, 9.0));
        assertTrue(lastBetween(run, "normalizedRed", 6.4, 7.0) > 0.2);
        assertTrue(lastBetween(run, "normalizedGreen", 7.4, 8.0) > 0.2);
        assertTrue(lastBetween(run, "normalizedAlpha", 6.4, 7.0) > lastBetween(run, "normalizedAlpha", 10.4, 11.0));
    }

    @Test
    void theColorSensorReadingsDoNotDependOnHowSimulatedTimeIsSliced() throws Exception {
        Run first = colorRun(SLICES[0]);

        for (int i = 1; i < SLICES.length; i++) {
            assertEquals(first, colorRun(SLICES[i]), "slice of " + SLICES[i] + " ns");
        }
    }

    // ---- OctoQuad ---------------------------------------------------------------------------------------------

    /** One metre forward, half a metre left, then a quarter turn on the spot. */
    private static ScriptedBody forwardLeftTurn() {
        return ScriptedBody.startingAt(0, 0, 0)
                .stay(1.5)
                .move(2, 0.5, 0, 0)
                .move(1, 0, 0.5, 0)
                .move(1, 0, 0, Math.PI / 2)
                .build();
    }

    private static Map<String, Object> octoquadSettings() {
        Map<String, Object> settings = new HashMap<>();
        settings.put("channel0.pod.x", 0.02);
        settings.put("channel0.pod.y", -0.1135);
        settings.put("channel0.pod.ticksPerMetre", 19_894.36789);
        settings.put("channel1.pod.x", -0.004);
        settings.put("channel1.pod.y", 0.03);
        settings.put("channel1.pod.axisDegrees", 90);
        settings.put("channel1.pod.ticksPerMetre", 19_894.36789);
        settings.put("channel2.encoder", "flywheel");
        return settings;
    }

    private static Run octoquadRun(long slice, List<String> log) throws Exception {
        TestRobot.Builder robot = TestRobot.builder()
                .motor("flywheel", 2, DcMotor.goBilda435(), 4.0e-4, 2.0e-4)
                .shareEncoder("flywheel", 0)
                .chassis(forwardLeftTurn())
                .device("octoquad", 2, "octoquad-ftc-mk2", octoquadSettings());
        return run(robot, "OctoQuad Localizer", 1000 * MS, 6000 * MS, slice, log);
    }

    @Test
    void theOctoQuadsLocalizerAndEncodersWorkThroughTheSdkDriver() throws Exception {
        Run run = octoquadRun(Long.MAX_VALUE / 4, new ArrayList<>());

        assertEquals(List.of(), run.problems());
        assertEquals(0x51, run.series().get("chipId").get(0));
        assertEquals(List.of(3.0, 1.0, 0.0), List.of(
                run.series().get("firmwareMajor").get(0), run.series().get("firmwareMinor").get(0), run.series().get("firmwareEngineering").get(0)));
        assertEquals(1.0, run.series().get("directionOfChannel2").get(0), "REVERSE");
        assertEquals(25.0, run.series().get("velocityIntervalOfChannel2").get(0));
        assertTrue(run.counts().get("running") > 0, "the localizer finishes calibrating while the OpMode is initialising");
        assertEquals(1000.0, last(run, "x"), 2.0);
        assertEquals(500.0, last(run, "y"), 2.0);
        assertEquals(Math.PI / 2, last(run, "heading"), 2e-3);
        assertEquals(0.0, last(run, "velX"), 1.0);
        for (double crcOk : run.series().get("crcOk")) {
            assertEquals(1.0, crcOk, "the CRCs of both blocks hold");
        }
    }

    @Test
    void theOctoQuadCountsTheTrackingWheelsAndAMechanismsEncoder() throws Exception {
        Run run = octoquadRun(Long.MAX_VALUE / 4, new ArrayList<>());

        // The forward pod rolled 1 m, and 0.1135 m * pi/2 more as the robot turned; the sideways pod 0.5 m and -0.004 * pi/2.
        assertEquals(19_894.4 + 0.1135 * Math.PI / 2 * 19_894.4, last(run, "encoder0"), 5.0);
        assertEquals(0.5 * 19_894.4 - 0.004 * Math.PI / 2 * 19_894.4, last(run, "encoder1"), 5.0);
        assertTrue(last(run, "encoder2") < -1000, "the flywheel's encoder, reversed on the OctoQuad, counts down");
        assertTrue(last(run, "velocity2") < -20, "in counts per 25 ms: " + last(run, "velocity2"));
    }

    @Test
    void theOctoQuadReadingsDoNotDependOnHowSimulatedTimeIsSliced() throws Exception {
        Run first = octoquadRun(SLICES[0], new ArrayList<>());

        for (int i = 1; i < SLICES.length; i++) {
            assertEquals(first, octoquadRun(SLICES[i], new ArrayList<>()), "slice of " + SLICES[i] + " ns");
        }
    }

    @Test
    void theSdkTwelveDriverChecksTheOctoQuadsUptimeWhenTheOpModeEndsAndSdkElevenDoesNot() throws Exception {
        List<String> log = new ArrayList<>();
        Run run = octoquadRun(Long.MAX_VALUE / 4, log);
        String text = String.join("\n", log);
        String sdk = System.getProperty("ftcsim.sdkVersion", "11.1.0");

        assertFalse(text.contains("Possible OctoQuad reboot"), "the chip's uptime agrees with the simulated clock");
        assertFalse(text.contains("CRC error"));
        assertEquals(sdk.startsWith("12"), text.contains("OctoQuad uptime delta agrees with OpMode runtime"), "SDK " + sdk);
        assertEquals(
                !sdk.startsWith("12"),
                run.warnings().contains("newer minor firmware revision"),
                "SDK 11 expects firmware 3.0.x and warns about the 3.1.0 of the model, SDK 12 expects 3.1.x: " + run.warnings());
    }
}
