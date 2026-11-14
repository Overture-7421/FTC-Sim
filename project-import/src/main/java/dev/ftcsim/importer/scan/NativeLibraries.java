package dev.ftcsim.importer.scan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Finds native libraries that ship with an Android library or module. They are built for the phone's ARM
 * processors, so a desktop JVM cannot load them; whether the program asks for them is another question, which
 * the scan of the program's code answers with {@link Finding.Codes#NATIVE_LIBRARY_LOAD}.
 */
public final class NativeLibraries {
    private static final String JNI = "jni/";

    private NativeLibraries() {}

    /** The {@code .so} files in the {@code jni/} folder of an AAR, as one finding, or nothing when it has none. */
    public static Optional<Finding> inAar(String libraryId, Path aar) throws IOException {
        List<String> found = new ArrayList<>();
        try (ZipFile zip = new ZipFile(aar.toFile())) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                if (!entry.isDirectory() && entry.getName().startsWith(JNI) && entry.getName().endsWith(".so")) {
                    found.add(entry.getName().substring(JNI.length()));
                }
            }
        }
        return describe(libraryId, found);
    }

    /** The {@code .so} files in a module's {@code jniLibs} folders. */
    public static Optional<Finding> inFolders(String moduleId, List<Path> folders) throws IOException {
        List<String> found = new ArrayList<>();
        for (Path folder : folders) {
            try (Stream<Path> files = Files.walk(folder)) {
                files.filter(file -> Files.isRegularFile(file) && file.getFileName().toString().endsWith(".so"))
                        .sorted()
                        .forEach(file -> found.add(folder.relativize(file).toString().replace('\\', '/')));
            }
        }
        return describe(moduleId, found);
    }

    private static Optional<Finding> describe(String subject, List<String> libraries) {
        if (libraries.isEmpty()) {
            return Optional.empty();
        }
        List<String> sorted = libraries.stream().sorted().toList();
        return Optional.of(new Finding(
                Finding.Codes.NATIVE_LIBRARY_PACKAGED,
                subject,
                "",
                "Ships " + sorted.size() + " native librar" + (sorted.size() == 1 ? "y" : "ies") + " built for Android ("
                        + String.join(", ", sorted.subList(0, Math.min(6, sorted.size()))) + (sorted.size() > 6 ? ", ..." : "")
                        + "); they cannot load in a desktop JVM."));
    }
}
