package dev.ftcsim.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.launcher.Stage0.Result;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
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
 * The hand-off from stage 0 to the application, through the real FTC-Sim.ps1 and a test double that plays javaw.exe: how the
 * application is started, where what it writes goes, what its exit code does, and what a double-click leaves on screen.
 * Nothing here touches the network or the real %LOCALAPPDATA%.
 */
class Stage0LaunchTest {

    private static final Pattern STUB_ARGUMENT = Pattern.compile("(?m)^STUB-ARG (.*)$");

    @TempDir(factory = WorkDirFactory.class)
    Path work;

    private Path manifest;
    private Path appJar;
    private Path cache;

    @BeforeEach
    void prepare() throws IOException {
        manifest = TestManifest.offline().write(work);
        appJar = FakePc.dummyApplicationJar(work);
        cache = work.resolve("data folder").resolve("FTC-Sim");
    }

    private List<String> options(String... more) {
        List<String> arguments = new ArrayList<>(List.of("-NoProvision", "-NonInteractive", "-CacheRoot", cache.toString(),
                "-ManifestPath", manifest.toString(), "-DiscoverySources", "JavaHome", "-AppJar", appJar.toString()));
        arguments.addAll(List.of(more));
        return arguments;
    }

    private Result launch(Path jdk, String... more) {
        return Stage0.run(Map.of("JAVA_HOME", jdk.toString()), options(more).toArray(String[]::new));
    }

    private static List<String> argumentsOf(Result result) {
        List<String> arguments = new ArrayList<>();
        Matcher matcher = STUB_ARGUMENT.matcher(result.output());
        while (matcher.find()) {
            arguments.add(matcher.group(1).stripTrailing());
        }
        return arguments;
    }

    private List<Path> appLogs() throws IOException {
        Path logs = cache.resolve("logs");
        if (!Files.isDirectory(logs)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(logs)) {
            return files.filter(file -> file.getFileName().toString().startsWith("app-")).sorted().toList();
        }
    }

