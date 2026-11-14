package dev.ftcsim.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.launcher.Stage0.Result;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Discovery and the pinned-runtime policy, exercised through the real FTC-Sim.ps1 against fixture runtimes. The simulator
 * runs on exactly the Java major version the manifest pins (25), so every other version must be refused, wherever it was
 * found. None of these tests may reach the network: the manifest points at a port nothing listens on, and every run says
 * -NoProvision.
 */
class Stage0DiscoveryTest {

    @TempDir(factory = WorkDirFactory.class)
    Path work;

    private Path manifest;

    @BeforeEach
    void useAnOfflineManifest() throws IOException {
        manifest = TestManifest.offline().write(work);
    }

    private Result resolve(Map<String, String> environment, String... options) {
        List<String> arguments = new ArrayList<>(List.of("-ResolveOnly", "-NoProvision", "-NonInteractive",
                "-CacheRoot", work.resolve("cache").toString(), "-ManifestPath", manifest.toString()));
        arguments.addAll(List.of(options));
        return Stage0.run(environment, arguments.toArray(String[]::new));
    }

    private Result list(Map<String, String> environment, String... options) {
        List<String> arguments = new ArrayList<>(List.of("-ListJdks", "-NonInteractive",
                "-CacheRoot", work.resolve("cache").toString(), "-ManifestPath", manifest.toString()));
        arguments.addAll(List.of(options));
        return Stage0.run(environment, arguments.toArray(String[]::new));
    }

    @Test
    void aRuntimeOfThePinnedMajorOnJavaHomeIsUsedWithoutTheNetwork() throws Exception {
        Path home = FixtureJdk.at(work.resolve("jdk25")).build();

        Result result = resolve(Map.of("JAVA_HOME", home.toString()), "-DiscoverySources", "JavaHome");

        assertEquals(0, result.exitCode(), result.output());
        assertEquals("JavaHome", result.json("source"));
        assertEquals(home.toString(), result.json("home"));
        assertEquals("Pinned", result.json("policy"), "without an option the pinned policy applies");
        assertTrue(result.jsonFlag("pinnedMatch"), result.output());
    }

    @Test
    void everyOtherMajorVersionIsRefusedByDefaultAndJavaHomeIsToldSo() throws Exception {
        for (int major : List.of(17, 21, 24, 26)) {
            Path home = FixtureJdk.at(work.resolve("jdk" + major)).major(major).build();

            Result result = resolve(Map.of("JAVA_HOME", home.toString()), "-DiscoverySources", "JavaHome");

            assertEquals(82, result.exitCode(), major + "\n" + result.output());
            assertTrue(result.output().contains("JAVA_HOME (" + home.resolve("bin").resolve("java.exe") + ") is not used: Java " + major
                    + " is not the pinned runtime (Java 25)."), result.output());
            assertTrue(result.output().contains("No usable Java 25 runtime was found (Pinned)"), result.output());
        }
    }

    @Test
    void anyAcceptsANewerJdkOnlyWhenADeveloperAsksForItExplicitly() throws Exception {
        Path home = FixtureJdk.at(work.resolve("jdk26")).major(26).build();
        Map<String, String> environment = Map.of("JAVA_HOME", home.toString());

        Result byDefault = resolve(environment, "-DiscoverySources", "JavaHome");
        Result any = resolve(environment, "-DiscoverySources", "JavaHome", "-RuntimePolicy", "Any");

        assertEquals(82, byDefault.exitCode(), byDefault.output());
        assertEquals(0, any.exitCode(), any.output());
        assertEquals("JavaHome", any.json("source"));
        assertEquals("Any", any.json("policy"));
        assertFalse(any.jsonFlag("pinnedMatch"), any.output());
    }

    @Test
    void aJreOfThePinnedMajorIsAcceptedBecauseTheSimulatorNeedsNoCompiler() throws Exception {
        Path home = FixtureJdk.at(work.resolve("jre25")).withoutJavac().build();

        Result result = resolve(Map.of("JAVA_HOME", home.toString()), "-DiscoverySources", "JavaHome");

        assertEquals(0, result.exitCode(), result.output());
        assertEquals(home.toString(), result.json("home"));
        assertTrue(result.jsonFlag("pinnedMatch"), result.output());
    }

    @Test
    void aJreOfAnotherMajorIsRefusedLikeAJdkEvenWhenAnEditorBundlesIt() throws Exception {
        Path home = FixtureJdk.at(work.resolve("redhat.java-1.58.0-win32-x64").resolve("jre").resolve("21.0.9-win32-x86_64"))
                .major(21).withoutJavac().build();

        Result result = resolve(Map.of("JAVA_HOME", home.toString()), "-DiscoverySources", "JavaHome");

        assertEquals(82, result.exitCode(), result.output());
        assertTrue(result.output().contains("Java 21 is not the pinned runtime (Java 25)"), result.output());
    }

