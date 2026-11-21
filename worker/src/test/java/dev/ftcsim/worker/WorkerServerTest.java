package dev.ftcsim.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.field.Alliance;
import dev.ftcsim.contracts.field.Score;
import dev.ftcsim.contracts.robot.GamepadState;
import dev.ftcsim.contracts.robot.RobotPhase;
import dev.ftcsim.contracts.robot.TelemetrySnapshot;
import dev.ftcsim.contracts.session.Readout;
import dev.ftcsim.contracts.session.SessionRequest;
import dev.ftcsim.contracts.session.SessionStatus;
import dev.ftcsim.link.wire.Command;
import dev.ftcsim.link.wire.Event;
import dev.ftcsim.link.wire.Protocol;
import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** The worker's command loop, with sessions that are only a list of what was done to them. */
@Timeout(60)
class WorkerServerTest {

    private static final String ROBOT = "robot.json";

    private static SessionRequest request(String program) {
        return new SessionRequest(program, "none", Alliance.RED, 1L, 0, 0, 0, 1.0);
    }

    private static Command.Open open(String program) {
        return new Command.Open(request(program));
    }

    private static String openCall(double speed) {
        return "open " + ROBOT + " speed " + speed;
    }

    @Test
    void openingAnnouncesTheSessionAndThenPublishesEachNewSnapshotAndStatusOnce() throws Exception {
        try (ServerFixture fixture = new ServerFixture()) {
            fixture.send(open(ROBOT));

            assertEquals(FakeSessions.INFO, fixture.next(Event.Opened.class).info());
            FakeSessions.FakeSession session = fixture.sessions.opened.get(0);
            session.snapshot = FakeSessions.snapshot(1);
            assertEquals(FakeSessions.snapshot(1), fixture.next(Event.Snapshot.class).snapshot());
            session.status = FakeSessions.status(10);
            assertEquals(FakeSessions.status(10), fixture.next(Event.Status.class).status());
            session.snapshot = FakeSessions.snapshot(2);
            assertEquals(
                    FakeSessions.snapshot(2), fixture.next(Event.Snapshot.class).snapshot(), "snapshot 1 is not sent again while it is the latest");
        }
    }

    @Test
    void theCatalogueIsTheFirstThingTheUiHearsAndItIsNotSentAgainWhenASessionOpens() throws Exception {
        try (ServerFixture fixture = new ServerFixture()) {
            assertEquals(FakeSessions.CATALOGUE, fixture.catalogue.catalogue());

            fixture.send(open(ROBOT));
            fixture.send(new Command.Reset());

            fixture.next(Event.Opened.class);
            fixture.next(Event.Opened.class);
        }
    }

    @Test
    void aMatchIsArmedAndStartedOnTheSessionInTheOrderItWasAskedFor() throws Exception {
        try (ServerFixture fixture = new ServerFixture()) {
            fixture.send(open(ROBOT));
            fixture.send(new Command.ArmMatch("Linear Auto", "Mecanum Drive"));
            fixture.send(new Command.StartMatch());
            fixture.send(new Command.Close());

            assertEquals(WorkerServer.EXIT_CLOSED, fixture.awaitExit());
            assertEquals(
                    List.of(
                            openCall(1.0), "gamepad 1 0.0 on 1", "gamepad 2 0.0 on 1", "arm 'Linear Auto' 'Mecanum Drive' on 1",
                            "start match on 1", "close 1"),
                    fixture.sessions.calls);
        }
    }

    @Test
    void whatTheSimulationRefusesIsRefusedInItsOwnWordsAndTheSessionGoesOnAsItWas() throws Exception {
        try (ServerFixture fixture = new ServerFixture()) {
            fixture.send(open(ROBOT));
            fixture.next(Event.Opened.class);
            Map<Command, String> reasons = new LinkedHashMap<>();
            reasons.put(new Command.Init("Mecanum Drive"), "Init is not available during a match. Press Stop to end the match first.");
            reasons.put(new Command.Start(), "Start needs an initialised OpMode, but the robot is IDLE");
            reasons.put(new Command.PerformHumanAction("feed"), "Human players only load in TELEOP.");
            reasons.put(new Command.ArmMatch("", "Mecanum Drive"), "An OpMode is running. Press Stop before arming a match.");
            reasons.put(new Command.StartMatch(), "No match is armed. Arm one first.");

            for (Map.Entry<Command, String> refused : reasons.entrySet()) {
                fixture.sessions.refusal = new IllegalStateException(refused.getValue());
                fixture.send(refused.getKey());
                assertEquals(refused.getValue(), fixture.next(Event.Refused.class).message(), refused.getKey().toString());
            }
            fixture.sessions.refusal = new IllegalArgumentException("The robot program declares no OpMode named 'x'.");
            fixture.send(new Command.ArmMatch("x", "Mecanum Drive"));
            assertEquals("The robot program declares no OpMode named 'x'.", fixture.next(Event.Refused.class).message());
            fixture.sessions.refusal = null;
            fixture.send(new Command.Init("Mecanum Drive"));
            fixture.send(new Command.Close());

            assertEquals(WorkerServer.EXIT_CLOSED, fixture.awaitExit());
            assertTrue(fixture.sessions.calls.contains("init Mecanum Drive on 1"), "the session went on being served");
        }
    }

