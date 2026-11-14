package dev.ftcsim.importer.javares;

import dev.ftcsim.importer.io.ZipBuilder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Merges the Java resources of a program's libraries and of the project itself into one jar, the way the Android
 * Gradle plugin merges them into an APK (see {@link PackagingRules}): on a device {@code ClassLoader.getResource}
 * finds an image that a library ships, and {@code ServiceLoader} finds the provider files of all libraries, because
 * the build puts those files into the APK. The class jars that the plugin hands to the compiler no longer hold them.
 *
 * <p>Sources are given in priority order: where two of them hold a file of the same name the first one is kept. The
 * jar's bytes depend only on the files and their order: entries are sorted by name and carry one fixed time stamp.
 */
public final class JavaResourceMerger {

    /**
     * Somewhere Java resources come from.
     *
     * @param name what it is, for messages: a library id or a Gradle path
     * @param root a jar, or a folder whose files are the resources
     */
    public record Source(String name, Path root) {}

    /**
     * A file that several sources hold with different contents; only the first one is in the merged jar, as it would
     * be on a class path. The plugin stops the build over such a file unless the project says which one to take.
     *
     * @param path     the file's path inside the jar
     * @param keptFrom the source whose file is kept
     * @param others   the sources whose files differ and were dropped
     */
    public record Conflict(String path, String keptFrom, List<String> others) {}

    /**
     * @param files       the files in the merged jar
     * @param mergedFiles how many of them were made by joining files of several sources
     * @param conflicts   the files that differ between sources
     */
    public record Result(int files, int mergedFiles, List<Conflict> conflicts) {}

    private JavaResourceMerger() {}

    /**
     * Writes the merged resources to {@code target}, unless there are none, in which case nothing is written.
     *
     * @param sources in priority order
     */
    public static Result merge(List<Source> sources, PackagingRules rules, Path target) throws IOException {
        Collector collector = new Collector(rules);
        for (Source source : sources) {
            if (Files.isDirectory(source.root())) {
                readFolder(source, collector);
            } else {
                readJar(source, collector);
            }
        }
        List<Conflict> conflicts = collector.conflicts();
        int files = collector.kept.size() + collector.merged.size();
        if (files == 0) {
            return new Result(0, 0, conflicts);
        }
        ZipBuilder jar = new ZipBuilder();
        collector.kept.forEach((name, first) -> jar.add(name, first.content));
        collector.merged.forEach((name, parts) -> jar.add(name, join(parts)));
        Files.createDirectories(target.toAbsolutePath().getParent());
        jar.writeTo(target);
        return new Result(files, collector.merged.size(), conflicts);
    }

    private static void readFolder(Source source, Collector collector) throws IOException {
        try (Stream<Path> walk = Files.walk(source.root())) {
            for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                String name = source.root().relativize(file).toString().replace('\\', '/');
                collector.offer(source.name(), name, () -> Files.readAllBytes(file));
            }
        }
    }

    private static void readJar(Source source, Collector collector) throws IOException {
        try (ZipFile jar = new ZipFile(source.root().toFile())) {
            for (ZipEntry entry : Collections.list(jar.entries())) {
                if (!entry.isDirectory()) {
                    collector.offer(source.name(), entry.getName(), () -> {
                        try (InputStream in = jar.getInputStream(entry)) {
                            return in.readAllBytes();
                        }
                    });
                }
            }
        }
    }

    /**
     * The files one after the other, with a line feed between two of them unless the first ends with one, so that a
     * file of lines, such as a list of service providers, stays one line per entry.
     */
    private static byte[] join(List<byte[]> parts) {
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        for (int i = 0; i < parts.size(); i++) {
            byte[] part = parts.get(i);
            if (i > 0) {
                byte[] before = parts.get(i - 1);
                if (before.length == 0 || before[before.length - 1] != '\n') {
                    joined.write('\n');
                }
            }
            joined.writeBytes(part);
        }
        return joined.toByteArray();
    }

    @FunctionalInterface
    private interface Content {
        byte[] read() throws IOException;
    }

    private record First(String source, byte[] content) {}

    private static final class Collector {
        private final PackagingRules rules;
        private final Map<String, First> kept = new TreeMap<>();
        private final Map<String, List<byte[]>> merged = new TreeMap<>();
        private final Map<String, List<String>> differing = new TreeMap<>();

        Collector(PackagingRules rules) {
            this.rules = rules;
        }

        void offer(String source, String name, Content content) throws IOException {
            if (!ZipBuilder.isUsableName(name)) {
                return;
            }
            switch (rules.actionFor(name)) {
                case EXCLUDE -> {}
                case MERGE -> merged.computeIfAbsent(name, key -> new ArrayList<>()).add(content.read());
                case PICK_FIRST -> {
                    if (!kept.containsKey(name)) {
                        kept.put(name, new First(source, content.read()));
                    }
                }
                case KEEP -> {
                    First first = kept.get(name);
                    if (first == null) {
                        kept.put(name, new First(source, content.read()));
                    } else if (!Arrays.equals(first.content, content.read())) {
                        List<String> others = differing.computeIfAbsent(name, key -> new ArrayList<>());
                        if (!others.contains(source)) {
                            others.add(source);
                        }
                    }
                }
            }
        }

        List<Conflict> conflicts() {
            List<Conflict> conflicts = new ArrayList<>();
            differing.forEach((name, others) -> conflicts.add(new Conflict(name, kept.get(name).source, List.copyOf(others))));
            return conflicts;
        }
    }
}
