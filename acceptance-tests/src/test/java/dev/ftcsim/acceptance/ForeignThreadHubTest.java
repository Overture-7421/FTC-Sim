package dev.ftcsim.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.ftcsim.contracts.hardware.HubWire;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A thread the simulation does not schedule, such as the timer of a dashboard library, reading the hub while the
 * robot runs: it reaches the hubs through the unscheduled path of the wire, which never moves simulated time, and the
 * robot goes on with its own loop and its own path.
 */
@Timeout(120)
class ForeignThreadHubTest {
    private static final long MS = 1_000_000L;
    private static final long PATIENCE_MILLIS = 20_000;
    private static final String TIMER_THREAD = "battery-timer";
    private static final String LOOPS = "loop at ns";

    /** Remembers which of its two entry points each thread used, and how often. */
    private static final class RecordingWire implements HubWire {
        private final HubWire bus;
        private final Map<String, Integer> uses = new ConcurrentHashMap<>();

        RecordingWire(HubWire bus) {
            this.bus = bus;
        }

        @Override
        public Exchange transmit(byte[] datagram) {
            uses.merge("transmit by " + Thread.currentThread().getName(), 1, Integer::sum);
            return bus.transmit(datagram);
        }

        @Override
        public Exchange transmitUnscheduled(byte[] datagram) {
            uses.merge("transmitUnscheduled by " + Thread.currentThread().getName(), 1, Integer::sum);
            return bus.transmitUnscheduled(datagram);
        }

        Map<String, Integer> uses() {
            return new TreeMap<>(uses);
        }
    }

    private record Run(
            Map<String, Integer> wireUses,
            Map<String, List<Double>> series,
            List<String> telemetry,
            Map<String, Integer> counts,
            List<String> problems) {

        /** What the scheduled threads sent, which is all the robot's own behaviour can account for. */
        Map<String, Integer> scheduledUses() {
            return wireUses.entrySet().stream()
                    .filter(use -> use.getKey().startsWith("transmit by "))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, Integer::sum, TreeMap::new));
        }

        List<Double> loops() {
            return series.get(LOOPS);
        }
    }

    /** Runs 'Timer Battery' for {@code slices} slices of 10 ms, with a timer thread that reads {@code foreignReads} times. */
    private static Run run(int foreignReads, int slices) throws Exception {
        System.setProperty("ftcsim.test.batteryTimerReads", Integer.toString(foreignReads));
        try {
            RecordingWire[] wire = new RecordingWire[1];
            try (VirtualSession session = new VirtualSession(TestRobot.builder(), bus -> wire[0] = new RecordingWire(bus))) {
                session.init("Timer Battery");
                session.start();
                for (int slice = 0; slice < slices; slice++) {
                    session.advance(10 * MS);
                }
                awaitForeignReads(session, foreignReads);
                Map<String, Integer> counts = session.world.run("dev.ftcsim.scenarios.ReadProbe");
                return new Run(
                        wire[0].uses(),
                        session.world.run("dev.ftcsim.scenarios.ReadSeries"),
                        session.telemetry().lines(),
                        counts,
                        session.kernel.diagnostics().problems());
            }
        } finally {
            System.clearProperty("ftcsim.test.batteryTimerReads");
        }
    }

    /** The timer runs in real time, so the test waits until it has read as often as it was asked to. */
    private static void awaitForeignReads(VirtualSession session, int reads) throws Exception {
        long deadline = System.nanoTime() + PATIENCE_MILLIS * 1_000_000L;
        for (;;) {
            Map<String, Integer> counts = session.world.run("dev.ftcsim.scenarios.ReadProbe");
            if (counts.getOrDefault("foreign reads", 0) >= reads) {
                return;
            }
            if (System.nanoTime() > deadline) {
                fail("the timer thread read the battery " + counts.getOrDefault("foreign reads", 0) + " of " + reads + " times in "
                        + PATIENCE_MILLIS + " ms: " + counts);
            }
            Thread.sleep(5);
        }
    }

    @Test
    void aLibraryTimerReadingTheBatteryUsesTheUnscheduledPathAndTheRobotKeepsItsOwn() throws Exception {
        Run alone = run(0, 40);
        Run withTimer = run(100, 40);

        assertEquals(100, withTimer.counts().get("foreign reads"), withTimer.counts().toString());
        assertEquals(List.of(), withTimer.counts().keySet().stream().filter(key -> key.startsWith("foreign read failed")).toList());
        assertTrue(
                withTimer.wireUses().get("transmitUnscheduled by " + TIMER_THREAD) >= 100,
                "every read of the timer reached the hub through the unscheduled path: " + withTimer.wireUses());
        assertFalse(withTimer.wireUses().containsKey("transmit by " + TIMER_THREAD), "the timer never used the scheduled path");
        assertTrue(
                withTimer.wireUses().keySet().stream().noneMatch(use -> use.startsWith("transmitUnscheduled by ") && use.contains("OpModeThread")),
                "the robot thread never used the unscheduled path: " + withTimer.wireUses());
        assertEquals(List.of(), withTimer.problems(), "the kernel reports nothing about a thread that only reads");
        assertEquals(List.of(), alone.problems());

        assertTrue(alone.loops().size() > 100, "the robot looped " + alone.loops().size() + " times");
        assertTrue(withTimer.loops().size() > 100, "the robot looped " + withTimer.loops().size() + " times beside the timer");
        assertFalse(withTimer.scheduledUses().isEmpty(), "the robot's own traffic is on the scheduled path");
        assertFalse(withTimer.telemetry().isEmpty());
    }
}
