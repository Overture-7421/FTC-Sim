package dev.ftcsim.app;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** The folders that a path with wildcards names, such as {@code C:\Program Files\Android\Android Studio*\jbr}. */
final class PathPatterns {
    private PathPatterns() {}

    /**
     * The existing folders that match, in the order the disk lists them. A {@code *} or {@code ?} in one segment of the
     * path matches within that segment only. Only a path that starts with a drive letter is looked at, and one that
     * names nothing matches nothing. (A wildcard is not a character of a Windows path, so the pattern is not parsed as one.)
     */
    static List<Path> folders(String pattern) {
        String[] segments = pattern.split("[\\\\/]+");
        if (segments.length == 0 || !segments[0].matches("[A-Za-z]:")) {
            return List.of();
        }
        List<Path> current;
        try {
            current = List.of(Path.of(segments[0] + "\\"));
        } catch (InvalidPathException invalid) {
            return List.of();
        }
        for (int i = 1; i < segments.length; i++) {
            String name = segments[i];
            List<Path> next = new ArrayList<>();
            for (Path base : current) {
                if (name.indexOf('*') < 0 && name.indexOf('?') < 0) {
                    try {
                        Path child = base.resolve(name);
                        if (Files.exists(child)) {
                            next.add(child);
                        }
                    } catch (InvalidPathException invalid) {
                        // Not a name a folder can have, so none matches.
                    }
                    continue;
                }
                try (DirectoryStream<Path> children = Files.newDirectoryStream(base, name)) {
                    children.forEach(next::add);
                } catch (IOException | RuntimeException unreadable) {
                    // A folder that cannot be listed has no JDK that can be used from it.
                }
            }
            current = next;
        }
        return current.stream().filter(Files::isDirectory).toList();
    }
}