    @Test
    void theApplicationIsStartedWithJavawTheManifestsOptionsTheLoggingOptionsAndTheUsersArgumentsInThatOrder() throws Exception {
        Path jdk = FixtureJdk.at(work.resolve("jdk 25")).build();
        String settings = "--settings=" + work.resolve("my settings").resolve("ui.json");

        Result result = launch(jdk, "--no-focus", settings, "--exit-after=8");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.hasLine("STUB-EXE " + jdk.resolve("bin").resolve("javaw.exe")), result.output());
        List<String> expected = new ArrayList<>(TestManifest.applicationOptions());
        expected.addAll(List.of("-XX:+ErrorFileToStdout", "-XX:-CreateCoredumpOnCrash", "-Dstdout.encoding=UTF-8",
                "-Dstderr.encoding=UTF-8", "-jar", appJar.toString(), "--no-focus", settings, "--exit-after=8"));
        assertEquals(expected, argumentsOf(result), "the options of the manifest, then the logging options, then the jar, then the user's own");
    }

    @Test
    void theShippedManifestPassesWhatTheWindowAndTheWorkerNeedOnTheJavaTheyRunOn() {
        assertTrue(TestManifest.applicationOptions().containsAll(List.of("--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow")),
                TestManifest.applicationOptions().toString());
    }

    @Test
    void theExitCodeOfTheApplicationIsTheExitCodeOfTheLauncherAndTheLogIsNamed() throws Exception {
        Path jdk = FixtureJdk.at(work.resolve("jdk25")).with("STUB_EXIT_CODE", "7").build();

        Result result = launch(jdk);

        assertEquals(7, result.exitCode(), result.output());
        Path log = appLogs().get(0);
        assertTrue(result.output().contains("Starting FTC-Sim. Its log is " + log), result.output());
        assertTrue(result.output().contains("FTC-Sim stopped with exit code 7."), result.output());
        assertTrue(result.output().contains(log.toString()), "the message names the file to send");
        assertEquals(0, launch(FixtureJdk.at(work.resolve("fine")).build()).exitCode(), "an application that ends well ends the launcher well");
    }

    @Test
    void whatTheApplicationWritesToOutputAndToErrorsIsKeptInOneLogInTheOrderItWasWrittenAndShownOnTheConsole() throws Exception {
        Path jdk = FixtureJdk.at(work.resolve("jdk25")).build();

        Result result = launch(jdk);

        List<Path> logs = appLogs();
        assertEquals(1, logs.size(), logs.toString());
        assertEquals(cache.resolve("logs"), logs.get(0).getParent());
        assertTrue(logs.get(0).getFileName().toString().matches("app-\\d{8}-\\d{6}\\.log"), logs.get(0).toString());
        String log = Files.readString(logs.get(0));
        assertTrue(log.contains("STUB-OUT-1\r\nSTUB-ERR-1\r\nSTUB-OUT-2\r\nSTUB-ERR-2\r\n"), "standard output and error in the order written:\n" + log);
        assertTrue(result.output().contains("STUB-OUT-1\nSTUB-ERR-1\nSTUB-OUT-2\nSTUB-ERR-2\n"), "and shown in that order here:\n" + result.output());
    }

    @Test
    void theLogsLiveUnderLocalAppDataWhenNoDataFolderIsGiven() throws Exception {
        Path jdk = FixtureJdk.at(work.resolve("jdk25")).build();
        Path localAppData = work.resolve("Local App Data");

        Result result = Stage0.run(Map.of("JAVA_HOME", jdk.toString(), "LOCALAPPDATA", localAppData.toString()),
                "-NoProvision", "-NonInteractive", "-ManifestPath", manifest.toString(), "-DiscoverySources", "JavaHome", "-AppJar", appJar.toString());

        assertEquals(0, result.exitCode(), result.output());
        try (Stream<Path> logs = Files.list(localAppData.resolve("FTC-Sim").resolve("logs"))) {
            assertEquals(1, logs.filter(file -> file.getFileName().toString().startsWith("app-")).count());
        }
    }

    @Test
    void aRuntimeWithoutJavawIsRunWithJavaAndOldLogsOfTheApplicationAreDeletedButNotTheWorkers() throws Exception {
        Path jdk = FixtureJdk.at(work.resolve("jdk25")).withoutJavaw().build();
        Path logs = Files.createDirectories(cache.resolve("logs"));
        for (int day = 1; day <= 25; day++) {
            Path old = Files.writeString(logs.resolve(String.format("app-202001%02d-100000.log", day)), "old");
            Files.setLastModifiedTime(old, FileTime.from(Instant.parse("2020-01-01T00:00:00Z").plus(Duration.ofDays(day))));
        }
        Path worker = Files.writeString(logs.resolve("worker-20200101-000000-abcd.log"), "worker");

        Result result = launch(jdk);

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.hasLine("STUB-EXE " + jdk.resolve("bin").resolve("java.exe")), result.output());
        List<Path> kept = appLogs();
        assertEquals(20, kept.size(), kept.toString());
        assertFalse(Files.exists(logs.resolve("app-20200101-100000.log")) || Files.exists(logs.resolve("app-20200105-100000.log")), "the oldest are gone");
        assertTrue(Files.exists(logs.resolve("app-20200125-100000.log")), "the newest of the old ones stay");
        assertTrue(Files.exists(worker), "the application's logs are pruned, the worker's are the application's to keep");
    }

    @Test
    void twoLogsBegunInTheSameSecondDoNotShareAFile() throws Exception {
        Files.createDirectories(cache.resolve("logs"));

        Result result = Stage0.scenario(work, manifest, String.join("\n",
                "$first = New-ApplicationLog " + Stage0.quote(cache.resolve("logs")),
                "$second = New-ApplicationLog " + Stage0.quote(cache.resolve("logs")),
                "$third = New-ApplicationLog " + Stage0.quote(cache.resolve("logs")),
                "Out-Fact distinct (@($first, $second, $third) | Select-Object -Unique).Count",
                "Out-Fact allExist ((Test-Path $first) -and (Test-Path $second) -and (Test-Path $third))"));

        assertEquals(0, result.exitCode(), result.output());
        assertEquals("3", result.fact("distinct"));
        assertEquals("True", result.fact("allExist"));
    }

    @Test
    void optionsThatCannotBePassedOnSafelyAreRefusedBeforeAnythingIsStarted() throws Exception {
        Path jdk = FixtureJdk.at(work.resolve("jdk25")).build();

        for (String unsafe : List.of("50%", "%TEMP%")) {
            Result result = launch(jdk, "--name=" + unsafe);

            assertEquals(93, result.exitCode(), unsafe + "\n" + result.output());
            assertTrue(result.output().contains("must not contain a quote or a percent sign"), result.output());
            assertFalse(result.output().contains("STUB-LAUNCH"), "the application must not have been started: " + result.output());
        }
        assertEquals(List.of(), appLogs(), "nothing was begun");

        Result quote = Stage0.scenario(work, manifest, "ConvertTo-CmdArgument 'say \"hi\"'");
        assertEquals("BadArguments", quote.kind(), quote.output());
    }

    @Test
    void anApplicationThatEndsWhileTheDoubleClickWatchesIsReportedWithItsExitCode() throws Exception {
        Path jdk = FixtureJdk.at(work.resolve("jdk25")).with("STUB_EXIT_CODE", "3").build();

        Result result = launch(jdk, "-Detach", "-StartupWatchSeconds", "30");

        assertEquals(3, result.exitCode(), result.output());
        assertTrue(result.output().contains("FTC-Sim stopped with exit code 3."), result.output());
        assertTrue(result.output().contains("STUB-ERR-2"), "what it wrote is shown, so the window that stays open explains itself: " + result.output());
    }

    @Test
    void anApplicationThatKeepsRunningIsLeftRunningAndTheLauncherReportsSuccessSoTheConsoleCloses() throws Exception {
        Path jdk = FixtureJdk.at(work.resolve("jdk25")).with("STUB_SLEEP_MS", "60000").build();
        Path output = work.resolve("detached.txt");
        long pid = -1;
        try {
            Instant started = Instant.now();
            Result result = Stage0.runToFile(output, Map.of("JAVA_HOME", jdk.toString()), options("-Detach", "-StartupWatchSeconds", "2").toArray(String[]::new));

            assertEquals(0, result.exitCode(), result.output());
            assertTrue(Duration.between(started, Instant.now()).compareTo(Duration.ofSeconds(50)) < 0, "it did not wait for the application");
            Matcher pidLine = Pattern.compile("(?m)^STUB-PID (\\d+)").matcher(result.output());
            assertTrue(pidLine.find(), result.output());
            pid = Long.parseLong(pidLine.group(1));
            assertTrue(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "the application is still running");
            assertFalse(result.output().contains("stopped with exit code"), result.output());
        } finally {
            if (pid > 0) {
                endWithItsCommandProcessor(pid);
            }
        }
    }

    /** Ends the application the test left running, and the hidden cmd.exe that started it, and waits until they hold no file of the test. */
    private static void endWithItsCommandProcessor(long applicationPid) {
        ProcessHandle.of(applicationPid).ifPresent(application -> {
            List<ProcessHandle> ours = new ArrayList<>();
            application.parent().ifPresent(ours::add);
            ours.add(application);
            ours.forEach(ProcessHandle::destroyForcibly);
            ours.forEach(handle -> handle.onExit().orTimeout(30, TimeUnit.SECONDS).join());
        });
    }

    @Test
    void aDoubleClickedWrapperInAFolderWithSpacesStartsTheApplicationAndKeepsItsLogWhereTheStudentCanFindIt() throws Exception {
        Path installed = Files.createDirectories(work.resolve("Downloads").resolve("FTC Sim 1"));
        Path copied = copyScripts(installed);
        Files.createDirectories(installed.resolve("lib"));
        Files.writeString(installed.resolve("lib").resolve("app.jar"), "not a jar");
        Path jdk = FixtureJdk.at(work.resolve("jdk25")).with("STUB_EXIT_CODE", "4").build();
        Path localAppData = work.resolve("AppData Local");
        Map<String, String> environment = new HashMap<>();
        environment.put("JAVA_HOME", jdk.toString());
        environment.put("LOCALAPPDATA", localAppData.toString());

        Result result = Stage0.runWrapperOf(copied, work, Path.of(System.getenv("SystemRoot")), environment, "--no-focus", "-DiscoverySources", "JavaHome", "-NoProvision");

        assertEquals(4, result.exitCode(), result.output());
        assertTrue(result.hasLine("STUB-ARG --no-focus"), "the application's own option reaches it: " + result.output());
        assertTrue(result.hasLine("STUB-ARG " + installed.resolve("lib").resolve("app.jar")), result.output());
        try (Stream<Path> logs = Files.list(localAppData.resolve("FTC-Sim").resolve("logs"))) {
            assertEquals(1, logs.count());
        }
    }

    private static Path copyScripts(Path target) throws IOException {
        try (Stream<Path> files = Files.walk(Stage0.scripts())) {
            for (Path source : files.filter(Files::isRegularFile).toList()) {
                Path destination = target.resolve(Stage0.scripts().relativize(source));
                Files.createDirectories(destination.getParent());
                Files.copy(source, destination);
            }
        }
        return target.resolve("FTC-Sim.cmd");
    }
}
