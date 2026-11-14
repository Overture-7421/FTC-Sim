package dev.ftcsim.importer.scan;

import java.util.Objects;

/**
 * A pointer from the importer's static checks to something in the team's code that may not work in the
 * simulator. A finding says what was seen and where; it is not a claim that the code fails when it runs.
 *
 * @param code    what kind of thing was seen, one of the constants of {@link Codes}
 * @param subject the class (binary name) or library (group:name:version) it was seen in; empty for the project as a whole
 * @param member  the method, as {@code name(descriptor)}, when it was seen in one; otherwise empty
 * @param detail  a sentence that says what it is
 */
public record Finding(String code, String subject, String member, String detail) {

    /** The kinds of finding. Readers of a package must accept codes they do not know, so these are not an enum. */
    public static final class Codes {
        /** A class from the Android framework that the simulator's Android layer does not provide. */
        public static final String ANDROID_CLASS_MISSING = "ANDROID_CLASS_MISSING";
        /** A method or field the simulator's Android layer lacks on a class it does provide. */
        public static final String ANDROID_MEMBER_MISSING = "ANDROID_MEMBER_MISSING";
        /** A class that no class path of the simulated program provides. */
        public static final String CLASS_NOT_PROVIDED = "CLASS_NOT_PROVIDED";
        /** A call that loads a native library. */
        public static final String NATIVE_LIBRARY_LOAD = "NATIVE_LIBRARY_LOAD";
        /** Native libraries packaged for Android, which cannot load in a desktop JVM. */
        public static final String NATIVE_LIBRARY_PACKAGED = "NATIVE_LIBRARY_PACKAGED";
        /** Code that starts a thread or an executor. */
        public static final String THREAD_USE = "THREAD_USE";
        /** Code that opens a network connection or listens on one. */
        public static final String NETWORK_USE = "NETWORK_USE";
        /** The Android check was not run because no simulator class path was given. */
        public static final String ANDROID_CHECK_SKIPPED = "ANDROID_CHECK_SKIPPED";
        /** A class annotated as an OpMode that the SDK would not register. */
        public static final String OPMODE_REJECTED = "OPMODE_REJECTED";
        /** Two OpModes with the same name. */
        public static final String OPMODE_DUPLICATE_NAME = "OPMODE_DUPLICATE_NAME";
        /** A method that registers OpModes in code, whose OpModes no scan of the class files can list. */
        public static final String OPMODE_REGISTRAR = "OPMODE_REGISTRAR";
        /** The FTC SDK artifacts of the project do not all have the same version. */
        public static final String SDK_VERSION_MIXED = "SDK_VERSION_MIXED";
        /** A link in the project folder that was not followed. */
        public static final String LINK_SKIPPED = "LINK_SKIPPED";
        /** More findings than the manifest keeps. */
        public static final String TRUNCATED = "TRUNCATED";
        /** The package was made by an importer that left the Java resources of the libraries out; prepare the program again. */
        public static final String JAVA_RESOURCES_MISSING = "JAVA_RESOURCES_MISSING";
        /** A Java resource that several libraries ship with different contents; the one first on the class path is used. */
        public static final String JAVA_RESOURCE_CONFLICT = "JAVA_RESOURCE_CONFLICT";

        private Codes() {}
    }

    public Finding {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(member, "member");
        Objects.requireNonNull(detail, "detail");
    }

    /** A finding about the project as a whole. */
    public static Finding general(String code, String detail) {
        return new Finding(code, "", "", detail);
    }
}
