package dev.ftcsim.core.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.hooks.Hooks;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Threads that a library starts in the robot world run in real time next to the simulator. What they do must be done
 * before the robot thread takes its next turn, or the robot would see as much of it as the machine had got through.
 * Here the frames of {@link Library} are the frames of the robot world.
 */
@Timeout(60)
class WorldThreadsTest {
    private static final long MS = 1_000_000L;

    private static KernelConfig watchingLibraries(KernelConfig config) {
        return config.withWorldThreads(frame -> frame.getClassName().startsWith(Library.class.getName()), 20_000);
    }

    private static KernelConfig giveUpQuickly(KernelConfig config) {
        return config.withWorldThreads(frame -> frame.getClassName().startsWith(Library.class.getName()), 300);
    }

    private static void spinFor(long millis) {
        long end = System.nanoTime() + millis * MS;
        while (System.nanoTime() < end) {
            Thread.onSpinWait();
        }
    }

    /** A library's thread: works in steps, each of which makes visible progress, or until it is told to stop. */
    static final class Library implements Runnable {
        private final AtomicInteger progress;
        private final int steps;
        private final long stepMillis;
        private final AtomicBoolean stop;

        Library(AtomicInteger progress, int steps, long stepMillis) {
            this(progress, steps, stepMillis, null);
        }

        Library(AtomicInteger progress, int steps, long stepMillis, AtomicBoolean stop) {
            this.progress = progress;
            this.steps = steps;
            this.stepMillis = stepMillis;
            this.stop = stop;
        }

        @Override
        public void run() {
            for (int step = 0; stop != null ? !stop.get() : step < steps; step++) {
                spinFor(stepMillis);
                progress.incrementAndGet();
            }
        }
    }

    /** A robot that sleeps a millisecond and then looks at what the library has got done. */
    private static AtomicInteger robotLooksAfterASleep(KernelFixture fixture, AtomicInteger progress) {
        AtomicInteger seen = new AtomicInteger(-1);
        fixture.startRobot("robot", () -> {
            Hooks.sleep(1);
            seen.set(progress.get());
        });
        return seen;
    }

    @RepeatedTest(3)
    void aLibraryThreadThatIsRunningHasFinishedBeforeTheRobotTakesItsNextTurn() throws Exception {
        try (KernelFixture fixture = new KernelFixture(WorldThreadsTest::watchingLibraries)) {
            AtomicInteger progress = new AtomicInteger();
            AtomicInteger seen = robotLooksAfterASleep(fixture, progress);
            Thread library = new Thread(new Library(progress, 20, 10), "library");
            library.start();

            fixture.kernel.runFor(5 * MS);

            assertEquals(20, seen.get(), "the robot took its turn while the library was still at work");
            assertTrue(fixture.kernel.diagnostics().worldWaits() >= 1, "the simulator did not wait for the library");
            assertEquals(List.of(), fixture.kernel.diagnostics().problems());
            library.join();
        }
    }

    /** The robot goes on at once after it starts a thread; the thread has shown no robot code yet when the simulator looks. */
    @RepeatedTest(30)
    void aThreadThatWasStartedAMomentAgoIsWaitedForBeforeItHasShownWhatItRuns() throws Exception {
        try (KernelFixture fixture = new KernelFixture(WorldThreadsTest::watchingLibraries)) {
            AtomicInteger progress = new AtomicInteger();
            AtomicInteger seen = robotLooksAfterASleep(fixture, progress);
            Thread library = new Thread(new Library(progress, 1, 5), "library");
            library.start();

            fixture.kernel.runFor(5 * MS);

            assertEquals(1, seen.get(), "the robot took its turn before the library that had just been started had done anything");
            library.join();
        }
    }

    @Test
    void aThreadThatNeverRestsIsRecordedAsAProblemAndNotWaitedForAgain() throws Exception {
        try (KernelFixture fixture = new KernelFixture(WorldThreadsTest::giveUpQuickly)) {
            AtomicBoolean stop = new AtomicBoolean();
            Thread library = new Thread(new Library(new AtomicInteger(), 0, 1, stop), "busy-library");
            library.start();
            try {
                fixture.kernel.runFor(MS);
                fixture.kernel.runFor(MS);

                List<String> problems = fixture.kernel.diagnostics().problems();
                assertEquals(1, problems.size(), problems.toString());
                assertTrue(problems.get(0).contains("'busy-library'"), problems.get(0));
                assertTrue(problems.get(0).contains(Library.class.getName()), "it says where the thread was: " + problems.get(0));
                assertEquals(1, fixture.kernel.diagnostics().worldWaits());
            } finally {
                stop.set(true);
                library.join();
            }
        }
    }

    @Test
    void threadsThatWaitAndThreadsThatRunNoRobotCodeAreNotWaitedFor() throws Exception {
        try (KernelFixture fixture = new KernelFixture(WorldThreadsTest::watchingLibraries)) {
            CountDownLatch release = new CountDownLatch(1);
            Thread waiting = new Thread(() -> awaitQuietly(release), "waiting");
            AtomicBoolean stop = new AtomicBoolean();
            Thread busy = new Thread(() -> {
                while (!stop.get()) {
                    Thread.onSpinWait();
                }
            }, "busy-elsewhere");
            waiting.start();
            busy.start();
            try {
                spinFor(150);

                fixture.kernel.runFor(MS);

                assertEquals(0, fixture.kernel.diagnostics().worldWaits());
                assertEquals(List.of(), fixture.kernel.diagnostics().problems());
            } finally {
                stop.set(true);
                release.countDown();
                busy.join();
                waiting.join();
            }
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
