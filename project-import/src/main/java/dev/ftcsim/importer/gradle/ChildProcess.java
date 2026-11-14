package dev.ftcsim.importer.gradle;

import dev.ftcsim.importer.ImportException;
import dev.ftcsim.importer.ImportException.Failure;
import dev.ftcsim.importer.ImportProgress;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

/**
 * Runs one child process to its end: its output goes to a log file and, line by line, to the progress sink on the
 * calling thread; it is killed, with everything it started, when the import is cancelled or takes too long.
 * Starting a process is not a sandbox. The child runs with the rights of the user who runs the importer.
 */
final class ChildProcess {
    /** The lines kept in memory for diagnosis; the log file keeps all of them. */
    private static final int MAX_LINES = 50_000;
    private static final long POLL_MILLIS = 100;

    /** @param lines the output, standard error merged in, at most {@value #MAX_LINES} lines */
    record Result(int exitCode, List<String> lines) {}

    private ChildProcess() {}

    static Result run(
            List<String> command,
            Path directory,
            Map<String, String> environment,
            Path logFile,
            Duration timeout,
            ImportProgress progress)
            throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
        builder.environment().putAll(environment);
        Process process = builder.start();
        process.getOutputStream().close();

        Queue<String> pending = new ConcurrentLinkedQueue<>();
        Thread pump = new Thread(() -> pump(process, pending, logFile), "import-output");
        pump.setDaemon(true);
        pump.start();

        List<String> lines = new ArrayList<>();
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (!process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                drain(pending, lines, progress);
                if (progress.isCancelled()) {
                    kill(process);
                    throw new ImportException(Failure.CANCELLED, "The import was cancelled.", lines(lines), logFile);
                }
                if (System.nanoTime() - deadline > 0) {
                    kill(process);
                    throw new ImportException(
                            Failure.TIMED_OUT,
                            "The project's build did not finish within " + timeout.toMinutes() + " minutes and was stopped.",
                            lines(lines),
                            logFile);
                }
            }
            pump.join(TimeUnit.SECONDS.toMillis(10));
        } catch (InterruptedException interrupted) {
            kill(process);
            Thread.currentThread().interrupt();
            throw new ImportException(Failure.CANCELLED, "The import was interrupted.", lines(lines), logFile);
        }
        drain(pending, lines, progress);
        return new Result(process.exitValue(), lines);
    }

    private static List<String> lines(List<String> lines) {
        return lines.subList(Math.max(0, lines.size() - 30), lines.size());
    }

    private static void drain(Queue<String> pending, List<String> lines, ImportProgress progress) {
        for (String line = pending.poll(); line != null; line = pending.poll()) {
            if (lines.size() < MAX_LINES) {
                lines.add(line);
            }
            progress.message(line);
        }
    }

    private static void pump(Process process, Queue<String> pending, Path logFile) {
        try (BufferedReader output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                BufferedWriter log = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8)) {
            for (String line = output.readLine(); line != null; line = output.readLine()) {
                log.write(line);
                log.newLine();
                pending.add(line);
            }
        } catch (IOException e) {
            pending.add("[the importer could not read the build output: " + e + "]");
        }
    }

    /** Ends the process and the processes it started, such as the single-use Gradle daemon. */
    private static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
