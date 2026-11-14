package dev.ftcsim.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.OutputStream;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The distribution as a driver starts it: a copy of the folder that {@code :app:dist} built, in a folder with spaces and
 * accented letters in its name, started through its own FTC-Sim.cmd on a PC with no JAVA_HOME and nothing of Java on the PATH.
 * The test never downloads a Java, and nothing of the real user's folders is written. A window is never opened.
 *
 * <p>The shipped manifest pins one exact build of Java 25, which this computer's own Java 25 rarely is, so the tests that
 * are about the distribution and not about the pin run on a copy whose manifest has the pin cleared (the only change to it),
 * on whatever JDK 25 stage 0 finds, and are skipped, saying so, when there is none. The pin itself is tested with the
 * shipped manifest: another build of Java 25 is refused with its reason, and the pinned build is accepted and runs the self
 * test when it is in this user's FTC-Sim cache (run FTC-Sim.cmd once to put it there), and is skipped otherwise.
 */
@Tag("two-process")
@Timeout(600)
class DistributionEndToEndTest {

    private static final int NO_RUNTIME = 82;
    private static final Pattern PIN = Pattern.compile("\"exactRelease\"\\s*:\\s*\"jdk-([^\"]+)\"");

    @TempDir
    Path root;

    private Path install;
    private Path localAppData;

    @BeforeEach
    void installTheDistributionAsAFolderWithSpaces() throws IOException {
        String folder = System.getProperty("ftcsim.dist");
        assumeTrue(folder != null, "run through Gradle, which builds the distribution and names it in ftcsim.dist");
        install = copyTree(Path.of(folder), root.resolve("Some Földer").resolve("FTC-Sim Zoë 1"));
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

    private FtcSimCmd.Result run(String... arguments) throws Exception {
        return FtcSimCmd.run(install.resolve("FTC-Sim.cmd"), root, localAppData, "", arguments);
    }

    /** The build the shipped manifest pins, as a runtime reports it: 25.0.5+8. */
    private String pinnedBuild() throws IOException {
        Matcher pin = PIN.matcher(Files.readString(install.resolve("jdk-manifest.json")));
        assertTrue(pin.find(), "the distribution's manifest pins an exact release");
        return pin.group(1);
    }

    /** Lets stage 0 take any build of Java 25 in the copy of the distribution: the pin is cleared, nothing else is changed. */
    private void acceptAnyBuildOfThePinnedJava() throws IOException {
        Path manifest = install.resolve("jdk-manifest.json");
        String shipped = Files.readString(manifest);
        String cleared = shipped.replaceAll("(\"exactRelease\"\\s*:\\s*)\"[^\"]*\"", "$1null").replaceAll("(\"exactSha256\"\\s*:\\s*)\"[^\"]*\"", "$1null");
        assertNotEquals(shipped, cleared, "the shipped manifest carries the pin");
        Files.writeString(manifest, cleared);
    }

    private Path data() {
        return localAppData.resolve("FTC-Sim");
    }

    private List<Path> appLogs() throws IOException {
        try (Stream<Path> logs = Files.list(data().resolve("logs"))) {
            return logs.filter(log -> log.getFileName().toString().startsWith("app-")).sorted().toList();
        }
    }

    @Test
    void theDistributionPassesItsOwnSelfTestOnTheJdkOfThisComputerAndAWarmStartDoesTheSame() throws Exception {
        acceptAnyBuildOfThePinnedJava();
        Set<Long> before = ProcessHandle.current().children().map(ProcessHandle::pid).collect(Collectors.toSet());
        FtcSimCmd.Result cold = run("-NoProvision", "--self-test");
        assumeTrue(cold.exitCode() != NO_RUNTIME, "no JDK " + RuntimePin.bundled().major() + " is installed on this computer, and this test never downloads one:\n" + cold.output());

        assertEquals(0, cold.exitCode(), cold.output());
        assertTrue(cold.output().contains("Using Java " + RuntimePin.bundled().major()), cold.output());
        assertTrue(cold.output().contains(SelfTest.PASSED), "the console shows what the application printed:\n" + cold.output());
        List<Path> logs = appLogs();
        assertEquals(1, logs.size(), logs.toString());
        String log = Files.readString(logs.get(0));
        assertTrue(log.contains(SelfTest.PASSED), "and so does the log, which is what a student sends:\n" + log);
        assertTrue(log.contains("ok    Java options: --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow"), log);
        assertTrue(log.contains("ok    worker (pid ") && log.contains("ok    physics engine loaded and stepped"), log);
        try (Stream<Path> natives = Files.walk(data().resolve("natives"), 2)) {
            assertTrue(natives.anyMatch(file -> file.getFileName().toString().equals("joltjni.dll")), "the physics engine was unpacked");
        }
        assertFalse(Files.exists(data().resolve("config")), "the self test does not touch the user's configuration");
        assertEquals(List.of(), ProcessHandle.current().children().filter(ProcessHandle::isAlive).map(ProcessHandle::pid).filter(pid -> !before.contains(pid)).toList(),
                "no process is left");

        FtcSimCmd.Result warm = run("-NoProvision", "--self-test");

        assertEquals(0, warm.exitCode(), warm.output());
        assertTrue(warm.output().contains(SelfTest.PASSED), warm.output());
        assertEquals(2, appLogs().size(), "every start has a log of its own");
        System.out.println("distribution self test through FTC-Sim.cmd: " + cold.millis() + " ms first, " + warm.millis() + " ms after");
    }

    @Test
    void aCrashOfTheJavaRuntimeAnExceptionAndAnExitCodeAllReachTheLogAndTheCaller() throws Exception {
        acceptAnyBuildOfThePinnedJava();
        Path tiny = tinyApplication();
        FtcSimCmd.Result probe = run("-NoProvision", "-AppJar", tiny.toString(), "exit7");
        assumeTrue(probe.exitCode() != NO_RUNTIME, "no JDK " + RuntimePin.bundled().major() + " is installed on this computer:\n" + probe.output());
        assertEquals(7, probe.exitCode(), "the application's exit code is the launcher's:\n" + probe.output());
        assertTrue(probe.output().contains("FTC-Sim stopped with exit code 7."), probe.output());

        FtcSimCmd.Result thrown = run("-NoProvision", "-AppJar", tiny.toString(), "throw");
        assertEquals(1, thrown.exitCode(), thrown.output());
        assertTrue(thrown.output().contains("Exception in thread \"main\" java.lang.IllegalStateException: boom"), thrown.output());

        FtcSimCmd.Result crashed = run("-NoProvision", "-AppJar", tiny.toString(), "crash");

        assertEquals(1, crashed.exitCode(), crashed.output());
        List<String> logs = new ArrayList<>();
        for (Path file : appLogs()) {
            logs.add(Files.readString(file));
        }
        assertEquals(3, logs.size(), "every start has a log of its own, even when two begin in the same second");
        String log = logs.stream().filter(text -> text.contains("tiny application: crash")).findFirst().orElseThrow();
        assertTrue(log.contains("A fatal error has been detected by the Java Runtime Environment"), "the crash report is in the log:\n" + log);
        assertTrue(log.contains("EXCEPTION_ACCESS_VIOLATION"), log);
        assertTrue(crashed.output().contains("A fatal error has been detected by the Java Runtime Environment"), "and on the console");
        try (Stream<Path> stray = Files.walk(root)) {
            List<String> dumps = stray.map(path -> path.getFileName().toString().toLowerCase(Locale.ROOT))
                    .filter(name -> name.startsWith("hs_err") || name.endsWith(".mdmp")).toList();
            assertEquals(List.of(), dumps, "no crash file is left beside the program or in the folder it was started from");
        }
    }

    @Test
    void theShippedPinRefusesThisComputersOtherBuildOfJava25WithItsReasonAndStartsNothing() throws Exception {
        String build = pinnedBuild();
        int major = RuntimePin.bundled().major();
        FtcSimCmd.Result listed = run("-NoProvision", "-ListJdks");
        List<String> otherBuilds = listed.output().lines().filter(line -> line.contains("| Java " + major + ".") && line.contains("| rejected: build ")).toList();
        assumeTrue(!otherBuilds.isEmpty(), "this computer has no other build of Java " + major + " than the pinned " + build + " to refuse:\n" + listed.output());

        FtcSimCmd.Result refused = run("-NoProvision", "--self-test");

        assertEquals(NO_RUNTIME, refused.exitCode(), refused.output());
        assertTrue(refused.output().contains("is not the pinned build " + build), refused.output());
        assertTrue(refused.output().contains("[FTCSIM-NORUNTIME, exit code 82]"), refused.output());
        assertFalse(refused.output().contains(SelfTest.PASSED), "no application was started: " + refused.output());
        assertFalse(Files.exists(data().resolve("logs")), "and no log was begun");
    }

    @Test
    void theShippedPinAcceptsTheRealPinnedBuildFromTheUsersCacheAndTheSelfTestPassesOnIt() throws Exception {
        String build = pinnedBuild();
        Path pinned = pinnedBuildInTheUsersCache(build);
        assumeTrue(pinned != null, "the pinned build " + build + " is not in this user's FTC-Sim cache (FTC-Sim.cmd downloads it on its first start), so the pin was not tried against the real build");

        FtcSimCmd.Result result = FtcSimCmd.run(install.resolve("FTC-Sim.cmd"), root, localAppData, pinned.toString(), "-NoProvision", "--self-test");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("Using Java " + build.substring(0, build.indexOf('+')) + " (Eclipse Adoptium, amd64) at " + pinned + " [JavaHome]"), result.output());
        assertTrue(result.output().contains(SelfTest.PASSED), result.output());
    }

