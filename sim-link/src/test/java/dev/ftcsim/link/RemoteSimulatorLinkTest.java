package dev.ftcsim.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.robot.GamepadState;
import dev.ftcsim.contracts.session.Catalogue;
import dev.ftcsim.contracts.session.SessionInfo;
import dev.ftcsim.contracts.session.SessionStatus;
import dev.ftcsim.contracts.session.SimulatorLink;
import dev.ftcsim.contracts.snapshot.WorldSnapshot;
import dev.ftcsim.link.wire.Command;
import dev.ftcsim.link.wire.Event;
import dev.ftcsim.link.wire.Frames;
import dev.ftcsim.link.wire.Handshake;
import dev.ftcsim.link.wire.Protocol;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** The link against a worker that is the test itself, over a real loopback socket. */
@Timeout(60)
class RemoteSimulatorLinkTest {

    @Test
    void commandsReachTheWorkerInTheOrderTheyWereGiven() throws Exception {
        try (LinkFixture fixture = LinkFixture.open()) {
            fixture.link.open(Messages.request("hornet-2026.json"));
            fixture.link.init("Mecanum Drive");
            fixture.link.start();
            fixture.link.setPaused(true);
            fixture.link.setSpeed(0.5);
            fixture.link.humanAction("feed");
            fixture.link.stop();
            fixture.link.reset();
            fixture.link.armMatch("Linear Auto", "Counting");
            fixture.link.armMatch("", "Counting");
            fixture.link.startMatch();

            assertEquals(new Command.Open(Messages.request("hornet-2026.json")), fixture.nextCommand());
            assertEquals(new Command.Init("Mecanum Drive"), fixture.nextCommand());
            assertEquals(new Command.Start(), fixture.nextCommand());
            assertEquals(new Command.SetPaused(true), fixture.nextCommand());
            assertEquals(new Command.SetSpeed(0.5), fixture.nextCommand());
            assertEquals(new Command.PerformHumanAction("feed"), fixture.nextCommand());
            assertEquals(new Command.Stop(), fixture.nextCommand());
            assertEquals(new Command.Reset(), fixture.nextCommand());
            assertEquals(new Command.ArmMatch("Linear Auto", "Counting"), fixture.nextCommand());
            assertEquals(new Command.ArmMatch("", "Counting"), fixture.nextCommand());
            assertEquals(new Command.StartMatch(), fixture.nextCommand());
        }
    }

    @Test
    void aRefusalIsToldOnTheLinksThreadInOrderWithTheRestAndDoesNotEndTheLink() throws Exception {
        try (LinkFixture fixture = LinkFixture.open()) {
            fixture.send(new Event.Opened(Messages.info("r")));
            fixture.send(new Event.Refused("An OpMode is running. Press Stop before arming a match."));
            fixture.send(new Event.Failed("the simulation stopped"));

            assertEquals("r", fixture.events.next(SessionInfo.class).robot().name());
            assertEquals(
                    new RecordingListener.Refusal("An OpMode is running. Press Stop before arming a match."),
                    fixture.events.next(RecordingListener.Refusal.class));
            assertTrue(fixture.link.isUsable(), "a refused command does not end the link");
            assertEquals("the simulation stopped", fixture.events.next(String.class), "and what is not a refusal is still a failure");
            assertEquals(Set.of("ftcsim-link-dispatcher"), fixture.events.threadNames);
            fixture.link.init("Counting");
            assertEquals(new Command.Init("Counting"), fixture.nextCommand(), "the link still carries commands");
        }
    }

    @Test
    void aListenerThatIgnoresRefusalsStillHearsWhatComesAfterThem() throws Exception {
        try (LinkFixture fixture = LinkFixture.open()) {
            fixture.link.addListener(new SimulatorLink.Listener() {
                @Override
                public void opened(SessionInfo info) {}

                @Override
                public void snapshot(WorldSnapshot snapshot) {}

                @Override
                public void status(SessionStatus status) {}

                @Override
                public void failed(String message) {}

                @Override
                public void refused(String message) {}

                @Override
                public void catalogue(Catalogue catalogue) {}
            });

            fixture.send(new Event.Refused("not now"));
            fixture.send(new Event.Opened(Messages.info("after")));

            assertEquals(new RecordingListener.Refusal("not now"), fixture.events.next(RecordingListener.Refusal.class));
            assertEquals("after", fixture.events.next(SessionInfo.class).robot().name());
        }
    }

