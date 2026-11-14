package dev.ftcsim.importer.scan;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** The class files of directories and jars, read without loading a class. */
final class ClassFiles {
    private static final String SUFFIX = ".class";

    /** @param internalName the class's name with '/' separators, for example {@code com/acme/Foo$Bar} */
    interface Consumer {
        void accept(String internalName, byte[] bytes) throws IOException;
    }

    private ClassFiles() {}

    /** Whether a path inside a jar or folder is a class a class loader could load. */
    static boolean isClass(String entryName) {
        return entryName.endsWith(SUFFIX)
                && !entryName.startsWith("META-INF/")
                && !entryName.endsWith("module-info.class")
                && !entryName.endsWith("package-info.class");
    }

    static String internalName(String entryName) {
        return entryName.substring(0, entryName.length() - SUFFIX.length());
    }

    /** The names of the classes in a directory or jar, in name order. */
    static List<String> names(Path root) throws IOException {
        if (Files.isDirectory(root)) {
            try (Stream<Path> files = Files.walk(root)) {
                return files.filter(Files::isRegularFile)
                        .map(file -> root.relativize(file).toString().replace('\\', '/'))
                        .filter(ClassFiles::isClass)
                        .map(ClassFiles::internalName)
                        .sorted()
                        .toList();
            }
        }
        try (ZipFile jar = new ZipFile(root.toFile())) {
            return Collections.list(jar.entries()).stream()
                    .filter(entry -> !entry.isDirectory() && isClass(entry.getName()))
                    .map(entry -> internalName(entry.getName()))
                    .sorted()
                    .toList();
        }
    }

    /** Gives every class of the directory or jar, in name order, to the consumer. */
    static void forEach(Path root, Consumer consumer) throws IOException {
        if (Files.isDirectory(root)) {
            for (String name : names(root)) {
                consumer.accept(name, Files.readAllBytes(root.resolve(name + SUFFIX)));
            }
            return;
        }
        try (ZipFile jar = new ZipFile(root.toFile())) {
            for (String name : names(root)) {
                ZipEntry entry = jar.getEntry(name + SUFFIX);
                try (InputStream in = jar.getInputStream(entry)) {
                    consumer.accept(name, in.readAllBytes());
                }
            }
        }
    }
}
