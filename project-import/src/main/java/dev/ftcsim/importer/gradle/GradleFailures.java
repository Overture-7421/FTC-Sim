package dev.ftcsim.importer.gradle;

import dev.ftcsim.importer.ImportException;
import dev.ftcsim.importer.ImportException.Failure;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the output of a failed Gradle run into the failure a person can act on. Gradle and the Android Gradle
 * plugin report their problems in prose, so each failure is recognised by the sentence that announces it; the
 * check runs from the most specific cause to the most general, and what matched is attached as evidence. The
 * wording of these sentences is Gradle's and the plugin's, which is why the rules are tested against real output.
 */
final class GradleFailures {
    /** What the importer knew when it started the build; it makes the advice concrete. */
    record Context(Path jdkHome, Path androidSdk, boolean offline) {}

    private static final int EVIDENCE_LINES = 14;
    private static final Pattern MARKER = Pattern.compile("FTCSIM-EXPORT-ERROR ([a-z-]+): (.*)");
    private static final Pattern LICENCES = Pattern.compile("licen[cs]es have not been accepted", Pattern.CASE_INSENSITIVE);
    private static final Pattern SDK_PACKAGE = Pattern.compile("\\s+(\\S+;\\S+)\\s+.*");
    private static final Pattern NO_SDK = Pattern.compile("SDK location not found|The SDK directory .* does not exist|ANDROID_HOME .* (does not exist|is not a directory)");
    private static final Pattern INCOMPLETE_SDK = Pattern.compile(
            "Failed to find target with hash string|Failed to find Platform SDK|Failed to find Build Tools revision"
                    + "|Installed Build Tools revision .* is corrupted|Failed to install the following SDK components"
                    + "|Failed to install the following Android SDK packages");
    private static final Pattern WRONG_JDK = Pattern.compile(
            "Android Gradle plugin requires Java \\d+|requires Java \\d+|Unsupported class file major version"
                    + "|Could not determine java version from|Unsupported Java\\.|incompatible Java"
                    + "|UnsupportedClassVersionError|compiled by a more recent version of the Java Runtime"
                    + "|does not provide the required capabilities|No compiler is provided in this environment");
    private static final Pattern OFFLINE = Pattern.compile("No cached version of .* available for offline mode");
    private static final Pattern NETWORK = Pattern.compile(
            "UnknownHostException|Connection refused|connect timed out|Read timed out|Could not (GET|HEAD) |Network is unreachable"
                    + "|SSLHandshakeException|Could not install Gradle distribution|java\\.net\\.SocketException|Connection reset"
                    + "|PKIX path building failed|Server returned HTTP response code");
    private static final Pattern DEPENDENCY = Pattern.compile("Could not find \\S+:\\S+|Could not resolve all (files|artifacts) for configuration|Could not resolve \\S+:\\S+");
    private static final Pattern MISSING_DEPENDENCY = Pattern.compile("Could not find \\S+:\\S+");
    private static final Pattern COMPILATION = Pattern.compile("Compilation failed; see the compiler error output|Execution failed for task '[^']*:compile[^']*(Java|Kotlin)[^']*'");
    private static final Pattern MEMORY = Pattern.compile("OutOfMemoryError|Java heap space|GC overhead limit exceeded|Metaspace");
    private static final Pattern FAILED_TASK = Pattern.compile("Execution failed for task '([^']+)'");

    private GradleFailures() {}

    static ImportException diagnose(List<String> output, Context context, Path logFile) {
        String text = String.join("\n", output);
        Matcher marker = MARKER.matcher(text);
        if (marker.find()) {
            return fail(Failure.NO_APPLICATION_MODULE, "Cannot build this project: " + marker.group(2) + ".", around(output, MARKER), logFile);
        }
        if (LICENCES.matcher(text).find()) {
            return licences(output, context, logFile);
        }
        if (NO_SDK.matcher(text).find()) {
            return fail(Failure.NO_ANDROID_SDK,
                    "No Android SDK at " + context.androidSdk() + ". Choose the folder with 'platforms' and 'build-tools'.",
                    around(output, NO_SDK), logFile);
        }
        if (INCOMPLETE_SDK.matcher(text).find()) {
            return fail(Failure.ANDROID_SDK_INCOMPLETE,
                    "The Android SDK at " + context.androidSdk() + " lacks " + first(output, INCOMPLETE_SDK)
                            + ". Install it in Android Studio's SDK Manager.",
                    around(output, INCOMPLETE_SDK), logFile);
        }
        if (WRONG_JDK.matcher(text).find()) {
            return fail(Failure.WRONG_JDK,
                    "The JDK at " + context.jdkHome() + " cannot run this build. Use a JDK, version 17 or 21.",
                    around(output, WRONG_JDK), logFile);
        }
        if (MEMORY.matcher(text).find()) {
            return fail(Failure.OUT_OF_MEMORY,
                    "The build ran out of memory. Close other programs and build again.",
                    around(output, MEMORY), logFile);
        }
        if (OFFLINE.matcher(text).find()) {
            return fail(Failure.OFFLINE_CACHE_COLD,
                    context.offline()
                            ? "Offline, and Gradle's cache lacks " + first(output, OFFLINE) + ". Build once with network access."
                            : "Gradle's cache lacks " + first(output, OFFLINE) + ".",
                    around(output, OFFLINE), logFile);
        }
        if (NETWORK.matcher(text).find()) {
            return fail(Failure.NETWORK_UNAVAILABLE,
                    "Gradle could not download " + first(output, NETWORK) + ". Check the network and proxy.",
                    around(output, NETWORK), logFile);
        }
        if (DEPENDENCY.matcher(text).find()) {
            String missing = first(output, MISSING_DEPENDENCY);
            return fail(Failure.DEPENDENCY_RESOLUTION,
                    (missing.isEmpty() ? first(output, DEPENDENCY) : missing) + ". Check its version and repository.",
                    around(output, DEPENDENCY), logFile);
        }
        if (COMPILATION.matcher(text).find()) {
            return fail(Failure.COMPILATION_FAILED,
                    "Your code does not compile" + failedTask(output).map(task -> " (" + task + ")").orElse("")
                            + ". Fix the errors and build again.",
                    errors(output), logFile);
        }
        return fail(Failure.BUILD_FAILED,
                "The build failed" + failedTask(output).map(task -> " in " + task).orElse("")
                        + wentWrong(output).map(reason -> ": " + reason).orElse("") + ".",
                wentWrongBlock(output), logFile);
    }

