package dev.ftcsim.importer.scan;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * What the simulator's own Android layer provides, so that the importer can point at what a team's code uses
 * beyond it. The simulator replaces the Android framework with a small clean-room subset; code that calls
 * something outside it fails when that call runs.
 */
public interface AndroidApi {
    /** Whether the class (internal name, {@code android/util/Log}) exists in the simulator's Android layer. */
    boolean hasClass(String internalName);

    /**
     * Whether a method exists on the class, declared there or inherited. A method that cannot be looked up
     * because a supertype is unknown counts as existing, since the importer only reports what it is sure of.
     */
    boolean hasMethod(String owner, String name, String descriptor);

    /** Like {@link #hasMethod} for fields. */
    boolean hasField(String owner, String name);

    /**
     * The Android layer that the given class path provides: the simulator's {@code android-shim} and SDK adapter jars
     * or class folders. Classes are read, never loaded.
     */
    static AndroidApi of(List<Path> classPath) throws IOException {
        return ShimClasses.of(classPath);
    }
}
