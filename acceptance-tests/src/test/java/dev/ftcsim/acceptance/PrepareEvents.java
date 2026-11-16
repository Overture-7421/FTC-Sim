package dev.ftcsim.acceptance;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.ui.host.ProgramPreparer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** What the window would be told while a project is prepared. */
final class PrepareEvents implements ProgramPreparer.Listener {
    final List<ProgramPreparer.Step> steps = Collections.synchronizedList(new ArrayList<>());
    final List<String> output = Collections.synchronizedList(new ArrayList<>());
    final CountDownLatch ended = new CountDownLatch(1);
    volatile ProgramPreparer.Prepared prepared;
    volatile ProgramPreparer.Failure failure;

    @Override
    public void step(ProgramPreparer.Step step) {
        steps.add(step);
    }

    @Override
    public void output(String line) {
        output.add(line);
    }

    @Override
    public void finished(ProgramPreparer.Prepared program) {
        prepared = program;
        ended.countDown();
    }

    @Override
    public void failed(ProgramPreparer.Failure latest) {
        failure = latest;
        ended.countDown();
    }

    /** Waits for the preparation to end and fails, with the end of its output, if it did not succeed. */
    PrepareEvents await(Duration patience) throws InterruptedException {
        assertTrue(ended.await(patience.toSeconds(), TimeUnit.SECONDS), "the preparation did not end; steps " + steps);
        assertNull(failure, () -> "the preparation failed: " + failure + "; its output ended with " + output.subList(Math.max(0, output.size() - 15), output.size()));
        return this;
    }

    /** The label of the last step, which says whether the program was built or taken from a build that was already there. */
    String lastStep() {
        return steps.get(steps.size() - 1).label();
    }
}
