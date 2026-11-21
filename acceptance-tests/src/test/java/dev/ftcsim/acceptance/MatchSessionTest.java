package dev.ftcsim.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.acceptance.TestField.PhaseEntry;
import dev.ftcsim.contracts.field.MatchPhase;
import dev.ftcsim.contracts.robot.GamepadState;
import dev.ftcsim.contracts.robot.RobotPhase;
import dev.ftcsim.contracts.session.MatchState;
import dev.ftcsim.contracts.session.MatchStatus;
import dev.ftcsim.contracts.world.BodyPose;
import dev.ftcsim.field.EntityIds;
import dev.ftcsim.simulator.robot.RobotDesign;
import dev.ftcsim.simulator.robot.RobotSpec;
import dev.ftcsim.simulator.SimulationSession;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A timed match played by the real SDK on the fixture OpModes: the Driver Station's presses and the field's phases
 * happen at exact instants of simulated time, however the caller slices that time.
 */
@Timeout(900)
class MatchSessionTest {
    private static final long SECOND = 1_000_000_000L;
    private static final long AUTONOMOUS = 30 * SECOND;
    private static final long TRANSITION = 8 * SECOND;
    private static final long TELEOP = 120 * SECOND;

    private static RobotSpec hornet;

    @BeforeAll
    static void loadProfile() throws IOException {
        hornet = RobotDesign.decode().spec();
    }

    private static MatchStatus status(MatchState state, long elapsed, long duration) {
        return new MatchStatus(state, elapsed, duration);
    }

    @Test
    void aMatchEntersEveryPeriodAtItsExactSimulatedInstantAndTellsTheField() throws IOException {
        TestField field = new TestField();
        try (TestSessions.Handle handle = TestSessions.open(hornet, field, 0, 0, 0)) {
            SimulationSession session = handle.session;
            session.armMatch("Sleeping Auto", "Counting");
            assertEquals(status(MatchState.ARMED, 0, 0), session.match());
            assertEquals(RobotPhase.INIT, session.phase());

            session.runUntil(2 * SECOND);
            long start = session.now();
            session.startMatch();
            assertEquals(status(MatchState.AUTONOMOUS, 0, AUTONOMOUS), session.match());
            assertEquals(RobotPhase.RUNNING, session.phase());

            session.runUntil(start + AUTONOMOUS - 1);
            assertEquals(status(MatchState.AUTONOMOUS, AUTONOMOUS - 1, AUTONOMOUS), session.match());
            assertEquals(RobotPhase.RUNNING, session.phase(), "the autonomous OpMode runs until its period is over");

            long autoEnd = start + AUTONOMOUS;
            session.runUntil(autoEnd);
            assertEquals(status(MatchState.TRANSITION, session.now() - autoEnd, TRANSITION), session.match());
            assertEquals(RobotPhase.INIT, session.phase(), "the autonomous OpMode was stopped and the driver-controlled one initialised");

            long teleOpStart = autoEnd + TRANSITION;
            session.runUntil(teleOpStart - 1);
            assertEquals(MatchState.TRANSITION, session.match().state());
            assertEquals(RobotPhase.INIT, session.phase());
            assertEquals(2, field.phases().size(), "the pause between the periods is not a phase of the field");

            session.runUntil(teleOpStart);
            assertEquals(status(MatchState.TELEOP, session.now() - teleOpStart, TELEOP), session.match());
            assertEquals(RobotPhase.RUNNING, session.phase());
            session.runUntil(teleOpStart + 50_000_000L);
            assertTrue(session.telemetry().lines().contains("callback : loop"), session.telemetry().lines().toString());

            long end = teleOpStart + TELEOP;
            session.runUntil(end - 1);
            assertEquals(status(MatchState.TELEOP, TELEOP - 1, TELEOP), session.match());
            assertEquals(RobotPhase.RUNNING, session.phase());

            session.runUntil(end);
            assertEquals(status(MatchState.ENDED, 0, 0), session.match());
            assertEquals(RobotPhase.IDLE, session.phase(), "the driver-controlled OpMode was stopped at the end");
            assertEquals(
                    List.of(
                            new PhaseEntry(MatchPhase.PRACTICE, 0),
                            new PhaseEntry(MatchPhase.AUTONOMOUS, start),
                            new PhaseEntry(MatchPhase.TELEOP, teleOpStart),
                            new PhaseEntry(MatchPhase.ENDED, end)),
                    field.phases());
            assertEquals(List.of(), session.problems());
        }
    }