    @Test
    void logLinesAreToldInOrderBetweenTheOtherEventsAndNeverDropped() throws Exception {
        try (LinkFixture fixture = LinkFixture.open()) {
            fixture.send(new Event.Log(java.util.List.of("I/Robot: one", "I/Robot: two")));
            fixture.send(new Event.Log(java.util.List.of("W/Robot: three")));
            fixture.send(new Event.Refused("not now"));

            assertEquals(java.util.List.of("I/Robot: one", "I/Robot: two"), fixture.events.next(java.util.List.class));
            assertEquals(java.util.List.of("W/Robot: three"), fixture.events.next(java.util.List.class));
            assertEquals(new RecordingListener.Refusal("not now"), fixture.events.next(RecordingListener.Refusal.class));
        }
    }

    @Test
    void aCatalogueIsToldToTheListenersThereAtTheTimeAndToThoseThatComeLateAndNeverTwice() throws Exception {
        try (LinkFixture fixture = LinkFixture.open()) {
            fixture.send(new Event.Catalogue(Messages.catalogue("one")));
            assertEquals(Messages.catalogue("one"), fixture.events.next(Catalogue.class));

            RecordingListener late = new RecordingListener();
            fixture.link.addListener(late);
            assertEquals(Messages.catalogue("one"), late.next(Catalogue.class), "a late listener is told what it missed");
            fixture.send(new Event.Catalogue(Messages.catalogue("two")));
            fixture.send(new Event.Failed("done"));

            assertEquals(Messages.catalogue("two"), late.next(Catalogue.class));
            assertEquals(Messages.catalogue("two"), fixture.events.next(Catalogue.class));
            assertEquals("done", late.next(String.class), "nothing was told twice");
            assertEquals("done", fixture.events.next(String.class));
            assertEquals(Set.of("ftcsim-link-dispatcher"), late.threadNames, "told from the link's own thread, not the caller's");
        }
    }

    @Test
    void aBurstOfGamepadStatesNeverReordersOrLosesTheLastAndNeverOvertakesALaterCommand() throws Exception {
        try (LinkFixture fixture = LinkFixture.open()) {
            fixture.link.init("A");
            for (int i = 1; i <= 2000; i++) {
                fixture.link.setGamepad(1, GamepadState.sticks(i, 0f, 0f, 0f));
                if (i % 2 == 0) {
                    fixture.link.setGamepad(2, GamepadState.sticks(0f, i, 0f, 0f));
                }
            }
            fixture.link.start();

            assertEquals(new Command.Init("A"), fixture.nextCommand());
            float lastFirst = 0;
            float lastSecond = 0;
            int frames = 0;
            for (Command command = fixture.nextCommand(); !(command instanceof Command.Start); command = fixture.nextCommand()) {
                Command.SetGamepad pad = (Command.SetGamepad) command;
                frames++;
                if (pad.gamepad() == 1) {
                    assertTrue(pad.state().leftStickX() > lastFirst, "gamepad 1 went back in time");
                    lastFirst = pad.state().leftStickX();
                } else {
                    assertTrue(pad.state().leftStickY() > lastSecond, "gamepad 2 went back in time");
                    lastSecond = pad.state().leftStickY();
                }
            }
            assertEquals(2000f, lastFirst, "the last state of gamepad 1 is never the one that is dropped");
            assertEquals(2000f, lastSecond);
            assertTrue(frames <= 3000, "no more frames than there were calls: " + frames);
        }
    }

    @Test
    void whenTheWorkerFallsBehindOnlyTheLatestStateOfEachGamepadIsLeftToSendAndItKeepsItsPlaceAmongTheCommands() throws Exception {
        String bulky = "x".repeat(Protocol.MAX_STRING_BYTES - 16);
        int bulkyCommands = 100;
        try (LinkFixture fixture = LinkFixture.open()) {
            // The worker reads nothing for now, so the writer is stuck on these for as long as it takes to give the rest.
            for (int i = 0; i < bulkyCommands; i++) {
                fixture.link.init(bulky);
            }
            for (int i = 1; i <= 1000; i++) {
                fixture.link.setGamepad(1, GamepadState.sticks(i, 0f, 0f, 0f));
                fixture.link.setGamepad(2, GamepadState.sticks(0f, i, 0f, 0f));
            }
            fixture.link.start();

            for (int i = 0; i < bulkyCommands; i++) {
                assertEquals(new Command.Init(bulky), fixture.nextCommand());
            }
            List<Command> restOfIt = new ArrayList<>();
            for (Command command = fixture.nextCommand(); !(command instanceof Command.Start); command = fixture.nextCommand()) {
                restOfIt.add(command);
            }
            assertEquals(
                    List.of(
                            new Command.SetGamepad(1, GamepadState.sticks(1000f, 0f, 0f, 0f)),
                            new Command.SetGamepad(2, GamepadState.sticks(0f, 1000f, 0f, 0f))),
                    restOfIt,
                    "2000 states were given while the writer was stuck; the latest of each is what is sent, ahead of Start");
        }
    }

