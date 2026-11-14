package dev.ftcsim.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The places to look for a JDK on this computer, which are defined once, in the launcher's {@code jdk-manifest.json}
 * (the {@code discovery.directoryPatterns} that the launcher itself searches): the build copies that file into this
 * module as a resource. Patterns are paths with {@code %VARIABLES%} and wildcards.
 */
final class JdkPatterns {
    /** The manifest's sources that are folders on disk, in the order the manifest lists them. The registry and the path are not searched. */
    private static final Set<String> SOURCES = Set.of("CommonDirs", "AndroidStudio");

    private JdkPatterns() {}

    /** The patterns of the manifest that came with this application. */
    static List<String> bundled() throws IOException {
        try (InputStream manifest = JdkPatterns.class.getResourceAsStream("jdk-manifest.json")) {
            if (manifest == null) {
                throw new IOException("the application lacks its list of places to look for a JDK (jdk-manifest.json)");
            }
            return read(manifest);
        }
    }

    static List<String> read(InputStream manifest) throws IOException {
        JsonNode entries = new ObjectMapper().readTree(manifest).path("discovery").path("directoryPatterns");
        if (!entries.isArray()) {
            throw new IOException("jdk-manifest.json has no discovery.directoryPatterns list");
        }
        List<String> patterns = new ArrayList<>();
        for (JsonNode entry : entries) {
            if (SOURCES.contains(entry.path("source").asText()) && !entry.path("pattern").asText().isBlank()) {
                patterns.add(entry.path("pattern").asText());
            }
        }
        return patterns;
    }
}
