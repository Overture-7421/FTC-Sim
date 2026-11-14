package dev.ftcsim.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the JDK that runs a team project's own Android build, which is not the JDK 25 that the simulator itself runs
 * on: the Android Gradle plugin of an FTC project needs a JDK between 17 and 21. It looks at {@code JAVA_HOME} and at
 * the folders of the manifest's patterns, such as Android Studio's bundled JDK, and decides from the {@code release}
 * file that every JDK has. It never runs a program, so a JDK that cannot start is found only when it is used.
 */
final class JdkDiscovery {
    static final int OLDEST = 17;
    static final int NEWEST = 21;

    private static final Pattern MAJOR = Pattern.compile("^(?:1\\.)?(\\d+)");

    /** A JDK that can run the build. */
    record Jdk(Path home, int major, String version, String vendor) {
        String describe() {
            return "Java " + version + (vendor.isEmpty() ? "" : " (" + vendor + ")") + " at " + home;
        }
    }

    /**
     * @param usable   the JDKs that can run the build, in the order they were found
     * @param rejected one sentence for each folder that looked like a JDK and cannot be used, and why
     */
    record Search(List<Jdk> usable, List<String> rejected) {
        /** Version 21 first, then 17, then the others, newest first; where two are alike, the one found first. */
        Optional<Jdk> best() {
            return usable.stream().min(Comparator.comparingInt(jdk -> rank(jdk.major())));
        }

        private static int rank(int major) {
            return major == NEWEST ? 0 : major == OLDEST ? 1 : NEWEST + 1 - major;
        }
    }

    private final Machine machine;
    private final List<String> patterns;

    /** @param patterns paths with {@code %VARIABLES%} and wildcards, see {@link JdkPatterns} */
    JdkDiscovery(Machine machine, List<String> patterns) {
        this.machine = machine;
        this.patterns = List.copyOf(patterns);
    }

    Search search() {
        List<Path> candidates = new ArrayList<>();
        String javaHome = machine.variable("JAVA_HOME");
        if (javaHome != null) {
            try {
                candidates.add(Path.of(javaHome));
            } catch (InvalidPathException invalid) {
                // JAVA_HOME is not a path: nothing to look at.
            }
        }
        for (String pattern : patterns) {
            String expanded = machine.expand(pattern);
            if (expanded != null) {
                candidates.addAll(PathPatterns.folders(expanded));
            }
        }
        Set<String> seen = new HashSet<>();
        List<Jdk> usable = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        for (Path candidate : candidates) {
            if (!seen.add(candidate.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT))) {
                continue;
            }
            try {
                usable.add(examine(candidate));
            } catch (Unusable reason) {
                rejected.add(candidate + ": " + reason.getMessage());
            }
        }
        return new Search(usable, rejected);
    }

    private static Jdk examine(Path home) throws Unusable {
        if (!Files.isDirectory(home)) {
            throw new Unusable("it is not a folder");
        }
        Path bin = home.resolve("bin");
        if (!Files.isRegularFile(bin.resolve("java.exe"))) {
            throw new Unusable("it has no bin\\java.exe, so it is not a Java installation");
        }
        if (!Files.isRegularFile(bin.resolve("javac.exe"))) {
            throw new Unusable("it has no bin\\javac.exe, so it is a Java runtime without the compiler, not a JDK");
        }
        Map<String, String> release = release(home.resolve("release"));
        String version = release.get("JAVA_VERSION");
        if (version == null) {
            throw new Unusable("it has no release file that says which Java version it is");
        }
        Matcher major = MAJOR.matcher(version);
        if (!major.find()) {
            throw new Unusable("its Java version '" + version + "' cannot be read");
        }
        String architecture = release.getOrDefault("OS_ARCH", "").toLowerCase(Locale.ROOT);
        if (!architecture.isEmpty() && !architecture.equals("x86_64") && !architecture.equals("amd64")) {
            throw new Unusable("it is a " + architecture + " JDK; only 64-bit x64 is supported");
        }
        int number = Integer.parseInt(major.group(1));
        if (number < OLDEST || number > NEWEST) {
            throw new Unusable("it is Java " + number + ", but a project's own build needs Java " + OLDEST + " to " + NEWEST);
        }
        return new Jdk(home.toAbsolutePath().normalize(), number, version, release.getOrDefault("IMPLEMENTOR", ""));
    }

    private static Map<String, String> release(Path file) {
        Map<String, String> values = new HashMap<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.ISO_8859_1)) {
                int equals = line.indexOf('=');
                if (equals > 0) {
                    String value = line.substring(equals + 1).trim();
                    if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                        value = value.substring(1, value.length() - 1);
                    }
                    values.put(line.substring(0, equals).trim(), value);
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            // No release file: the caller says that the version is unknown.
        }
        return values;
    }

    private static final class Unusable extends Exception {
        private static final long serialVersionUID = 1L;

        Unusable(String reason) {
            super(reason);
        }
    }
}
