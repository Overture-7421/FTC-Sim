package dev.ftcsim.link;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A worker process this program started: the {@link Process}, the threads that read what it writes, and what the
 * launch left in temporary places. Everything the worker writes to its standard output and standard error goes to a
 * log file and, bounded, to a ring of recent lines that failure messages quote.
 */
final class LaunchedWorker implements WorkerHandle {

    private static final int RING_LINES = 200;
    private static final int MAX_LINE_CHARS = 4000;
    private static final long MAX_LOG_CHARS = 16L * 1024 * 1024;
    private static final int ERROR_LINES_SHOWN = 20;
    private static final Duration OUTPUT_GRACE = Duration.ofSeconds(2);

    private final Process process;
    private final Path logFile;
    private final Path crashReport;
    private final List<Path> temporary;
    private final LineRing recent = new LineRing(RING_LINES);
    private final Log log;
    private final Thread outputPump;
    private final Thread errorPump;
    private final AtomicBoolean released = new AtomicBoolean();

    /**
     * @param temporary files and directories that exist only for this launch and are removed when it is over
     */
    LaunchedWorker(Process process, Path logFile, Path crashReport, List<Path> temporary) throws IOException {
        this.process = process;
        this.logFile = logFile;
        this.crashReport = crashReport;
        this.temporary = List.copyOf(temporary);
        this.log = new Log(logFile);
        this.outputPump = startPump("stdout", process.getInputStream(), false);
        this.errorPump = startPump("stderr", process.getErrorStream(), true);
    }

    /** Hands the worker its token on standard input. A worker that has already died is reported by the accept loop. */
    void sendToken(String token) {
        try {
            process.getOutputStream().write((token + "\n").getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().flush();
        } catch (IOException gone) {
            // The process is gone; the caller finds out when it waits for it to connect.
        }
    }

    @Override
    public long pid() {
        return process.pid();
    }

    @Override
    public boolean isAlive() {
        return process.isAlive();
    }

    @Override
    public OptionalInt exitCode() {
        try {
            return OptionalInt.of(process.exitValue());
        } catch (IllegalThreadStateException running) {
            return OptionalInt.empty();
        }
    }

    @Override
    public boolean awaitExit(Duration timeout) throws InterruptedException {
        return process.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    @Override
    public void destroyForcibly() {
        process.destroyForcibly();
    }

    @Override
    public String describeEnd(String whatHappened) {
        StringBuilder text = new StringBuilder(whatHappened);
        try {
            if (process.waitFor(1, TimeUnit.SECONDS)) {
                text.append(" (the worker exited with code ").append(process.exitValue()).append(')');
            } else {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                text.append(" (the worker was still running and has been stopped)");
            }
            joinPumps();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        text.append('.');
        List<String> errors = recent.errorLines(ERROR_LINES_SHOWN);
        if (errors.isEmpty()) {
            text.append(" It wrote nothing to its error output.");
        } else {
            text.append(" The last lines of its error output:");
            errors.forEach(line -> text.append("\n  ").append(line));
        }
        if (Files.isRegularFile(crashReport)) {
            text.append("\nThe Java runtime wrote a crash report to ").append(crashReport).append('.');
        }
        return text.append("\nThe worker's log is ").append(logFile).append('.').toString();
    }

    @Override
    public Optional<Path> logFile() {
        return Optional.of(logFile);
    }

    @Override
    public void release() {
        if (process.isAlive() || !released.compareAndSet(false, true)) {
            return;
        }
        try {
            joinPumps();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        log.close();
        temporary.forEach(WorkerEnvironment::deleteTree);
    }

    private void joinPumps() throws InterruptedException {
        outputPump.join(OUTPUT_GRACE.toMillis());
        errorPump.join(OUTPUT_GRACE.toMillis());
    }

    private Thread startPump(String stream, InputStream source, boolean error) {
        Thread thread = new Thread(() -> copyLines(stream, source, error), "ftcsim-worker-" + stream);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /** Reads lines of at most {@link #MAX_LINE_CHARS}: a worker that writes without ever ending a line costs no memory. */
    private void copyLines(String stream, InputStream source, boolean error) {
        StringBuilder line = new StringBuilder();
        boolean cut = false;
        char[] chunk = new char[4096];
        try (Reader reader = new InputStreamReader(source, StandardCharsets.UTF_8)) {
            for (int read = reader.read(chunk); read >= 0; read = reader.read(chunk)) {
                for (int i = 0; i < read; i++) {
                    char c = chunk[i];
                    if (c == '\n') {
                        record(stream, error, line, cut);
                        line.setLength(0);
                        cut = false;
                    } else if (c != '\r') {
                        if (line.length() < MAX_LINE_CHARS) {
                            line.append(c);
                        } else {
                            cut = true;
                        }
                    }
                }
            }
        } catch (IOException ended) {
            // The process is gone and its pipe with it.
        }
        if (line.length() > 0) {
            record(stream, error, line, cut);
        }
    }

    private void record(String stream, boolean error, StringBuilder line, boolean cut) {
        String text = cut ? line + "…" : line.toString();
        recent.add(error, text);
        log.write(stream, text);
    }

    /** The worker's log file, capped so that a worker that never stops talking cannot fill the disk. */
    private static final class Log {
        private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

        private final BufferedWriter writer;
        private long written;
        private boolean stopped;

        Log(Path file) throws IOException {
            writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
        }

        synchronized void write(String stream, String text) {
            if (stopped) {
                return;
            }
            try {
                written += text.length() + 20;
                if (written > MAX_LOG_CHARS) {
                    stopped = true;
                    writer.write("... the log stops here: it reached " + MAX_LOG_CHARS / (1024 * 1024) + " MiB\n");
                } else {
                    writer.write(CLOCK.format(LocalTime.now()) + " [" + stream + "] " + text + "\n");
                }
                writer.flush();
            } catch (IOException diskProblem) {
                stopped = true;
            }
        }

        synchronized void close() {
            stopped = true;
            try {
                writer.close();
            } catch (IOException ignored) {
                // There is nothing left to write to it.
            }
        }
    }
}
