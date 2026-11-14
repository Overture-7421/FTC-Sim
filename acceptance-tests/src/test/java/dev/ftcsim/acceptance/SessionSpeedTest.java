package dev.ftcsim.acceptance;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.simulator.SimulationSession;
import dev.ftcsim.simulator.profile.RobotProfile;
import dev.ftcsim.simulator.profile.RobotProfileLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A robot program that only spins, as every busy-wait does, must not slow the simulation below the clock. A loop with
 * no hardware, clock or sleep in it lets simulated time pass only through the cost charged for each jump back
 * ({@code SimulationSession.LOOP_NANOS}); with a cost too small the loop runs a billion iterations for every simulated
 * second, which is how a command OpMode's Init loop once held a desktop machine to 0.4 times real time.
 */
@Timeout(300)
class SessionSpeedTest {
    private static final long SECOND = 1_000_000_000L;

    @Test
    void aProgramThatOnlySpinsRunsFasterThanTheClock() throws Exception {
        RobotProfile hornet = RobotProfileLoader.loadBundled("hornet-2026.json");
        try (TestSessions.Handle handle = TestSessions.open(hornet, 0, 0, 0)) {
            SimulationSession session = handle.session;
            session.init("Busy Wait");
            session.runFor(SECOND);
            session.start();
            long begun = System.nanoTime();
            session.runFor(10 * SECOND);
            long took = System.nanoTime() - begun;
            assertTrue(took < 10 * SECOND, "ten simulated seconds of a spinning program took " + took / 1_000_000 + " ms of real time");
        }
    }
}
