package dev.ftcsim.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.world.BodyPose;
import dev.ftcsim.contracts.world.BodyView;
import dev.ftcsim.hardware.camera.Mat3;
import dev.ftcsim.hardware.device.scripted.ScriptedBody;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.LongFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The SDK's own drivers for the BNO055 inside the Control Hub, the new {@code IMU} interface, the legacy
 * {@code BNO055IMU} and the hub's embedded drivers, run by robot programs as a team writes them, talking over the
 * simulated hub to the chip model, with a chassis whose true attitude is known. Every number the program reads is compared
 * with what the chassis really does, worked out here from the angles with no help from the model or the SDK.
 */
@Timeout(600)
class ImuHostTest {
    private static final long MS = 1_000_000L;
    private static final long SECOND = 1_000 * MS;
    private static final long WHOLE = Long.MAX_VALUE / 4;
    private static final double DEG = Math.PI / 180.0;
    private static final String SETTINGS = "dev.ftcsim.scenarios.opmodes.ImuSettings";

    /** What a run of one OpMode looked like from the robot program and from the simulator. */
    private record Run(Map<String, List<Double>> series, List<String> messages, List<String> problems) {}

    /** A chassis whose true state is a function of time. */
    private static final class Attitude implements BodyView {
        private final LongFunction<BodyPose> truth;
        private long now;

        Attitude(LongFunction<BodyPose> truth) {
            this.truth = truth;
        }

        void advanceTo(long nanos) {
            now = nanos;
        }

        @Override
        public BodyPose sample() {
            return truth.apply(now);
        }
    }

    /** Level and facing +X until {@code atNanos}, then standing still with the given yaw, pitch (nose down positive) and roll. */
    private static Attitude tiltedAt(long atNanos, double yaw, double pitch, double roll) {
        return new Attitude(nanos -> {
            boolean tilted = nanos >= atNanos;
            double[] q = Mat3.fromYawPitchRoll(tilted ? yaw : 0, tilted ? pitch : 0, tilted ? roll : 0).toQuaternion();
            return new BodyPose(nanos, 0, 0, 0, q[0], q[1], q[2], q[3], 0, 0, 0, 0, 0, 0);
        });
    }

    /** Stands still for 4 s, turns left at 0.5 rad/s for 2 s, which is 57.3 degrees, and stands still for 2 s more. */
    private static ScriptedBody leftTurn() {
        return ScriptedBody.startingAt(0, 0, 0).stay(4).move(2, 0, 0, 0.5).stay(2).build();
    }

