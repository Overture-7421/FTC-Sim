package dev.ftcsim.runtime;

import dev.ftcsim.contracts.hooks.RobotHooks;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** A clock that stands still at a given instant and seeds that follow from one number, which is all the time and randomness hooks need. */
final class SeededHooks implements RobotHooks {
    final long epochMillis;
    private final long seed;
    private final AtomicLong requests = new AtomicLong();
    private final Random math;

    SeededHooks(long epochMillis, long seed) {
        this.epochMillis = epochMillis;
        this.seed = seed;
        this.math = new Random(seed);
    }

    /** How many seeds robot code asked for. */
    long seedsHandedOut() {
        return requests.get();
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
    public long randomSeed() {
        return seed * 1_000_003L + requests.getAndIncrement();
    }

    @Override
    public double mathRandom() {
        return math.nextDouble();
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
