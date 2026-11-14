package dev.ftcsim.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * One JVM proves that slicing simulated time differently gives the same world; this proves the same across processes.
 * Hornet's Far Auto - Blue runs for six simulated seconds after Start in a JVM of its own, twice, and once with the JIT
 * compiler off, and every figure that could differ must be identical: the hash of the physics state, the pose in exact
 * bits, the telemetry, the number of times control passed between the simulator and the robot thread, and the problems.
 * What could make them differ is what the JVM decides for itself: the order of identity hash codes, which threads run
 * first, and the arithmetic of compiled code against interpreted code.
 *
 * <p>It runs with {@code -Pftcsim.hornetPackage=<folder>}, like {@link HornetBringupTest}.
 */
@Timeout(3600)
class HornetCrossJvmTest {
    private static final String OPMODE = "Far Auto - Blue";
    private static final int SECONDS_AFTER_START = 6;
    private static final long CHILD_TIMEOUT_MINUTES = 25;

    private record Variant(String name, List<String> jvmArguments) {}

    private static void report(String line) {
        System.out.println("HORNET-CROSS-JVM> " + line);
    }

    @Test
    void theSameOpModeGivesTheSameWorldInSeparateJvmProcessesWhateverTheJit() throws Exception {
        HornetFixture.prepared();
        List<Variant> variants = List.of(
                new Variant("first JVM", List.of()),
                new Variant("second JVM", List.of()),
                new Variant("interpreter only (-Xint)", List.of("-Xint")));

        Map<String, String> results = new LinkedHashMap<>();
        for (Variant variant : variants) {
            long began = System.nanoTime();
            String result = run(variant);
            report(variant.name() + " (" + TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - began) + " s): " + result);
            results.put(variant.name(), result);
        }

        String reference = results.values().iterator().next();
        assertTrue(reference.contains("world="), reference);
        for (Map.Entry<String, String> entry : results.entrySet()) {
            assertEquals(reference, entry.getValue(), entry.getKey() + " differs from the first JVM");
        }
    }

    /** The line the child printed. */
    private static String run(Variant variant) throws IOException, InterruptedException {
        Path directory = Files.createTempDirectory("ftcsim-cross-jvm");
        try {
            Path arguments = directory.resolve("arguments.txt");
            Path output = directory.resolve("output.txt");
            Files.writeString(arguments, String.join("\n", childArguments(variant)) + "\n", StandardCharsets.UTF_8);
            Process process = new ProcessBuilder(
                            Path.of(System.getProperty("java.home"), "bin", "java").toString(), "@" + arguments)
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile())
                    .start();
            if (!process.waitFor(CHILD_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly().waitFor();
                throw new AssertionError(variant.name() + " did not finish within " + CHILD_TIMEOUT_MINUTES + " minutes");
            }
            String text = Files.readString(output, StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new AssertionError(variant.name() + " exited with " + process.exitValue() + ":\n" + tail(text));
            }
            return text.lines()
                    .filter(line -> line.startsWith(HornetChildRun.RESULT))
                    .map(line -> line.substring(HornetChildRun.RESULT.length()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(variant.name() + " printed no result:\n" + tail(text)));
        } finally {
            try (var files = Files.walk(directory)) {
                for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    /** One option to a line, quoted as the launcher's argument files want them; the class path is far too long for a command line. */
    private static List<String> childArguments(Variant variant) {
        List<String> arguments = new ArrayList<>();
        arguments.add("--enable-native-access=ALL-UNNAMED");
        arguments.addAll(variant.jvmArguments());
        System.getProperties().stringPropertyNames().stream()
                .filter(name -> name.startsWith("ftcsim.") && !name.equals("ftcsim.childClasspath"))
                .sorted()
                .forEach(name -> arguments.add("-D" + name + "=" + System.getProperty(name)));
        arguments.add("-cp");
        arguments.add(System.getProperty("ftcsim.childClasspath"));
        arguments.add(HornetChildRun.class.getName());
        arguments.add(OPMODE);
        arguments.add(Integer.toString(SECONDS_AFTER_START));
        return arguments.stream().map(HornetCrossJvmTest::quoted).toList();
    }

    private static String quoted(String argument) {
        return '"' + argument.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static String tail(String output) {
        return output.length() <= 6000 ? output : "..." + output.substring(output.length() - 6000);
    }
}
