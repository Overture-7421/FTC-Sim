package dev.ftcsim.ui.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.field.StartPose;
import dev.ftcsim.contracts.robot.GamepadState;
import dev.ftcsim.contracts.robot.RobotPhase;
import dev.ftcsim.contracts.session.Catalogue;
import dev.ftcsim.contracts.session.FieldChoice;
import dev.ftcsim.contracts.session.MatchState;
import dev.ftcsim.contracts.session.SessionInfo;
import dev.ftcsim.contracts.session.SessionStatus;
import dev.ftcsim.contracts.session.SimulatorLink;
import dev.ftcsim.contracts.snapshot.BodyState;
import dev.ftcsim.contracts.snapshot.WorldSnapshot;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Talks to the fake link through the port only. It runs on its own thread against the wall clock, so these tests wait
 * for events with a generous timeout and never for a duration, and run the link at the top speed to keep short.
 */
class FakeSimulatorLinkTest {
    private static final long TIMEOUT_SECONDS = 20;

    private record Opened(SessionInfo info, String thread) {}

    private record Failed(String message) {}

    private record Refused(String message) {}

    private record Catalogued(Catalogue catalogue, String thread) {}

    private static final class Recorder implements SimulatorLink.Listener {
        final BlockingQueue<Object> events = new LinkedBlockingQueue<>();
        final AtomicInteger openCalls = new AtomicInteger();
        final AtomicInteger failures = new AtomicInteger();

        @Override
        public void opened(SessionInfo info) {
            openCalls.incrementAndGet();
            events.add(new Opened(info, Thread.currentThread().getName()));
        }

        @Override
        public void snapshot(WorldSnapshot snapshot) {
            events.add(snapshot);
        }

        @Override
        public void status(SessionStatus status) {
            events.add(status);
        }

        @Override
        public void failed(String message) {
            failures.incrementAndGet();
            events.add(new Failed(message));
        }

        @Override
        public void refused(String message) {
            events.add(new Refused(message));
        }

        @Override
        public void catalogue(Catalogue catalogue) {
            events.add(new Catalogued(catalogue, Thread.currentThread().getName()));
        }

        /** Takes events until one of the given type satisfies the test, and returns it. */
        <T> T await(Class<T> type, Predicate<T> test) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
            while (true) {
                Object event = events.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (event == null) {
                    throw new AssertionError("timed out waiting for a " + type.getSimpleName());
                }
                if (type.isInstance(event) && test.test(type.cast(event))) {
                    return type.cast(event);
                }
            }
        }

