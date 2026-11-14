package dev.ftcsim.importer.snapshot;

import dev.ftcsim.importer.ImportProgress;
import dev.ftcsim.importer.io.Sha256;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The SHA-256 of every source and build file of a project, by path below the project folder. Taken before and
 * after an import, it is the proof that the original project was left alone.
 *
 * @param hashes lower-case hexadecimal digests by path with '/' separators, in path order
 */
public record SourceManifest(SortedMap<String, String> hashes) {

    /** What differs between two manifests of the same folder. */
    public record Differences(List<String> added, List<String> removed, List<String> changed) {
        public Differences {
            added = List.copyOf(added);
            removed = List.copyOf(removed);
            changed = List.copyOf(changed);
        }

        public boolean isEmpty() {
            return added.isEmpty() && removed.isEmpty() && changed.isEmpty();
        }

        /** The differences in a few lines, naming at most ten files of each kind. */
        public String describe() {
            StringBuilder text = new StringBuilder();
            append(text, "changed", changed);
            append(text, "added", added);
            append(text, "removed", removed);
            return text.toString().strip();
        }

        private static void append(StringBuilder text, String what, List<String> paths) {
            if (!paths.isEmpty()) {
                text.append(paths.size()).append(" file(s) ").append(what).append(": ")
                        .append(String.join(", ", paths.subList(0, Math.min(10, paths.size()))))
                        .append(paths.size() > 10 ? ", ..." : "").append('\n');
            }
        }
    }

    public SourceManifest {
        hashes = Collections.unmodifiableSortedMap(new TreeMap<>(hashes));
    }

    /** Hashes the project's files as they are now. */
    public static SourceManifest scan(Path projectDirectory, ImportProgress progress) throws IOException {
        SortedMap<String, String> hashes = new TreeMap<>();
        ProjectFiles.walk(projectDirectory, new ProjectFiles.Visitor() {
            @Override
            public void file(String relative, Path file, BasicFileAttributes attributes) throws IOException {
                progress.checkCancelled();
                hashes.put(relative, Sha256.of(file));
            }

            @Override
            public void link(String relative) {}
        });
        return new SourceManifest(hashes);
    }

    public int fileCount() {
        return hashes.size();
    }

    /** One digest for the whole tree, so two imports of identical sources can be recognised as such. */
    public String fingerprint() {
        return Sha256.of(toText().getBytes(StandardCharsets.UTF_8));
    }

    /** One line per file, {@code <digest>  <path>}, in path order. */
    public String toText() {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> entry : hashes.entrySet()) {
            text.append(entry.getValue()).append("  ").append(entry.getKey()).append('\n');
        }
        return text.toString();
    }

    /** What changed from this manifest to {@code later}. */
    public Differences compareWith(SourceManifest later) {
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, String> entry : hashes.entrySet()) {
            String after = later.hashes.get(entry.getKey());
            if (after == null) {
                removed.add(entry.getKey());
            } else if (!after.equals(entry.getValue())) {
                changed.add(entry.getKey());
            }
        }
        for (String path : later.hashes.keySet()) {
            if (!hashes.containsKey(path)) {
                added.add(path);
            }
        }
        return new Differences(added, removed, changed);
    }
}
