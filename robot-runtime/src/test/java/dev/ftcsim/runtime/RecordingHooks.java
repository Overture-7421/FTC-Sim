package dev.ftcsim.runtime;

import dev.ftcsim.contracts.hooks.RobotHooks;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Records every hook call and answers the clock from a fixed value, so tests can see exactly what ran. */
final class RecordingHooks implements RobotHooks {
    final List<String> calls = new ArrayList<>();
    /** Monitor acquisitions and releases, kept apart because almost every probe synchronises something. */
    final List<String> locks = new ArrayList<>();
    /** How many backward jumps were executed, kept apart for the same reason. */
    int backEdges;
    long nanos = 987_654_321L;
    long millis = 123_456L;

    @Override
    public long nanoTime() {
        calls.add("nanoTime");
        return nanos;
    }

    @Override
    public long currentTimeMillis() {
        calls.add("currentTimeMillis");
        return millis;
    }

    @Override
    public void sleep(long millis) {
        calls.add("sleep(" + millis + ")");
    }

    @Override
    public void sleep(long millis, int nanos) {
        calls.add("sleep(" + millis + "," + nanos + ")");
    }

    @Override
    public void sleep(TimeUnit unit, long duration) {
        calls.add("sleep(" + unit + "," + duration + ")");
    }

    @Override
    public void yieldThread() {
        calls.add("yield");
    }

    @Override
    public void loadLibrary(String name) {
        calls.add("loadLibrary(" + name + ")");
    }

    @Override
    public void load(String absolutePath) {
        calls.add("load(" + absolutePath + ")");
    }

    @Override
    public void wait(Object monitor, long timeoutMillis, int nanos) {
        calls.add("wait(" + timeoutMillis + "," + nanos + ")");
    }

    @Override
    public void notify(Object monitor) {
        calls.add("notify");
    }

    @Override
    public void notifyAll(Object monitor) {
        calls.add("notifyAll");
    }

    @Override
    public void lockAcquired() {
        locks.add("acquired");
    }

    @Override
    public void lockReleased() {
        locks.add("released");
    }

    @Override
    public void threadStarted() {
        calls.add("threadStarted");
    }

    @Override
    public void backEdge() {
        backEdges++;
    }
}