    @Test
    void aGamepadThatDoesNotExistIsRefusedAndTheOnesThatDoAreStillServed() throws Exception {
        try (ServerFixture fixture = new ServerFixture()) {
            fixture.send(open(ROBOT));
            fixture.next(Event.Opened.class);

            fixture.send(new Command.SetGamepad(3, GamepadState.NEUTRAL));
            fixture.send(new Command.SetGamepad(0, GamepadState.NEUTRAL));
            String third = fixture.next(Event.Refused.class).message();
            String zeroth = fixture.next(Event.Refused.class).message();
            fixture.send(new Command.SetGamepad(2, GamepadState.sticks(0.25f, 0f, 0f, 0f)));
            fixture.send(new Command.Close());

            assertTrue(third.contains("no gamepad 3"), third);
            assertTrue(zeroth.contains("no gamepad 0"), zeroth);
            assertEquals(WorkerServer.EXIT_CLOSED, fixture.awaitExit());
            assertTrue(fixture.sessions.calls.contains("gamepad 2 0.25 on 1"), fixture.sessions.calls.toString());
            assertTrue(fixture.sessions.calls.stream().noneMatch(call -> call.startsWith("gamepad 3") || call.startsWith("gamepad 0")));
        }
    }

    @Test
    void aCommandThatFailsInAWayNobodyExpectedIsRefusedWithWhatHappenedAndTheWorkerGoesOn() throws Exception {
        try (ServerFixture fixture = new ServerFixture()) {
            fixture.send(open(ROBOT));
            fixture.next(Event.Opened.class);
            fixture.sessions.refusal = new UnsupportedOperationException("not today");

            fixture.send(new Command.Start());
            String message = fixture.next(Event.Refused.class).message();
            fixture.sessions.refusal = null;
            fixture.send(new Command.Start());
            fixture.send(new Command.Close());

            assertTrue(message.startsWith("The simulator worker could not carry out the command") && message.contains("not today"), message);
            assertEquals(WorkerServer.EXIT_CLOSED, fixture.awaitExit());
            assertFalse(fixture.diagnostics.isEmpty(), "the worker also says it in its own log");
            assertEquals(2, fixture.sessions.calls.stream().filter(call -> call.equals("start on 1")).count());
        }
    }

    @Test
    void commandsAreCarriedOutOnTheSessionInTheOrderTheyWereGiven() throws Exception {
        try (ServerFixture fixture = new ServerFixture()) {
            fixture.send(open(ROBOT));
            fixture.send(new Command.Init("Mecanum Drive"));
            fixture.send(new Command.Start());
            fixture.send(new Command.SetPaused(true));
            fixture.send(new Command.SetSpeed(3.0));
            fixture.send(new Command.SetGamepad(1, GamepadState.sticks(0.5f, 0f, 0f, 0f)));
            fixture.send(new Command.SetGamepad(2, GamepadState.NEUTRAL));
            fixture.send(new Command.PerformHumanAction("feed"));
            fixture.send(new Command.Stop());
            fixture.send(new Command.Close());

            assertEquals(WorkerServer.EXIT_CLOSED, fixture.awaitExit());
            assertEquals(
                    List.of(
                            openCall(1.0), "gamepad 1 0.0 on 1", "gamepad 2 0.0 on 1", "init Mecanum Drive on 1", "start on 1",
                            "paused true on 1", "speed 3.0 on 1", "gamepad 1 0.5 on 1", "gamepad 2 0.0 on 1", "human feed on 1",
                            "stop on 1", "close 1"),
                    fixture.sessions.calls);
        }
    }

