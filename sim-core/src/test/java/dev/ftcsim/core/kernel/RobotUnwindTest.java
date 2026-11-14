package dev.ftcsim.core.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.hooks.Hooks;
import dev.ftcsim.contracts.hooks.RobotExit;
import dev.ftcsim.contracts.hooks.RobotUnwind;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Ending a robot thread that will not end by itself: unwinding it, giving up on it, and the exits it asks for. */
@Timeout(60)
class RobotUnwindTest {
    private static final long MS = 1_000_000L;

    @Test
    void aRobotThatNeverChecksForStopIsUnwoundWhenAskedAndItsFinallyBlocksRun() throws Exception {
        try (KernelFixture fixture = new KernelFixture()) {
            Thread robot = fixture.startRobot("robot", () -> {
                Hooks.nanoTime();
                try {
                    for (;;) {
                        Hooks.backEdge();
                    }
                } finally {
                    fixture.trace.add("finally ran");
                }
            });
            fixture.kernel.runUntil(5 * MS);
            assertTrue(robot.isAlive(), "it spins for as long as simulated time runs");

            assertTrue(fixture.kernel.unwindRobot());
            fixture.kernel.runUntil(6 * MS);

            robot.join(2_000);
            assertFalse(robot.isAlive());
            assertInstanceOf(RobotUnwind.class, fixture.robotFailure());
            assertEquals(List.of("finally ran"), fixture.trace);
            assertEquals(1, fixture.kernel.diagnostics().unwinds());
            assertEquals(List.of(), fixture.kernel.diagnostics().problems());
        }
    }

    @Test
    void aSleepingRobotIsUnwoundAtTheMomentItIsAskedAndNotAtTheEndOfItsSleep() throws Exception {
        try (KernelFixture fixture = new KernelFixture()) {
            Thread robot = fixture.startRobot("robot", () -> {
                try {
                    Hooks.sleep(10_000);
                } finally {
                    fixture.trace.add("unwound at " + Hooks.nanoTime());
                }
            });
            fixture.kernel.runUntil(1_000 * MS);

            fixture.kernel.unwindRobot();
            fixture.kernel.runUntil(1_001 * MS);

            robot.join(2_000);
            assertInstanceOf(RobotUnwind.class, fixture.robotFailure());
            long unwoundAt = Long.parseLong(fixture.trace.get(0).substring("unwound at ".length()));
            assertTrue(unwoundAt >= 1_000 * MS && unwoundAt < 1_001 * MS, "unwound at " + unwoundAt);
        }
    }

    @Test
    void aRobotWaitingOnAMonitorIsUnwoundAndLeavesTheMonitorFree() throws Exception {
        try (KernelFixture fixture = new KernelFixture()) {
            Object monitor = new Object();
            Thread robot = fixture.startRobot("robot", () -> {
                Hooks.nanoTime();
                synchronized (monitor) {
                    Hooks.lockAcquired();
                    try {
                        Hooks.wait(monitor);
                    } finally {
                        Hooks.lockReleased();
                    }
                }
            });
            fixture.kernel.runUntil(1 * MS);

            fixture.kernel.unwindRobot();
            fixture.kernel.runUntil(2 * MS);

            robot.join(2_000);
            assertInstanceOf(RobotUnwind.class, fixture.robotFailure());
            boolean lockedByTest = false;
            synchronized (monitor) {
                lockedByTest = Thread.holdsLock(monitor); // reached only because the unwound robot let go of it
            }
            assertTrue(lockedByTest);
        }
    }

    @Test
    void aRobotBlockedOutsideTheSimulatorIsInterruptedAndUnwoundWhenItGoesRoundItsLoop() throws Exception {
        try (KernelFixture fixture = new KernelFixture()) {
            BlockingQueue<Integer> queue = new LinkedBlockingQueue<>();
            Thread robot = fixture.startRobot("robot", () -> {
                Hooks.nanoTime();
                for (;;) {
                    try {
                        queue.take();
                    } catch (InterruptedException ignored) {
                        // swallowed, as careless code does
                    }
                    Hooks.backEdge();
                }
            });
            fixture.kernel.runUntil(1 * MS);
            assertEquals(RobotGate.State.EXTERNAL, fixture.kernel.gateState(), fixture.kernel.describeRobot());

            fixture.kernel.unwindRobot();

            robot.join(5_000);
            assertFalse(robot.isAlive(), "the interrupt woke it and its next turn of the loop ended it");
            assertInstanceOf(RobotUnwind.class, fixture.robotFailure());
        }
    }