    /** The JDK of the pinned build that an earlier start of FTC-Sim put in this user's cache, which the test reads and does not write to. */
    private static Path pinnedBuildInTheUsersCache(String build) throws IOException {
        String local = System.getenv("LOCALAPPDATA");
        Path jdks = local == null ? null : Path.of(local, "FTC-Sim", "jdks");
        if (jdks == null || !Files.isDirectory(jdks)) {
            return null;
        }
        String prefix = "temurin-" + build.replace('+', '_') + "-";
        try (Stream<Path> folders = Files.list(jdks)) {
            return folders.filter(folder -> folder.getFileName().toString().startsWith(prefix) && Files.isRegularFile(folder.resolve("bin").resolve("java.exe")))
                    .sorted().findFirst().orElse(null);
        }
    }

    /** A jar with a main class that exits, throws or crashes the Java runtime on request, made with the compiler of the JDK that runs this test. */
    private Path tinyApplication() throws IOException {
        Path source = Files.writeString(Files.createDirectories(root.resolve("tiny src")).resolve("Tiny.java"), String.join("\n",
                "public final class Tiny {",
                "    public static void main(String[] args) throws Exception {",
                "        System.out.println(\"tiny application: \" + String.join(\" \", args));",
                "        switch (args[0]) {",
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
        Path jar = root.resolve("tiny app").resolve("tiny.jar");
        Files.createDirectories(jar.getParent());
        try (OutputStream file = Files.newOutputStream(jar); JarOutputStream out = new JarOutputStream(file, manifest)) {
            out.putNextEntry(new JarEntry("Tiny.class"));
            out.write(Files.readAllBytes(classes.resolve("Tiny.class")));
            out.closeEntry();
        }
        return jar;
    }
}
