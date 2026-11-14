package dev.ftcsim.core.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.hooks.Hooks;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Robot threads that run in real time next to the simulator: one that is still starting, one that waits for a lock, one
 * that is woken by an interrupt. Each takes a stretch of real time that simulated time must not run ahead of, or what
 * the thread observes would depend on how fast the machine is.
 */
@Timeout(60)
class RobotThreadsOutsideTheGateTest {
    private static final long MS = 1_000_000L;

    /** Real time spent in the JDK, where no hook can see it. */
    private static void spinFor(long millis) {
        long end = System.nanoTime() + millis * MS;
        while (System.nanoTime() < end) {
            Thread.onSpinWait();
        }
    }

    @RepeatedTest(5)
    void aRobotThreadThatIsStillStartingIsWaitedForBeforeTimeMoves() throws Exception {
        try (KernelFixture fixture = new KernelFixture()) {
            Thread late = new Thread(() -> {
                spinFor(100);
                fixture.trace.add("first hook at " + Hooks.nanoTime());
            }, "robot-late");
            late.setDaemon(true);
            late.start();

            fixture.kernel.runUntil(50 * MS);
            late.join();

            assertEquals(List.of("first hook at " + KernelFixture.POLL_COST), fixture.trace, "the thread began at the instant it was created");
            assertEquals(List.of(), fixture.kernel.diagnostics().problems());
        }
    }

    @RepeatedTest(5)
    void aRobotWaitingForALockARunningThreadHoldsIsWaitedForAndNotGivenUpOn() throws Exception {
        try (KernelFixture fixture = new KernelFixture()) {
            ReentrantLock lock = new ReentrantLock(true);
            CountDownLatch holding = new CountDownLatch(1);
            Thread holder = new Thread(() -> {
                lock.lock();
                holding.countDown();
                spinFor(150);
                lock.unlock();
            }, "holder");
            holder.start();
            holding.await();
            fixture.startRobot("robot", () -> {
                Hooks.nanoTime();
                lock.lock();
                try {
                    fixture.trace.add("got the lock at " + Hooks.nanoTime());
                } finally {
                    lock.unlock();
                }
            });

            fixture.kernel.runUntil(10_000 * MS);
            holder.join();

            assertEquals(1, fixture.trace.size(), fixture.trace.toString());
            long gotIt = Long.parseLong(fixture.trace.get(0).substring("got the lock at ".length()));
            assertTrue(gotIt < MS, "simulated time stood still while the lock was held: " + gotIt);
            assertEquals(List.of(), fixture.kernel.diagnostics().problems());
        }
    }

    @Test
    void aRobotWaitingForALockAThreadThatIsItselfBlockedHoldsIsOutsideTheSimulatorAndSaysWhereItWasWhenItReturns() throws Exception {
        try (KernelFixture fixture = new KernelFixture()) {
            ReentrantLock lock = new ReentrantLock();
            BlockingQueue<Integer> release = new LinkedBlockingQueue<>();
            CountDownLatch holding = new CountDownLatch(1);
            Thread holder = new Thread(() -> {
                lock.lock();
                try {
                    holding.countDown();
                    release.take();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    lock.unlock();
                }
            }, "holder");
            holder.start();
            holding.await();
            fixture.startRobot("robot", () -> {
                Hooks.nanoTime();
                lock.lock();
                lock.unlock();
                Hooks.nanoTime();
            });

            fixture.kernel.runUntil(10 * MS);

            assertEquals(RobotGate.State.EXTERNAL, fixture.kernel.gateState(), fixture.kernel.describeRobot());
            assertEquals(10 * MS, fixture.kernel.clock().now(), "time went on without the robot");

            release.put(1);
            holder.join();
            long deadline = System.nanoTime() + 5_000_000_000L;
            for (long target = 11 * MS; fixture.kernel.gateState() != RobotGate.State.DONE && System.nanoTime() < deadline; target += MS) {
                fixture.kernel.runUntil(target);
            }

            String problems = fixture.kernel.diagnostics().problems().toString();
            assertTrue(problems.contains("resumed from a wait the simulator does not control"), problems);
            assertTrue(problems.contains("ReentrantLock"), "the problem says where the robot was blocked: " + problems);
        }
    }

    @RepeatedTest(5)
    void aRobotThreadBlockedOutsideTheGateThatIsInterruptedIsWaitedForBeforeTimeMoves() throws Exception {
        try (KernelFixture fixture = new KernelFixture()) {
            BlockingQueue<Integer> queue = new LinkedBlockingQueue<>();
            Thread robot = fixture.startRobot("robot", () -> {
                Hooks.nanoTime();
                try {
                    queue.take();
                } catch (InterruptedException interrupted) {
                    spinFor(100);
                    fixture.trace.add("reacted at " + Hooks.nanoTime());
                }
            });
            fixture.kernel.runUntil(5 * MS);
            assertEquals(RobotGate.State.EXTERNAL, fixture.kernel.gateState(), fixture.kernel.describeRobot());

            // What the SDK does to end an OpMode: it interrupts the thread and goes on at once
            robot.interrupt();
            fixture.kernel.runUntil(10_000 * MS);

            assertEquals(1, fixture.trace.size(), fixture.trace.toString());
            long reactedAt = Long.parseLong(fixture.trace.get(0).substring("reacted at ".length()));
            assertTrue(reactedAt >= 5 * MS && reactedAt < 6 * MS, "the robot reacted at the instant of the interrupt: " + reactedAt);
            String problems = fixture.kernel.diagnostics().problems().toString();
            assertTrue(problems.contains("resumed from a wait the simulator does not control"), problems);
            assertTrue(problems.contains("LinkedBlockingQueue.take"), "it says where the robot was blocked: " + problems);
        }
    }

    @RepeatedTest(5)
    void unwindingARobotThreadBlockedOutsideTheGateEndsItBeforeTheRequestReturns() throws Exception {
        try (KernelFixture fixture = new KernelFixture()) {
            BlockingQueue<Integer> queue = new LinkedBlockingQueue<>();
            Thread robot = fixture.startRobot("robot", () -> {
                Hooks.nanoTime();
                for (;;) {
                    try {
                        queue.take();
                    } catch (InterruptedException swallowed) {
                        spinFor(50);
                    }
                    Hooks.backEdge();
                }
            });
            fixture.kernel.runUntil(5 * MS);
            assertEquals(RobotGate.State.EXTERNAL, fixture.kernel.gateState(), fixture.kernel.describeRobot());

            assertTrue(fixture.kernel.unwindRobot());

            assertFalse(robot.isAlive(), "the unwind was carried out at the instant it was asked for");
            assertEquals(5 * MS, fixture.kernel.clock().now());
            assertEquals(1, fixture.kernel.diagnostics().unwinds());
        }
    }
}
