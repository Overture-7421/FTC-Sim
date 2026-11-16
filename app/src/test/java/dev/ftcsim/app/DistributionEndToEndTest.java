package dev.ftcsim.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The distribution as a driver starts it: a copy of the folder that {@code :app:dist} built, in a folder with spaces and
 * accented letters in its name, started through its own FTC-Sim.cmd on a PC with no JAVA_HOME and nothing of Java on the PATH.
 * Nothing of the real user's folders is written, and a window is never opened.
 *
 * <p>Two copies are made. One is the real distribution, on which the self test runs the real application on the runtime that
 * comes with it. The other has a tiny program in place of {@code lib\app.jar}, which says how the runtime was started and
 * exits, throws or crashes the Java runtime on request: that is the hand-off from FTC-Sim.cmd to the Java it starts, the
 * options it passes, the arguments it passes on, where the crash report goes, and the exit code it hands back.
 */
@Tag("two-process")
@Timeout(600)
class DistributionEndToEndTest {

    private static final int NOT_UNZIPPED = 79;

    @TempDir
    static Path root;

    private static Path real;
    private static Path tiny;
    private static Path localAppData;

    @BeforeAll
    static void installTheDistributionTwiceAsAFolderWithSpaces() throws IOException {
        String folder = System.getProperty("ftcsim.dist");
        assumeTrue(folder != null, "run through Gradle, which builds the distribution and names it in ftcsim.dist");
        real = copyTree(Path.of(folder), root.resolve("Some Földer").resolve("FTC-Sim Zoë 1"));
        tiny = copyTree(Path.of(folder), root.resolve("Some Földer").resolve("FTC-Sim Zoë 2"));
        replaceApplicationWithTinyProgram(tiny.resolve("lib").resolve("app.jar"));
        localAppData = Files.createDirectories(root.resolve("Local App Dätä"));
    }

