package dev.ftcsim.worker;

import java.io.PrintStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Carries the log lines of the SDK and of the robot program to a stream without ever making the simulation wait for
 * the stream: a program that logs faster than the stream is drained loses lines, and the log says how many.
 */
final class WorkerLog implements Consumer<String>, AutoCloseable {

    private static final int CAPACITY = 8192;

    private final BlockingQueue<String> lines = new ArrayBlockingQueue<>(CAPACITY);
    private final AtomicLong dropped = new AtomicLong();
    private final PrintStream out;
    private final Thread thread;
    private volatile boolean closed;

    WorkerLog(PrintStream out) {
        this.out = out;
        this.thread = new Thread(this::run, "ftcsim-worker-log");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    @Override
    public void accept(String line) {
        if (!lines.offer(line)) {
            dropped.incrementAndGet();
        }
    }

    private void run() {
        try {
            while (true) {
                String line = lines.poll(50, TimeUnit.MILLISECONDS);
                if (line != null) {
                    out.println(line);
                } else if (closed) {
                    return;
                }
                long lost = dropped.getAndSet(0);
                if (lost > 0) {
                    out.println("... " + lost + " log lines were dropped because they came faster than they could be written");
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Writes what is left, for as long as that takes up to two seconds. */
    @Override
    public void close() {
        closed = true;
        try {
            thread.join(2000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        out.flush();
    }
}