    @Test
    void withNoAutonomousOpModeTheMatchGoesStraightToTeleOpForItsFullLength() throws IOException {
        TestField field = new TestField();
        try (TestSessions.Handle handle = TestSessions.open(hornet, field, 0, 0, 0)) {
            SimulationSession session = handle.session;
            session.armMatch("", "Counting");
            assertEquals(RobotPhase.INIT, session.phase(), "the driver-controlled OpMode is the one initialised");

            session.runUntil(SECOND);
            long start = session.now();
            session.startMatch();
            assertEquals(status(MatchState.TELEOP, 0, TELEOP), session.match());
            assertEquals(RobotPhase.RUNNING, session.phase());

            session.runUntil(start + TELEOP - 1);
            assertEquals(status(MatchState.TELEOP, TELEOP - 1, TELEOP), session.match());
            session.runUntil(start + TELEOP);
            assertEquals(status(MatchState.ENDED, 0, 0), session.match());
            assertEquals(RobotPhase.IDLE, session.phase());
            assertEquals(
                    List.of(
                            new PhaseEntry(MatchPhase.PRACTICE, 0),
                            new PhaseEntry(MatchPhase.TELEOP, start),
                            new PhaseEntry(MatchPhase.ENDED, start + TELEOP)),
                    field.phases());
            assertEquals(List.of(), session.problems());
        }
    }

    /**
     * Presses Stop {@code stopAfter} into a match whose field was told {@code enteredBeforeStop}, then lets time run
     * to {@code watchUntil} after the start, past every boundary that Stop must have cancelled.
     */
    private static void stopDuringAMatch(
            String autonomous, String teleOp, long stopAfter, long watchUntil, MatchState period, List<MatchPhase> enteredBeforeStop)
            throws IOException {
        TestField field = new TestField();
        try (TestSessions.Handle handle = TestSessions.open(hornet, field, 0, 0, 0)) {
            SimulationSession session = handle.session;
            session.armMatch(autonomous, teleOp);
            long start = session.now();
            session.startMatch();
            session.runUntil(start + stopAfter);
            assertEquals(period, session.match().state());

            long stoppedAt = session.now();
            session.stop();
            assertEquals(status(MatchState.ENDED, 0, 0), session.match());
            assertEquals(RobotPhase.IDLE, session.phase());

            session.runUntil(start + watchUntil);
            assertEquals(status(MatchState.ENDED, 0, 0), session.match());
            assertEquals(RobotPhase.IDLE, session.phase(), "nothing is initialised or started after Stop");
            List<MatchPhase> expected = new ArrayList<>(List.of(MatchPhase.PRACTICE));
            expected.addAll(enteredBeforeStop);
            expected.add(MatchPhase.ENDED);
            assertEquals(expected, field.phases().stream().map(PhaseEntry::phase).toList());
            assertEquals(new PhaseEntry(MatchPhase.ENDED, stoppedAt), field.phases().get(field.phases().size() - 1));
            assertEquals(List.of(), session.problems());
        }
    }

    @Test
    void stopDuringTheAutonomousPeriodEndsTheMatchThereAndTheLaterPeriodsNeverBegin() throws IOException {
        stopDuringAMatch("Sleeping Auto", "Counting", 10 * SECOND, 40 * SECOND, MatchState.AUTONOMOUS, List.of(MatchPhase.AUTONOMOUS));
    }

    @Test
    void stopDuringTheTransitionEndsTheMatchThereAndTeleOpNeverBegins() throws IOException {
        stopDuringAMatch(
                "Sleeping Auto", "Counting", AUTONOMOUS + 4 * SECOND, 40 * SECOND, MatchState.TRANSITION, List.of(MatchPhase.AUTONOMOUS));
    }

    @Test
    void stopDuringTeleOpEndsTheMatchThereAndItsOwnEndNeverComesAgain() throws IOException {
        stopDuringAMatch("", "Counting", 5 * SECOND, TELEOP + SECOND, MatchState.TELEOP, List.of(MatchPhase.TELEOP));
    }

    @Test
    void stopWhileArmedGivesTheMatchUpWithoutTheFieldHearingOfIt() throws IOException {
        TestField field = new TestField();
        try (TestSessions.Handle handle = TestSessions.open(hornet, field, 0, 0, 0)) {
            SimulationSession session = handle.session;
            session.armMatch("Linear Auto", "Counting");

            session.stop();
            assertEquals(MatchStatus.NONE, session.match());
            assertEquals(RobotPhase.IDLE, session.phase());
            assertEquals(List.of(new PhaseEntry(MatchPhase.PRACTICE, 0)), field.phases());

            session.armMatch("", "Counting");
            assertEquals(MatchState.ARMED, session.match().state());
        }
    }

