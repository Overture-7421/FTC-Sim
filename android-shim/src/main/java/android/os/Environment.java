package android.os;

import java.io.File;
import java.nio.file.Path;

/**
 * Storage locations. The "external storage" of the simulated robot controller is an ordinary directory the
 * host chooses per session, so the FTC folder tree the SDK builds below it never touches the user's files.
 */
public final class Environment {
    private static volatile File externalStorage = Path.of(System.getProperty("java.io.tmpdir"), "ftcsim-sdcard").toFile();

    private Environment() {}

    public static void setExternalStorageDirectory(File directory) {
        externalStorage = java.util.Objects.requireNonNull(directory, "directory");
    }

    public static File getExternalStorageDirectory() {
        return externalStorage;
    }
}
