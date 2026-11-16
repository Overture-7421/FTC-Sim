package dev.ftcsim.acceptance;

import static dev.ftcsim.acceptance.SessionTelemetry.number;
import static dev.ftcsim.acceptance.SessionTelemetry.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.hardware.UnmappedDevice;
import dev.ftcsim.simulator.SimulationSession;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The digital and analog pins and the servos are not part of a robot mapping: a program that reads a pin through the SDK's
 * own {@code DigitalChannel}, {@code TouchSensor} and {@code AnalogInput} classes gets one that is idle, and the session
 * says that it was given a port of its own.
 */
@Timeout(300)
class PinsSessionTest {
    private static final long MS = 1_000_000L;
    private static final long SECOND = 1_000 * MS;

    private static void start(SimulationSession session, String opMode) {
        session.init(opMode);
        session.runFor(500 * MS);
        session.start();
        session.runFor(200 * MS);
    }

    @Test
    void pinsNobodyConnectedAnythingToReadHighAndZeroVoltsAndEachIsReportedOnceAsUnmappedAndUnbound() throws IOException {
        try (TestSessions.Handle handle = TestSessions.open(HornetMapping.spec(), 0, 0, 0)) {
            SimulationSession session = handle.session;
            start(session, "Unbound Pins");
            session.runFor(SECOND);

            assertEquals("false", text(session, "touch pressed"), "an open input is high, which is a touch sensor that is not pressed");
            assertEquals("true", text(session, "digital state"));
            assertEquals(0.0, number(session, "analog volts"));

            List<String> problems = session.problems();
            assertEquals(
                    Set.of(
                            "'missingTouch' is not in the robot mapping, so it was given a port of its own",
                            "'missingDigital' is not in the robot mapping, so it was given a port of its own",
                            "'missingAnalog' is not in the robot mapping, so it was given a port of its own",
                            "hub 173 (Control Hub) digital port 0 was read with nothing connected, so it read high",
                            "hub 173 (Control Hub) digital port 1 was read with nothing connected, so it read high",
                            "hub 173 (Control Hub) analog port 0 was read with nothing connected, so it read 0 V"),
                    Set.copyOf(problems),
                    problems.toString());
            assertEquals(6, problems.size(), "each is reported once however often the program reads it: " + problems);
            assertEquals(
                    List.of(
                            new UnmappedDevice(UnmappedDevice.Kind.DIGITAL, "missingTouch"),
                            new UnmappedDevice(UnmappedDevice.Kind.DIGITAL, "missingDigital"),
                            new UnmappedDevice(UnmappedDevice.Kind.ANALOG, "missingAnalog")),
                    session.unmapped().stream().filter(device -> device.kind() != UnmappedDevice.Kind.MOTOR).toList());
        }
    }

    @Test
    void underBulkCachingTheHubCannotTellWhichPinWasReadSoOnlyTheNamesAreReported() throws IOException {
        try (TestSessions.Handle handle = TestSessions.open(HornetMapping.spec(), 0, 0, 0)) {
            SimulationSession session = handle.session;
            start(session, "Unbound Bulk Pins");
            session.runFor(SECOND);

            assertEquals("false", text(session, "touch pressed"));
            assertEquals("true", text(session, "digital state"));
            assertEquals(0.0, number(session, "analog volts"));
            assertEquals(
                    Set.of(
                            "'missingTouch' is not in the robot mapping, so it was given a port of its own",
                            "'missingDigital' is not in the robot mapping, so it was given a port of its own",
                            "'missingAnalog' is not in the robot mapping, so it was given a port of its own"),
                    Set.copyOf(session.problems()),
                    session.problems().toString());
        }
    }

    @Test
    void aNameCannotBeBothATouchSensorAndADigitalChannelAndTheSdksErrorSaysWhatItAlreadyIs() throws IOException {
        try (TestSessions.Handle handle = TestSessions.open(HornetMapping.spec(), 0, 0, 0)) {
            SimulationSession session = handle.session;
            session.init("Wrong Pin Type");
            session.runFor(SECOND);

            String error = session.telemetry().error();
            assertTrue(
                    error.contains("Unable to find a hardware device with name \"slideLimit\" and type DigitalChannel: \"slideLimit\" already exists as RevTouchSensor"),
                    error);
        }
    }
}
