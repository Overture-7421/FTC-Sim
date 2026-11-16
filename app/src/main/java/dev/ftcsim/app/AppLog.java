package dev.ftcsim.app;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * The log of one start of the application: {@code logs\app-<yyyyMMdd-HHmmss>.log}, which gets everything the program
 * writes to standard output and standard error, in the order it was written, while the console (when there is one) shows
 * it too. A student who has a problem sends the newest files of the folder. The 20 newest application logs are kept; the
 * worker's logs in the same folder are kept by the worker's launcher.
 */
final class AppLog implements AutoCloseable {

    static final int KEPT = 20;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);
    private static final int NAME_ATTEMPTS = 100;

    private final Path file;
    private final OutputStream stream;

    private AppLog(Path file, OutputStream stream) {
        this.file = file;
        this.stream = stream;
    }

    /** Creates the log of a start that happens at {@code now}, under a name no other start has used, and prunes the old ones. */
    static AppLog create(Path logs, LocalDateTime now) throws IOException {
        Files.createDirectories(logs);
        prune(logs);
        String stamp = STAMP.format(now);
        for (int attempt = 0; attempt < NAME_ATTEMPTS; attempt++) {
            Path candidate = logs.resolve("app-" + stamp + (attempt == 0 ? "" : "-" + attempt) + ".log");
            try {
                return new AppLog(candidate, Files.newOutputStream(candidate, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE));
            } catch (FileAlreadyExistsException taken) {
                // Another start in the same second: the next name.
            }
        }
        throw new IOException("no free log name in " + logs);
    }

    /** Keeps the newest {@code KEPT - 1} logs, so that the one about to be created makes {@code KEPT}. */
    private static void prune(Path logs) throws IOException {
        List<Path> old;
        try (Stream<Path> files = Files.list(logs)) {
            old = files.filter(file -> {
                String name = file.getFileName().toString();
                return name.startsWith("app-") && name.endsWith(".log") && Files.isRegularFile(file);
            }).sorted(Comparator.comparing(AppLog::modified).reversed()).skip(KEPT - 1).toList();
        }
        for (Path file : old) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException inUse) {
                // A log another start still holds open stays until the next start.
            }
        }
    }

    private static long modified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException gone) {
            return 0;
        }
    }

    Path file() {
        return file;
    }

    /** A stream that writes to {@code console} and to the log, UTF-8 in the log. */
    PrintStream tee(PrintStream console) {
        return new PrintStream(new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                console.write(b);
                stream.write(b);
            }

            @Override
            public void write(byte[] bytes, int offset, int length) throws IOException {
                console.write(bytes, offset, length);
                stream.write(bytes, offset, length);
            }

            @Override
            public void flush() throws IOException {
                console.flush();
                stream.flush();
            }
        }, true, StandardCharsets.UTF_8);
    }

    @Override
    public void close() throws IOException {
        stream.close();
    }
}
