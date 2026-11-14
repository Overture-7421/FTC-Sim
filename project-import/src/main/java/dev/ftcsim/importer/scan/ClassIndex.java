package dev.ftcsim.importer.scan;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;

/**
 * Which classes a class path holds and how they extend each other, read from the class files without loading
 * any. Where two entries hold the same class the earlier one counts, as it does for a class loader.
 */
final class ClassIndex implements AutoCloseable {

    /** Whether a class extends another, when the chain of superclasses can be followed to the end. */
    enum Inheritance {
        YES,
        NO,
        /** A class on the way is not on the class path, so the chain cannot be followed. */
        UNKNOWN
    }

    private record Location(Path directory, ZipFile jar) {}

    private final Map<String, Location> classes = new HashMap<>();
    private final Map<String, Optional<String>> superNames = new HashMap<>();
    private final List<ZipFile> open = new ArrayList<>();

    private ClassIndex() {}

    /** Indexes the directories and jars, in class path order. */
    static ClassIndex of(List<Path> entries) throws IOException {
        ClassIndex index = new ClassIndex();
        try {
            for (Path entry : entries) {
                index.add(entry);
            }
        } catch (IOException | RuntimeException e) {
            index.close();
            throw e;
        }
        return index;
    }

    private void add(Path entry) throws IOException {
        if (Files.isDirectory(entry)) {
            Location location = new Location(entry, null);
            for (String name : ClassFiles.names(entry)) {
                classes.putIfAbsent(name, location);
            }
        } else if (Files.isRegularFile(entry)) {
            ZipFile jar = new ZipFile(entry.toFile());
            open.add(jar);
            Location location = new Location(null, jar);
            for (ZipEntry zipEntry : Collections.list(jar.entries())) {
                if (!zipEntry.isDirectory() && ClassFiles.isClass(zipEntry.getName())) {
                    classes.putIfAbsent(ClassFiles.internalName(zipEntry.getName()), location);
                }
            }
        }
    }

    boolean contains(String internalName) {
        return classes.containsKey(internalName);
    }

    /** The superclass of an indexed class; empty when the class is not indexed or is {@code java/lang/Object}. */
    Optional<String> superName(String internalName) {
        return superNames.computeIfAbsent(internalName, this::readSuperName);
    }

    private Optional<String> readSuperName(String internalName) {
        Location location = classes.get(internalName);
        if (location == null) {
            return Optional.empty();
        }
        try {
            byte[] bytes;
            if (location.jar() != null) {
                try (InputStream in = location.jar().getInputStream(location.jar().getEntry(internalName + ".class"))) {
                    bytes = in.readAllBytes();
                }
            } else {
                bytes = Files.readAllBytes(location.directory().resolve(internalName + ".class"));
            }
            return Optional.ofNullable(new ClassReader(bytes).getSuperName());
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    Inheritance inheritsFrom(String internalName, String ancestor) {
        String current = internalName;
        for (int depth = 0; depth < 100; depth++) {
            if (current.equals(ancestor)) {
                return Inheritance.YES;
            }
            if (!contains(current)) {
                return current.startsWith("java/") ? Inheritance.NO : Inheritance.UNKNOWN;
            }
            Optional<String> parent = superName(current);
            if (parent.isEmpty()) {
                return Inheritance.NO;
            }
            current = parent.get();
        }
        return Inheritance.UNKNOWN;
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        for (ZipFile jar : open) {
            try {
                jar.close();
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        open.clear();
        if (failure != null) {
            throw failure;
        }
    }
}