    @Test
    void aJvmThatIsNotX64IsRejected() throws Exception {
        Path home = FixtureJdk.at(work.resolve("arm")).arch("aarch64").build();

        Result result = resolve(Map.of("JAVA_HOME", home.toString()), "-DiscoverySources", "JavaHome");

        assertEquals(82, result.exitCode(), result.output());
        assertTrue(result.output().contains("it is a aarch64 JVM; only 64-bit x64 is supported"), result.output());
    }

    @Test
    void aJdkOlderThanThePinnedMajorIsRejectedEvenWhenAnyIsAllowed() throws Exception {
        Path home = FixtureJdk.at(work.resolve("jdk21")).major(21).build();

        Result result = resolve(Map.of("JAVA_HOME", home.toString()), "-DiscoverySources", "JavaHome", "-RuntimePolicy", "Any");

        assertEquals(82, result.exitCode(), result.output());
        assertTrue(result.output().contains("Java 21 is older than the minimum, Java 25"), result.output());
    }

    @Test
    void anExactBuildPinAcceptsOnlyThatBuild() throws Exception {
        String build = FakeAdoptium.RELEASE_NAME.replaceFirst("^jdk-", "");
        manifest = TestManifest.offline().exactRelease(FakeAdoptium.RELEASE_NAME, "0".repeat(64)).write(work);
        Path older = FixtureJdk.at(work.resolve("older")).version("25.0.1", "25.0.1+8-LTS").build();
        Path exact = FixtureJdk.at(work.resolve("exact")).build();

        Result rejected = resolve(Map.of("JAVA_HOME", older.toString()), "-DiscoverySources", "JavaHome");
        Result accepted = resolve(Map.of("JAVA_HOME", exact.toString()), "-DiscoverySources", "JavaHome");

        assertEquals(82, rejected.exitCode(), rejected.output());
        assertTrue(rejected.output().contains("is not the pinned build " + build), rejected.output());
        assertEquals(0, accepted.exitCode(), accepted.output());
        assertEquals(exact.toString(), accepted.json("home"));
    }

    @Test
    void aJdkOnPathIsFoundAndRelativePathEntriesAreIgnored() throws Exception {
        Path home = FixtureJdk.at(work.resolve("onpath")).build();
        String path = home.resolve("bin") + ";.;relative\\bin;" + Path.of(System.getenv("WINDIR"), "System32");

        Result result = resolve(Map.of("PATH", path), "-DiscoverySources", "Path");

        assertEquals(0, result.exitCode(), result.output());
        assertEquals("Path", result.json("source"));
        assertEquals(home.toString(), result.json("home"));
    }

    @Test
    void cacheJdksAreFoundAndBrokenOrHiddenFoldersAreIgnored() throws Exception {
        Path jdks = work.resolve("cache").resolve("jdks");
        Path good = FixtureJdk.at(jdks.resolve("temurin-25.0.5_8-aaaaaaaa")).build();
        FixtureJdk.at(jdks.resolve("temurin-25.0.99_9-bbbbbbbb.broken-1")).build();
        FixtureJdk.at(jdks.resolve(".staging")).build();

        Result result = resolve(Map.of(), "-IgnoreSystemJdks");

        assertEquals(0, result.exitCode(), result.output());
        assertEquals("Cache", result.json("source"));
        assertTrue(result.json("home").endsWith(good.getFileName().toString()), result.output());
    }

    @Test
    void anOlderRuntimeInTheCacheIsIgnoredWhenThePinMoves() throws Exception {
        Path jdks = work.resolve("cache").resolve("jdks");
        FixtureJdk.at(jdks.resolve("temurin-21.0.13_9-aaaaaaaa")).major(21).build();

        Result result = resolve(Map.of(), "-IgnoreSystemJdks");

        assertEquals(82, result.exitCode(), result.output());
        assertTrue(result.output().contains("Cache: "), result.output());
        assertTrue(result.output().contains("Java 21 is not the pinned runtime (Java 25)"), result.output());
    }

    @Test
    void commonFoldersAndAndroidStudioComeFromTheManifestPatterns() throws Exception {
        Path programFiles = work.resolve("pf");
        FixtureJdk.at(programFiles.resolve("Java").resolve("jdk-25")).build();
        FixtureJdk.at(programFiles.resolve("Android").resolve("Android Studio 2026").resolve("jbr")).build();
        manifest = TestManifest.offline()
                .directoryPattern("CommonDirs", "%FTCSIM_TEST_PF%\\Java\\*")
                .directoryPattern("AndroidStudio", "%FTCSIM_TEST_PF%\\Android\\Android Studio*\\jbr")
                .write(work);

        Result result = list(Map.of("FTCSIM_TEST_PF", programFiles.toString()), "-DiscoverySources", "CommonDirs,AndroidStudio");

        assertEquals(0, result.exitCode(), result.output());
        assertEquals(1, result.output().lines().filter(line -> line.startsWith("CommonDirs | ") && line.endsWith("| accepted")).count(), result.output());
        assertEquals(1, result.output().lines().filter(line -> line.startsWith("AndroidStudio | ") && line.endsWith("| accepted")).count(), result.output());
    }

