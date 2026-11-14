package dev.ftcsim.contracts.hooks;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** The watchdogs of a host with no clock of its own: a real-time timer on a daemon thread. */
final class RealTimeWatchdogs {
    private static final ScheduledThreadPoolExecutor TIMER = create();

    private RealTimeWatchdogs() {}

    private static ScheduledThreadPoolExecutor create() {
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = Executors.defaultThreadFactory().newThread(task);
            thread.setName("ftcsim-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        timer.setRemoveOnCancelPolicy(true);
        return timer;
    }

    static Runnable schedule(long delayNanos, Runnable action) {
        ScheduledFuture<?> future = TIMER.schedule(action, delayNanos, TimeUnit.NANOSECONDS);
        return () -> future.cancel(false);
    }
}
