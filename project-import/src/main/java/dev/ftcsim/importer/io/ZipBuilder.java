package dev.ftcsim.importer.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes a zip or jar whose bytes depend only on what was added: entries in name order, one fixed time stamp, no
 * extra fields. The same classes therefore always give the same jar, and so the same hash.
 */
public final class ZipBuilder {
    private static final LocalDateTime TIME = LocalDateTime.of(1980, 2, 1, 0, 0);

    private final Map<String, Object> entries = new TreeMap<>();

    /** @param name the path inside the archive, with '/' separators and no leading '/' */
    public ZipBuilder add(String name, byte[] content) {
        return put(name, content);
    }

    public ZipBuilder add(String name, Path file) {
        return put(name, file);
    }

    /** Adds the entry unless the archive already has one of that name, so the first of several sources wins. */
    public ZipBuilder addIfAbsent(String name, Path file) {
        return entries.containsKey(name) ? this : put(name, file);
    }

    public ZipBuilder addIfAbsent(String name, byte[] content) {
        return entries.containsKey(name) ? this : put(name, content);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** Whether {@code name} is a path that an archive entry can have and that a class loader could ask for. */
    public static boolean isUsableName(String name) {
        return !(name.isEmpty() || name.startsWith("/") || name.contains("\\") || name.endsWith("/")
                || ("/" + name + "/").contains("/../") || ("/" + name + "/").contains("/./"));
    }

    private ZipBuilder put(String name, Object source) {
        if (!isUsableName(name)) {
            throw new IllegalArgumentException("not a usable archive entry name: '" + name + "'");
        }
        if (entries.putIfAbsent(name, source) != null) {
            throw new IllegalArgumentException("the archive would hold '" + name + "' twice");
        }
        return this;
    }

    /**
     * Writes the archive to a temporary file beside {@code target} and moves it into place when it is complete. A
     * target that already holds exactly these bytes is left untouched: Windows does not let a program replace a
     * jar that another process has open, and regenerating an unchanged jar should not need to.
     */
    public void writeTo(Path target) throws IOException {
        Path partial = target.resolveSibling(target.getFileName() + ".partial");
        try {
            try (OutputStream file = Files.newOutputStream(partial); ZipOutputStream zip = new ZipOutputStream(file)) {
                zip.setLevel(Deflater.BEST_SPEED);
                for (Map.Entry<String, Object> entry : entries.entrySet()) {
                    ZipEntry zipEntry = new ZipEntry(entry.getKey());
                    zipEntry.setTimeLocal(TIME);
                    zip.putNextEntry(zipEntry);
                    if (entry.getValue() instanceof byte[] content) {
                        zip.write(content);
                    } else {
                        try (InputStream in = Files.newInputStream((Path) entry.getValue())) {
                            in.transferTo(zip);
                        }
                    }
                    zip.closeEntry();
                }
            }
            if (!Files.isRegularFile(target) || Files.mismatch(partial, target) != -1L) {
                Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(partial);
        }
    }
}
