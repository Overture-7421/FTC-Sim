package dev.ftcsim.worker;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Carries the log lines of the SDK and of the robot program to a stream, and to the window through a sink, without ever
 * making the simulation wait for either: a program that logs faster than they are drained loses lines, and the log says how many.
 */
final class WorkerLog implements Consumer<String>, AutoCloseable {

    private static final int CAPACITY = 8192;
    private static final int MAX_BATCH = 500;

    private final BlockingQueue<String> lines = new ArrayBlockingQueue<>(CAPACITY);
    private final AtomicLong dropped = new AtomicLong();
    private final PrintStream out;
    private final Thread thread;
    private volatile boolean closed;
    private volatile Consumer<List<String>> sink = lines -> {};

    WorkerLog(PrintStream out) {
        this.out = out;
        this.thread = new Thread(this::run, "ftcsim-worker-log");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    /** Also hands every batch of lines to [sink], from the log's own thread, for the window's console. */
    void sink(Consumer<List<String>> sink) {
        this.sink = sink;
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
                List<String> batch = new ArrayList<>();
                if (line != null) {
                    batch.add(line);
                    lines.drainTo(batch, MAX_BATCH - 1);
                } else if (closed) {
                    return;
                }
                long lost = dropped.getAndSet(0);
                if (lost > 0) {
                    batch.add("... " + lost + " log lines were dropped because they came faster than they could be written");
                }
                if (!batch.isEmpty()) {
                    batch.forEach(out::println);
                    try {
                        sink.accept(batch);
                    } catch (RuntimeException failure) {
                        // The window's console is a convenience; the file and the stream above have the lines.
                    }
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