    private static Path copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path source : files.toList()) {
                Path target = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(source, target);
                }
            }
        }
        return to;
    }

    private FtcSimCmd.Result run(Path install, String... arguments) throws Exception {
        return FtcSimCmd.run(install.resolve("FTC-Sim.cmd"), root, localAppData, false, arguments);
    }

    private static Path logs() {
        return localAppData.resolve("FTC-Sim").resolve("logs");
    }

    private static List<Path> files(Path folder, String prefix) throws IOException {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> found = Files.list(folder)) {
            return found.filter(file -> file.getFileName().toString().startsWith(prefix)).sorted().toList();
        }
    }

    @Test
    void theDistributionPassesItsOwnSelfTestOnItsOwnRuntimeAndAWarmStartDoesTheSame() throws Exception {
        Set<Long> before = ProcessHandle.current().children().map(ProcessHandle::pid).collect(Collectors.toSet());

        FtcSimCmd.Result cold = run(real, "--self-test");

        assertEquals(0, cold.exitCode(), cold.output());
        assertTrue(cold.output().contains(SelfTest.PASSED), "the console shows what the application printed:\n" + cold.output());
        List<Path> logs = files(logs(), "app-");
        assertEquals(1, logs.size(), logs.toString());
        String log = Files.readString(logs.get(0));
        assertTrue(log.contains(SelfTest.PASSED), "and so does the log, which is what a student sends:\n" + log);
        assertTrue(cold.output().contains("FTC-Sim log: " + logs.get(0)), "the console says where the log is:\n" + cold.output());
        assertTrue(log.contains("ok    runtime folder: " + real.resolve("runtime") + " is the Java that runs FTC-Sim"), log);
        assertTrue(log.contains("ok    Java options: --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow"), log);
        assertTrue(log.contains("ok    worker (pid ") && log.contains("ok    physics engine loaded and stepped"), log);
        try (Stream<Path> natives = Files.walk(localAppData.resolve("FTC-Sim").resolve("natives"), 2)) {
            assertTrue(natives.anyMatch(file -> file.getFileName().toString().equals("joltjni.dll")), "the physics engine was unpacked");
        }
        assertFalse(Files.exists(localAppData.resolve("FTC-Sim").resolve("config")), "the self test does not touch the user's configuration");
        assertEquals(List.of(), ProcessHandle.current().children().filter(ProcessHandle::isAlive).map(ProcessHandle::pid).filter(pid -> !before.contains(pid)).toList(),
                "no process is left");

        FtcSimCmd.Result warm = run(real, "--self-test");

        assertEquals(0, warm.exitCode(), warm.output());
        assertTrue(warm.output().contains(SelfTest.PASSED), warm.output());
        assertEquals(2, files(logs(), "app-").size(), "every start has a log of its own");
        System.out.println("distribution self test through FTC-Sim.cmd: " + cold.millis() + " ms first, " + warm.millis() + " ms after");
    }

    @Test
    void theCmdFileStartsTheRuntimeOfItsFolderWithTheOptionsFileAndPassesTheUsersArgumentsOnAndItsExitCodeBack() throws Exception {
        String settings = "--settings=" + root.resolve("my settings").resolve("ui.json");

        FtcSimCmd.Result exit7 = run(tiny, "exit7", "--no-focus", settings, "--exit-after=8");

        assertEquals(7, exit7.exitCode(), "the application's exit code is the launcher's:\n" + exit7.output());
        List<String> arguments = exit7.output().lines().filter(line -> line.startsWith("ARG ")).map(line -> line.substring(4)).toList();
        assertEquals(List.of("exit7", "--no-focus", settings, "--exit-after=8"), arguments, "the user's arguments reach the application unchanged, in order");
        List<String> options = exit7.output().lines().filter(line -> line.startsWith("OPT ")).map(line -> line.substring(4)).toList();
        assertTrue(options.containsAll(optionsOf(tiny.resolve("runtime-options"))), "every option of runtime-options is passed: " + options);
        assertTrue(options.stream().anyMatch(option -> option.startsWith("-XX:ErrorFile=" + logs() + "\\hs_err_pid")), "the crash report goes to the logs folder: " + options);
        assertTrue(exit7.output().contains("FTC-Sim stopped with exit code 7."), exit7.output());
        assertTrue(exit7.output().contains("java.home=" + tiny.resolve("runtime")), "the Java is the runtime folder of the installation, not one of the PC:\n" + exit7.output());

        assertEquals(0, run(tiny, "exit0").exitCode(), "an application that ends well ends the launcher well");
        FtcSimCmd.Result thrown = run(tiny, "throw");
        assertEquals(1, thrown.exitCode(), thrown.output());
        assertTrue(thrown.output().contains("Exception in thread \"main\" java.lang.IllegalStateException: boom"), thrown.output());
    }

    @Test
    void aCrashOfTheJavaRuntimeLeavesItsReportInTheLogsFolderAndNowhereElse() throws Exception {
        FtcSimCmd.Result crashed = run(tiny, "crash");

        assertEquals(1, crashed.exitCode(), crashed.output());
        assertTrue(crashed.output().contains("A fatal error has been detected by the Java Runtime Environment"), "and on the console:\n" + crashed.output());
        List<Path> reports = files(logs(), "hs_err_pid");
        assertEquals(1, reports.size(), "one crash report in the logs folder: " + reports);
        assertTrue(Files.readString(reports.get(0), StandardCharsets.ISO_8859_1).contains("EXCEPTION_ACCESS_VIOLATION"), reports.get(0).toString());
        try (Stream<Path> everything = Files.walk(root)) {
            List<Path> strays = everything.filter(path -> {
                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                return (name.startsWith("hs_err") || name.endsWith(".mdmp")) && !path.startsWith(logs());
            }).toList();
            assertEquals(List.of(), strays, "no crash file is left beside the program or in the folder it was started from");
        }
    }

    @Test
    void aFolderWithoutItsRuntimeIsSaidToBeNotUnzippedAndStartsNothingAndADoubleClickKeepsTheWindowOpen() throws Exception {
        Path partial = Files.createDirectories(root.resolve("Partial folder"));
        Files.copy(real.resolve("FTC-Sim.cmd"), partial.resolve("FTC-Sim.cmd"));

        FtcSimCmd.Result script = run(partial, "--self-test");
        FtcSimCmd.Result doubleClick = FtcSimCmd.run(partial.resolve("FTC-Sim.cmd"), root, localAppData, true, "--self-test");

        assertEquals(NOT_UNZIPPED, script.exitCode(), script.output());
        assertTrue(script.output().contains("Extract All") && script.output().stripTrailing().endsWith("[FTCSIM-NOTUNZIPPED, exit code 79]"), script.output());
        assertFalse(script.output().contains(SelfTest.PASSED), "no application was started: " + script.output());
        assertEquals(NOT_UNZIPPED, doubleClick.exitCode(), doubleClick.output());
        assertFalse(doubleClick.output().stripTrailing().endsWith("[FTCSIM-NOTUNZIPPED, exit code 79]"), "a failure waits for a key before the window closes:\n" + doubleClick.output());
    }

    private static List<String> optionsOf(Path file) throws IOException {
        return Files.readAllLines(file).stream().map(String::strip).filter(line -> !line.isEmpty() && !line.startsWith("#")).toList();
    }

    /** Replaces the application's jar with one whose main class reports how it was started and exits, throws or crashes the Java runtime on request. */
    private static void replaceApplicationWithTinyProgram(Path jar) throws IOException {
        Path source = Files.writeString(Files.createDirectories(root.resolve("tiny src")).resolve("Tiny.java"), String.join("\n",
                "public final class Tiny {",
                "    public static void main(String[] args) throws Exception {",
                "        for (String argument : args) {",
                "            System.out.println(\"ARG \" + argument);",
                "        }",
                "        for (String option : java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments()) {",
                "            System.out.println(\"OPT \" + option);",
                "        }",
                "        System.out.println(\"java.home=\" + System.getProperty(\"java.home\"));",
                "        switch (args[0]) {",
                "            case \"exit0\" -> System.exit(0);",
                "            case \"exit7\" -> System.exit(7);",
                "            case \"throw\" -> throw new IllegalStateException(\"boom\");",
                "            default -> {",
                "                java.lang.reflect.Field field = sun.misc.Unsafe.class.getDeclaredField(\"theUnsafe\");",
                "                field.setAccessible(true);",
                "                ((sun.misc.Unsafe) field.get(null)).putAddress(0L, 1L);",
                "            }",
                "        }",
                "    }",
                "}",
                ""));
        Path classes = Files.createDirectories(root.resolve("tiny classes"));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertEquals(0, compiler.run(null, null, null, "-nowarn", "-d", classes.toString(), source.toString()), "the compiler");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "Tiny");
        try (OutputStream file = Files.newOutputStream(jar); JarOutputStream out = new JarOutputStream(file, manifest)) {
            out.putNextEntry(new JarEntry("Tiny.class"));
            out.write(Files.readAllBytes(classes.resolve("Tiny.class")));
            out.closeEntry();
        }
    }
}
