package dev.ftcsim.runtime.fixture;

import java.util.concurrent.Callable;

/** Every way robot code waits on and notifies a monitor, on an explicit receiver, on {@code this} and by reference. */
public final class MonitorProbe implements Callable<String> {
    @Override
    public String call() throws InterruptedException {
        Object monitor = new Object();
        synchronized (monitor) {
            monitor.wait();
            monitor.wait(5);
            monitor.wait(6, 7);
            monitor.notify();
            monitor.notifyAll();
        }
        synchronized (this) {
            wait(1);
            notifyAll();
        }
        Runnable notifier = monitor::notifyAll;
        synchronized (monitor) {
            notifier.run();
        }
        return "done";
    }
}
