package dev.ftcsim.link;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * What the launcher and the worker agree on besides the wire: how the launcher tells the worker where to put what it
 * writes, by system property, and where that is when nobody said.
 */
public final class WorkerEnvironment {

    /** Where the physics engine's native library is unpacked; the same directory can serve every launch. */
    public static final String NATIVE_DIRECTORY_PROPERTY = "ftcsim.nativeDirectory";

    /** A directory the worker may fill with the simulated robot controller's storage and must leave empty. */
    public static final String STORAGE_DIRECTORY_PROPERTY = "ftcsim.storageDirectory";

    /**
     * The simulator's own jars that every robot world needs: the Android shim, the SDK adapters and what they use,
     * separated as a class path is. A prepared program does not carry them.
     */
    public static final String SIMULATOR_CLASSPATH_PROPERTY = "ftcsim.simulatorClasspath";

    /** The argument that tells the worker which loopback port the launcher listens on. */
    public static final String PORT_ARGUMENT = "--port";

    private WorkerEnvironment() {}

    /** {@code %LOCALAPPDATA%\FTC-Sim\natives}, a per-user cache that survives updates and that no one else writes. */
    public static Path defaultNativeDirectory() {
        String local = System.getenv("LOCALAPPDATA");
        Path base = local == null || local.isBlank() ? Path.of(System.getProperty("user.home"), "AppData", "Local") : Path.of(local);
        return base.resolve("FTC-Sim").resolve("natives");
    }

    /** Deletes a file or a directory with everything in it; what cannot be deleted is left. */
    public static void deleteTree(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException stuck) {
                    // A file another process still holds is the owner's to clean up later.
                }
            }
        } catch (IOException | UncheckedIOException unreadable) {
            // The same: whatever could not be listed stays.
        }
    }
}