    private static void set(VirtualSession session, String field, Object value) throws Exception {
        session.world.load(SETTINGS).getField(field).set(null, value);
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

    private static Run run(TestRobot.Builder robot, String opMode, Map<String, Object> settings, long initNanos, long runNanos, long slice)
            throws Exception {
        try (VirtualSession session = new VirtualSession(robot)) {
            for (Map.Entry<String, Object> setting : settings.entrySet()) {
                set(session, setting.getKey(), setting.getValue());
            }
            session.init(opMode);
            session.advance(initNanos, slice);
            start(session);
            session.advance(runNanos, slice);
            List<String> problems = new ArrayList<>(session.kernel.diagnostics().problems());
            // The SDK's constructor and resetYaw poll the chip inside a synchronized method, where the world cannot
            // step; a chip that only answered after a step would make that poll give up.
            if (session.world.log().stream().anyMatch(line -> line.contains("Failed to retrieve valid quaternion"))) {
                problems.add("the SDK gave up polling the chip for a quaternion");
            }
            Run run = new Run(
                    session.world.run("dev.ftcsim.scenarios.ReadSeries"),
                    session.world.run("dev.ftcsim.scenarios.ReadImuMessages"),
                    problems);
            session.stop();
            session.advance(100 * MS, slice);
            return run;
        }
    }

    private static Run turning(String opMode, Map<String, Object> settings, long slice) throws Exception {
        return run(TestRobot.builder().chassis(leftTurn()).embeddedImu(Map.of()), opMode, settings, 3 * SECOND, 5 * SECOND, slice);
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
        for (int i = 0; i < Math.min(times.size(), values.size()); i++) {
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

    // ---- what the SDK is supposed to report, from the true attitude alone -------------------------------------

    private static double[][] rotZ(double angle) {
        return new double[][] {{Math.cos(angle), -Math.sin(angle), 0}, {Math.sin(angle), Math.cos(angle), 0}, {0, 0, 1}};
    }

    private static double[][] rotY(double angle) {
        return new double[][] {{Math.cos(angle), 0, Math.sin(angle)}, {0, 1, 0}, {-Math.sin(angle), 0, Math.cos(angle)}};
    }

    private static double[][] rotX(double angle) {
        return new double[][] {{1, 0, 0}, {0, Math.cos(angle), -Math.sin(angle)}, {0, Math.sin(angle), Math.cos(angle)}};
    }

    private static double[][] times(double[][] a, double[][] b) {
        double[][] product = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                for (int k = 0; k < 3; k++) {
                    product[i][j] += a[i][k] * b[k][j];
                }
            }
        }
        return product;
    }

    private static double[][] transpose(double[][] a) {
        return new double[][] {{a[0][0], a[1][0], a[2][0]}, {a[0][1], a[1][1], a[2][1]}, {a[0][2], a[1][2], a[2][2]}};
    }

    /**
     * The yaw, pitch and roll in degrees that the SDK documents for a chassis that is turned from level, facing where
     * it faced when the yaw was reset, by {@code yaw} about the vertical, then {@code pitch} about its left axis
     * (positive when the nose goes down) and {@code roll} about its forward axis (positive when its right side goes
     * down). The SDK measures in the Robot Coordinate System, X to the right, Y forward, Z up, as intrinsic rotations in
     * the order yaw about Z, pitch about X, roll about Y, with the right-hand rule: yaw is positive to the left, pitch
     * positive when the nose goes up, roll positive when the robot leans to the right.
     */
    private static double[] sdkAngles(double yaw, double pitch, double roll) {
        double[][] chassis = times(times(rotZ(yaw), rotY(pitch)), rotX(roll));
        double[][] toRobotFrame = rotZ(Math.PI / 2);
        double[][] r = times(times(toRobotFrame, chassis), transpose(toRobotFrame));
        double pitchAboutX = Math.asin(r[2][1]);
        double yawAboutZ = Math.atan2(-r[0][1], r[1][1]);
        double rollAboutY = Math.atan2(-r[2][0], r[2][2]);
        return new double[] {yawAboutZ / DEG, pitchAboutX / DEG, rollAboutY / DEG};
    }

    // ---- the Control Hub's IMU through the new interface -------------------------------------------------------

    @Test
    void aChassisThatTurnsLeftReadsPositiveYawAndItsTurnRateThroughTheNewInterface() throws Exception {
        Run run = turning("IMU Probe", Map.of(), WHOLE);

        assertEquals(List.of(), run.problems());
        assertEquals(1.0, run.series().get("embeddedDriver").get(0), "the Control Hub's own driver of the new API");
        assertEquals(1.0, run.series().get("initialized").get(0));
        assertAllWithin("yaw before the turn", 0.0, 0.3, between(run, "yaw", 3.5, 3.95));
        List<Double> times = run.series().get("t");
        List<Double> yaws = run.series().get("yaw");
        for (int i = 0; i < times.size(); i++) {
            double seconds = times.get(i) / 1e9;
            if (seconds > 4.3 && seconds < 5.9) {
                assertEquals((seconds - 4.0) * 0.5 / DEG, yaws.get(i), 1.2, "yaw in the turn at " + seconds + " s");
            }
        }
        assertAllWithin("yaw after the turn", 0.5 * 2 / DEG, 0.3, between(run, "yaw", 6.3, 8.0));
        assertAllWithin("turn rate in the turn", 0.5, 0.03, between(run, "rateZ", 4.2, 5.8));
        assertAllWithin("turn rate when still", 0.0, 0.005, between(run, "rateZ", 6.3, 8.0));
        assertAllWithin("the chassis stays level: pitch", 0.0, 0.2, run.series().get("pitch"));
        assertAllWithin("the chassis stays level: roll", 0.0, 0.2, run.series().get("roll"));
        assertAllWithin("no spin about the other axes", 0.0, 0.005, run.series().get("rateX"));
        assertAllWithin("no spin about the other axes", 0.0, 0.005, run.series().get("rateY"));
        // The same orientation as the other forms of the interface give it.
        assertEquals(last(run, "yaw"), last(run, "firstAngle"), 0.01);
        assertEquals(Math.cos(0.5 * 57.2958 * DEG), last(run, "qw"), 0.003);
        assertEquals(Math.sin(0.5 * 57.2958 * DEG), last(run, "qz"), 0.003);
        assertEquals(0.0, last(run, "qx"), 0.003);
    }

    @Test
    void resetYawMakesTheHeadingTheChassisHasNowTheZero() throws Exception {
        Run run = turning("IMU Probe", Map.of("resetYawAtSeconds", 3.5), WHOLE);

        // The turn is over at 6 s of simulated time; the reset comes 3.5 s after Start, which was at 3 s.
        assertAllWithin("yaw before the reset", 57.2958, 0.4, between(run, "yaw", 6.2, 6.45));
        assertAllWithin("yaw after the reset", 0.0, 0.3, between(run, "yaw", 6.7, 8.0));
        assertEquals(1, run.series().get("resetAt").size());
    }

    @Test
    void theYawReferenceSurvivesAnOpModeAndTheNextOneStartsWhereTheLastEnded() throws Exception {
        try (VirtualSession session = new VirtualSession(TestRobot.builder().chassis(leftTurn()).embeddedImu(Map.of()))) {
            session.init("IMU Probe");
            session.advance(3 * SECOND);
            session.start();
            session.advance(5 * SECOND);
            session.stop();
            session.advance(300 * MS);
            int loopsOfTheFirstRun = session.world.<Map<String, List<Double>>>run("dev.ftcsim.scenarios.ReadSeries").get("yaw").size();

            session.init("IMU Probe");
            session.advance(SECOND);
            session.start();
            session.advance(SECOND);

            List<Double> yaws = session.world.<Map<String, List<Double>>>run("dev.ftcsim.scenarios.ReadSeries").get("yaw");
            assertTrue(yaws.size() > loopsOfTheFirstRun, "the second OpMode ran");
            assertEquals(57.2958, yaws.get(loopsOfTheFirstRun), 0.4, "no reset happened between the OpModes");
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"imu", "IMU", "gyro", "Control Hub IMU"})
    void theIMUInterfaceFindsTheHubsIMUUnderAnyNameTheCodeGivesIt(String name) throws Exception {
        Run run = turning("IMU Probe", Map.of("name", name), WHOLE);

        assertEquals(1.0, run.series().get("embeddedDriver").get(0));
        assertAllWithin("yaw after the turn", 57.2958, 0.4, between(run, "yaw", 6.3, 8.0));
    }

    @Test
    void aSecondNameForTheOneIMUOfTheHubIsRefusedAsAConfigurationWouldRefuseIt() throws Exception {
        Run run = turning("IMU Names", Map.of(), WHOLE);

        assertEquals(1.0, run.series().get("first").get(0));
        assertFalse(run.series().containsKey("second"), "the second name must not find a second IMU");
        assertEquals(1, run.messages().size(), "only the second name was refused: " + run.messages());
        String refusal = run.messages().get(0);
        assertTrue(refusal.startsWith("IllegalArgumentException: Unable to find a hardware device with name \"gyro\" and type IMU"), refusal);
        assertTrue(refusal.contains("\"imu\" already is it"), "it says which name has the IMU: " + refusal);
        assertEquals(1.0, run.series().get("legacyUnderTheSameName").get(0), "the legacy API under the same name is allowed");
        assertEquals(1.0, run.series().get("legacyIsTheEmbeddedDriver").get(0));
    }

    // ---- every way to mount the hub, against the SDK's own tables --------------------------------------------

    private static final List<String> DIRECTIONS = List.of("UP", "DOWN", "FORWARD", "BACKWARD", "LEFT", "RIGHT");

    /** Every pair of directions the SDK accepts for a hub: the logo and the USB ports at right angles. */
    static Stream<Arguments> mountings() {
        List<Arguments> pairs = new ArrayList<>();
        for (int logo = 0; logo < 6; logo++) {
            for (int usb = 0; usb < 6; usb++) {
                if (logo / 2 != usb / 2) {
                    pairs.add(Arguments.of(DIRECTIONS.get(logo), DIRECTIONS.get(usb)));
                }
            }
        }
        return pairs.stream();
    }

    @ParameterizedTest(name = "logo {0}, USB {1}")
    @MethodSource("mountings")
    void whicheverWayTheHubIsMountedTheSdkReportsTheAnglesOfTheChassisWhenCodeTellsItTheSameMounting(String logo, String usb)
            throws Exception {
        double yaw = 40 * DEG;
        double pitch = -15 * DEG;
        double roll = 10 * DEG;
        Attitude attitude = tiltedAt(3 * SECOND, yaw, pitch, roll);
        TestRobot.Builder robot = TestRobot.builder()
                .chassis(attitude, attitude::advanceTo)
                .embeddedImu(Map.of("mount.logo", logo, "mount.port", usb));

        Run run = run(robot, "IMU Probe", Map.of("logo", logo, "usb", usb, "resetYawAtSeconds", 0.0), 2 * SECOND, 3 * SECOND, WHOLE);

        double[] expected = sdkAngles(yaw, pitch, roll);
        assertAllWithin("yaw", expected[0], 0.6, between(run, "yaw", 4.5, 6.0));
        assertAllWithin("pitch", expected[1], 0.6, between(run, "pitch", 4.5, 6.0));
        assertAllWithin("roll", expected[2], 0.6, between(run, "roll", 4.5, 6.0));
    }

    @Test
    void theExpectedAnglesAreWhatTheSdkDocumentsForTheSimplestCases() {
        assertArrayEquals3(new double[] {40, 0, 0}, sdkAngles(40 * DEG, 0, 0));
        assertArrayEquals3(new double[] {0, 15, 0}, sdkAngles(0, -15 * DEG, 0));
        assertArrayEquals3(new double[] {0, 0, 10}, sdkAngles(0, 0, 10 * DEG));
    }

    private static void assertArrayEquals3(double[] expected, double[] actual) {
        for (int i = 0; i < 3; i++) {
            assertEquals(expected[i], actual[i], 1e-9, "angle " + i);
        }
    }

    @Test
    void aMountingThatCodeGetsWrongGivesTheWrongAnglesAsItDoesOnARobot() throws Exception {
        double yaw = 40 * DEG;
        double pitch = -15 * DEG;
        Attitude attitude = tiltedAt(3 * SECOND, yaw, pitch, 0);
        TestRobot.Builder robot = TestRobot.builder().chassis(attitude, attitude::advanceTo).embeddedImu(Map.of());

        // The hub is mounted as the SDK assumes, logo up and USB forward; the code believes the USB ports face right.
        Run quarterTurnOff = run(robot, "IMU Probe", Map.of("usb", "RIGHT", "resetYawAtSeconds", 0.0), 2 * SECOND, 3 * SECOND, WHOLE);
        // And now it believes they face backward.
        Attitude again = tiltedAt(3 * SECOND, yaw, pitch, 0);
        Run halfTurnOff = run(TestRobot.builder().chassis(again, again::advanceTo).embeddedImu(Map.of()), "IMU Probe",
                Map.of("usb", "BACKWARD", "resetYawAtSeconds", 0.0), 2 * SECOND, 3 * SECOND, WHOLE);

        assertAllWithin("the heading does not care which way the hub is turned about the vertical", 40.0, 0.6,
                between(quarterTurnOff, "yaw", 4.5, 6.0));
        assertAllWithin("a nose-up is not a pitch for a program that is a quarter turn off", 0.0, 0.8, between(quarterTurnOff, "pitch", 4.5, 6.0));
        for (double roll : between(quarterTurnOff, "roll", 4.5, 6.0)) {
            assertEquals(15.0, Math.abs(roll), 0.8, "it shows up as a roll instead");
        }
        assertAllWithin("the heading is the same half a turn off", 40.0, 0.6, between(halfTurnOff, "yaw", 4.5, 6.0));
        assertAllWithin("and the nose-up reads as a nose-down", -15.0, 0.8, between(halfTurnOff, "pitch", 4.5, 6.0));
    }

    // ---- the legacy API ----------------------------------------------------------------------------------------

    @Test
    void theLegacyApiInitialisesTheChipInPlausibleTimeAndReadsOrientationGravityAndRate() throws Exception {
        Run run = turning("Legacy IMU Probe", Map.of(), WHOLE);

        assertEquals(List.of(), run.problems());
        assertEquals(1.0, run.series().get("initialized").get(0));
        assertEquals(5.0, run.series().get("systemStatus").get(0), "running with sensor fusion");
        double initDoneSeconds = run.series().get("initDone").get(0) / 1e9;
        assertTrue(initDoneSeconds > 0.7 && initDoneSeconds < 1.5,
                "a reset takes 650 ms, the driver's own waits another 500: " + initDoneSeconds + " s");
        assertAllWithin("heading before the turn", 0.0, 0.3, between(run, "firstAngle", 3.5, 3.95));
        assertAllWithin("heading after the turn: positive is counter-clockwise", 57.2958, 0.4, between(run, "firstAngle", 6.3, 8.0));
        assertAllWithin("level: the second angle", 0.0, 0.3, run.series().get("secondAngle"));
        assertAllWithin("level: the third angle", 0.0, 0.3, run.series().get("thirdAngle"));
        assertAllWithin("gravity is up along the chip's z", 9.81, 0.05, run.series().get("gravityZ"));
        assertAllWithin("and not along its x", 0.0, 0.05, run.series().get("gravityX"));
        assertAllWithin("turn rate in degrees per second", 0.5 / DEG, 1.5, between(run, "rateZ", 4.2, 5.8));
        assertAllWithin("no linear acceleration", 0.0, 0.1, run.series().get("linearX"));
        assertEquals(30.0, last(run, "temperature"), 0.0);
        assertEquals(0xFC, last(run, "calibration"), "the chip has calibrated by now, except the magnetometer, which IMU mode does not use");
    }

    @Test
    void theLegacyApiShowsATiltAsTheSdkDocumentsItsAnglesAndGravityInTheChipsAxes() throws Exception {
        Attitude attitude = tiltedAt(3 * SECOND, 0, -15 * DEG, 10 * DEG);
        Run run = run(TestRobot.builder().chassis(attitude, attitude::advanceTo).embeddedImu(Map.of()), "Legacy IMU Probe", Map.of(),
                3 * SECOND, 2 * SECOND, WHOLE);

        // The second angle turns about the chip's Y axis, which points left: a nose up of 15 degrees is minus 15.
        assertAllWithin("second angle", -15.0, 0.5, between(run, "secondAngle", 4.0, 5.0));
        // The third turns about the chip's X axis, which points to the back, with the sign the SDK documents for it.
        assertAllWithin("third angle", 10.0, 0.5, between(run, "thirdAngle", 4.0, 5.0));
        double g = 9.80665;
        // The up direction in the chassis' frame, and which way each of the chip's axes (back, right, up) points in it.
        assertAllWithin("gravity along the chip's back-pointing x", -g * Math.sin(15 * DEG), 0.08, between(run, "gravityX", 4.0, 5.0));
        assertAllWithin("gravity along the chip's right-pointing y", -g * Math.cos(15 * DEG) * Math.sin(10 * DEG), 0.08,
                between(run, "gravityY", 4.0, 5.0));
        assertAllWithin("gravity along the chip's up-pointing z", g * Math.cos(15 * DEG) * Math.cos(10 * DEG), 0.08,
                between(run, "gravityZ", 4.0, 5.0));
    }

    // ---- the IMU of the newer Control Hub ------------------------------------------------------------------------

    @Test
    void theNewerControlHubsDriverIsRefusedWithAMessageThatSaysWhyAndWhatToUse() throws Exception {
        Run run = turning("BHI260 IMU", Map.of(), WHOLE);

        assertFalse(run.series().containsKey("found"));
        String failure = run.messages().get(0);
        assertTrue(failure.startsWith("java.lang.IllegalArgumentException: Unable to find a hardware device with name \"imu\""), failure);
        assertTrue(failure.contains("BHI260") && failure.contains("BNO055"), failure);
        assertTrue(failure.contains("hardwareMap.get(IMU.class"), "it says what to ask for instead: " + failure);
    }

    // ---- slicing of simulated time -------------------------------------------------------------------------------

    @Test
    void everyReadingIsTheSameWhateverTheSlicingOfSimulatedTime() throws Exception {
        Run whole = turning("IMU Probe", Map.of(), WHOLE);
        for (long slice : new long[] {MS, 7 * MS, 33 * MS}) {
            Run sliced = turning("IMU Probe", Map.of(), slice);

            assertEquals(whole.series(), sliced.series(), "the readings with slices of " + slice / MS + " ms");
        }
        Run legacy = turning("Legacy IMU Probe", Map.of(), WHOLE);
        assertEquals(legacy.series(), turning("Legacy IMU Probe", Map.of(), 7 * MS).series());
        assertNotEquals(0, whole.series().get("yaw").size());
    }
}