        <T> T await(Class<T> type) throws InterruptedException {
            return await(type, event -> true);
        }
    }

    private final Recorder recorder = new Recorder();
    private FakeSimulatorLink link;

    private FakeSimulatorLink open(long delayMillis, String failure) {
        link = new FakeSimulatorLink(delayMillis, failure);
        link.addListener(recorder);
        link.setSpeed(4.0);
        link.open(FakeSimulatorLink.defaultRequest());
        return link;
    }

    private FakeSimulatorLink open() {
        return open(0, null);
    }

    @AfterEach
    void close() {
        if (link != null) {
            link.close();
        }
    }

    private static BodyState chassis(WorldSnapshot snapshot) {
        return snapshot.bodies().get(0);
    }

    @Test
    void openingTellsTheListenerWhatTheSessionOffersThenSendsSnapshotsAndStatus() throws Exception {
        open();

        Opened opened = recorder.await(Opened.class);
        assertEquals("fake-simulator-link", opened.thread(), "events come from the link's own thread");
        assertEquals("decode", opened.info().field().id());
        assertEquals(4, opened.info().opModes().size());
        assertEquals("Fake robot", opened.info().robot().name());
        recorder.await(WorldSnapshot.class);
        SessionStatus status = recorder.await(SessionStatus.class);
        assertEquals(RobotPhase.IDLE, status.phase());
        assertEquals(4.0, status.speed());
    }

    @Test
    void aSlowOpenDoesNotBlockTheCallerAndAFailingOneIsReported() throws Exception {
        open(1000, null);
        assertEquals(0, recorder.openCalls.get(), "the call returned before the session was open");
        recorder.await(Opened.class);
        link.close();

        Recorder failing = new Recorder();
        FakeSimulatorLink broken = new FakeSimulatorLink(0, "the worker did not start");
        broken.addListener(failing);
        broken.open(FakeSimulatorLink.defaultRequest());
        try {
            assertEquals("the worker did not start", failing.await(Failed.class).message());
            assertEquals(0, failing.openCalls.get());
        } finally {
            broken.close();
        }
    }

    @Test
    void listenerCallsNeverOverlap() throws Exception {
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger overlaps = new AtomicInteger();
        SimulatorLink.Listener busy = new SimulatorLink.Listener() {
            private void work() {
                if (inside.incrementAndGet() != 1) {
                    overlaps.incrementAndGet();
                }
                Thread.onSpinWait();
                inside.decrementAndGet();
            }

            @Override
            public void opened(SessionInfo info) {
                work();
            }

            @Override
            public void snapshot(WorldSnapshot snapshot) {
                work();
            }

            @Override
            public void status(SessionStatus status) {
                work();
            }

            @Override
            public void failed(String message) {
                work();
            }

            @Override
            public void refused(String message) {
                work();
            }

            @Override
            public void catalogue(Catalogue catalogue) {
                work();
            }
        };
        open();
        link.addListener(busy);

        for (int i = 0; i < 200; i++) {
            recorder.await(WorldSnapshot.class);
        }

        assertEquals(0, overlaps.get());
    }

    @Test
    void theDriverStationCommandsMoveThePhase() throws Exception {
        open();
        recorder.await(Opened.class);

        link.init("Mecanum TeleOp");
        recorder.await(SessionStatus.class, status -> status.phase() == RobotPhase.INIT);
        link.start();
        recorder.await(SessionStatus.class, status -> status.phase() == RobotPhase.RUNNING);
        link.stop();
        recorder.await(SessionStatus.class, status -> status.phase() == RobotPhase.IDLE);
    }

    @Test
    void gamepadOneDrivesTheChassisAndGamepadTwoIsAccepted() throws Exception {
        open();
        recorder.await(Opened.class);
        double startY = chassis(recorder.await(WorldSnapshot.class)).y();
        link.init("Mecanum TeleOp");
        link.start();
        link.setGamepad(2, GamepadState.NEUTRAL);

        link.setGamepad(1, GamepadState.sticks(0f, -1f, 0f, 0f));

        WorldSnapshot moved = recorder.await(WorldSnapshot.class, snapshot -> chassis(snapshot).y() > startY + 0.3);
        assertTrue(chassis(moved).y() > startY);
    }

    @Test
    void pausingStopsSimulatedTimeAndResumingStartsItAgain() throws Exception {
        open();
        recorder.await(Opened.class);

        link.setPaused(true);
        SessionStatus paused = recorder.await(SessionStatus.class, SessionStatus::paused);
        for (int i = 0; i < 5; i++) {
            assertEquals(paused.simTimeNanos(), recorder.await(SessionStatus.class).simTimeNanos());
        }

        link.setPaused(false);
        recorder.await(SessionStatus.class, status -> !status.paused() && status.simTimeNanos() > paused.simTimeNanos());
    }

    @Test
    void resetOpensTheSessionAgainFromTheBeginning() throws Exception {
        open();
        recorder.await(Opened.class);
        recorder.await(SessionStatus.class, status -> status.simTimeNanos() > 100_000_000L);

        link.reset();

        recorder.await(Opened.class);
        SessionStatus restarted = recorder.await(SessionStatus.class);
        assertTrue(restarted.simTimeNanos() < 100_000_000L);
        assertEquals(RobotPhase.IDLE, restarted.phase());
    }

    @Test
    void humanActionsReachTheFieldAndTheOtherGamepadNumberIsRefused() throws Exception {
        open();
        Opened opened = recorder.await(Opened.class);
        int bodies = recorder.await(WorldSnapshot.class).bodies().size();
        assertFalse(opened.info().field().humanActions().isEmpty());

        link.humanAction(opened.info().field().humanActions().get(0).id());

        recorder.await(WorldSnapshot.class, snapshot -> snapshot.bodies().size() == bodies + 1);
        try {
            link.setGamepad(3, GamepadState.NEUTRAL);
            throw new AssertionError("gamepad 3 should be refused");
        } catch (IllegalArgumentException expected) {
            assertNotEquals("", expected.getMessage());
        }
    }

    @Test
    void closingEndsTheLinksThreadAndIsHarmlessTwice() throws Exception {
        open();
        recorder.await(Opened.class);

        link.close();
        link.close();

        boolean alive = Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> thread.getName().equals("fake-simulator-link") && thread.isAlive());
        assertFalse(alive);
    }

    @Test
    void theCatalogueGoesToEveryListenerAtOnceFromTheLinksThreadBeforeAnythingElse() throws Exception {
        link = new FakeSimulatorLink();
        link.addListener(recorder);

        Catalogued first = recorder.await(Catalogued.class);

        assertEquals("fake-simulator-link", first.thread());
        assertEquals(List.of("none", "decode", "biobuzz"), first.catalogue().fields().stream().map(FieldChoice::id).toList());
        assertEquals("none", first.catalogue().fields().get(0).id());
        assertTrue(first.catalogue().fields().get(0).startPoses().isEmpty(), "a bare floor offers no start pose");
        assertFalse(first.catalogue().fields().get(1).startPoses().isEmpty());
        assertFalse(first.catalogue().fields().get(1).humanActions().isEmpty());
        assertEquals("goBILDA-5203-435", first.catalogue().motors().get(0).id());

        Recorder late = new Recorder();
        link.addListener(late);
        assertEquals(first.catalogue(), late.await(Catalogued.class).catalogue(), "a listener added later is told as well");
    }

    @Test
    void theStartPosesOfAFieldNameTheirAllianceAsTheRealGamesDo() {
        FieldChoice decode = FakeField.catalogue().fields().get(1);

        assertEquals(
                List.of("RED start", "RED far side", "BLUE start", "BLUE far side", "Centre of the field"),
                decode.startPoses().stream().map(StartPose::name).toList());
        assertEquals(FakeField.START_X, decode.startPoses().get(0).x(), "the first pose is where the default request starts");
    }

    @Test
    void aCommandThatCannotBeDoneIsRefusedNotFailedAndTheSessionGoesOn() throws Exception {
        open();
        recorder.await(Opened.class);

        link.init("no such OpMode");
        assertTrue(recorder.await(Refused.class).message().contains("no OpMode called no such OpMode"));
        link.init("Mecanum TeleOp");
        recorder.await(SessionStatus.class, status -> status.phase() == RobotPhase.INIT);
        link.armMatch("Drive Square", "Mecanum TeleOp");
        assertTrue(recorder.await(Refused.class).message().contains("OpMode is initialised or running"));
        link.humanAction("juggle");
        assertTrue(recorder.await(Refused.class).message().contains("juggle"));

        SessionStatus still = recorder.await(SessionStatus.class);
        assertEquals(RobotPhase.INIT, still.phase(), "the refusals changed nothing");
        assertEquals(MatchState.NONE, still.match().state());
        assertEquals(0, recorder.failures.get(), "a refusal is not a failure");
    }

    @Test
    void aCommandBeforeAnySessionIsOpenIsRefused() throws Exception {
        link = new FakeSimulatorLink();
        link.addListener(recorder);

        link.start();

        assertTrue(recorder.await(Refused.class).message().contains("no session"));
    }

    @Test
    void aTimedMatchRunsThroughItsPeriodsAndEndsWithTheScoreStillThere() throws Exception {
        link = new FakeSimulatorLink(0, null, MatchTimes.ofMillis(400, 200, 600));
        link.addListener(recorder);
        link.setSpeed(4.0);
        link.open(FakeSimulatorLink.defaultRequest());
        recorder.await(Opened.class);

        link.armMatch("Drive Square", "Mecanum TeleOp");
        SessionStatus armed = recorder.await(SessionStatus.class, status -> status.match().state() == MatchState.ARMED);
        assertEquals(RobotPhase.INIT, armed.phase());
        link.startMatch();

        for (MatchState state : List.of(MatchState.AUTONOMOUS, MatchState.TRANSITION, MatchState.TELEOP, MatchState.ENDED)) {
            SessionStatus seen = recorder.await(SessionStatus.class, status -> status.match().state() == state);
            if (state != MatchState.ENDED) {
                assertTrue(seen.match().phaseDurationNanos() > 0, state + " has a length");
                assertTrue(seen.match().phaseElapsedNanos() <= seen.match().phaseDurationNanos());
            }
        }
        link.armMatch("Drive Square", "Mecanum TeleOp");
        assertTrue(recorder.await(Refused.class).message().contains("Reset the session"));
    }

    @Test
    void stoppingDuringAMatchEndsItAndAResetMakesAnotherPossible() throws Exception {
        link = new FakeSimulatorLink(0, null, MatchTimes.ofMillis(5_000, 2_000, 6_000));
        link.addListener(recorder);
        link.setSpeed(4.0);
        link.open(FakeSimulatorLink.defaultRequest());
        recorder.await(Opened.class);
        link.armMatch("", "Mecanum TeleOp");
        link.startMatch();
        recorder.await(SessionStatus.class, status -> status.match().state() == MatchState.TELEOP);

        link.stop();
        recorder.await(SessionStatus.class, status -> status.match().state() == MatchState.ENDED);
        link.reset();
        recorder.await(Opened.class);
        link.armMatch("", "Mecanum TeleOp");

        recorder.await(SessionStatus.class, status -> status.match().state() == MatchState.ARMED);
    }
}