    private static ImportException licences(List<String> output, Context context, Path logFile) {
        List<String> packages = new ArrayList<>();
        for (String line : output) {
            Matcher found = SDK_PACKAGE.matcher(line);
            if (found.matches() && found.group(1).matches("[a-z-]+;[\\w.;-]+")) {
                packages.add(found.group(1));
            }
        }
        String missing = packages.isEmpty() ? "some components" : String.join(", ", packages);
        return fail(Failure.LICENSES_NOT_ACCEPTED,
                "Accept the Android SDK licences for " + missing + ": '"
                        + context.androidSdk().resolve("cmdline-tools").resolve("latest").resolve("bin").resolve("sdkmanager.bat")
                        + " --licenses' or Android Studio's SDK Manager.",
                around(output, LICENCES), logFile);
    }

    private static ImportException fail(Failure failure, String message, List<String> evidence, Path logFile) {
        return new ImportException(failure, message, evidence, logFile);
    }

    private static String first(List<String> output, Pattern pattern) {
        for (String line : output) {
            Matcher found = pattern.matcher(line);
            if (found.find()) {
                return line.strip();
            }
        }
        return "";
    }

    /** The first line that matches and the lines after it, which is where Gradle explains. */
    private static List<String> around(List<String> output, Pattern pattern) {
        for (int i = 0; i < output.size(); i++) {
            if (pattern.matcher(output.get(i)).find()) {
                return output.subList(i, Math.min(output.size(), i + EVIDENCE_LINES));
            }
        }
        return List.of();
    }

    private static Optional<String> failedTask(List<String> output) {
        for (String line : output) {
            Matcher failed = FAILED_TASK.matcher(line);
            if (failed.find()) {
                return Optional.of("task " + failed.group(1));
            }
        }
        return Optional.empty();
    }

    /** The compiler's own error lines ("File.java:12: error: ..."), which say what is wrong. */
    private static List<String> errors(List<String> output) {
        List<String> errors = new ArrayList<>();
        for (String line : output) {
            if (line.contains(": error:") || line.startsWith("e: ")) {
                errors.add(line);
                if (errors.size() == EVIDENCE_LINES) {
                    break;
                }
            }
        }
        return errors.isEmpty() ? wentWrongBlock(output) : errors;
    }

    /** What Gradle says below "What went wrong:", at most two lines of it, which state where and why. */
    private static Optional<String> wentWrong(List<String> output) {
        List<String> block = wentWrongBlock(output);
        List<String> reasons = new ArrayList<>();
        for (String line : block.subList(Math.min(1, block.size()), block.size())) {
            if (!line.isBlank() && !line.startsWith("Execution failed for task")) {
                reasons.add(line.replaceFirst("^[> ]+", "").strip());
            }
            if (reasons.size() == 2) {
                break;
            }
        }
        return reasons.isEmpty() ? Optional.empty() : Optional.of(String.join(" ", reasons));
    }

    private static List<String> wentWrongBlock(List<String> output) {
        for (int i = 0; i < output.size(); i++) {
            if (output.get(i).startsWith("* What went wrong:")) {
                int end = i + 1;
                while (end < output.size() && !output.get(end).startsWith("* Try:") && end - i <= 20) {
                    end++;
                }
                return output.subList(i, end);
            }
        }
        int tail = Math.max(0, output.size() - EVIDENCE_LINES);
        return output.subList(tail, output.size());
    }
}
