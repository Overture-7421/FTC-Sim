package dev.ftcsim.importer.snapshot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Which parts of a project folder are the project's own source and build files. Everything else is an output of
 * some tool, or belongs to this machine rather than to the project, and is neither copied nor hashed:
 * version-control and IDE folders, Gradle's caches, native build folders, the {@code build} folder of every
 * Gradle project, IntelliJ module files, {@code local.properties}, which names the Android SDK of whoever
 * wrote it. Links (symbolic links and Windows junctions) are never followed, because they can lead out of the
 * project; they are reported to the visitor instead.
 */
final class ProjectFiles {
    private static final Set<String> TOOL_DIRECTORIES =
            Set.of(".git", ".gradle", ".idea", ".vscode", ".vs", ".kotlin", ".cxx", ".externalNativeBuild", "captures");
    private static final Set<String> MACHINE_FILES = Set.of("local.properties", ".git");
    private static final Set<String> BUILD_SCRIPTS =
            Set.of("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts");

    interface Visitor {
        /** @param relative the path below the root, with '/' separators */
        void file(String relative, Path file, BasicFileAttributes attributes) throws IOException;

        void link(String relative) throws IOException;
    }

    private ProjectFiles() {}

    /** Visits the project's files in path order. */
    static void walk(Path root, Visitor visitor) throws IOException {
        walkDirectory(root, "", visitor);
    }

    private static void walkDirectory(Path directory, String prefix, Visitor visitor) throws IOException {
        List<Path> children;
        try (Stream<Path> listing = Files.list(directory)) {
            children = listing.sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
        }
        boolean gradleProject = children.stream().anyMatch(child -> BUILD_SCRIPTS.contains(child.getFileName().toString()));
        for (Path child : children) {
            String name = child.getFileName().toString();
            BasicFileAttributes attributes = Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther()) {
                if (!isToolDirectory(name, gradleProject) && !MACHINE_FILES.contains(name)) {
                    visitor.link(prefix + name);
                }
            } else if (attributes.isDirectory()) {
                if (!isToolDirectory(name, gradleProject)) {
                    walkDirectory(child, prefix + name + "/", visitor);
                }
            } else if (attributes.isRegularFile() && !MACHINE_FILES.contains(name) && !name.endsWith(".iml")) {
                visitor.file(prefix + name, child, attributes);
            }
        }
    }

    private static boolean isToolDirectory(String name, boolean insideGradleProject) {
        return TOOL_DIRECTORIES.contains(name) || (insideGradleProject && name.equals("build"));
    }

    /** Removes the empty folders below {@code root} (not {@code root} itself), leaving tool folders alone. */
    static void deleteEmptyDirectories(Path root) throws IOException {
        List<Path> children;
        try (Stream<Path> listing = Files.list(root)) {
            children = listing.toList();
        }
        boolean gradleProject = children.stream().anyMatch(child -> BUILD_SCRIPTS.contains(child.getFileName().toString()));
        for (Path child : children) {
            BasicFileAttributes attributes = Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isDirectory() && !attributes.isOther() && !isToolDirectory(child.getFileName().toString(), gradleProject)) {
                deleteEmptyDirectories(child);
                try (Stream<Path> remaining = Files.list(child)) {
                    if (remaining.findAny().isEmpty()) {
                        Files.delete(child);
                    }
                }
            }
        }
    }
}
