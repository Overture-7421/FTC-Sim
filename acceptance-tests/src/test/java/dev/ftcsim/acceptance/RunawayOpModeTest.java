package dev.ftcsim.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.robot.RobotPhase;
import dev.ftcsim.contracts.robot.TelemetrySnapshot;
import dev.ftcsim.physics.DcMotor;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * An OpMode that ignores Stop, or ends the process, must not hang, kill or desynchronise the simulator: the SDK's own
 * timeouts decide, in simulated time, and the controller is idle and usable afterwards. Loops cost a few microseconds
 * of simulated time each so that a runaway spends its second in thousands of turns, not billions.
 */
@Timeout(120)
class RunawayOpModeTest {
    private static final long MS = 1_000_000L;
    private static final long LOOP_NANOS = 5_000;
    private static final long SDK_TIMEOUT = 900 * MS;
    private static final long SDK_TIMEOUT_AND_GRACE = 1_000 * MS;
    /** What the real-time start-up of the next OpMode's thread may add to a Stop on a busy machine. */
    private static final long SLACK = 250 * MS;

    private static TestRobot.Builder flywheelRobot() {
        return TestRobot.builder().motor("flywheel", 2, DcMotor.goBilda435(), 4.0e-4, 2.0e-4);
    }

    private static Map<String, Integer> counts(VirtualSession session) throws Exception {
        return session.world.run("dev.ftcsim.scenarios.ReadProbe");
    }

    /**
     * Runs the OpMode for 100 ms, presses Stop and returns how long, in simulated time, Stop took. Stop ends by starting the
     * thread of the next OpMode, whose start-up in real time counts as simulated time, so the figure can run long on a busy
     * machine.
     */
    private static long stopTakes(VirtualSession session, String opMode) {
        session.init(opMode);
        session.start();
        session.advance(100 * MS);
        long pressed = session.now();
        session.stop();
        return session.now() - pressed;
    }

    private static void assertTheControllerIsUsable(VirtualSession session) throws Exception {
        assertEquals(RobotPhase.IDLE, session.phase());
        session.init("Counting");
        session.start();
        session.advance(100 * MS);
        assertTrue(counts(session).get("loop") > 10, counts(session).toString());
        assertEquals("", session.telemetry().error(), "initialising the next OpMode clears the last one's error");
        session.stop();
        assertEquals(RobotPhase.IDLE, session.phase());
    }

    @Test
    void aLinearOpModeThatNeverChecksForStopIsEndedAtTheSdksTimeoutAndTheSessionCarriesOn() throws Exception {
        try (VirtualSession session = VirtualSession.withCoarseLoops(TestRobot.builder(), LOOP_NANOS)) {
            long took = stopTakes(session, "Runaway Linear");

            assertTrue(took >= SDK_TIMEOUT_AND_GRACE && took < SDK_TIMEOUT_AND_GRACE + SLACK, "Stop took " + took + " ns");
            String error = session.telemetry().error();
            assertTrue(error.contains("'Runaway Linear' ignored Stop for 1.0 s (stuck in stop())"), error);
            assertTrue(error.contains("on a robot the Robot Controller app would restart"), error);
            assertTrue(error.contains("make sure you are calling opModeIsActive() in any loops"), error);
            assertEquals(1, counts(session).get("runaway finally"), "the finally blocks of the unwound OpMode ran");
            assertEquals(1, session.kernel.diagnostics().unwinds());
            assertEquals(List.of(), session.kernel.diagnostics().abandonedThreads());
            assertEquals(List.of(), session.kernel.diagnostics().problems());

            assertTheControllerIsUsable(session);
        }
    }

    @Test
    void anIterativeOpModeWhoseLoopNeverReturnsIsEndedToo() throws Exception {
        try (VirtualSession session = VirtualSession.withCoarseLoops(TestRobot.builder(), LOOP_NANOS)) {
            long took = stopTakes(session, "Runaway Iterative");

            assertTrue(took >= SDK_TIMEOUT_AND_GRACE && took < SDK_TIMEOUT_AND_GRACE + SLACK, "Stop took " + took + " ns");
            String error = session.telemetry().error();
            assertTrue(error.contains("ignored Stop for 1.0 s"), error);
            assertTrue(error.contains("make sure you aren't using your own loops"), error);
            assertEquals(1, counts(session).get("runaway finally"));
            assertTheControllerIsUsable(session);
        }
    }

    @Test
    void anOpModeBlockedInAJdkPrimitiveThatSwallowsInterruptsIsInterruptedAndUnwound() throws Exception {
        try (VirtualSession session = VirtualSession.withCoarseLoops(TestRobot.builder(), LOOP_NANOS)) {
            long took = stopTakes(session, "Blocked Queue");

            assertTrue(took >= SDK_TIMEOUT_AND_GRACE && took < SDK_TIMEOUT_AND_GRACE + SLACK, "Stop took " + took + " ns");
            assertTrue(session.telemetry().error().contains("ignored Stop for 1.0 s"), session.telemetry().error());
            Map<String, Integer> counts = counts(session);
            assertTrue(counts.get("interrupt swallowed") >= 1, counts.toString());
            assertEquals(1, counts.get("runaway finally"));
            assertTheControllerIsUsable(session);
        }
    }

