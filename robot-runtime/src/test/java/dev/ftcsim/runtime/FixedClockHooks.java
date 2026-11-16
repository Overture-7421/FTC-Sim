package dev.ftcsim.runtime;

import dev.ftcsim.contracts.hooks.RobotHooks;
import java.util.concurrent.TimeUnit;

/** A clock that stands still at a given instant, which is all the time hooks need. */
final class FixedClockHooks implements RobotHooks {
    final long epochMillis;

    FixedClockHooks(long epochMillis) {
        this.epochMillis = epochMillis;
    }

    @Override
    public long nanoTime() {
        return 0;
    }

    @Override
    public long currentTimeMillis() {
        return epochMillis;
    }

    @Override
    public long currentTimeNanos() {
        return epochMillis * 1_000_000L;
    }

    @Override
    public void sleep(long millis) {}

    @Override
    public void sleep(long millis, int nanos) {}

    @Override
    public void sleep(TimeUnit unit, long duration) {}

    @Override
    public void yieldThread() {}

    @Override
    public void loadLibrary(String name) {}

    @Override
    public void load(String absolutePath) {}
}