    @Test
    void androidStudioRuntimesAreJudgedLikeEveryOtherCandidate() throws Exception {
        Path programFiles = work.resolve("pf");
        FixtureJdk.at(programFiles.resolve("Android").resolve("Android Studio 2025").resolve("jbr")).major(21).build();
        FixtureJdk.at(programFiles.resolve("Android").resolve("Android Studio 2027").resolve("jbr")).major(25).build();
        manifest = TestManifest.offline()
                .directoryPattern("AndroidStudio", "%FTCSIM_TEST_PF%\\Android\\Android Studio*\\jbr")
                .write(work);

        Result result = list(Map.of("FTCSIM_TEST_PF", programFiles.toString()), "-DiscoverySources", "AndroidStudio");

        assertEquals(0, result.exitCode(), result.output());
        assertEquals(1, result.output().lines().filter(line -> line.contains("Android Studio 2025")
                && line.endsWith("| rejected: Java 21 is not the pinned runtime (Java 25)")).count(), result.output());
        assertEquals(1, result.output().lines().filter(line -> line.contains("Android Studio 2027") && line.endsWith("| accepted")).count(), result.output());
    }

    @Test
    void theRegistryIsReadWithoutChangingIt() throws Exception {
        manifest = TestManifest.offline()
                .registryRoot("HKLM:\\SOFTWARE\\JavaSoft\\JDK")
                .registryRoot("HKLM:\\SOFTWARE\\Eclipse Adoptium")
                .write(work);

        Result result = list(Map.of(), "-DiscoverySources", "Registry");

        assertEquals(0, result.exitCode(), result.output());
        if (registryListsAJavaSoftJdk()) {
            assertTrue(result.output().contains("Registry | "), result.output());
        }
    }

    @Test
    void aJavaThatNeverAnswersIsRejectedAfterTheProbeTimeout() throws Exception {
        manifest = TestManifest.offline().probeTimeoutSeconds(2).write(work);
        Path home = FixtureJdk.at(work.resolve("hung")).with("STUB_HANG", "true").build();

        Result result = resolve(Map.of("JAVA_HOME", home.toString()), "-DiscoverySources", "JavaHome");

        assertEquals(82, result.exitCode(), result.output());
        assertTrue(result.output().contains("it did not answer within 2 seconds"), result.output());
    }

    @Test
    void anUnknownDiscoverySourceIsABadArgument() {
        Result result = resolve(Map.of(), "-DiscoverySources", "Bogus");

        assertEquals(93, result.exitCode(), result.output());
        assertTrue(result.output().contains("Unknown discovery source 'Bogus'"), result.output());
    }

    @Test
    void aManifestThatAllowsPlainHttpToARemoteHostIsRejected() throws Exception {
        manifest = TestManifest.forServer("http://example.org").write(work);

        Result result = resolve(Map.of(), "-IgnoreSystemJdks");

        assertEquals(86, result.exitCode(), result.output());
        assertTrue(result.output().contains("non-HTTPS origin: http://example.org"), result.output());
    }

    @Test
    void theShippedManifestPinsTheTestedMajorAndNoScriptNamesAnotherJavaVersion() throws Exception {
        Matcher pin = Pattern.compile("\"major\"\\s*:\\s*(\\d+)").matcher(Files.readString(Stage0.scripts().resolve("jdk-manifest.json")));
        assertTrue(pin.find(), "the manifest has no runtime.major");
        assertEquals(TestManifest.PINNED_MAJOR, Integer.parseInt(pin.group(1)),
                "the pin moved: update TestManifest.PINNED_MAJOR, the fixtures and the advice in Messages.ps1");

        Pattern named = Pattern.compile("(?i)\\b(?:JDK|Java|Temurin)\\s+(\\d+)\\b");
        try (Stream<Path> files = Files.walk(Stage0.scripts())) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Matcher mention = named.matcher(Files.readString(file));
                while (mention.find()) {
                    assertEquals(String.valueOf(TestManifest.PINNED_MAJOR), mention.group(1),
                            file.getFileName() + " names another Java version: " + mention.group());
                }
            }
        }
    }

    @Test
    void theShippedManifestIsValidAndAllowsOnlyHttpsOrigins() throws Exception {
        String text = Files.readString(Stage0.scripts().resolve("jdk-manifest.json"));
        assertFalse(text.contains("\"http://"), "every allowed origin must be https");

        Result result = Stage0.run(Map.of(), "-ListJdks", "-NonInteractive", "-IgnoreSystemJdks",
                "-CacheRoot", work.resolve("cache").toString());

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("No Java installation was found"), result.output());
    }

    private static boolean registryListsAJavaSoftJdk() throws IOException, InterruptedException {
        Process process = new ProcessBuilder("reg.exe", "query", "HKLM\\SOFTWARE\\JavaSoft\\JDK", "/s", "/v", "JavaHome")
                .redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        return process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0;
    }
}
