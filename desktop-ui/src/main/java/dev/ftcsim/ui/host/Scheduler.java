package dev.ftcsim.ui.host;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runs things later, one at a time and in the order they are due. The fake preparer waits with one, so that a test can
 * step the time by hand and the application lets real time pass.
 */
public interface Scheduler {

    /** Runs {@code task} once, {@code delayMillis} from now, on a thread of the scheduler's own. */
    void schedule(long delayMillis, Runnable task);

    /** A scheduler against the wall clock, with one daemon thread of the given name. */
    static Scheduler realTime(String threadName) {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, threadName);
            thread.setDaemon(true);
            return thread;
        });
        return (delayMillis, task) -> executor.schedule(() -> {
            try {
                task.run();
            } catch (RuntimeException failure) {
                System.err.println("a scheduled task failed: " + failure);
            }
        }, delayMillis, TimeUnit.MILLISECONDS);
    }
}
