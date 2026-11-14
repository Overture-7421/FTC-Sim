package dev.ftcsim.hardware.lynx;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Threads that call into a bus as often as they can, as a library's own timer thread would, only far more
 * often. They begin together when the test says so and keep going until it says to finish, so that they are at
 * work during whatever the test does in between.
 */
final class Hammer {

    /** One call of one thread: which thread it is and how many calls that thread has made before. */
    @FunctionalInterface
    interface Call {
        void make(int thread, long number);
    }

    private final List<Thread> threads = new ArrayList<>();
    private final CountDownLatch go = new CountDownLatch(1);
    private final AtomicBoolean finishing = new AtomicBoolean();
    private final AtomicLong calls = new AtomicLong();
    private final Queue<Throwable> failures = new ConcurrentLinkedQueue<>();

    /** Creates the threads, which wait for {@link #start}; each makes at least {@code minimumPerThread} calls. */
    Hammer(int threadCount, long minimumPerThread, Call call) {
        for (int i = 0; i < threadCount; i++) {
            int thread = i;
            Thread worker = new Thread(() -> run(thread, minimumPerThread, call), "hammer-" + i);
            worker.setDaemon(true);
            threads.add(worker);
            worker.start();
        }
    }

    private void run(int thread, long minimum, Call call) {
        try {
            go.await();
            for (long number = 0; number < minimum || !finishing.get(); number++) {
                call.make(thread, number);
                calls.incrementAndGet();
            }
        } catch (Throwable failure) {
            failures.add(failure);
        }
    }

    /** Lets every thread begin at the same moment. */
    void start() {
        go.countDown();
    }

    /** Waits, without sleeping, until the threads have made this many calls between them. */
    void awaitCalls(long total) {
        while (calls.get() < total && failures.isEmpty()) {
            Thread.onSpinWait();
        }
    }

    long calls() {
        return calls.get();
    }

    /** Lets the threads end once each has made its minimum, waits for them, and fails if any of them did. Returns the calls made. */
    long finish() throws InterruptedException {
        finishing.set(true);
        go.countDown();
        for (Thread worker : threads) {
            worker.join(TimeUnit.SECONDS.toMillis(60));
            if (worker.isAlive()) {
                throw new AssertionError("thread " + worker.getName() + " did not end");
            }
        }
        Throwable first = failures.peek();
        if (first != null) {
            throw new AssertionError("a thread failed: " + first, first);
        }
        return calls.get();
    }
}
