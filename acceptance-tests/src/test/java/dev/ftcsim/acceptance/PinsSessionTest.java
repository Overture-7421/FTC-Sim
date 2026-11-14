package dev.ftcsim.acceptance;

import static dev.ftcsim.acceptance.SessionTelemetry.number;
import static dev.ftcsim.acceptance.SessionTelemetry.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.hardware.MotorPlant;
import dev.ftcsim.contracts.robot.GamepadState;
import dev.ftcsim.hardware.lynx.LynxHub;
import dev.ftcsim.simulator.SimulationSession;
import dev.ftcsim.simulator.profile.RobotProfile;
import dev.ftcsim.simulator.profile.RobotProfileLoader;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The digital and analog pins of a robot profile, read by a robot program through the SDK's own {@code DigitalChannel},
 * {@code TouchSensor} and {@code AnalogInput} classes on the simulated hubs. The lift they sit on is a motor the same
 * program drives through the real SDK, so a switch that closes or a potentiometer that moves is a consequence of what
 * the program did, in simulated time, and not of anything a test sets.
 */
@Timeout(300)
class PinsSessionTest {
    private static final long MS = 1_000_000L;
    private static final long SECOND = 1_000 * MS;
    private static final int EXPANSION_HUB = 2;

    /** Where the profile below closes the limit switch, in encoder counts. */
    private static final double SWITCH_AT = 600;

    private static final String PROFILE = """
            {"schema": 1, "name": "pins",
             "hubs": [{"address": 173, "name": "Control Hub", "parent": true, "embedded": true},
                      {"address": 2, "name": "Expansion Hub 2"}],
             "motors": {"slide": {"port": 0, "shaft": {"loadInertiaKgM2": 0.002, "frictionNmsPerRad": 0.002}}},
             "digitals": {
               "slideLimit": {"channel": 0, "source": {"type": "encoder", "motor": "slide", "counts": 600, "closedWhen": "ABOVE"}},
               "beam": {"hub": 2, "channel": 1, "source": {"type": "constant", "level": "LOW"}},
               "idleSwitch": {"channel": 2}},
             "analogs": {
               "slidePot": {"channel": 0, "source": {"type": "encoder", "motor": "slide", "voltsAtZero": 0.5, "voltsPerCount": 0.001}}}}
            """;

    private static RobotProfile profile() {
        return RobotProfileLoader.parse(PROFILE, "the pins profile");
    }

    private static void start(SimulationSession session, String opMode) {
        session.init(opMode);
        session.runFor(500 * MS);
        session.start();
        session.runFor(200 * MS);
    }

    /** The left stick pushed forward (which is negative on the gamepad) by {@code power}, or back when negative. */
    private static GamepadState lift(float power) {
        return GamepadState.sticks(0f, -power, 0f, 0f);
    }

    private static GamepadState buttons(boolean a, boolean b, boolean x, boolean y) {
        return new GamepadState(
                0f, 0f, 0f, 0f, 0f, 0f, false, false, false, false, a, b, x, y, false, false, false, false, false, false, false);
    }

    /** How far the slide can have moved since the program last looked: what it shows is as of a pass of its loop a few ms ago. */
    private static long lagCounts(MotorPlant slide) {
        return (long) Math.ceil(Math.abs(slide.encoderCountsPerSecond()) * 0.05) + 2;
    }

    /**
     * Pushes the lift forward until the program shows its switch closed, checking every pass against where the slide
     * really is: the program may not see the switch closed before it is, nor fail to see it a moment after.
     */
    private static void driveUntilClosed(SimulationSession session, String caption, String closedText) {
        MotorPlant slide = session.rig().plant("slide");
        session.setGamepad(1, lift(0.6f));
        for (int pass = 0; pass < 300; pass++) {
            session.runFor(10 * MS);
            long truth = slide.encoderCounts();
            if (text(session, caption).equals(closedText)) {
                assertTrue(truth > SWITCH_AT, "the program saw the switch closed, but the slide is only at " + truth);
                return;
            }
            assertTrue(truth <= SWITCH_AT + lagCounts(slide), "the switch should have closed by " + truth + " counts");
        }
        throw new AssertionError("the switch never closed: the slide is at " + slide.encoderCounts());
    }

    /** Lets the lift go on a little, stops it, waits until it is at rest, and returns where it stopped. */
    private static long stopPastTheSwitch(SimulationSession session) {
        session.runFor(500 * MS);
        session.setGamepad(1, GamepadState.NEUTRAL);
        session.runFor(1500 * MS);
        MotorPlant slide = session.rig().plant("slide");
        assertEquals(0.0, slide.encoderCountsPerSecond(), 1.0, "the slide is at rest");
        assertTrue(slide.encoderCounts() > SWITCH_AT + 300, "the slide should be well past the switch: " + slide.encoderCounts());
        return slide.encoderCounts();
    }

    /** What the SDK's {@code AnalogInput.getVoltage} gives for a potentiometer on the lift: whole millivolts times 0.001. */
    private static double potVolts(long counts) {
        return Math.round((0.5 + 0.001 * counts) * 1000.0) * 0.001;
    }

