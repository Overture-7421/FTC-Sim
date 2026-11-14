package dev.ftcsim.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkerLogsTest {

    @TempDir
    Path directory;

    private Path file(String name, long writtenAtMillis) throws IOException {
        Path file = Files.writeString(directory.resolve(name), "text");
        Files.setLastModifiedTime(file, FileTime.fromMillis(writtenAtMillis));
        return file;
    }

    private List<String> names() throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(file -> file.getFileName().toString()).sorted().toList();
        }
    }

    private static List<String> logs(int first, int last) {
        return IntStream.rangeClosed(first, last).mapToObj(i -> "worker-launch" + i + ".log").sorted().toList();
    }

    @Test
    void theNewestLaunchesStayAndTheOlderOnesGoWithTheirCrashReportsAndArgumentFiles() throws IOException {
        for (int i = 1; i <= 12; i++) {
            file("worker-launch" + i + ".log", i * 1000L);
        }
        file("worker-launch2.crash.log", 2_000L);
        file("worker-launch3.args", 3_000L);
        file("worker-launch12.args", 12_000L);
        file("notes.txt", 0);
        file("other.log", 0);
        file("worker.log", 0);
        Files.createDirectory(directory.resolve("worker-folder.log"));

        WorkerLogs.prune(directory, 9);

        List<String> expected = new ArrayList<>(logs(4, 12));
        expected.addAll(List.of("worker-launch12.args", "notes.txt", "other.log", "worker.log", "worker-folder.log"));
        assertEquals(expected.stream().sorted().toList(), names());
    }

    @Test
    void theNewestLaunchIsTheOneWrittenLastWhateverItsName() throws IOException {
        file("worker-a.log", 9_000);
        file("worker-z.log", 1_000);
        file("worker-m.log", 5_000);

        WorkerLogs.prune(directory, 2);

        assertEquals(List.of("worker-a.log", "worker-m.log"), names());
    }

    @Test
    void launchesWrittenAtTheSameTimeAreToldApartByTheirIds() throws IOException {
        file("worker-20261114-100000-aaaa.log", 7_000);
        file("worker-20261114-100001-bbbb.log", 7_000);

        WorkerLogs.prune(directory, 1);

        assertEquals(List.of("worker-20261114-100001-bbbb.log"), names());
    }

    @Test
    void aLogThatCannotBeDeletedStaysAndTheRestStillGo() throws IOException {
        Path locked = file("worker-old.log", 1_000);
        file("worker-older.log", 500);
        file("worker-new.log", 9_000);
        assertTrue(locked.toFile().setReadOnly());
        try {
            WorkerLogs.prune(directory, 1);

            assertTrue(names().contains("worker-new.log"));
            assertTrue(names().contains("worker-old.log"), "the file that could not be deleted stays");
            assertTrue(names().stream().noneMatch(name -> name.equals("worker-older.log")), names().toString());
        } finally {
            assertTrue(locked.toFile().setWritable(true));
        }
    }

    @Test
    void aDirectoryThatIsNotThereIsNothingToPrune() {
        WorkerLogs.prune(directory.resolve("nowhere"), 9);

        assertThrows(IllegalArgumentException.class, () -> WorkerLogs.prune(directory, -1));
    }
}
