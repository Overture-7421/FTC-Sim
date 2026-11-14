package dev.ftcsim.physics;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Runs a scenario's {@code main}, normally {@link GoldenRunner}, in a fresh JVM with chosen flags and natives, and
 * collects the hash lines it prints.
 */
final class GoldenChildJvm {

    /**
     * @param jvmArguments  extra JVM flags, such as a JIT mode
     * @param debugNatives  use the DebugSp natives instead of ReleaseSp
     * @param workerThreads job system workers; 0 is the single-threaded job system
     */
    record Variant(String name, List<String> jvmArguments, boolean debugNatives, int workerThreads) {}

    private static final Pattern HASH_LINE = Pattern.compile("\\d+ [0-9a-f]{64}");
    private static final int LOG_TAIL_CHARACTERS = 4000;

    private GoldenChildJvm() {}

    /** The golden-format lines the variant printed; throws when the JVM fails or times out. */
    static List<String> run(Variant variant, long timeoutSeconds) throws IOException, InterruptedException {
        return run(GoldenRunner.class, variant, timeoutSeconds);
    }

    /** The same for another runner, whose arguments are the SHA-256 of the natives and the number of workers. */
    static List<String> run(Class<?> runner, Variant variant, long timeoutSeconds) throws IOException, InterruptedException {
        Files.createDirectories(TestNatives.directory());
        Path log = Files.createTempFile(TestNatives.directory(), "golden-", ".log");
        try {
            Process process = new ProcessBuilder(command(runner, variant))
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile())
                    .start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor();
                throw new AssertionError(variant.name() + " did not finish within " + timeoutSeconds + " s");
            }
            String output = Files.readString(log, StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new AssertionError(variant.name() + " exited with " + process.exitValue() + ":\n" + tail(output));
            }
            List<String> lines = output.lines().map(String::strip).filter(l -> HASH_LINE.matcher(l).matches()).toList();
            if (lines.isEmpty()) {
                throw new AssertionError(variant.name() + " printed no hashes:\n" + tail(output));
            }
            return lines;
        } finally {
            Files.deleteIfExists(log);
        }
    }

    private static List<String> command(Class<?> runner, Variant variant) {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("--enable-native-access=ALL-UNNAMED");
        command.add("-Dftcsim.nativeDirectory=" + TestNatives.directory());
        command.addAll(variant.jvmArguments());
        command.add("-cp");
        command.add(classpath(variant.debugNatives()));
        command.add(runner.getName());
        command.add(variant.debugNatives() ? TestNatives.DEBUG_SHA256 : TestNatives.RELEASE_SHA256);
        command.add(Integer.toString(variant.workerThreads()));
        return command;
    }

    // The test classpath carries the ReleaseSp natives; the debug variant swaps in the DebugSp jar at the same position.
    private static String classpath(boolean debugNatives) {
        String classpath = System.getProperty("ftcsim.childClasspath");
        if (!debugNatives) {
            return classpath;
        }
        String release = System.getProperty("ftcsim.releaseNativesJar");
        String debug = System.getProperty("ftcsim.debugNativesJar");
        return Arrays.stream(classpath.split(File.pathSeparator))
                .map(entry -> entry.equals(release) ? debug : entry)
                .collect(Collectors.joining(File.pathSeparator));
    }

    private static String tail(String output) {
        return output.length() <= LOG_TAIL_CHARACTERS ? output : "..." + output.substring(output.length() - LOG_TAIL_CHARACTERS);
    }
}