    @Test
    void aGamepadThatWasSetBeforeASessionOpenedIsAppliedToItAsSoonAsItIsOpen() throws Exception {
        try (ServerFixture fixture = new ServerFixture()) {
            fixture.send(new Command.SetGamepad(1, GamepadState.sticks(0.5f, 0f, 0f, 0f)));
            fixture.send(new Command.SetPaused(true));
            fixture.send(open(ROBOT));
            fixture.send(new Command.Close());

            assertEquals(WorkerServer.EXIT_CLOSED, fixture.awaitExit());
            assertEquals(List.of(openCall(1.0), "gamepad 1 0.5 on 1", "gamepad 2 0.0 on 1", "close 1"), fixture.sessions.calls);
        }
    }

    @Test
    void openingAnotherSessionClosesTheFirstBeforeTheSecondOpens() throws Exception {
        try (ServerFixture fixture = new ServerFixture()) {
            fixture.send(open("a.json"));
            fixture.send(open("b.json"));
            fixture.send(new Command.Close());

            assertEquals(WorkerServer.EXIT_CLOSED, fixture.awaitExit());
            assertEquals(
                    List.of(
                            "open a.json speed 1.0", "gamepad 1 0.0 on 1", "gamepad 2 0.0 on 1", "close 1", "open b.json speed 1.0",
                            "gamepad 1 0.0 on 2", "gamepad 2 0.0 on 2", "close 2"),
                    fixture.sessions.calls);
            assertEquals(FakeSessions.INFO, fixture.next(Event.Opened.class).info());
            assertEquals(FakeSessions.INFO, fixture.next(Event.Opened.class).info());
        }
    }

    @Test
    void aSessionThatCannotBeOpenedIsAFailureTheUiCanReadAndTheWorkerGoesOnServing() throws Exception {
        try (ServerFixture fixture = new ServerFixture()) {
            fixture.sessions.failToOpen = new IOException("the robot program does not exist");
            fixture.send(open("bad"));

            assertEquals(
                    "Could not open the session: the robot program does not exist",
                    fixture.next(Event.Failed.class).message());

            fixture.sessions.failToOpen = null;
            fixture.send(open(ROBOT));
            assertEquals(FakeSessions.INFO, fixture.next(Event.Opened.class).info());
        }
    }

    @Test
    void resetPutsTheWorldBackInTheSameSessionAndTellsTheWindowWithoutOpeningAnother() throws Exception {
        try (ServerFixture fixture = new ServerFixture()) {
            fixture.send(open(ROBOT));
            fixture.send(new Command.SetSpeed(3.0));
            fixture.send(new Command.Reset());
            fixture.send(new Command.Close());

            assertEquals(WorkerServer.EXIT_CLOSED, fixture.awaitExit());
            assertEquals(
                    List.of(
                            openCall(1.0), "gamepad 1 0.0 on 1", "gamepad 2 0.0 on 1", "speed 3.0 on 1", "reset on 1", "close 1"),
                    fixture.sessions.calls);
            fixture.next(Event.Opened.class);
            fixture.next(Event.Opened.class);
        }
    }

    @Test
    void whatNeedsASessionIsRefusedAndNotACrashWhenThereIsNone() throws Exception {
        try (ServerFixture fixture = new ServerFixture()) {
            for (Command command : List.of(
                    new Command.Reset(), new Command.Init("Mecanum Drive"), new Command.Start(), new Command.Stop(),
                    new Command.PerformHumanAction("feed"), new Command.ArmMatch("", "Mecanum Drive"), new Command.StartMatch())) {
                fixture.send(command);
                String message = fixture.next(Event.Refused.class).message();
                assertTrue(message.contains("session"), command + ": " + message);
            }
            fixture.send(new Command.SetPaused(true));
            fixture.send(new Command.SetSpeed(2.0));
            fixture.send(new Command.Close());

            assertEquals(WorkerServer.EXIT_CLOSED, fixture.awaitExit());
            assertEquals(List.of(), fixture.sessions.calls);
        }
    }

