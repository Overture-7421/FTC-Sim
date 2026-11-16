package dev.ftcsim.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the self test says when something is wrong. That everything is right is shown by the test that runs it on the real
 * distribution; here the failures are provoked, and none of them may touch the user's folders or leave a process behind.
 */
class SelfTestTest {

    private static final List<String> OPTIONS = List.of("--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow");

    @TempDir
    Path temp;

    private record Outcome(int exitCode, String output) {}

    private static SelfTest.Jvm jvm(int major, String arch, String os, List<String> options) {
        return new SelfTest.Jvm(major, major + ".0.1", "Test Vendor", arch, os, Path.of("C:\\jdk"), options);
    }

    private Outcome run(SelfTest.Jvm jvm, Source<AppClasspath> classpath) {
        return run(jvm, classpath, Optional.empty());
    }

    private Outcome run(SelfTest.Jvm jvm, Source<AppClasspath> classpath, Optional<Path> installedRuntime) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Machine machine = new Machine(name -> name.equals("LOCALAPPDATA") ? temp.resolve("Local").toString() : null);
        int code = new SelfTest(new PrintStream(bytes, true, StandardCharsets.UTF_8), jvm, machine, () -> new RuntimePin(25, OPTIONS), classpath, installedRuntime).run();
        return new Outcome(code, bytes.toString(StandardCharsets.UTF_8));
    }

    private static Source<AppClasspath> noClasspath() {
        return () -> {
            throw new IOException("ftcsim-classpath.properties is not next to C:\\somewhere\\lib\\app.jar");
        };
    }

    @Test
    void theBundledPinIsTheJavaTheBuildRunsOnAndCarriesTheOptionsTheWindowNeeds() throws IOException {
        RuntimePin pin = RuntimePin.bundled();

        assertEquals(Runtime.version().feature(), pin.major(), "the runtime is made from the JDK the project's toolchain builds and tests on");
        assertTrue(pin.jvmArguments().containsAll(OPTIONS), pin.jvmArguments().toString());
    }

    @Test
    void aRuntimeFolderThatIsMissingOrIsNotTheJavaThatRunsItIsReportedAndOneThatIsTheJavaIsAccepted() throws IOException {
        Path runtime = Files.createDirectories(temp.resolve("FTC-Sim").resolve("runtime"));
        SelfTest.Jvm running = new SelfTest.Jvm(25, "25.0.1", "Test Vendor", "amd64", "Windows 11", runtime, OPTIONS);

        Outcome missing = run(running, noClasspath(), Optional.of(runtime));
        Files.createDirectories(runtime.resolve("bin"));
        Files.writeString(runtime.resolve("bin").resolve("java.exe"), "stand-in");
        Outcome same = run(running, noClasspath(), Optional.of(runtime));
        Outcome other = run(jvm(25, "amd64", "Windows 11", OPTIONS), noClasspath(), Optional.of(runtime));
        Outcome development = run(running, noClasspath());

        assertTrue(missing.output().contains("FAIL  runtime folder: " + runtime + " has no bin\\java.exe"), missing.output());
        assertTrue(same.output().contains("ok    runtime folder: " + runtime + " is the Java that runs FTC-Sim"), same.output());
        assertTrue(other.output().contains("FAIL  runtime folder: FTC-Sim runs on C:\\jdk and not on the runtime folder of its installation"), other.output());
        assertTrue(development.output().contains("skip  the Java is not the runtime folder of an installation"), development.output());
    }

    @Test
    void theRuntimeDescriptionIsReadFromItsTwoResourcesAndAnEmptyOneIsRefused() throws IOException {
        RuntimePin pin = RuntimePin.read(
                new ByteArrayInputStream("major=25\n".getBytes(StandardCharsets.UTF_8)),
                new ByteArrayInputStream("# a comment\r\n--one\r\n\r\n  --two=2  \r\n".getBytes(StandardCharsets.UTF_8)));

        assertEquals(new RuntimePin(25, List.of("--one", "--two=2")), pin);
        assertThrows(IOException.class, () -> RuntimePin.read(new ByteArrayInputStream("major=x".getBytes(StandardCharsets.UTF_8)), new ByteArrayInputStream("--one".getBytes(StandardCharsets.UTF_8))));
        assertThrows(IOException.class, () -> RuntimePin.read(new ByteArrayInputStream("major=25".getBytes(StandardCharsets.UTF_8)), new ByteArrayInputStream("# nothing".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void aJavaOfAnotherMajorVersionFailsAndSaysWhichOneIsNeeded() {
        Outcome outcome = run(jvm(21, "amd64", "Windows 11", OPTIONS), noClasspath());

        assertEquals(1, outcome.exitCode(), outcome.output());
        assertTrue(outcome.output().contains("FAIL  Java version: Java 21.0.1 (Test Vendor), amd64, Windows 11, C:\\jdk; FTC-Sim runs on Java 25 only"), outcome.output());
        assertFalse(outcome.output().contains(SelfTest.PASSED), outcome.output());
    }

    @Test
    void aJavaThatIsNotX64WindowsFailsAndALaunchOptionThatWasNotPassedIsNamed() {
        Outcome arm = run(jvm(25, "aarch64", "Windows 11", OPTIONS), noClasspath());
        Outcome linux = run(jvm(25, "amd64", "Linux", OPTIONS), noClasspath());
        Outcome bare = run(jvm(25, "amd64", "Windows 11", List.of("--enable-native-access=ALL-UNNAMED")), noClasspath());

        assertTrue(arm.output().contains("FAIL  Java platform: ") && arm.output().contains("needs 64-bit x64 Windows"), arm.output());
        assertTrue(linux.output().contains("FAIL  Java platform: "), linux.output());
        assertTrue(bare.output().contains("FAIL  Java options: the launcher did not pass --sun-misc-unsafe-memory-access=allow"), bare.output());
        assertFalse(bare.output().contains("did not pass --enable-native-access"), "only what is missing is named: " + bare.output());
    }

    @Test
    void aMissingClassPathFileIsReportedTheWorkerAndThePhysicsEngineAreSkippedAndTheUsersFoldersAreNotTouched() {
        Outcome outcome = run(jvm(25, "amd64", "Windows 11", OPTIONS), noClasspath());

        assertEquals(1, outcome.exitCode(), outcome.output());
        assertTrue(outcome.output().contains("FAIL  class path file: ftcsim-classpath.properties is not next to C:\\somewhere\\lib\\app.jar"), outcome.output());
        assertTrue(outcome.output().contains("skip  the worker and the physics engine need the class path file"), outcome.output());
        assertTrue(outcome.output().contains("FTC-Sim self-test FAILED, 1 problem(s): class path file"), outcome.output());
        assertFalse(Files.exists(temp.resolve("Local")), "nothing is created in the user's folders when there is nothing to test");
    }

    @Test
    void aWorkerThatCannotStartIsReportedWithWhatItSaidAndNoProcessIsLeftRunning() throws IOException {
        Path notAJar = Files.writeString(temp.resolve("broken.jar"), "this is not a jar");
        Source<AppClasspath> broken = () -> new AppClasspath(List.of(notAJar), List.of(notAJar));
        Set<Long> before = ProcessHandle.current().children().map(ProcessHandle::pid).collect(Collectors.toSet());

        Outcome outcome = run(jvm(25, "amd64", "Windows 11", OPTIONS), broken);

        assertEquals(1, outcome.exitCode(), outcome.output());
        assertTrue(outcome.output().contains("FAIL  worker: The simulator could not be started. The simulator worker ended before it connected"), outcome.output());
        assertTrue(outcome.output().contains("FAIL  physics engine: "), "the physics engine cannot be had from jars that are not there either: " + outcome.output());
        List<Long> left = ProcessHandle.current().children().filter(ProcessHandle::isAlive).map(ProcessHandle::pid).filter(pid -> !before.contains(pid)).toList();
        assertEquals(List.of(), left, "the worker that failed to start is gone");
    }
}
