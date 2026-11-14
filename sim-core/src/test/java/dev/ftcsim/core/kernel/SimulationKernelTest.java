package dev.ftcsim.core.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.hooks.Hooks;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class SimulationKernelTest {

    @Test
    void aSleepEndsExactlyWhenSimulatedTimeReachesIt() throws Exception {
        try (KernelFixture fixture = new KernelFixture()) {
            fixture.startRobot("robot", () -> {
                long before = Hooks.nanoTime();
                Hooks.sleep(10);
                long after = Hooks.nanoTime();
                fixture.trace.add("before=" + before + " after=" + after);
            });

            fixture.kernel.runUntil(20_000_000);

            fixture.assertRobotDidNotFail();
            assertEquals(List.of("before=200 after=10000400"), fixture.trace);
            assertEquals(20_000_000, fixture.kernel.clock().now());
        }
    }

    @Test
    void aLoopWaitingOnTheClockTerminatesAfterAnExactNumberOfReads() throws Exception {
        try (KernelFixture fixture = new KernelFixture()) {
            fixture.startRobot("robot", () -> {
                int reads = 0;
                while (Hooks.nanoTime() < 1_000_000) {
                    reads++;
                }
                fixture.trace.add("reads=" + reads);
            });

            fixture.kernel.runUntil(2_000_000);

            fixture.assertRobotDidNotFail();
            assertEquals(List.of("reads=4999"), fixture.trace);
        }
    }

    @Test
    void yieldingCostsSimulatedTimeSoSpinLoopsAlwaysProgress() throws Exception {
        try (KernelFixture fixture = new KernelFixture()) {
            fixture.startRobot("robot", () -> {
                for (int i = 0; i < 1000; i++) {
                    Hooks.yieldThread();
                }
                fixture.trace.add("t=" + Hooks.nanoTime());
            });

            fixture.kernel.runUntil(2_000_000);

            fixture.assertRobotDidNotFail();
            assertEquals(List.of("t=1000200"), fixture.trace);
        }
    }

    @Test
    void timeSpentOnModelledWorkAdvancesTheRobotsClockAndTheWorldCatchesUp() throws Exception {
        try (KernelFixture fixture = new KernelFixture()) {
            fixture.startRobot("robot", () -> {
                Hooks.elapse(2_000_000);
                long first = Hooks.nanoTime();
                fixture.trace.add(first + ":" + fixture.world.ticks());
                Hooks.elapse(10_000_000);
                long second = Hooks.nanoTime();
                fixture.trace.add(second + ":" + fixture.world.ticks());
            });

            fixture.kernel.runUntil(30_000_000);

            fixture.assertRobotDidNotFail();
            assertEquals(2, fixture.trace.size());
            assertTrue(fixture.trace.get(0).startsWith("2000200:"), fixture.trace.toString());
            assertTrue(fixture.trace.get(1).startsWith("12000400:"), fixture.trace.toString());
            for (String line : fixture.trace) {
                String[] parts = line.split(":");
                assertEquals(TickWorld.ticksAt(Long.parseLong(parts[0])), Integer.parseInt(parts[1]), line);
            }
        }
    }

    @Test
    void anActionRunAsAnEventCanSleepWithoutThePeriodicCallbacksReenteringIt() throws Exception {
        try (KernelFixture fixture = new KernelFixture()) {
            fixture.startRobot("robot", () -> {
                Hooks.sleep(4);
                fixture.trace.add("robot at " + Hooks.nanoTime());
            });
            AtomicBoolean inAction = new AtomicBoolean();
            AtomicInteger reentered = new AtomicInteger();
            Runnable[] tick = new Runnable[1];
            tick[0] = () -> {
                if (inAction.get()) {
                    reentered.incrementAndGet();
                }
                fixture.trace.add("tick " + fixture.kernel.clock().now());
                fixture.kernel.schedule(fixture.kernel.clock().now() + 5_000_000, tick[0]);
            };
            fixture.kernel.schedule(5_000_000, tick[0]);

            fixture.kernel.runAsEvent(() -> {
                inAction.set(true);
                try {
                    Hooks.sleep(12);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                inAction.set(false);
                fixture.trace.add("action done at " + fixture.kernel.clock().now());
            });

            assertEquals(0, reentered.get());
            assertEquals(12_000_000, fixture.kernel.clock().now());
            assertEquals(List.of("robot at 4000200", "action done at 12000000"), fixture.trace);

            fixture.kernel.runUntil(20_000_000);
            assertEquals(List.of("robot at 4000200", "action done at 12000000", "tick 12000000", "tick 17000000"), fixture.trace);
        }
    }

    @Test
    @Timeout(30)
    void aRobotInsideACriticalSectionKeepsTheBatonSoTheSimulatorIsNeverBlockedOnItsLock() throws Exception {
        try (KernelFixture fixture = new KernelFixture()) {
            Object lock = new Object();
            fixture.startRobot("robot", () -> {
                Hooks.threadStarted();
                synchronized (lock) {
                    Hooks.lockAcquired();
                    try {
                        // The clock passes the simulator's event at 1 ms while the lock is held.
                        long end = Hooks.nanoTime() + 3_000_000;
                        while (Hooks.nanoTime() < end) {
                            Thread.onSpinWait();
                        }
                        fixture.trace.add("robot leaves the lock at " + Hooks.nanoTime());
                    } finally {
                        Hooks.lockReleased();
                    }
                }
                Hooks.sleep(1);
                fixture.trace.add("robot continues at " + Hooks.nanoTime());
            });
            fixture.kernel.schedule(1_000_000, () -> {
                synchronized (lock) {
                    fixture.trace.add("simulator takes the lock at " + fixture.kernel.clock().now());
                }
            });

            fixture.kernel.runUntil(10_000_000);

            fixture.assertRobotDidNotFail();
            assertEquals(
                    List.of(
                            "robot leaves the lock at 3000400",
                            "simulator takes the lock at 1000000",
                            "robot continues at 4000600"),
                    fixture.trace);
            assertTrue(fixture.kernel.diagnostics().problems().isEmpty(), fixture.kernel.diagnostics().problems().toString());
        }
    }

    @Test
    @Timeout(30)
    void aLoopThatOnlyWaitsForTheWorldToChangeIsGivenTimeToSeeItChange() throws Exception {
        try (KernelFixture fixture = new KernelFixture()) {
            AtomicBoolean flag = new AtomicBoolean();
            fixture.startRobot("robot", () -> {
                Hooks.threadStarted();
                long spins = 0;
                while (!flag.get()) {
                    spins++;
                    Hooks.backEdge();
                }
                fixture.trace.add("saw the change after " + spins + " spins at " + Hooks.nanoTime());
            });
            fixture.kernel.schedule(2_000_000, () -> flag.set(true));

            fixture.kernel.runUntil(10_000_000);

            fixture.assertRobotDidNotFail();
            // 2 ms of simulated time at 100 ns a spin, and the event that sets the flag runs at exactly 2 ms.
            assertEquals(List.of("saw the change after 20000 spins at 2000200"), fixture.trace);
        }
    }

    @Test
    void theRobotAlwaysSeesTheWorldAsOfItsOwnTime() throws Exception {
        try (KernelFixture fixture = new KernelFixture()) {
            fixture.startRobot("robot", () -> {
                for (int i = 0; i < 40; i++) {
                    Hooks.sleep(1);
                    long time = Hooks.nanoTime();
                    fixture.trace.add(time + ":" + fixture.world.ticks());
                }
            });

            fixture.kernel.runUntil(100_000_000);

            fixture.assertRobotDidNotFail();
            assertEquals(40, fixture.trace.size());
            for (String line : fixture.trace) {
                String[] parts = line.split(":");
                assertEquals(TickWorld.ticksAt(Long.parseLong(parts[0])), Integer.parseInt(parts[1]), line);
            }
        }
    }

    @Test
    void whenTheRobotPollsTheClockItNeverOutrunsTheWorld() throws Exception {
        try (KernelFixture fixture = new KernelFixture()) {
            fixture.startRobot("robot", () -> {
                for (int i = 0; i < 20_000; i++) {
                    long time = Hooks.nanoTime();
                    if (TickWorld.ticksAt(time) != fixture.world.ticks()) {
                        fixture.trace.add("stale world at " + time);
                        return;
                    }
                }
            });

            fixture.kernel.runUntil(50_000_000);

            fixture.assertRobotDidNotFail();
            assertEquals(List.of(), fixture.trace);
        }
    }

    @Test
    void runsAreIdenticalWhateverTheSlicingAndWhateverElseTheMachineIsDoing() throws Exception {
        List<List<String>> traces = new ArrayList<>();
        int repetitions = Integer.getInteger("ftcsim.kernelStress", 4);
        BackgroundLoad load = new BackgroundLoad();
        try {
            for (long slice : new long[] {Long.MAX_VALUE, 1_000_000L, 3_300_000L, 17_000_000L}) {
                for (int repetition = 0; repetition < repetitions; repetition++) {
                    traces.add(runMixedWorkload(slice));
                }
            }
        } finally {
            load.close();
        }
        assertEquals(40, traces.get(0).size(), traces.get(0).toString());
        for (List<String> trace : traces) {
            assertEquals(traces.get(0), trace);
        }
    }

    private static List<String> runMixedWorkload(long slice) throws Exception {
        try (KernelFixture fixture = new KernelFixture()) {
            fixture.startRobot("robot", () -> {
                for (int i = 0; i < 20; i++) {
                    Hooks.sleep(i % 3 + 1);
                    fixture.trace.add(Hooks.nanoTime() + ":" + fixture.world.ticks());
                    long deadline = Hooks.nanoTime() + 500_000;
                    int spins = 0;
                    while (Hooks.nanoTime() < deadline) {
                        spins++;
                    }
                    fixture.trace.add("spins=" + spins);
                    Hooks.yieldThread();
                }
            });

            long end = 80_000_000;
            for (long target = Math.min(slice, end); ; target = Math.min(target + slice, end)) {
                fixture.kernel.runUntil(target);
                if (target >= end) {
                    break;
                }
            }
            fixture.assertRobotDidNotFail();
            assertTrue(fixture.kernel.diagnostics().problems().isEmpty(), fixture.kernel.diagnostics().problems().toString());
            return new ArrayList<>(fixture.trace);
        }
    }

    @Test
    void theSimulatorThreadSleepingLetsTheWorldAndTheRobotRun() throws Exception {
        try (KernelFixture fixture = new KernelFixture()) {
            AtomicBoolean woke = new AtomicBoolean();
            fixture.startRobot("robot", () -> {
                Hooks.sleep(3);
                woke.set(true);
            });
            fixture.kernel.runUntil(0);

            Hooks.sleep(5);

            fixture.assertRobotDidNotFail();
            assertTrue(woke.get());
            assertEquals(5_000_000, fixture.kernel.clock().now());
        }
    }

    @Test
    void aCallbackThatLetsTimePassBeyondTheCallersTargetLeavesTheClockThereAndNeverSendsItBackwards() throws Exception {
        try (KernelFixture fixture = new KernelFixture()) {
            fixture.kernel.schedule(2_000_000, () -> {
                try {
                    Hooks.sleep(10);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                fixture.trace.add("callback done at " + fixture.kernel.clock().now());
            });

            // The callback sleeps on the simulator thread, as the SDK does while it stops a crashed OpMode, and the
            // clock reaches 12 ms inside it, past this target.
            fixture.kernel.runUntil(5_000_000);

            assertEquals(List.of("callback done at 12000000"), fixture.trace);
            assertEquals(12_000_000, fixture.kernel.clock().now());
            fixture.kernel.runUntil(20_000_000);
            assertEquals(20_000_000, fixture.kernel.clock().now());
        }
    }

    @Test
    void interruptingASleepingRobotEndsItsSleepAtTheMomentOfTheInterrupt() throws Exception {
        try (KernelFixture fixture = new KernelFixture()) {
            Thread robot = fixture.startRobot("robot", () -> {
                try {
                    Hooks.sleep(1_000);
                    fixture.trace.add("slept");
                } catch (InterruptedException interrupted) {
                    fixture.trace.add("interrupted at " + Hooks.nanoTime());
                }
            });
            fixture.kernel.schedule(5_000_000, robot::interrupt);

            fixture.kernel.runUntil(20_000_000);

            fixture.assertRobotDidNotFail();
            assertEquals(List.of("interrupted at 5000200"), fixture.trace);
        }
    }

    @Test
    void callbacksRunInTimeOrderAndThenInTheOrderTheyWereScheduled() throws Exception {
        try (KernelFixture fixture = new KernelFixture()) {
            fixture.kernel.schedule(5_000_000, () -> fixture.trace.add("b"));
            fixture.kernel.schedule(3_000_000, () -> fixture.trace.add("a"));
            fixture.kernel.schedule(5_000_000, () -> fixture.trace.add("c"));

            fixture.kernel.runUntil(10_000_000);

            assertEquals(List.of("a", "b", "c"), fixture.trace);
        }
    }

    @Test
    void aSecondThreadThatLooksLikeTheRobotIsReportedAndNotTrusted() throws Exception {
        try (KernelFixture fixture = new KernelFixture()) {
            fixture.startRobot("robot", () -> Hooks.sleep(100));
            fixture.startRobot("robot-two", () -> fixture.trace.add("second saw " + Hooks.nanoTime()));

            assertEquals(List.of("second saw 0"), fixture.trace);
            assertTrue(
                    fixture.kernel.diagnostics().problems().stream().anyMatch(p -> p.contains("also looks like a robot thread")),
                    fixture.kernel.diagnostics().problems().toString());
        }
    }

    @RepeatedTest(25)
    void aRobotBlockedOutsideTheSimulatorDoesNotStallTimeAndIsReportedWhenItResumes() throws Exception {
        try (KernelFixture fixture = new KernelFixture()) {
            CountDownLatch release = new CountDownLatch(1);
            fixture.startRobot("robot", () -> {
                Hooks.nanoTime();
                release.await();
                fixture.trace.add("resumed at " + Hooks.nanoTime());
            });

            fixture.kernel.runUntil(2_000_000);
            assertEquals(2_000_000, fixture.kernel.clock().now());
            assertEquals(List.of(), fixture.trace);

            release.countDown();
            // The robot wakes on its own schedule, which is exactly why this situation is reported; keep
            // advancing time in small slices until it has rejoined the simulation.
            long deadline = System.nanoTime() + 5_000_000_000L;
            long target = 2_000_000;
            while (fixture.trace.isEmpty() && System.nanoTime() < deadline) {
                target += 1_000_000;
                fixture.kernel.runUntil(target);
            }

            fixture.assertRobotDidNotFail();
            assertEquals(
                    1,
                    fixture.trace.size(),
                    () -> fixture.trace + " " + fixture.kernel.describeRobot() + " problems=" + fixture.kernel.diagnostics().problems());
            assertTrue(
                    fixture.kernel.diagnostics().problems().stream().anyMatch(p -> p.contains("does not control")),
                    fixture.kernel.diagnostics().problems().toString());
        }
    }

    @Test
    void nativeLibraryRequestsAreDeclinedAndRecordedInOrder() throws Exception {
        try (KernelFixture fixture = new KernelFixture()) {
            Hooks.loadLibrary("RobotCore");
            Hooks.load("C:\\natives\\other.dll");

            assertEquals(List.of("RobotCore", "C:\\natives\\other.dll"), fixture.kernel.diagnostics().declinedNativeLibraries());
        }
    }

    @Test
    void threadsOtherThanTheRobotReadTheSimulatedClockAndNeverTheRealOne() throws Exception {
        try (KernelFixture fixture = new KernelFixture()) {
            fixture.kernel.runUntil(7_000_000);

            long[] seen = new long[2];
            Thread helper = new Thread(() -> {
                seen[0] = Hooks.nanoTime();
                seen[1] = Hooks.currentTimeMillis();
            }, "helper");
            helper.start();
            helper.join();

            assertEquals(7_000_000, seen[0]);
            assertEquals(1_000_007, seen[1]);
            assertFalse(fixture.kernel.diagnostics().problems().iterator().hasNext());
        }
    }

    @Test
    void onlyTheRobotAndTheSimulatorAreScheduledThreads() throws Exception {
        try (KernelFixture fixture = new KernelFixture()) {
            assertTrue(Hooks.isScheduledThread(), "until time moves, the thread that made the kernel is the simulator to be");
            assertFalse(askFromAnotherThread(), "a thread the kernel does not schedule, before time moves");

            fixture.kernel.runUntil(1_000_000);
            assertTrue(Hooks.isScheduledThread(), "the simulator thread");
            assertFalse(askFromAnotherThread(), "a thread the kernel does not schedule, once time moves");

            boolean[] robotIsScheduled = new boolean[1];
            fixture.startRobot("robot", () -> robotIsScheduled[0] = Hooks.isScheduledThread());
            fixture.kernel.runUntil(2_000_000);
            assertTrue(robotIsScheduled[0], "the robot thread");
            assertFalse(fixture.kernel.diagnostics().problems().iterator().hasNext(), "asking is not a problem");
        }
    }

    private static boolean askFromAnotherThread() throws InterruptedException {
        boolean[] answer = {true};
        Thread helper = new Thread(() -> answer[0] = Hooks.isScheduledThread(), "helper");
        helper.start();
        helper.join();
        return answer[0];
    }

    @Test
    void aWaitingRobotWakesAtTheSimulatedTimeOfTheNotificationThatEndsItsWait() throws Exception {
        try (KernelFixture fixture = new KernelFixture()) {
            Object monitor = new Object();
            AtomicBoolean started = new AtomicBoolean();
            fixture.startRobot("robot", () -> {
                int wakeUps = 0;
                while (!started.get()) {
                    synchronized (monitor) {
                        Hooks.wait(monitor);
                    }
                    wakeUps++;
                }
                fixture.trace.add("started at " + Hooks.nanoTime() + " after " + wakeUps + " wake-ups");
            });
            for (int tick = 1; tick <= 10; tick++) {
                fixture.kernel.schedule(tick * 5_000_000L, () -> {
                    synchronized (monitor) {
                        Hooks.notifyAll(monitor);
                    }
                });
            }
            fixture.kernel.schedule(22_000_000L, () -> started.set(true));

            fixture.kernel.runUntil(60_000_000);

            fixture.assertRobotDidNotFail();
            assertEquals(List.of("started at 25000200 after 5 wake-ups"), fixture.trace);
            assertTrue(fixture.kernel.diagnostics().problems().isEmpty(), fixture.kernel.diagnostics().problems().toString());
        }
    }

    @Test
    void aTimedWaitEndsExactlyWhenSimulatedTimeReachesItsTimeout() throws Exception {
        try (KernelFixture fixture = new KernelFixture()) {
            Object monitor = new Object();
            fixture.startRobot("robot", () -> {
                long before = Hooks.nanoTime();
                synchronized (monitor) {
                    Hooks.wait(monitor, 7);
                }
                fixture.trace.add("before=" + before + " after=" + Hooks.nanoTime());
            });

            fixture.kernel.runUntil(20_000_000);

            fixture.assertRobotDidNotFail();
            assertEquals(List.of("before=200 after=7000400"), fixture.trace);
        }
    }

    @Test
    void interruptingAWaitingRobotEndsItsWaitAtTheMomentOfTheInterrupt() throws Exception {
        try (KernelFixture fixture = new KernelFixture()) {
            Object monitor = new Object();
            Thread robot = fixture.startRobot("robot", () -> {
                try {
                    synchronized (monitor) {
                        Hooks.wait(monitor);
                    }
                    fixture.trace.add("notified");
                } catch (InterruptedException interrupted) {
                    fixture.trace.add("interrupted at " + Hooks.nanoTime() + " flag=" + Thread.currentThread().isInterrupted());
                }
            });
            fixture.kernel.schedule(5_000_000, robot::interrupt);

            fixture.kernel.runUntil(20_000_000);

            fixture.assertRobotDidNotFail();
            assertEquals(List.of("interrupted at 5000200 flag=false"), fixture.trace);
        }
    }

    @Test
    void monitorWaitsAreIdenticalWhateverTheSlicingAndWhateverElseTheMachineIsDoing() throws Exception {
        List<List<String>> traces = new ArrayList<>();
        int repetitions = Integer.getInteger("ftcsim.kernelStress", 4);
        BackgroundLoad load = new BackgroundLoad();
        try {
            for (long slice : new long[] {Long.MAX_VALUE, 1_000_000L, 3_300_000L, 17_000_000L}) {
                for (int repetition = 0; repetition < repetitions; repetition++) {
                    traces.add(runLinearOpModeWorkload(slice));
                }
            }
        } finally {
            load.close();
        }
        assertEquals(8, traces.get(0).size(), traces.get(0).toString());
        for (List<String> trace : traces) {
            assertEquals(traces.get(0), trace);
        }
    }

    /** The shape of an SDK LinearOpMode: wait for a start signal notified on every loop tick, run, get stopped. */
    private static List<String> runLinearOpModeWorkload(long slice) throws Exception {
        try (KernelFixture fixture = new KernelFixture()) {
            Object monitor = new Object();
            AtomicBoolean started = new AtomicBoolean();
            Thread robot = fixture.startRobot("robot", () -> {
                try {
                    while (!started.get()) {
                        synchronized (monitor) {
                            Hooks.wait(monitor);
                        }
                    }
                    fixture.trace.add("start " + Hooks.nanoTime() + ":" + fixture.world.ticks());
                    for (int i = 0; i < 5; i++) {
                        Hooks.sleep(i % 2 + 1);
                        fixture.trace.add("work " + Hooks.nanoTime() + ":" + fixture.world.ticks());
                    }
                    synchronized (monitor) {
                        Hooks.wait(monitor, 40);
                    }
                    fixture.trace.add("timed " + Hooks.nanoTime());
                    Hooks.sleep(1_000);
                } catch (InterruptedException stopped) {
                    fixture.trace.add("stop " + Hooks.nanoTime());
                }
            });
            for (int tick = 1; tick <= 40; tick++) {
                fixture.kernel.schedule(tick * 5_000_000L, () -> {
                    synchronized (monitor) {
                        Hooks.notifyAll(monitor);
                    }
                });
            }
            fixture.kernel.schedule(32_000_000L, () -> started.set(true));
            fixture.kernel.schedule(150_000_000L, robot::interrupt);

            long end = 200_000_000;
            for (long target = Math.min(slice, end); ; target = Math.min(target + slice, end)) {
                fixture.kernel.runUntil(target);
                if (target >= end) {
                    break;
                }
            }
            fixture.assertRobotDidNotFail();
            assertTrue(fixture.kernel.diagnostics().problems().isEmpty(), fixture.kernel.diagnostics().problems().toString());
            return new ArrayList<>(fixture.trace);
        }
    }

    @Test
    void waitingOrNotifyingWithoutOwningTheMonitorFailsAsItDoesInTheJdk() throws Exception {
        try (KernelFixture fixture = new KernelFixture()) {
            Object monitor = new Object();
            fixture.startRobot("robot", () -> {
                try {
                    Hooks.wait(monitor);
                } catch (IllegalMonitorStateException expected) {
                    fixture.trace.add("wait refused");
                }
                try {
                    Hooks.notifyAll(monitor);
                } catch (IllegalMonitorStateException expected) {
                    fixture.trace.add("notify refused");
                }
            });

            fixture.kernel.runUntil(1_000_000);

            fixture.assertRobotDidNotFail();
            assertEquals(List.of("wait refused", "notify refused"), fixture.trace);
            assertThrows(IllegalMonitorStateException.class, () -> Hooks.notify(monitor));
        }
    }

    @Test
    void aThreadThatIsNotTheRobotStillWaitsAndIsNotifiedForReal() throws Exception {
        try (KernelFixture fixture = new KernelFixture()) {
            Object monitor = new Object();
            CountDownLatch waiting = new CountDownLatch(1);
            AtomicBoolean woke = new AtomicBoolean();
            Thread helper = new Thread(() -> {
                try {
                    synchronized (monitor) {
                        waiting.countDown();
                        Hooks.wait(monitor);
                    }
                    woke.set(true);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }, "helper");
            helper.start();
            waiting.await();

            synchronized (monitor) {
                Hooks.notifyAll(monitor);
            }
            helper.join(5_000);

            assertTrue(woke.get());
            assertTrue(fixture.kernel.diagnostics().problems().isEmpty());
        }
    }

    @Test
    void anotherThreadWakingTheRobotFromAWaitIsReportedBecauseItsTimingIsNotReproducible() throws Exception {
        try (KernelFixture fixture = new KernelFixture()) {
            Object monitor = new Object();
            fixture.startRobot("robot", () -> {
                synchronized (monitor) {
                    Hooks.wait(monitor);
                }
                fixture.trace.add("woke");
            });
            fixture.kernel.runUntil(1_000_000);

            Thread outsider = new Thread(() -> {
                synchronized (monitor) {
                    Hooks.notifyAll(monitor);
                }
            }, "outsider");
            outsider.start();
            outsider.join(5_000);
            fixture.kernel.runUntil(2_000_000);

            assertEquals(List.of("woke"), fixture.trace);
            assertTrue(
                    fixture.kernel.diagnostics().problems().stream().anyMatch(p -> p.contains("outsider")),
                    fixture.kernel.diagnostics().problems().toString());
        }
    }

    /** Spins a few threads so that scheduling of the threads under test is as unlike a quiet machine as possible. */
    private static final class BackgroundLoad implements AutoCloseable {
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final List<Thread> threads = new ArrayList<>();

        BackgroundLoad() {
            int count = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
            for (int i = 0; i < count; i++) {
                Thread thread = new Thread(() -> {
                    while (running.get()) {
                        Thread.onSpinWait();
                    }
                }, "load-" + i);
                thread.setDaemon(true);
                thread.start();
                threads.add(thread);
            }
        }

        @Override
        public void close() {
            running.set(false);
            for (Thread thread : threads) {
                KernelFixture.joinQuietly(thread);
            }
        }
    }
}
