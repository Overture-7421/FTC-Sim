package dev.ftcsim.runtime.fixture;

import java.util.concurrent.Callable;

/** Every way robot code holds a monitor: blocks, instance and static methods, with and without an exception. */
public final class LockProbe implements Callable<String> {
    private final Object monitor = new Object();

    @Override
    public String call() {
        synchronized (monitor) {
            touch();
        }
        plain();
        try {
            failing();
        } catch (IllegalStateException expected) {
            touch();
        }
        try {
            synchronized (monitor) {
                throw new IllegalStateException("inside a block");
            }
        } catch (IllegalStateException expected) {
            touch();
        }
        staticWithEveryKindOfArgument(1L, 2.0, "three", new int[0], 4f, (short) 5);
        return "done";
    }

    private void touch() {
    }

    private synchronized int plain() {
        return 1;
    }

    private synchronized void failing() {
        throw new IllegalStateException("inside a method");
    }

    private static synchronized long staticWithEveryKindOfArgument(long a, double b, String c, int[] d, float e, short f) {
        return a + (long) b + c.length() + d.length + (long) e + f;
    }
}