    @Test
    void aTouchSensorAndAPotentiometerOnTheSlideFollowTheMotorTheRealSdkDrives() throws IOException {
        try (TestSessions.Handle handle = TestSessions.open(profile(), 0, 0, 0)) {
            SimulationSession session = handle.session;
            MotorPlant slide = session.rig().plant("slide");
            start(session, "Touch Limit");
            assertEquals("false", text(session, "touch pressed"), "the switch is open at the start");
            assertEquals(0.0, number(session, "touch value"));
            assertEquals(0.5, number(session, "pot volts"), 0.0005);
            assertEquals(3.3, number(session, "pot max volts"));

            driveUntilClosed(session, "touch pressed", "true");
            assertEquals(1.0, number(session, "touch value"));
            long rest = stopPastTheSwitch(session);

            assertEquals("true", text(session, "touch pressed"));
            assertEquals(potVolts(rest), number(session, "pot volts"), 1e-9, "the potentiometer at " + rest + " counts");
            assertEquals(rest, number(session, "slide counts"), 0.0, "the SDK's own encoder reads the same shaft");

            session.setGamepad(1, lift(-0.6f));
            boolean released = false;
            for (int pass = 0; pass < 300 && !released; pass++) {
                session.runFor(10 * MS);
                long truth = slide.encoderCounts();
                released = text(session, "touch pressed").equals("false");
                if (released) {
                    assertTrue(truth <= SWITCH_AT, "the program saw the switch open, but the slide is at " + truth);
                } else {
                    assertTrue(truth > SWITCH_AT - lagCounts(slide), "the switch should have opened by " + truth + " counts");
                }
            }
            assertTrue(released, "the switch never opened again: the slide is at " + slide.encoderCounts());
            assertEquals(List.of(), session.problems(), handle.log.toString());
        }
    }

    @Test
    void theSameSwitchReadAsADigitalChannelIsHighWhenOpenAndLowWhenClosedAndAPinHeldLowOrLeftIdleReadsAsWired() throws IOException {
        try (TestSessions.Handle handle = TestSessions.open(profile(), 0, 0, 0)) {
            SimulationSession session = handle.session;
            start(session, "Digital Limit");
            assertEquals("true", text(session, "limit state"), "an open switch leaves the line to the hub's pull-up");
            assertEquals("false", text(session, "beam state"), "a pin on the expansion hub that the profile holds low");
            assertEquals("true", text(session, "idle state"), "a connected pin nothing drives is pulled up");
            assertEquals("INPUT", text(session, "limit mode"));

            driveUntilClosed(session, "limit state", "false");
            stopPastTheSwitch(session);

            assertEquals("false", text(session, "limit state"), "the closed switch pulls the line to ground");
            assertEquals("false", text(session, "beam state"));
            assertEquals("true", text(session, "idle state"));
            assertEquals(List.of(), session.problems(), handle.log.toString());
        }
    }

    @Test
    void thePinsAreReadFromTheHubsBulkDataWhenTheProgramTurnsBulkCachingOn() throws IOException {
        try (TestSessions.Handle handle = TestSessions.open(profile(), 0, 0, 0)) {
            SimulationSession session = handle.session;
            start(session, "Bulk Pins");
            assertEquals("false", text(session, "touch pressed"));
            assertEquals("false", text(session, "beam state"));

            driveUntilClosed(session, "touch pressed", "true");
            long rest = stopPastTheSwitch(session);

            assertEquals("true", text(session, "touch pressed"));
            assertEquals("false", text(session, "beam state"));
            assertEquals(potVolts(rest), number(session, "pot volts"), 1e-9, "the potentiometer at " + rest + " counts");
            assertEquals(List.of(), session.problems(), handle.log.toString());
        }
    }

    @Test
    void pinsTheProfileDoesNotMentionStillReadHighAndZeroVoltsAndEachIsReportedOnceAsUnbound() throws IOException {
        try (TestSessions.Handle handle = TestSessions.open(profile(), 0, 0, 0)) {
            SimulationSession session = handle.session;
            start(session, "Unbound Pins");
            session.runFor(SECOND);

            assertEquals("false", text(session, "touch pressed"), "an open input is high, which is a touch sensor that is not pressed");
            assertEquals("true", text(session, "digital state"));
            assertEquals(0.0, number(session, "analog volts"));

            List<String> problems = session.problems();
            assertEquals(
                    Set.of(
                            "'missingTouch' is not in the robot profile, so it was given a port of its own",
                            "'missingDigital' is not in the robot profile, so it was given a port of its own",
                            "'missingAnalog' is not in the robot profile, so it was given a port of its own",
                            "hub 173 (Control Hub) digital port 1 was read with nothing connected, so it read high",
                            "hub 173 (Control Hub) digital port 3 was read with nothing connected, so it read high",
                            "hub 173 (Control Hub) analog port 1 was read with nothing connected, so it read 0 V"),
                    Set.copyOf(problems),
                    problems.toString());
            assertEquals(6, problems.size(), "each is reported once however often the program reads it: " + problems);
        }
    }

