package dev.ftcsim.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.robot.GamepadState;
import dev.ftcsim.contracts.robot.RobotPhase;
import dev.ftcsim.contracts.session.SessionStatus;
import dev.ftcsim.contracts.snapshot.BodyState;
import dev.ftcsim.field.EntityIds;
import dev.ftcsim.simulator.RealtimeRunner;
import dev.ftcsim.simulator.robot.RobotDesign;
import dev.ftcsim.simulator.SessionConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** The simulated robot shown in real time: a thread of its own, paced by the wall clock, driven by other threads. */
@Timeout(180)
class RealtimeRunnerTest {
    private static final long SECOND = 1_000_000_000L;

    private static RealtimeRunner open(double speed, List<String> log, Path storage) throws IOException {
        SessionConfig config = new SessionConfig(
                RobotDesign.decode().spec(), TestSessions.program(), storage,
                Path.of(System.getProperty("ftcsim.nativeDirectory")), null, null, 0, 0, 0, log::add);
        return RealtimeRunner.open(config, speed);
    }

    private static void waitFor(java.util.function.BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + 60 * SECOND;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("gave up waiting for " + what);
            }
            Thread.sleep(2);
        }
    }

    @Test
    void aRunNeverGoesFasterThanTheWallClockAllowsAndTheRobotDrivesWhenAnotherThreadPushesTheStick() throws Exception {
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        Path storage = Files.createTempDirectory("ftcsim-sdcard");
        try (RealtimeRunner runner = open(1.0, log, storage)) {
            runner.post(session -> session.init("Mecanum Drive"));
            waitFor(() -> runner.status().phase() == RobotPhase.INIT, "Init");
            runner.post(session -> session.start());
            waitFor(() -> runner.status().phase() == RobotPhase.RUNNING, "Start");
            runner.setGamepad(1, GamepadState.sticks(0f, -1f, 0f, 0f));

            long simStart = runner.status().simTimeNanos();
            long wallStart = System.nanoTime();
            waitFor(() -> runner.status().simTimeNanos() - simStart >= SECOND, "one simulated second");
            double wallSeconds = (System.nanoTime() - wallStart) / 1e9;

            assertTrue(wallSeconds >= 0.95, "a simulated second took only " + wallSeconds + " s of wall-clock time");
            assertTrue(wallSeconds < 4.0, "a simulated second took " + wallSeconds + " s: the runner cannot keep up");
            BodyState chassis = runner.snapshot().bodies().stream().filter(b -> b.entityId() == EntityIds.CHASSIS).findFirst().orElseThrow();
            assertTrue(chassis.x() > 0.3, "the robot should be driving: x = " + chassis.x());
            assertEquals(List.of(), runner.status().problems(), log.toString());
            assertTrue(runner.status().achievedSpeed() > 0.5, "achieved speed " + runner.status().achievedSpeed());
        } finally {
            Files.deleteIfExists(storage);
        }
    }

    @Test
    void theWorldIsPublishedAfterEveryPhysicsSliceNotOnlyWithTheStatus() throws Exception {
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        Path storage = Files.createTempDirectory("ftcsim-sdcard");
        try (RealtimeRunner runner = open(1.0, log, storage)) {
            waitFor(() -> runner.snapshot().stepIndex() > 0, "the first physics steps");
            java.util.Set<Long> seen = new java.util.HashSet<>();
            long end = System.nanoTime() + SECOND / 2;
            while (System.nanoTime() < end) {
                seen.add(runner.snapshot().stepIndex());
                Thread.onSpinWait();
            }
            // Half a second at 1x is about 120 slices of 240 Hz; a snapshot that came with the 33 ms status would give 15.
            assertTrue(seen.size() >= 45, "only " + seen.size() + " distinct snapshots in half a second");
        } finally {
            Files.deleteIfExists(storage);
        }
    }

    @Test
    void aPausedRunStandsStillAndAFastOneStillNeverSkipsAnyOfTheSimulation() throws Exception {
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        Path storage = Files.createTempDirectory("ftcsim-sdcard");
        try (RealtimeRunner runner = open(4.0, log, storage)) {
            runner.setPaused(true);
            Thread.sleep(100);
            SessionStatus paused = runner.status();
            Thread.sleep(150);
            assertEquals(paused.simTimeNanos(), runner.status().simTimeNanos(), "a paused run must not advance");
            assertTrue(runner.status().paused());

            runner.setPaused(false);
            long before = runner.status().simTimeNanos();
            long wallStart = System.nanoTime();
            waitFor(() -> runner.status().simTimeNanos() - before >= 2 * SECOND, "two simulated seconds at 4x");
            double wallSeconds = (System.nanoTime() - wallStart) / 1e9;

            assertTrue(wallSeconds >= 0.45, "two simulated seconds at 4x took only " + wallSeconds + " s");
            assertTrue(runner.status().speed() == 4.0);
        } finally {
            Files.deleteIfExists(storage);
        }
    }
}