    @Test
    void aRobotThatSwallowsTheUnwindIsEndedByAskingAgain() throws Exception {
        try (KernelFixture fixture = new KernelFixture()) {
            AtomicInteger swallowed = new AtomicInteger();
            Thread robot = fixture.startRobot("robot", () -> {
                Hooks.nanoTime();
                for (;;) {
                    try {
                        Hooks.backEdge();
                    } catch (Throwable ignored) {
                        swallowed.incrementAndGet();
                    }
                }
            });
            fixture.kernel.runUntil(1 * MS);

            fixture.kernel.unwindRobot();
            fixture.kernel.runUntil(2 * MS);
            assertEquals(1, swallowed.get());
            fixture.kernel.unwindRobot();
            fixture.kernel.runUntil(3 * MS);
            assertEquals(2, swallowed.get(), "every request is thrown once");
            assertTrue(robot.isAlive());
            assertEquals(2, fixture.kernel.diagnostics().unwinds());
        }
    }

    @Test
    void aRobotThatLoopsWhereTheKernelCannotSeeIsAskedAndThenAbandonedAndANewRobotTakesItsPlace() throws Exception {
        try (KernelFixture fixture = new KernelFixture(config -> config.withRobotLivenessMillis(150))) {
            AtomicBoolean release = new AtomicBoolean();
            Thread stuck = fixture.startRobot("robot", () -> {
                Hooks.nanoTime();
                while (!release.get()) {
                    Thread.onSpinWait(); // a loop in the JDK, which the rewriter does not reach
                }
                Hooks.nanoTime();
                fixture.trace.add("the abandoned thread ran on");
            });

            fixture.kernel.runUntil(1 * MS);

            assertEquals(List.of("robot"), fixture.kernel.diagnostics().abandonedThreads());
            List<String> problems = fixture.kernel.diagnostics().problems();
            assertEquals(2, problems.size(), problems.toString());
            assertTrue(problems.get(0).contains("without reaching a synchronisation point"), problems.get(0));
            assertTrue(problems.get(1).contains("did not end when asked to and was given up on"), problems.get(1));

            fixture.startRobot("robot-2", () -> {
                Hooks.sleep(1);
                fixture.trace.add("the new robot ran");
            });
            fixture.kernel.runUntil(5 * MS);
            assertEquals(List.of("the new robot ran"), fixture.trace);

            release.set(true);
            Thread.sleep(100);
            fixture.kernel.runUntil(10 * MS);
            assertTrue(stuck.isAlive(), "the abandoned thread is parked for good at its next hook");
            assertEquals(List.of("the new robot ran"), fixture.trace);
        }
    }

    @Test
    void aWatchdogFiresAtItsSimulatedTimeEvenWhileTheSimulatorThreadWaitsInsideAnAction() throws Exception {
        try (KernelFixture fixture = new KernelFixture()) {
            AtomicLong firedAt = new AtomicLong(-1);
            AtomicBoolean cancelledOneFired = new AtomicBoolean();

            fixture.kernel.runAsEvent(() -> {
                Hooks.watchdog(5 * MS, () -> firedAt.set(fixture.kernel.clock().now()));
                Runnable cancel = Hooks.watchdog(7 * MS, () -> cancelledOneFired.set(true));
                cancel.run();
                try {
                    Hooks.sleep(20);
                } catch (InterruptedException interrupted) {
                    throw new IllegalStateException(interrupted);
                }
            });

            assertEquals(5 * MS, firedAt.get());
            assertFalse(cancelledOneFired.get());
        }
    }

    @Test
    void exitAndHaltEndTheCallingThreadAndAreRecordedAndNothingEndsTheProcess() throws Exception {
        try (KernelFixture fixture = new KernelFixture()) {
            Thread robot = fixture.startRobot("robot", () -> {
                Hooks.nanoTime();
                Hooks.exit(3);
                fixture.trace.add("not reached");
            });
            fixture.kernel.runUntil(1 * MS);
            robot.join(2_000);

            RobotExit exit = assertInstanceOf(RobotExit.class, fixture.robotFailure());
            assertEquals(3, exit.status());
            assertFalse(exit.isHalt());
            assertEquals("the robot program called System.exit(3)", exit.getMessage());

            AtomicInteger helperSaw = new AtomicInteger(-1);
            Thread helper = new Thread(() -> {
                try {
                    Hooks.halt(Runtime.getRuntime(), 4);
                } catch (RobotExit halted) {
                    helperSaw.set(halted.status());
                }
            }, "helper");
            helper.start();
            helper.join();

            assertEquals(4, helperSaw.get());
            assertEquals(
                    List.of(new KernelDiagnostics.ExitRequest(3, false, "robot"), new KernelDiagnostics.ExitRequest(4, true, "helper")),
                    fixture.kernel.diagnostics().exitRequests());
            assertEquals(List.of(), fixture.trace);
        }
    }
}