    @Test
    void anOpModeOrAnActionThatTheSessionDoesNotKnowNeverReachesItAndTheRefusalSaysWhatThereIs() throws Exception {
        try (ServerFixture fixture = new ServerFixture()) {
            fixture.send(open(ROBOT));
            fixture.next(Event.Opened.class);
            fixture.send(new Command.Init("Nope"));
            fixture.send(new Command.PerformHumanAction("nope"));
            String opMode = fixture.next(Event.Refused.class).message();
            String action = fixture.next(Event.Refused.class).message();

            fixture.sessions.info = FakeSessions.INFO_WITHOUT_FIELD;
            fixture.send(open(ROBOT));
            fixture.next(Event.Opened.class);
            fixture.send(new Command.PerformHumanAction("feed"));
            String noField = fixture.next(Event.Refused.class).message();
            fixture.send(new Command.Close());

            assertTrue(opMode.contains("no OpMode 'Nope'") && opMode.contains("Mecanum Drive"), opMode);
            assertTrue(action.contains("no action 'nope'") && action.contains("feed"), action);
            assertTrue(noField.contains("no field"), noField);
            assertEquals(WorkerServer.EXIT_CLOSED, fixture.awaitExit());
            assertTrue(fixture.sessions.calls.stream().noneMatch(call -> call.startsWith("init") || call.startsWith("human")));
        }
    }

    @Test
    void whenTheConnectionEndsTheSessionIsClosedAndTheWorkerEnds() throws Exception {
        try (ServerFixture fixture = new ServerFixture()) {
            fixture.send(open(ROBOT));
            fixture.next(Event.Opened.class);

            fixture.closeUi();

            assertEquals(WorkerServer.EXIT_CONNECTION_LOST, fixture.awaitExit());
            assertEquals("close 1", fixture.sessions.calls.get(fixture.sessions.calls.size() - 1));
        }
    }

    @Test
    void aMessageThatIsNotACommandIsAnsweredWithAFailureAndEndsTheWorker() throws Exception {
        try (ServerFixture fixture = new ServerFixture()) {
            fixture.send(open(ROBOT));
            fixture.next(Event.Opened.class);

            fixture.sendRaw(new byte[] {0x7F, 1, 2});

            assertTrue(fixture.next(Event.Failed.class).message().contains("cannot read"));
            assertEquals(WorkerServer.EXIT_MALFORMED, fixture.awaitExit());
            assertEquals("close 1", fixture.sessions.calls.get(fixture.sessions.calls.size() - 1));
        }
    }

    @Test
    void aSimulationThatStopsIsReportedOnceAndItsLastStatusStillGoesOut() throws Exception {
        try (ServerFixture fixture = new ServerFixture()) {
            fixture.send(open(ROBOT));
            fixture.next(Event.Opened.class);
            FakeSessions.FakeSession session = fixture.sessions.opened.get(0);

            session.failure = new IllegalStateException("physics exploded");
            String message = fixture.next(Event.Failed.class).message();
            session.status = FakeSessions.status(5);

            assertTrue(message.contains("The simulation stopped") && message.contains("physics exploded"), message);
            assertEquals(FakeSessions.status(5), fixture.next(Event.Status.class).status(), "no second failure comes first");
        }
    }

    @Test
    void aSessionThatWillNotStopEndsTheWorkerAfterTheLimitAndTheUiIsToldWhy() throws Exception {
        try (ServerFixture fixture = new ServerFixture(Duration.ofSeconds(1))) {
            fixture.sessions.holdClose = new CountDownLatch(1);
            fixture.send(open(ROBOT));
            fixture.next(Event.Opened.class);

            fixture.send(open(ROBOT));

            String message = fixture.next(Event.Failed.class).message();
            assertTrue(message.contains("did not stop within 1 seconds") && message.contains("Start a new one"), message);
            assertEquals(WorkerServer.EXIT_STUCK, fixture.awaitExit());
        }
    }

    @Test
    void aStatusThatCannotBeWrittenIsReplacedByAFailureThatSaysSoAndTheNextOneGoesThrough() throws Exception {
        try (ServerFixture fixture = new ServerFixture()) {
            fixture.send(open(ROBOT));
            fixture.next(Event.Opened.class);
            FakeSessions.FakeSession session = fixture.sessions.opened.get(0);
            List<Readout> tooMany = Collections.nCopies(Protocol.MAX_LIST + 1, new Readout("m", "v"));

            session.status = new SessionStatus(
                    1, RobotPhase.IDLE, new TelemetrySnapshot(List.of(), "", ""), new Score(List.of()), List.of(), tooMany, 1.0, 1.0, false);
            String message = fixture.next(Event.Failed.class).message();
            session.status = FakeSessions.status(2);

            assertTrue(message.contains("could not send a Status"), message);
            assertEquals(FakeSessions.status(2), fixture.next(Event.Status.class).status());
            assertFalse(fixture.diagnostics.isEmpty(), "the worker also says it in its own log");
        }
    }
}
