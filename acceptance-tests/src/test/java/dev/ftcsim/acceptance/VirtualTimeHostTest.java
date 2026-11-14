package dev.ftcsim.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.robot.GamepadState;
import dev.ftcsim.contracts.robot.RobotPhase;
import dev.ftcsim.core.kernel.KernelDiagnostics;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** The real SDK host under the simulation kernel: OpMode behaviour as a function of simulated time only. */
@Timeout(120)
class VirtualTimeHostTest {
    private static final long MS = 1_000_000L;

    /** What a run of the 'Counting' OpMode through Init, Start and Stop looks like from outside. */
    private record Run(Map<String, Integer> counts, long phaseIdleAt, List<String> problems) {}

    private static Run countingRun(long slice) throws Exception {
        try (VirtualSession session = new VirtualSession()) {
            session.init("Counting");
            advanceInSlices(session, 100 * MS, slice);
            session.start();
            advanceInSlices(session, 500 * MS, slice);
            session.stop();
            Map<String, Integer> counts = session.world.run("dev.ftcsim.scenarios.ReadProbe");
            KernelDiagnostics diagnostics = session.kernel.diagnostics();
            return new Run(counts, session.now(), diagnostics.problems());
        }
    }

    private static void advanceInSlices(VirtualSession session, long total, long slice) {
        long end = session.now() + total;
        while (session.now() < end) {
            session.kernel.runUntil(Math.min(end, session.now() + slice));
        }
    }

    @Test
    void anIterativeOpModeSeesTheSameTimeWhateverTheSlicingOfTheRun() throws Exception {
        List<Run> runs = new ArrayList<>();
        for (long slice : new long[] {Long.MAX_VALUE / 4, 1 * MS, 7 * MS, 33 * MS}) {
            runs.add(countingRun(slice));
        }

        Run first = runs.get(0);
        assertEquals(List.of(), first.problems());
        assertEquals(1, first.counts().get("init"));
        assertEquals(1, first.counts().get("start"));
        assertEquals(1, first.counts().get("stop"));
        // One init_loop or loop per millisecond of simulated time, less what each callback itself costs.
        assertTrue(first.counts().get("init_loop") > 50 && first.counts().get("init_loop") <= 100, first.counts().toString());
        assertTrue(first.counts().get("loop") > 250 && first.counts().get("loop") <= 500, first.counts().toString());
        for (Run run : runs) {
            assertEquals(first, run);
        }
    }

    @Test
    void aLinearOpModeWaitsForStartAndThenFinishesAtAnExactSimulatedTime() throws Exception {
        List<Long> idleTimes = new ArrayList<>();
        for (long slice : new long[] {Long.MAX_VALUE / 4, 3 * MS, 11 * MS}) {
            try (VirtualSession session = new VirtualSession()) {
                session.init("Linear Auto");
                advanceInSlices(session, 50 * MS, slice);
                assertEquals(RobotPhase.INIT, session.phase());
                assertEquals(null, session.world.<Map<String, Integer>>run("dev.ftcsim.scenarios.ReadProbe").get("started"));

                session.start();
                long started = session.now();
                advanceInSlices(session, 300 * MS, slice);

                assertEquals(RobotPhase.IDLE, session.phase(), "the OpMode never finished: " + session.telemetry());
                assertEquals(3, session.world.<Map<String, Integer>>run("dev.ftcsim.scenarios.ReadProbe").get("step"));
                assertEquals(List.of(), session.kernel.diagnostics().problems());
                idleTimes.add(session.becameIdleAt() - started);
            }
        }
        // Three steps of sleep(20) end after 60 ms; the event loop pass that notices the OpMode asked to stop is the next one.
        assertTrue(idleTimes.get(0) >= 60 * MS && idleTimes.get(0) <= 70 * MS, idleTimes.toString());
        assertEquals(List.of(idleTimes.get(0), idleTimes.get(0), idleTimes.get(0)), idleTimes);
    }

    @Test
    void aLoopThatOnlyWaitsForAButtonEndsWhenTheButtonIsPressedAtTheSameSimulatedTimeEveryRun() throws Exception {
        List<Long> idleTimes = new ArrayList<>();
        for (long slice : new long[] {Long.MAX_VALUE / 4, 2 * MS, 9 * MS}) {
            try (VirtualSession session = new VirtualSession()) {
                session.init("Busy Wait");
                session.start();
                advanceInSlices(session, 20 * MS, slice);
                assertEquals(RobotPhase.RUNNING, session.phase());
                assertEquals(null, session.world.<Map<String, Integer>>run("dev.ftcsim.scenarios.ReadProbe").get("busy-wait-ended"));

                GamepadState aPressed = new GamepadState(
                        0f, 0f, 0f, 0f, 0f, 0f, false, false, false, false, true, false, false, false, false, false, false, false,
                        false, false, false);
                session.hold(aPressed, GamepadState.NEUTRAL);
                advanceInSlices(session, 30 * MS, slice);

                assertEquals(1, session.world.<Map<String, Integer>>run("dev.ftcsim.scenarios.ReadProbe").get("busy-wait-ended"));
                assertEquals(RobotPhase.IDLE, session.phase());
                assertEquals(List.of(), session.kernel.diagnostics().problems());
                idleTimes.add(session.becameIdleAt());
            }
        }
        assertEquals(List.of(idleTimes.get(0), idleTimes.get(0), idleTimes.get(0)), idleTimes);
    }

    @Test
    void stopWakesASleepingLinearOpModeAtTheMomentOfTheStopAndNoRealTimePasses() throws Exception {
        try (VirtualSession session = new VirtualSession()) {
            session.init("Sleeping Auto");
            session.start();
            session.advance(300 * MS);
            assertEquals(RobotPhase.RUNNING, session.phase());

            long before = System.nanoTime();
            long stoppedAt = session.now();
            session.stop();
            long realMillis = (System.nanoTime() - before) / MS;

            assertEquals(RobotPhase.IDLE, session.phase());
            assertTrue(session.now() - stoppedAt < 50 * MS, "stop took " + (session.now() - stoppedAt) + " ns of simulated time");
            assertTrue(realMillis < 2_000, "stop took " + realMillis + " ms of real time");
            Map<String, Integer> counts = session.world.run("dev.ftcsim.scenarios.ReadProbe");
            assertEquals(1, counts.get("woke-by-stop"));
            assertEquals(null, counts.get("woke-by-timeout"));
            assertEquals(List.of(), session.kernel.diagnostics().problems());
        }
    }
}
