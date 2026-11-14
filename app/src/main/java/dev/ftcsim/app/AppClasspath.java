package dev.ftcsim.app;

import dev.ftcsim.link.ClasspathFile;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.List;

/**
 * Where the jars of the programs that the application starts are. The worker is a process of its own, so its jars are
 * not on the application's class path; the robot world's jars (the Android shim and the SDK adapters) are what the worker
 * and the importer both read. Both lists are in one file, in the format of {@link ClasspathFile}:
 *
 * <pre>
 * worker=lib\worker.jar;lib\sim-link.jar;...
 * robot=lib\sdk-common.jar;lib\android-shim.jar;...
 * </pre>
 *
 * Relative entries are relative to the file, so a folder with the file and the jars can be installed anywhere. During
 * development Gradle writes the file ({@code :app:writeClasspath}) with absolute paths into its own build folders and
 * {@code :app:run} names it in {@value #FILE_PROPERTY}; an installation has the file next to the application's jar.
 * Either way this is the one way the application finds them.
 *
 * @param worker the worker's jars: everything its main class needs
 * @param robot  the simulator's own jars that a robot world needs besides the program
 */
public record AppClasspath(List<Path> worker, List<Path> robot) {

    /** The name of the file, next to the application's jar in an installation. */
    public static final String FILE_NAME = "ftcsim-classpath.properties";

    /** The system property that names the file when it is not next to the jar. */
    public static final String FILE_PROPERTY = "ftcsim.classpathFile";

    public AppClasspath {
        worker = List.copyOf(worker);
        robot = List.copyOf(robot);
    }

    /**
     * Reads the file.
     *
     * @throws IOException if it cannot be read, lacks a line, or names a jar that is not there
     */
    public static AppClasspath read(Path file) throws IOException {
        ClasspathFile lines = ClasspathFile.read(file, List.of("worker", "robot"));
        return new AppClasspath(lines.classpath("worker"), lines.classpath("robot"));
    }

    /** Finds the file as {@value #FILE_PROPERTY} says, or next to the jar this class was loaded from, and reads it. */
    public static AppClasspath locate() throws IOException {
        return locate(System.getProperty(FILE_PROPERTY), applicationJar());
    }

    static AppClasspath locate(String property, Path applicationJar) throws IOException {
        if (property != null && !property.isBlank()) {
            Path file = Path.of(property);
            if (!Files.isRegularFile(file)) {
                throw new IOException(FILE_PROPERTY + " names " + file + ", which does not exist");
            }
            return read(file);
        }
        if (applicationJar != null && Files.isRegularFile(applicationJar)) {
            Path beside = applicationJar.resolveSibling(FILE_NAME);
            if (Files.isRegularFile(beside)) {
                return read(beside);
            }
            throw new IOException(FILE_NAME + " is not next to " + applicationJar);
        }
        throw new IOException("the application does not run from a jar and " + FILE_PROPERTY + " is not set");
    }

    private static Path applicationJar() {
        try {
            CodeSource source = AppClasspath.class.getProtectionDomain().getCodeSource();
            return source == null ? null : Path.of(source.getLocation().toURI());
        } catch (URISyntaxException | RuntimeException unusable) {
            return null;
        }
    }
}
