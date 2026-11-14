package dev.ftcsim.link;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A text file of {@code key=value} lines whose values are class paths: how one program tells another which jars to
 * use without a command line, which on Windows is too short for the class path of a program of this size. Values are
 * taken as they stand, with no escapes, so Windows paths are written as they are. Entries are separated as the
 * platform separates a class path, and a relative entry is relative to the folder of the file, so a folder that holds
 * the file and the jars can be moved as a whole. Blank lines and lines that start with {@code #} are ignored.
 *
 * <p>The files in use are the {@code program.properties} of a prepared robot program, which the worker reads, and the
 * class path file of the application, which says where the worker's jars are.
 */
public final class ClasspathFile {
    private static final int MISSING_SHOWN = 5;

    private final Path file;
    private final Map<String, String> values;

    private ClasspathFile(Path file, Map<String, String> values) {
        this.file = file;
        this.values = values;
    }

    /**
     * Reads the lines of a file.
     *
     * @param keys the keys the file may have, in the order a message lists them
     * @throws IOException if the file cannot be read, a line is not {@code key=value}, a key is not one of {@code keys}
     *                     or is set twice
     */
    public static ClasspathFile read(Path file, List<String> keys) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int equals = line.indexOf('=');
            if (equals <= 0) {
                throw new IOException(file + " line " + (i + 1) + " is not 'key=value': " + abbreviate(line));
            }
            String key = line.substring(0, equals).trim();
            if (!keys.contains(key)) {
                throw new IOException(file + " line " + (i + 1) + " has the unknown key '" + key + "'; the keys are " + listed(keys));
            }
            if (values.put(key, line.substring(equals + 1).trim()) != null) {
                throw new IOException(file + " line " + (i + 1) + " sets '" + key + "' a second time");
            }
        }
        return new ClasspathFile(file, values);
    }

    public Path file() {
        return file;
    }

    public boolean has(String key) {
        return values.containsKey(key);
    }

    /** A value that is not a class path, as written, or {@code fallback} when the file has no such line. */
    public String text(String key, String fallback) {
        return values.getOrDefault(key, fallback);
    }

    /**
     * The entries of a class path line, each of which exists.
     *
     * @throws IOException if there is no such line, it lists nothing, or entries do not exist
     */
    public List<Path> classpath(String key) throws IOException {
        String value = values.get(key);
        if (value == null) {
            throw new IOException(file + " has no '" + key + "' line");
        }
        Path folder = file.toAbsolutePath().getParent();
        List<Path> entries = new ArrayList<>();
        List<Path> missing = new ArrayList<>();
        for (String text : value.split(File.pathSeparator)) {
            if (text.isBlank()) {
                continue;
            }
            Path entry = folder.resolve(text.trim()).normalize();
            (Files.exists(entry) ? entries : missing).add(entry);
        }
        if (!missing.isEmpty()) {
            throw new IOException(file + ": '" + key + "' lists " + missing.size() + " entries that do not exist, among them "
                    + missing.subList(0, Math.min(missing.size(), MISSING_SHOWN)));
        }
        if (entries.isEmpty()) {
            throw new IOException(file + ": '" + key + "' lists no class path entries");
        }
        return entries;
    }

    private static String listed(List<String> keys) {
        return keys.size() < 2
                ? String.join("", keys)
                : String.join(", ", keys.subList(0, keys.size() - 1)) + " and " + keys.get(keys.size() - 1);
    }

    private static String abbreviate(String line) {
        return line.length() <= 80 ? line : line.substring(0, 80) + "…";
    }
}
