package dev.ftcsim.link;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Keeps the log directory from growing for ever: the files of the newest launches stay and the rest go. A launch owns
 * {@code worker-<id>.log} and the {@code worker-<id>.crash.log} and {@code worker-<id>.args} files of the same id.
 */
final class WorkerLogs {

    private static final Pattern LAUNCH_FILE = Pattern.compile("worker-(.+?)(?:\\.crash)?\\.(?:log|args)");

    private WorkerLogs() {}

    /**
     * Deletes the files of every launch but the {@code keep} newest, newest being the launch whose files were written
     * last, then the one with the later id. Files that belong to no launch are left alone. Best effort: a file that
     * cannot be deleted, such as the log of a worker still running, stays, and nothing here may stop a launch.
     */
    static void prune(Path directory, int keep) {
        if (keep < 0) {
            throw new IllegalArgumentException("keep is not negative: " + keep);
        }
        Map<String, List<Path>> launches = new TreeMap<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Matcher name = LAUNCH_FILE.matcher(file.getFileName().toString());
                if (name.matches()) {
                    launches.computeIfAbsent(name.group(1), id -> new ArrayList<>()).add(file);
                }
            }
        } catch (IOException | UncheckedIOException unreadable) {
            return;
        }
        Map<String, FileTime> lastWritten = new TreeMap<>();
        launches.forEach((id, files) -> lastWritten.put(id, newest(files)));
        Comparator<String> oldestFirst = Comparator.<String, FileTime>comparing(lastWritten::get).thenComparing(Comparator.naturalOrder());
        List<String> newestFirst = launches.keySet().stream().sorted(oldestFirst.reversed()).toList();
        for (String id : newestFirst.stream().skip(keep).toList()) {
            for (Path file : launches.get(id)) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException | RuntimeException stays) {
                    // in use or protected: it goes with a later launch
                }
            }
        }
    }

    private static FileTime newest(List<Path> files) {
        FileTime newest = FileTime.fromMillis(0);
        for (Path file : files) {
            try {
                FileTime written = Files.getLastModifiedTime(file);
                if (written.compareTo(newest) > 0) {
                    newest = written;
                }
            } catch (IOException vanished) {
                // another launcher pruned it first
            }
        }
        return newest;
    }
}
