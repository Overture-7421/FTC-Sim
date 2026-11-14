package android.os;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileObserverTest {
    private static final long PATIENCE_MS = 5_000;

    @TempDir
    Path folder;

    private final BlockingQueue<String> events = new LinkedBlockingQueue<>();

    private FileObserver observe(Path path, int mask) {
        return new FileObserver(path.toString(), mask) {
            @Override
            public void onEvent(int event, String entry) {
                events.add(name(event) + " " + entry);
            }
        };
    }

    @Test
    void aWatchedDirectoryReportsTheCreationModificationAndDeletionOfItsEntries() throws Exception {
        FileObserver observer = observe(folder, FileObserver.ALL_EVENTS);
        observer.startWatching();
        try {
            Path file = folder.resolve("data.txt");
            Files.writeString(file, "one");
            awaitEvent("CREATE data.txt");
            Files.writeString(file, "two");
            awaitEvent("MODIFY data.txt");
            Files.delete(file);
            awaitEvent("DELETE data.txt");
        } finally {
            observer.stopWatching();
        }
    }

    @Test
    void aWatchedFileReportsItsModificationsWithoutAPathAndItsDeletionAsDeleteSelf() throws Exception {
        Path file = Files.writeString(folder.resolve("settings.xml"), "<a/>");
        Files.writeString(folder.resolve("other.xml"), "<b/>");
        FileObserver observer = observe(file, FileObserver.ALL_EVENTS);
        observer.startWatching();
        try {
            Files.writeString(folder.resolve("other.xml"), "<c/>");
            Files.writeString(file, "<changed/>");
            awaitEvent("MODIFY null");
            Files.delete(file);
            awaitEvent("DELETE_SELF null");
            assertNull(events.poll(200, TimeUnit.MILLISECONDS), "the sibling's change must not be reported");
        } finally {
            observer.stopWatching();
        }
    }

    @Test
    void onlyEventsInTheMaskAreDelivered() throws Exception {
        FileObserver observer = observe(folder, FileObserver.DELETE);
        observer.startWatching();
        try {
            Path file = Files.writeString(folder.resolve("data.txt"), "one");
            Files.delete(file);
            awaitEvent("DELETE data.txt");
            assertNull(events.poll(200, TimeUnit.MILLISECONDS));
        } finally {
            observer.stopWatching();
        }
    }

    @Test
    void aPathThatDoesNotExistCannotBeWatchedAndTheAttemptIsSilent() throws IOException, InterruptedException {
        Path later = folder.resolve("later");
        FileObserver observer = observe(later, FileObserver.ALL_EVENTS);
        observer.startWatching();
        Files.createDirectory(later);
        Files.writeString(later.resolve("a.txt"), "x");

        assertNull(events.poll(300, TimeUnit.MILLISECONDS));
        observer.stopWatching();
    }

    private void awaitEvent(String expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PATIENCE_MS);
        while (System.nanoTime() < deadline) {
            String event = events.poll(100, TimeUnit.MILLISECONDS);
            if (expected.equals(event)) {
                return;
            }
        }
        fail("No event '" + expected + "' within " + PATIENCE_MS + " ms");
    }

    private static String name(int event) {
        return switch (event) {
            case FileObserver.CREATE -> "CREATE";
            case FileObserver.MODIFY -> "MODIFY";
            case FileObserver.DELETE -> "DELETE";
            case FileObserver.DELETE_SELF -> "DELETE_SELF";
            default -> "0x" + Integer.toHexString(event);
        };
    }
}
