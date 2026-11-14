package dev.ftcsim.runtime.fixture;

import java.util.concurrent.Callable;

/** Reads the clock the way ordinary robot code does. */
public final class NanoTimeProbe implements Callable<Long> {
    @Override
    public Long call() {
        return System.nanoTime();
    }
}
