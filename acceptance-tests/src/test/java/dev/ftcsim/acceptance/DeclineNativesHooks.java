package dev.ftcsim.acceptance;

import dev.ftcsim.contracts.hooks.RobotHooks;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Hooks for tests that do not care about time: the real clock, no sleeping, and every native library request
 * declined and remembered, which is what the simulator does with Android-only native code.
 */
final class DeclineNativesHooks implements RobotHooks {
    final List<String> declinedLibraries = new ArrayList<>();

    @Override
    public long nanoTime() {
        return System.nanoTime();
    }

    @Override
    public long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    @Override
    public void sleep(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }

    @Override
    public void sleep(long millis, int nanos) throws InterruptedException {
        Thread.sleep(millis, nanos);
    }

    @Override
    public void sleep(TimeUnit unit, long duration) throws InterruptedException {
        unit.sleep(duration);
    }

    @Override
    public void yieldThread() {
        Thread.yield();
    }

    @Override
    public void loadLibrary(String name) {
        declinedLibraries.add(name);
    }

    @Override
    public void load(String absolutePath) {
        declinedLibraries.add(absolutePath);
    }
}
