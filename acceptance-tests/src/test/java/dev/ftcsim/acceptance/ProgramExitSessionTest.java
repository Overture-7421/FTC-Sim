package dev.ftcsim.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.robot.RobotPhase;
import dev.ftcsim.simulator.SimulationSession;
import dev.ftcsim.simulator.profile.RobotProfile;
import dev.ftcsim.simulator.profile.RobotProfileLoader;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** A robot program that ends the process never ends the simulator's: the session reports it as a problem and goes on. */
@Timeout(180)
class ProgramExitSessionTest {
    private static final long SECOND = 1_000_000_000L;

    @Test
    void aProgramThatCallsExitOrHaltIsAProblemOfTheSessionAndItsShutdownHooksNeverRun() throws IOException {
        RobotProfile hornet = RobotProfileLoader.loadBundled("hornet-2026.json");
        for (String how : List.of("System.exit", "Runtime.exit", "Runtime.halt", "method reference")) {
            System.setProperty("ftcsim.test.exitHow", how);
            try (TestSessions.Handle handle = TestSessions.open(hornet, 0, 0, 0)) {
                SimulationSession session = handle.session;
                session.init("Exiting");
                session.start();
                session.runFor(SECOND / 2);

                assertEquals(RobotPhase.IDLE, session.phase(), how);
                assertTrue(session.telemetry().error().contains("the robot program called "), how + ": " + session.telemetry().error());
                List<String> problems = session.problems();
                assertTrue(
                        problems.stream().anyMatch(problem -> problem.contains(how.equals("Runtime.halt") ? "Runtime.halt(7)" : "System.exit or Runtime.exit(7)")
                                && problem.contains("OpModeThread")),
                        how + ": " + problems);
                assertTrue(problems.stream().anyMatch(problem -> problem.contains("registered 1 shutdown hook, which never run")), problems.toString());

                session.init("Counting");
                assertEquals(RobotPhase.INIT, session.phase(), "the controller takes the next OpMode");
            } finally {
                System.clearProperty("ftcsim.test.exitHow");
            }
        }
    }
}
