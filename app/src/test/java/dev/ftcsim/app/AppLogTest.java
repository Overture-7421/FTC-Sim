package dev.ftcsim.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppLogTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 16, 9, 5, 7);

    @TempDir
    Path logs;

    private List<String> names() throws IOException {
        try (Stream<Path> files = Files.list(logs)) {
            return files.map(file -> file.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void everyStartGetsALogOfItsOwnNamedByTheTimeEvenWhenTwoStartInTheSameSecond() throws IOException {
        try (AppLog first = AppLog.create(logs.resolve("new folder"), NOW); AppLog second = AppLog.create(logs.resolve("new folder"), NOW)) {
            assertEquals(logs.resolve("new folder").resolve("app-20261116-090507.log"), first.file());
            assertEquals(logs.resolve("new folder").resolve("app-20261116-090507-1.log"), second.file());
        }
    }

    @Test
    void whatIsPrintedGoesToTheConsoleAndToTheLogAsUtf8InTheOrderItWasWritten() throws IOException {
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        try (AppLog log = AppLog.create(logs, NOW)) {
            PrintStream out = log.tee(new PrintStream(console, true, StandardCharsets.UTF_8));
            out.println("one");
            out.print("Zoë ");
            out.println("two");
            String expected = "one" + System.lineSeparator() + "Zoë two" + System.lineSeparator();
            assertEquals(expected, Files.readString(log.file(), StandardCharsets.UTF_8), "the log is complete while the program runs");
            assertEquals(expected, console.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void onlyTheNewestTwentyApplicationLogsAreKeptAndOtherFilesAreLeftAlone() throws IOException {
        for (int i = 0; i < 25; i++) {
            Path log = Files.writeString(logs.resolve(String.format("app-2026010%d-%06d.log", 1, i)), "old");
            Files.setLastModifiedTime(log, FileTime.fromMillis(1_000_000L + i * 1000L));
        }
        Files.writeString(logs.resolve("worker-20260101-000000-1.log"), "the worker's");
        Files.writeString(logs.resolve("hs_err_pid1.log"), "a crash");

        try (AppLog current = AppLog.create(logs, NOW)) {
            List<String> names = names();
            assertEquals(20, names.stream().filter(name -> name.startsWith("app-")).count(), names.toString());
            assertTrue(names.contains(current.file().getFileName().toString()), "the new log is one of them");
            assertTrue(names.contains("app-20260101-000024.log") && names.contains("app-20260101-000006.log"), "the newest old ones stay: " + names);
            assertFalse(names.contains("app-20260101-000005.log"), "the oldest go: " + names);
            assertTrue(names.contains("worker-20260101-000000-1.log") && names.contains("hs_err_pid1.log"), names.toString());
        }
    }
}
