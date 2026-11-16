package dev.ftcsim.app;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The places to look for a JDK on this computer, for the project's own Gradle build: the resource {@code jdk-patterns.txt}
 * of this package. Patterns are paths with {@code %VARIABLES%} and wildcards, one a line; blank lines and lines that
 * start with {@code #} are comments. The simulator itself runs on the runtime that comes with it and uses none of them.
 */
final class JdkPatterns {
    private static final String RESOURCE = "jdk-patterns.txt";

    private JdkPatterns() {}

    /** The patterns that came with this application. */
    static List<String> bundled() throws IOException {
        try (InputStream patterns = JdkPatterns.class.getResourceAsStream(RESOURCE)) {
            if (patterns == null) {
                throw new IOException("the application lacks its list of places to look for a JDK (" + RESOURCE + ")");
            }
            return read(patterns);
        }
    }

    static List<String> read(InputStream patterns) throws IOException {
        List<String> found = new ArrayList<>();
        for (String line : new String(patterns.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
            String pattern = line.strip();
            if (!pattern.isEmpty() && !pattern.startsWith("#")) {
                found.add(pattern);
            }
        }
        if (found.isEmpty()) {
            throw new IOException(RESOURCE + " lists no place to look for a JDK");
        }
        return found;
    }
}
