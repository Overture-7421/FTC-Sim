package dev.ftcsim.core.kernel;

import java.util.ArrayList;
import java.util.List;

/**
 * The fixed-step part of the world as a set of tasks that each run at their own fixed rate: physics at one
 * rate, the hubs and the plants wired to them at another. Step {@code n} of a task at {@code r} steps per
 * second happens at {@code floor(n * 1e9 / r)} nanoseconds, computed exactly, so no rounding error accumulates
 * and every run sees the same instants. Tasks that fall due at the same instant run in the order they were
 * added, which is therefore part of the model.
 */
public final class FixedRateWorld implements WorldStepper {

    /** Work done at each step of one task. */
    @FunctionalInterface
    public interface Task {
        /**
         * Advances the task's state from the instant of the previous step to {@code timeNanos}.
         *
         * @param stepIndex 1 for the first step, which is due one period after time zero
         */
        void step(long stepIndex, long timeNanos);
    }

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final List<Schedule> schedules = new ArrayList<>();

    /** Adds a task that runs {@code stepsPerSecond} times per second of simulated time, starting at time zero. */
    public FixedRateWorld every(long stepsPerSecond, Task task) {
        if (stepsPerSecond <= 0 || stepsPerSecond > NANOS_PER_SECOND) {
            throw new IllegalArgumentException("a task runs between 1 and 1e9 times per second: " + stepsPerSecond);
        }
        schedules.add(new Schedule(stepsPerSecond, task));
        return this;
    }

    @Override
    public long nextStepNanos() {
        long next = Long.MAX_VALUE;
        for (Schedule schedule : schedules) {
            next = Math.min(next, schedule.nextDue());
        }
        return next;
    }

    @Override
    public void advanceTo(long nanos) {
        for (;;) {
            Schedule due = null;
            for (Schedule schedule : schedules) {
                if (schedule.nextDue() <= nanos && (due == null || schedule.nextDue() < due.nextDue())) {
                    due = schedule;
                }
            }
            if (due == null) {
                return;
            }
            due.run();
        }
    }

    private static final class Schedule {
        private final long stepsPerSecond;
        private final Task task;
        private long executed;

        Schedule(long stepsPerSecond, Task task) {
            this.stepsPerSecond = stepsPerSecond;
            this.task = task;
        }

        long nextDue() {
            return Math.multiplyExact(executed + 1, NANOS_PER_SECOND) / stepsPerSecond;
        }

        void run() {
            long time = nextDue();
            executed++;
            task.step(executed, time);
        }
    }
}