    @Test
    void underBulkCachingTheHubCannotTellWhichPinWasReadSoOnlyTheNamesAreReported() throws IOException {
        try (TestSessions.Handle handle = TestSessions.open(profile(), 0, 0, 0)) {
            SimulationSession session = handle.session;
            start(session, "Unbound Bulk Pins");
            session.runFor(SECOND);

            assertEquals("false", text(session, "touch pressed"));
            assertEquals("true", text(session, "digital state"));
            assertEquals(0.0, number(session, "analog volts"));
            assertEquals(
                    Set.of(
                            "'missingTouch' is not in the robot profile, so it was given a port of its own",
                            "'missingDigital' is not in the robot profile, so it was given a port of its own",
                            "'missingAnalog' is not in the robot profile, so it was given a port of its own"),
                    Set.copyOf(session.problems()),
                    session.problems().toString());
        }
    }

    @Test
    void aNameCannotBeBothATouchSensorAndADigitalChannelAndTheSdksErrorSaysWhatItAlreadyIs() throws IOException {
        try (TestSessions.Handle handle = TestSessions.open(profile(), 0, 0, 0)) {
            SimulationSession session = handle.session;
            session.init("Wrong Pin Type");
            session.runFor(SECOND);

            String error = session.telemetry().error();
            assertTrue(
                    error.contains("Unable to find a hardware device with name \"slideLimit\" and type DigitalChannel: \"slideLimit\" already exists as RevTouchSensor"),
                    error);
        }
    }

    @Test
    void aPinSetToOutputDrivesItsLevelAndReadsItsSourceAgainOnceItIsSetToInput() throws IOException {
        try (TestSessions.Handle handle = TestSessions.open(profile(), 0, 0, 0)) {
            SimulationSession session = handle.session;
            LynxHub hub = session.rig().hub(EXPANSION_HUB);
            start(session, "Digital Modes");
            assertEquals("INPUT", text(session, "mode"));
            assertEquals("false", text(session, "state"), "as an input the pin reads its source, which holds it low");
            assertFalse(hub.digitalIsOutput(1));

            session.setGamepad(1, buttons(true, false, false, false));
            session.runFor(100 * MS);
            session.setGamepad(1, GamepadState.NEUTRAL);
            session.runFor(100 * MS);
            assertEquals("OUTPUT", text(session, "mode"));
            assertEquals("false", text(session, "state"), "a pin that has just become an output drives low");
            assertTrue(hub.digitalIsOutput(1), "the hub saw the direction change");
            assertFalse(hub.digitalOutputLevel(1));

            session.setGamepad(1, buttons(false, true, false, false));
            session.runFor(100 * MS);
            session.setGamepad(1, GamepadState.NEUTRAL);
            session.runFor(100 * MS);
            assertEquals("true", text(session, "state"));
            assertTrue(hub.digitalOutputLevel(1), "the hub drives the pin high, whatever is connected to it");

            session.setGamepad(1, buttons(false, false, false, true));
            session.runFor(100 * MS);
            session.setGamepad(1, GamepadState.NEUTRAL);
            session.runFor(100 * MS);
            assertEquals("false", text(session, "state"));
            assertFalse(hub.digitalOutputLevel(1));

            session.setGamepad(1, buttons(false, true, false, false));
            session.runFor(100 * MS);
            session.setGamepad(1, buttons(false, false, true, false));
            session.runFor(100 * MS);
            session.setGamepad(1, GamepadState.NEUTRAL);
            session.runFor(100 * MS);
            assertEquals("INPUT", text(session, "mode"));
            assertEquals("false", text(session, "state"), "back to an input the pin reads its source again, not what it last drove");
            assertFalse(hub.digitalIsOutput(1));
            assertEquals(List.of(), session.problems(), handle.log.toString());
        }
    }

    @Test
    void theSameRunGivesTheSameReadingsAndTheSameWorldWhateverTheSlicingOfSimulatedTime() throws IOException {
        List<String> firstLines = null;
        String firstHash = null;
        long firstCounts = 0;
        for (long slice : new long[] {Long.MAX_VALUE / 4, 7 * MS, 250 * MS}) {
            try (TestSessions.Handle handle = TestSessions.open(profile(), 0, 0, 0)) {
                SimulationSession session = handle.session;
                start(session, "Touch Limit");
                session.setGamepad(1, lift(0.6f));
                long end = session.now() + 1500 * MS;
                while (session.now() < end) {
                    session.runUntil(Math.min(end, session.now() + slice));
                }
                List<String> lines = session.telemetry().lines();
                String hash = session.world().stateHash();
                long counts = session.rig().plant("slide").encoderCounts();
                if (firstLines == null) {
                    firstLines = lines;
                    firstHash = hash;
                    firstCounts = counts;
                    assertTrue(number(session, "first pressed ns") > 0, "the switch closed during the run: " + lines);
                }
                assertEquals(firstLines, lines, "what the program shows, with slices of " + slice / MS + " ms");
                assertEquals(firstHash, hash, "the world, with slices of " + slice / MS + " ms");
                assertEquals(firstCounts, counts, "the shaft, with slices of " + slice / MS + " ms");
                assertEquals(List.of(), session.problems());
            }
        }
    }
}