    @Test
    void anOpModeThatSwallowsTheUnwindIsAskedAgainAndThenAbandonedSoTheSessionStaysUsable() throws Exception {
        try (VirtualSession session = VirtualSession.withCoarseLoops(TestRobot.builder(), LOOP_NANOS)) {
            long took = stopTakes(session, "Swallowing Runaway");

            assertTrue(took >= 2_000 * MS && took < 2_000 * MS + SLACK, "Stop took " + took + " ns, the OpMode was asked four times and then abandoned");
            assertEquals(4, counts(session).get("error swallowed"));
            assertEquals(List.of("OpModeThread"), session.kernel.diagnostics().abandonedThreads());
            assertTrue(
                    session.kernel.diagnostics().problems().stream().anyMatch(problem -> problem.contains("was given up on")),
                    session.kernel.diagnostics().problems().toString());
            assertTheControllerIsUsable(session);
        }
    }

    @Test
    void aRunawayInsideASynchronizedBlockIsEndedByTheKernelsRealTimeLimit() throws Exception {
        try (VirtualSession session = new VirtualSession(
                TestRobot.builder(), bus -> bus, config -> config.withBackEdgeCostNanos(LOOP_NANOS).withRobotLivenessMillis(300))) {
            session.init("Runaway In Lock");
            session.start();
            session.advance(10 * MS);
            session.stop();

            assertEquals(RobotPhase.IDLE, session.phase());
            assertTrue(
                    session.kernel.diagnostics().problems().stream()
                            .anyMatch(problem -> problem.contains("without reaching a synchronisation point")),
                    "the robot held the baton in its critical section, so only real time could end it: "
                            + session.kernel.diagnostics().problems());
            assertEquals(1, session.kernel.diagnostics().unwinds());
        }
    }

    @Test
    void aRunawayThatReadsHardwareIsForcedOutThroughTheBusLockWithinTheGracePeriod() throws Exception {
        try (VirtualSession session = VirtualSession.withCoarseLoops(flywheelRobot(), LOOP_NANOS)) {
            long took = stopTakes(session, "Runaway On Hardware");

            assertTrue(took >= SDK_TIMEOUT && took < SDK_TIMEOUT_AND_GRACE + SLACK, "Stop took " + took + " ns");
            TelemetrySnapshot telemetry = session.telemetry();
            assertEquals("", telemetry.error());
            assertTrue(telemetry.warnings().contains("was able to be force stopped without restarting the app"), telemetry.warnings());
            assertEquals(0, session.kernel.diagnostics().unwinds());
            assertTheControllerIsUsable(session);
        }
    }

    @Test
    void aSlowStopThatTakesLongerInRealTimeThanTheSdksTimeoutIsNotMistakenForAStuckOpMode() throws Exception {
        try (VirtualSession session = new VirtualSession()) {
            // 500 ms of simulated stop at 1 kHz is 500 steps of the world, each of which now takes 4 ms of real time: the
            // SDK's own monitor, which counts real time, would have restarted the app after one second.
            session.slowTheWorldDownBy(4 * MS);
            long realStart = System.nanoTime();

            long took = stopTakes(session, "Slow Stop");

            assertTrue(System.nanoTime() - realStart > 1_500 * MS, "the stop must have taken longer in real time than the SDK allows");
            assertTrue(took >= 500 * MS && took < SDK_TIMEOUT, "Stop took " + took + " ns of simulated time");
            assertEquals(1, counts(session).get("slow stop finished"));
            assertEquals("", session.telemetry().error());
            assertEquals("", session.telemetry().warnings());
            assertEquals(List.of(), session.kernel.diagnostics().problems());
            assertEquals(0, session.kernel.diagnostics().unwinds());
        }
    }

    @Test
    void theSimulatedTimeStopTakesDoesNotDependOnHowFastTheMachineRunsIt() throws Exception {
        long[] took = new long[2];
        long[] realNanosPerStep = {0, 300_000};
        for (int run = 0; run < 2; run++) {
            try (VirtualSession session = VirtualSession.withCoarseLoops(TestRobot.builder(), LOOP_NANOS)) {
                session.slowTheWorldDownBy(realNanosPerStep[run]);
                took[run] = stopTakes(session, "Runaway Linear");
            }
        }

        assertEquals(took[0], took[1], SLACK, "a slow machine and a fast one end the runaway at the same simulated instant, give or take a thread's start-up");
    }

    @Test
    void aProgramThatEndsTheProcessGetsAClearErrorAndTheSimulatorLivesOn() throws Exception {
        for (String how : List.of("System.exit", "Runtime.exit", "Runtime.halt", "method reference")) {
            System.setProperty("ftcsim.test.exitHow", how);
            try (VirtualSession session = VirtualSession.withCoarseLoops(TestRobot.builder(), LOOP_NANOS)) {
                session.init("Exiting");
                session.start();
                session.advance(100 * MS);

                assertEquals(RobotPhase.IDLE, session.phase(), how);
                assertTrue(session.telemetry().error().startsWith("the robot program called "), how + ": " + session.telemetry().error());
                assertTrue(session.telemetry().error().contains("(7)"), session.telemetry().error());
                Map<String, Integer> counts = counts(session);
                assertEquals(1, counts.get("finally after exit"), how + ": the OpMode was unwound, so its finally blocks ran");
                assertEquals(null, counts.get("ran on after exit"), how);
                assertEquals(null, counts.get("shutdown hook ran"), how + ": the robot's shutdown hooks do not run, the process is the simulator's");
                assertEquals(1, dev.ftcsim.contracts.hooks.Hooks.shutdownHooks().size(), how);
                assertEquals(1, session.kernel.diagnostics().exitRequests().size());
                assertEquals(7, session.kernel.diagnostics().exitRequests().get(0).status());
                assertEquals(how.equals("Runtime.halt"), session.kernel.diagnostics().exitRequests().get(0).halt());
                assertTheControllerIsUsable(session);
            } finally {
                System.clearProperty("ftcsim.test.exitHow");
            }
        }
    }
}
