package dev.ftcsim.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.simulator.robot.RobotDesign;
import dev.ftcsim.simulator.robot.RobotSpec;
import dev.ftcsim.simulator.SimulationSession;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Threads that a library starts outlive its OpModes, and on a robot they die with the app's process. The simulator's process
 * goes on, so closing a session has to end them: it interrupts them, and a thread that ignores that cannot be killed, so it
 * is left running and reported.
 */
@Timeout(180)
class SessionCloseThreadsTest {
    private static final long SECOND = 1_000_000_000L;

    private static Optional<Thread> liveThread(String name) {
        return Thread.getAllStackTraces().keySet().stream().filter(thread -> thread.isAlive() && thread.getName().equals(name)).findFirst();
    }

    @Test
    void closingASessionEndsTheThreadsALibraryStartedAndReportsTheOneThatWillNotEnd() throws IOException, InterruptedException {
        RobotSpec hornet = RobotDesign.decode().spec();
        SimulationSession session;
        try (TestSessions.Handle handle = TestSessions.open(hornet, 0, 0, 0)) {
            session = handle.session;
            session.init("Library Threads");
            session.start();
            session.runFor(SECOND / 2);
            assertTrue(liveThread("library-polite").isPresent(), "the library's threads are running");
            assertTrue(liveThread("library-stubborn").isPresent());
            assertEquals(List.of(), session.problems());
        }

        try {
            assertFalse(liveThread("library-polite").isPresent(), "a thread that ends when interrupted is gone");
            assertTrue(liveThread("library-stubborn").isPresent(), "a thread that swallows the interrupt cannot be killed");
            List<String> problems = session.problems();
            assertEquals(1, problems.size(), problems.toString());
            assertTrue(problems.get(0).startsWith("the robot-world thread 'library-stubborn' did not end when the session closed"), problems.get(0));
        } finally {
            System.setProperty("ftcsim.test.releaseThreads", "now");
            liveThread("library-stubborn").ifPresent(thread -> {
                try {
                    thread.join(5_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            System.clearProperty("ftcsim.test.releaseThreads");
        }
    }
}