    @Test
    void aListenerThatIsBusyIsHandedTheLatestSnapshotAndNotEveryOneItMissed() throws Exception {
        try (LinkFixture fixture = LinkFixture.open()) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            fixture.events.beforeEachCall = () -> {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            };
            fixture.send(new Event.Snapshot(Messages.snapshot(1)));
            assertTrue(entered.await(10, TimeUnit.SECONDS), "the listener is inside its first call");

            for (int step = 2; step <= 300; step++) {
                fixture.send(new Event.Snapshot(Messages.snapshot(step)));
            }
            fixture.send(new Event.Status(Messages.status(777)));
            waitUntil(() -> fixture.link.framesReceived() == 301, "the link to have read all 301 frames while the listener was busy");
            release.countDown();

            assertEquals(1, fixture.events.next(WorldSnapshot.class).stepIndex());
            assertEquals(300, fixture.events.next(WorldSnapshot.class).stepIndex(), "299 snapshots in between are never delivered");
            assertEquals(777, fixture.events.next(SessionStatus.class).simTimeNanos(), "the status keeps its place after the snapshots");
        }
    }

    @Test
    void whatWasWaitingFromBeforeANewSessionOpenedIsNeverDeliveredAfterIt() throws Exception {
        try (LinkFixture fixture = LinkFixture.open()) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            fixture.events.beforeEachCall = () -> {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            };
            fixture.send(new Event.Snapshot(Messages.snapshot(1)));
            assertTrue(entered.await(10, TimeUnit.SECONDS));

            fixture.send(new Event.Snapshot(Messages.snapshot(2)));
            fixture.send(new Event.Opened(Messages.info("second session")));
            fixture.send(new Event.Snapshot(Messages.snapshot(3)));
            waitUntil(() -> fixture.link.framesReceived() == 4, "the link to have read the four frames");
            release.countDown();

            assertEquals(1, fixture.events.next(WorldSnapshot.class).stepIndex());
            assertEquals("second session", fixture.events.next(SessionInfo.class).robot().name());
            assertEquals(3, fixture.events.next(WorldSnapshot.class).stepIndex());
            assertNull(fixture.events.received.poll(200, TimeUnit.MILLISECONDS), "snapshot 2 belonged to the first session");
        }
    }

    @Test
    void listenersAreCalledOneAtATimeAlwaysFromTheSameThread() throws Exception {
        try (LinkFixture fixture = LinkFixture.open()) {
            RecordingListener second = new RecordingListener();
            fixture.link.addListener(second);
            AtomicInteger inside = new AtomicInteger();
            AtomicInteger mostInside = new AtomicInteger();
            Runnable dawdle = () -> {
                mostInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
                pause(1);
                inside.decrementAndGet();
            };
            fixture.events.beforeEachCall = dawdle;
            second.beforeEachCall = dawdle;

            fixture.send(new Event.Opened(Messages.info("r")));
            for (int step = 1; step <= 60; step++) {
                fixture.send(new Event.Snapshot(Messages.snapshot(step)));
                fixture.send(new Event.Status(Messages.status(step)));
                if (step % 20 == 0) {
                    pause(15);
                }
            }
            fixture.send(new Event.Failed("the session ended"));

            fixture.events.next(SessionInfo.class);
            Object last = null;
            while (!"the session ended".equals(last)) {
                last = fixture.events.received.poll(10, TimeUnit.SECONDS);
                assertTrue(last != null, "the failure never arrived");
            }
            Object lastOfSecond = null;
            while (!"the session ended".equals(lastOfSecond)) {
                lastOfSecond = second.received.poll(10, TimeUnit.SECONDS);
                assertTrue(lastOfSecond != null, "the failure never arrived at the second listener");
            }

            assertEquals(1, mostInside.get(), "two calls were running at the same time");
            assertEquals(Set.of("ftcsim-link-dispatcher"), fixture.events.threadNames);
            assertEquals(Set.of("ftcsim-link-dispatcher"), second.threadNames);
        }
    }

    @Test
    void aListenerThatThrowsDoesNotStopTheOthersOrTheLink() throws Exception {
        try (LinkFixture fixture = LinkFixture.open()) {
            fixture.link.addListener(new SimulatorLink.Listener() {
                @Override
                public void opened(SessionInfo info) {
                    throw new IllegalStateException("a bug in the first listener");
                }

                @Override
                public void snapshot(WorldSnapshot snapshot) {}

                @Override
                public void status(SessionStatus status) {}

                @Override
                public void failed(String message) {}

                @Override
                public void refused(String message) {}

                @Override
                public void catalogue(Catalogue catalogue) {}
            });

            fixture.send(new Event.Opened(Messages.info("first")));
            fixture.send(new Event.Opened(Messages.info("second")));

            assertEquals("first", fixture.events.next(SessionInfo.class).robot().name());
            assertEquals("second", fixture.events.next(SessionInfo.class).robot().name());
        }
    }

    @Test
    void aDroppedConnectionIsReportedOnceWithWhatTheWorkerLastWroteAndTheLinkIsDeadAfterwards() throws Exception {
        FakeProcess process = new FakeProcess();
        try (LinkFixture fixture = LinkFixture.open(process)) {
            assertTrue(fixture.link.isUsable());
            fixture.dropConnection();

            String message = fixture.events.next(String.class);

            assertTrue(message.contains("closed the connection"), message);
            assertTrue(message.contains("exit code 77") && message.contains("last words: out of memory"), message);
            assertFalse(fixture.link.isUsable(), "by the time a listener hears of it the link is known to be dead");
            fixture.link.open(Messages.request("x"));
            fixture.link.setGamepad(1, GamepadState.NEUTRAL);
            fixture.link.close();
            assertNull(fixture.events.received.poll(300, TimeUnit.MILLISECONDS), "failed is reported once");
            assertTrue(process.released.get(), "the process was let go");
        }
    }

    @Test
    void aWorkerThatSaysSomethingTheLinkCannotReadFailsTheLinkInsteadOfBeingSkipped() throws Exception {
        try (LinkFixture fixture = LinkFixture.open()) {
            fixture.sendRaw(new byte[] {0x7F, 1, 2, 3});

            String message = fixture.events.next(String.class);

            assertTrue(message.contains("no event of that kind"), message);
        }
    }

    @Test
    void closingAsksTheWorkerToEndWithoutReportingAFailureAndCanBeRepeated() throws Exception {
        try (LinkFixture fixture = LinkFixture.open()) {
            fixture.link.setGamepad(2, GamepadState.NEUTRAL);
            fixture.link.close();

            Command command;
            do {
                command = fixture.nextCommand();
            } while (command instanceof Command.SetGamepad);
            assertEquals(new Command.Close(), command);
            assertNull(fixture.nextCommand(), "the link then closes its end");
            fixture.link.close();
            assertNull(fixture.events.received.poll(300, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    void closingFromAListenerDoesNotDeadlock() throws Exception {
        try (LinkFixture fixture = LinkFixture.open()) {
            CountDownLatch closed = new CountDownLatch(1);
            fixture.events.beforeEachCall = () -> {
                fixture.link.close();
                closed.countDown();
            };

            fixture.send(new Event.Opened(Messages.info("r")));

            assertTrue(closed.await(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void callsWithValuesThatCanNeverBeMeantAreRefusedAtOnceInTheCallersThread() throws Exception {
        try (LinkFixture fixture = LinkFixture.open()) {
            assertThrows(IllegalArgumentException.class, () -> fixture.link.setGamepad(3, GamepadState.NEUTRAL));
            assertThrows(NullPointerException.class, () -> fixture.link.setGamepad(1, null));
            assertThrows(IllegalArgumentException.class, () -> fixture.link.setSpeed(0));
            assertThrows(IllegalArgumentException.class, () -> fixture.link.setSpeed(Double.NaN));
            assertThrows(NullPointerException.class, () -> fixture.link.init(null));
            assertThrows(NullPointerException.class, () -> fixture.link.open(null));
            assertThrows(NullPointerException.class, () -> fixture.link.armMatch(null, "Counting"));
            assertThrows(NullPointerException.class, () -> fixture.link.armMatch("", null));
        }
    }

    // The handshake from the launcher's side ------------------------------------------------------------------------

    @Test
    void aStrangerWithoutTheTokenIsDroppedAndTheRealWorkerIsStillWelcome() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 4, LinkFixture.LOOPBACK)) {
            String token = Handshake.newToken();
            List<RemoteSimulatorLink> accepted = new ArrayList<>();
            Thread acceptor = new Thread(() -> {
                try {
                    accepted.add(RemoteSimulatorLink.accept(server, token, Duration.ofSeconds(20), null, LinkFixture.GRACE));
                } catch (IOException failure) {
                    throw new IllegalStateException(failure);
                }
            });
            acceptor.start();

            try (Socket stranger = new Socket(LinkFixture.LOOPBACK, server.getLocalPort());
                    Socket garbage = new Socket(LinkFixture.LOOPBACK, server.getLocalPort());
                    Socket worker = new Socket(LinkFixture.LOOPBACK, server.getLocalPort())) {
                Frames.write(stranger.getOutputStream(), Handshake.hello("not the token of this launch!!!!", 1));
                stranger.getOutputStream().flush();
                garbage.getOutputStream().write("GET / HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                garbage.getOutputStream().flush();
                Frames.write(worker.getOutputStream(), Handshake.hello(token, 2));
                worker.getOutputStream().flush();
                worker.setSoTimeout(10_000);

                Handshake.readWelcome(Frames.read(worker.getInputStream()));
                acceptor.join(10_000);

                assertEquals(1, accepted.size());
                stranger.setSoTimeout(5_000);
                assertEquals(-1, stranger.getInputStream().read(), "the stranger got no answer and was disconnected");
                accepted.get(0).close();
            }
        }
    }

    @Test
    void aWorkerOfAnotherProtocolVersionIsToldWhyAndTheLaunchFails() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 4, LinkFixture.LOOPBACK)) {
            String token = Handshake.newToken();
            Socket worker = new Socket(LinkFixture.LOOPBACK, server.getLocalPort());
            worker.setSoTimeout(10_000);
            byte[] hello = Handshake.hello(token, 5);
            hello[5] = (byte) ((Protocol.VERSION + 1) >> 8);
            hello[6] = (byte) (Protocol.VERSION + 1);
            Frames.write(worker.getOutputStream(), hello);
            worker.getOutputStream().flush();

            IOException failure = assertThrows(
                    IOException.class, () -> RemoteSimulatorLink.accept(server, token, Duration.ofSeconds(20), null, LinkFixture.GRACE));
            IOException told = assertThrows(IOException.class, () -> Handshake.readWelcome(Frames.read(worker.getInputStream())));

            assertTrue(failure.getMessage().contains("protocol version"), failure.getMessage());
            assertTrue(told.getMessage().contains("refused"), told.getMessage());
            worker.close();
        }
    }

    @Test
    void aWorkerThatNeverComesIsAnErrorAfterTheTimeout() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 4, LinkFixture.LOOPBACK)) {
            IOException failure = assertThrows(
                    IOException.class, () -> RemoteSimulatorLink.accept(server, "t", Duration.ofMillis(400), null, LinkFixture.GRACE));

            assertTrue(failure.getMessage().contains("did not connect"), failure.getMessage());
        }
    }

    @Test
    void aWorkerThatEndedBeforeItConnectedIsAnErrorThatSaysHowItEnded() throws Exception {
        FakeProcess dead = new FakeProcess();
        dead.alive = false;
        try (ServerSocket server = new ServerSocket(0, 4, LinkFixture.LOOPBACK)) {
            IOException failure =
                    assertThrows(IOException.class, () -> RemoteSimulatorLink.accept(server, "t", Duration.ofSeconds(20), dead, LinkFixture.GRACE));

            assertTrue(failure.getMessage().contains("ended before it connected"), failure.getMessage());
            assertTrue(failure.getMessage().contains("exit code 77"), failure.getMessage());
        }
    }

    private static void waitUntil(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("gave up waiting for " + what);
            }
            Thread.sleep(2);
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** A worker process that is not a process: it can be dead or alive, and it has last words. */
    private static final class FakeProcess implements WorkerHandle {
        volatile boolean alive = true;
        final AtomicBoolean released = new AtomicBoolean();

        @Override
        public long pid() {
            return 1;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public OptionalInt exitCode() {
            return alive ? OptionalInt.empty() : OptionalInt.of(77);
        }

        @Override
        public boolean awaitExit(Duration timeout) {
            return true;
        }

        @Override
        public void destroyForcibly() {
            alive = false;
        }

        @Override
        public String describeEnd(String whatHappened) {
            alive = false;
            return whatHappened + " (exit code 77). last words: out of memory";
        }

        @Override
        public Optional<Path> logFile() {
            return Optional.empty();
        }

        @Override
        public void release() {
            released.set(true);
        }
    }
}
