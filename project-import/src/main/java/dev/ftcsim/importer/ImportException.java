package dev.ftcsim.importer;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * An import that did not produce a package. The message says what happened and what to do about it in words for a
 * student or mentor; {@link #failure()} lets a user interface choose a help page, and {@link #diagnostics()} holds
 * the lines of the project's own build output that the diagnosis rests on.
 */
public final class ImportException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** What went wrong, as precisely as the importer could tell. */
    public enum Failure {
        /** The caller did not accept that the project's build scripts will run. */
        NOT_TRUSTED,
        /** A parameter of the import cannot work: a folder that does not exist, a JRE instead of a JDK. */
        INVALID_REQUEST,
        /** A source or build file of the original project changed while it was imported. */
        ORIGINAL_CHANGED,
        /** The project has no Gradle wrapper, which is how the importer runs its build. */
        NOT_A_GRADLE_PROJECT,
        /** The Android SDK folder is missing. */
        NO_ANDROID_SDK,
        /** The Android SDK lacks the platform or build tools the project needs. */
        ANDROID_SDK_INCOMPLETE,
        /** The Android SDK licences have not been accepted, so the missing components cannot be installed. */
        LICENSES_NOT_ACCEPTED,
        /** The JDK cannot run this project's Gradle or Android Gradle plugin. */
        WRONG_JDK,
        /** Offline was requested but the Gradle cache lacks something the project needs. */
        OFFLINE_CACHE_COLD,
        /** A download failed because there is no network or a server did not answer. */
        NETWORK_UNAVAILABLE,
        /** A dependency of the project does not exist in its repositories. */
        DEPENDENCY_RESOLUTION,
        /** The team's own code does not compile. */
        COMPILATION_FAILED,
        /** The project has no (or more than one) Android application module. */
        NO_APPLICATION_MODULE,
        /** The project does not depend on the FTC SDK. */
        NOT_AN_FTC_PROJECT,
        /** The build ran out of memory. */
        OUT_OF_MEMORY,
        /** The build did not finish in time. */
        TIMED_OUT,
        /** Gradle failed for a reason the importer does not recognise; the build output is in the log. */
        BUILD_FAILED,
        /** The Android resources of the project or of one of its libraries could not be processed. */
        RESOURCES_FAILED,
        /** A file could not be read or written. */
        IO_ERROR,
        /** The user cancelled. */
        CANCELLED
    }

    private final Failure failure;
    private final transient List<String> diagnostics;
    private final transient Path logFile;

    public ImportException(Failure failure, String message) {
        this(failure, message, List.of(), null, null);
    }

    public ImportException(Failure failure, String message, Throwable cause) {
        this(failure, message, List.of(), null, cause);
    }

    public ImportException(Failure failure, String message, List<String> diagnostics, Path logFile) {
        this(failure, message, diagnostics, logFile, null);
    }

    private ImportException(Failure failure, String message, List<String> diagnostics, Path logFile, Throwable cause) {
        super(message, cause);
        this.failure = failure;
        this.diagnostics = List.copyOf(diagnostics);
        this.logFile = logFile;
    }

    public Failure failure() {
        return failure;
    }

    /** The lines of build output that show why, in the order Gradle printed them; empty when there are none. */
    public List<String> diagnostics() {
        return diagnostics;
    }

    /** The file that holds the complete output of the project's build, when it got that far. */
    public Optional<Path> logFile() {
        return Optional.ofNullable(logFile);
    }
}
