package dev.ftcsim.runtime.fixture;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/** Every way robot code waits or reads wall-clock time. */
public final class WaitingProbe implements Callable<String> {
    @Override
    public String call() throws InterruptedException {
        Thread.sleep(5);
        TimeUnit.MILLISECONDS.sleep(7);
        Thread.sleep(1, 5);
        Thread.yield();
        return Long.toString(System.currentTimeMillis());
    }
}