    @Test
    void armingIsRefusedWhileAnOpModeIsInitialisedOrRunningAndPracticeCarriesOn() throws IOException {
        try (TestSessions.Handle handle = TestSessions.open(hornet, new TestField(), 0, 0, 0)) {
            SimulationSession session = handle.session;
            session.init("Counting");
            String initialised = assertThrows(IllegalStateException.class, () -> session.armMatch("", "Counting")).getMessage();
            assertTrue(initialised.contains("initialised"), initialised);

            session.start();
            session.runFor(100_000_000L);
            String running = assertThrows(IllegalStateException.class, () -> session.armMatch("Linear Auto", "Counting")).getMessage();
            assertTrue(running.contains("running"), running);
            assertEquals(RobotPhase.RUNNING, session.phase(), "the refused request left the OpMode alone");
            assertEquals(MatchStatus.NONE, session.match());

            session.stop();
            session.armMatch("", "Counting");
            assertEquals(MatchState.ARMED, session.match().state());
            assertEquals(List.of(), session.problems());
        }
    }

    @Test
    void aMatchOwnsTheOpModesWhileItIsArmedOrUnderWayAndItsRequestsMustBeSound() throws IOException {
        try (TestSessions.Handle handle = TestSessions.open(hornet, new TestField(), 0, 0, 0)) {
            SimulationSession session = handle.session;
            assertTrue(assertThrows(IllegalStateException.class, session::startMatch).getMessage().contains("No match is armed"));
            assertTrue(assertThrows(IllegalArgumentException.class, () -> session.armMatch("Missing Auto", "Counting")).getMessage()
                    .contains("'Missing Auto'"));
            assertTrue(assertThrows(IllegalArgumentException.class, () -> session.armMatch("Linear Auto", "")).getMessage()
                    .contains("driver-controlled"));
            assertEquals(RobotPhase.IDLE, session.phase(), "a refused arming initialises nothing");
            assertEquals(MatchStatus.NONE, session.match());

            session.armMatch("Linear Auto", "Counting");
            assertTrue(assertThrows(IllegalStateException.class, () -> session.armMatch("", "Counting")).getMessage().contains("already armed"));
            assertTrue(assertThrows(IllegalStateException.class, () -> session.init("Counting")).getMessage().contains("armed"));
            assertTrue(assertThrows(IllegalStateException.class, session::start).getMessage().contains("armed"));

            session.startMatch();
            assertTrue(assertThrows(IllegalStateException.class, session::startMatch).getMessage().contains("under way"));
            assertTrue(assertThrows(IllegalStateException.class, () -> session.init("Counting")).getMessage().contains("during a match"));

            session.stop();
            String over = assertThrows(IllegalStateException.class, () -> session.armMatch("", "Counting")).getMessage();
            assertTrue(over.contains("has been played"), over);
            assertTrue(assertThrows(IllegalStateException.class, session::startMatch).getMessage().contains("has been played"));
            session.init("Counting");
            assertEquals(RobotPhase.INIT, session.phase(), "practice may follow a match");
            assertEquals(List.of(), session.problems());
        }
    }

    @Test
    void aRobotProgramThatCrashesDoesNotStopTheMatchClock() throws IOException {
        TestField field = new TestField();
        try (TestSessions.Handle handle = TestSessions.open(hornet, field, 0, 0, 0)) {
            SimulationSession session = handle.session;
            session.armMatch("", "Crasher");
            long start = session.now();
            session.startMatch();
            session.runFor(2 * SECOND);

            assertEquals(RobotPhase.IDLE, session.phase(), "the controller recovered to idle after the crash");
            assertTrue(session.telemetry().error().contains("boom from user code"), session.telemetry().error());
            assertEquals(status(MatchState.TELEOP, session.now() - start, TELEOP), session.match());
            assertEquals(List.of(), session.problems());
        }
    }

    @Test
    void theSlicingOfSimulatedTimeDoesNotChangeWhereTheRobotEndsUpOrWhenThePhasesBegin() throws IOException {
        BodyPose firstPose = null;
        List<PhaseEntry> firstPhases = null;
        for (long slice : new long[] {Long.MAX_VALUE / 4, 7_000_000L, 250_000_000L}) {
            TestField field = new TestField();
            try (TestSessions.Handle handle = TestSessions.open(hornet, field, 0, 0, 0)) {
                SimulationSession session = handle.session;
                session.armMatch("Linear Auto", "Mecanum Drive");
                session.startMatch();
                session.setGamepad(1, GamepadState.sticks(0.3f, -0.8f, 0.2f, 0f));
                long end = AUTONOMOUS + TRANSITION + 2 * SECOND;
                while (session.now() < end) {
                    session.runUntil(Math.min(end, session.now() + slice));
                }
                assertEquals(MatchState.TELEOP, session.match().state());
                BodyPose pose = session.world().bodyPose(EntityIds.CHASSIS);
                if (firstPose == null) {
                    firstPose = pose;
                    firstPhases = field.phases();
                }
                assertEquals(firstPose.x(), pose.x(), 0.02, "slice " + slice);
                assertEquals(firstPose.y(), pose.y(), 0.02, "slice " + slice);
                assertEquals(firstPhases, field.phases(), "slice " + slice);
                assertEquals(List.of(), session.problems());
            }
        }
    }
}
