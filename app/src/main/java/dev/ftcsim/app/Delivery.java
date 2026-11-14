package dev.ftcsim.app;

import dev.ftcsim.ui.host.ProgramPreparer.Failure;
import dev.ftcsim.ui.host.ProgramPreparer.Listener;
import dev.ftcsim.ui.host.ProgramPreparer.Prepared;
import dev.ftcsim.ui.host.ProgramPreparer.Step;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Hands the events of one preparation to its listener on the thread of an executor, so that they come one at a time
 * and in order, and ends them with the first of {@code finished} and {@code failed}: whatever comes after is dropped.
 */
final class Delivery {
    private final Listener listener;
    private final Executor events;
    private boolean ended;

    Delivery(Listener listener, Executor events) {
        this.listener = listener;
        this.events = events;
    }

    synchronized void step(Step step) {
        post(() -> listener.step(step), false);
    }

    synchronized void output(String line) {
        post(() -> listener.output(line), false);
    }

    synchronized void finished(Prepared program) {
        post(() -> listener.finished(program), true);
    }

    synchronized void failed(Failure failure) {
        post(() -> listener.failed(failure), true);
    }

    private void post(Runnable event, boolean last) {
        if (ended) {
            return;
        }
        ended = last;
        try {
            events.execute(() -> {
                try {
                    event.run();
                } catch (RuntimeException failure) {
                    System.err.println("FTC-Sim: the listener of a preparation threw " + failure);
                }
            });
        } catch (RejectedExecutionException closed) {
            // The application is closing, and nobody is listening any more.
        }
    }
}
