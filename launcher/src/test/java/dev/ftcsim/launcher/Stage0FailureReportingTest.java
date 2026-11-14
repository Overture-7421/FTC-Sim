package dev.ftcsim.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.launcher.Stage0.Result;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a driver sees on a PC where stage 0 cannot do its job: PowerShell restricted by policy, a script that cannot be
 * started at all, or hardware FTC-Sim does not support. Each case must explain itself, change nothing, and keep its exit code.
 */
class Stage0FailureReportingTest {

    @TempDir(factory = WorkDirFactory.class)
    Path work;

    private Path manifest;

    @BeforeEach
    void prepare() throws Exception {
        manifest = TestManifest.offline().write(work);
    }

    /** A Windows folder whose powershell.exe is the test double, so FTC-Sim.cmd can be run without a real PowerShell. */
    private Path windowsWithPowerShellIn(String systemFolder) throws Exception {
        Path root = work.resolve("windows");
        Path folder = Files.createDirectories(root.resolve(systemFolder).resolve("WindowsPowerShell").resolve("v1.0"));
        Files.copy(FixtureJdk.stub(), folder.resolve("powershell.exe"));
        return root;
    }

    private static Map<String, String> environment(String... keysAndValues) {
        Map<String, String> environment = new HashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            environment.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return environment;
    }

    @Test
    void powerShellInConstrainedLanguageModeGetsAnExplanationAndNothingElseIsTried() {
        Path cache = work.resolve("cache");

        Result result = Stage0.runConstrained(work, cache);

        assertTrue(result.output().contains("languageMode=ConstrainedLanguage"), result.output());
        assertEquals(81, result.exitCode(), result.output());
        assertTrue(result.output().contains("FTC-Sim could not start: PowerShell is running in 'ConstrainedLanguage' mode"), result.output());
        assertTrue(result.output().contains("you do not need to install anything"), result.output());
        assertTrue(result.output().contains("show this window to your IT administrator or team mentor"), result.output());
        assertTrue(result.output().contains("that runtime.exactRelease names in jdk-manifest.json") && !result.output().contains("JDK 25"), "the advice names the pinned build, not a Java version that the pin would refuse:\n" + result.output());
        assertTrue(result.output().contains("[FTCSIM-RESTRICTED, exit code 81]"), result.output());
        assertFalse(Files.exists(cache), "a restricted PowerShell must not get as far as touching the cache");
    }

    @Test
    void aScriptThatCannotBeStartedShowsTheWrapperMessageAndKeepsTheWindowOpen() throws Exception {
        Path windows = windowsWithPowerShellIn("System32");
        Map<String, String> blocked = environment("STUB_PS_KEEP_MARK", "1");

        Result result = Stage0.runWrapper(work, windows, blocked);

        assertEquals(79, result.exitCode(), result.output());
        assertTrue(result.output().contains("FTC-Sim could not run its PowerShell setup script on this PC."), result.output());
        assertTrue(result.output().contains("AppLocker or Windows Defender Application Control"), result.output());
        assertTrue(result.output().contains("Nothing was installed or changed, and you do not need to install anything."), result.output());
        assertTrue(result.output().contains("[FTCSIM-SCRIPTBLOCKED, exit code 79]"), result.output());
        assertFalse(result.output().contains("Press any key"), "the pause is only for windows opened by double-click and is switched off here");

        blocked.put("FTCSIM_NO_PAUSE", null);
        Result doubleClicked = Stage0.runWrapper(work, windows, blocked);
        assertEquals(79, doubleClicked.exitCode(), doubleClicked.output());
        assertTrue(doubleClicked.output().contains("Press any key to continue"), doubleClicked.output());
    }

    @Test
    void aPcWithoutPowerShellGetsTheSameWrapperMessage() throws Exception {
        Path windows = Files.createDirectories(work.resolve("windows"));

        Result result = Stage0.runWrapper(work, windows, environment());

        assertEquals(79, result.exitCode(), result.output());
        assertTrue(result.output().contains("[FTCSIM-SCRIPTBLOCKED, exit code 79]"), result.output());
    }

    @Test
    void aWrapperStartedFromInsideTheZipWithoutTheRestOfItsFolderTellsTheDriverToExtractAll() throws Exception {
        Path alone = Files.createDirectories(work.resolve("Temp zip view"));
        Path wrapper = Files.copy(Stage0.scripts().resolve("FTC-Sim.cmd"), alone.resolve("FTC-Sim.cmd"));
        Path windows = Path.of(System.getenv("SystemRoot"));

        Result result = Stage0.runWrapperOf(wrapper, work, windows, environment());

        assertEquals(79, result.exitCode(), result.output());
        assertTrue(result.output().contains("right-click the zip file, choose Extract All"), result.output());
        assertTrue(result.output().contains("[FTCSIM-NOTUNZIPPED, exit code 79]"), result.output());
        assertFalse(result.output().contains("SCRIPTBLOCKED"), "the cause is not a blocked script: " + result.output());
        Map<String, String> doubleClick = environment();
        doubleClick.put("FTCSIM_NO_PAUSE", null);
        assertTrue(Stage0.runWrapperOf(wrapper, work, windows, doubleClick).output().contains("Press any key to continue"), "a double-click keeps the window open");
    }

    @Test
    void theWrapperPassesArgumentsToTheScriptAndItsExitCodeBack() throws Exception {
        Path windows = windowsWithPowerShellIn("System32");
        Path log = work.resolve("arguments.log");

        Result result = Stage0.runWrapper(work, windows, environment("STUB_PS_LOG", log.toString(), "STUB_PS_EXIT", "7"),
                "first", "\"a b\"", "--flag");

        assertEquals(7, result.exitCode(), result.output());
        assertFalse(result.output().contains("SCRIPTBLOCKED"), result.output());
        assertEquals(List.of("-NoLogo", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", Stage0.scripts().resolve("FTC-Sim.ps1").toString(),
                "first", "a b", "--flag"), Files.readAllLines(log));
    }

    @Test
    void aDoubleClickWithoutOptionsLetsTheConsoleCloseOnceTheWindowIsUpButOptionsAndScriptsKeepWaitingForTheApplication() throws Exception {
        Path windows = windowsWithPowerShellIn("System32");
        Path log = work.resolve("arguments.log");
        Map<String, String> doubleClick = environment("STUB_PS_LOG", log.toString());
        doubleClick.put("FTCSIM_NO_PAUSE", null);

        assertEquals(0, Stage0.runWrapper(work, windows, doubleClick).exitCode());
        assertTrue(Files.readAllLines(log).contains("-Detach"), "a double-click detaches: " + Files.readAllLines(log));

        assertEquals(0, Stage0.runWrapper(work, windows, doubleClick, "--no-focus").exitCode());
        assertFalse(Files.readAllLines(log).contains("-Detach"), "a start with options waits: " + Files.readAllLines(log));

        assertEquals(0, Stage0.runWrapper(work, windows, environment("STUB_PS_LOG", log.toString())).exitCode());
        assertFalse(Files.readAllLines(log).contains("-Detach"), "FTCSIM_NO_PAUSE asks for a script's behaviour: " + Files.readAllLines(log));
    }

    @Test
    void aThirtyTwoBitCommandPromptStartsTheNativePowerShell() throws Exception {
        Path windows = windowsWithPowerShellIn("Sysnative");
        Path log = work.resolve("arguments.log");

        Result result = Stage0.runWrapper(work, windows,
                environment("PROCESSOR_ARCHITEW6432", "AMD64", "STUB_PS_LOG", log.toString(), "STUB_PS_EXIT", "5"));

        assertEquals(5, result.exitCode(), result.output());
        assertTrue(Files.exists(log), "the Sysnative PowerShell must have been started");
    }

    @Test
    void aPcThatIsNotX64IsRejectedWithAClearMessage() throws Exception {
        for (String architecture : List.of("Arm64", "X86", "Arm")) {
            Result result = Stage0.scenario(work, manifest, "function Get-MachineArchitecture { '" + architecture + "' }\nAssert-SupportedPlatform");

            assertEquals(3, result.exitCode(), result.output());
            assertEquals("UnsupportedOs", result.kind(), result.output());
            assertTrue(result.line("MESSAGE ").contains(architecture), result.output());
            assertTrue(result.line("MESSAGE ").contains("64-bit Windows on an Intel or AMD (x64) processor"), result.output());
        }
    }

    @Test
    void thisPcIsRecognisedAsSupported() {
        Result result = Stage0.scenario(work, manifest, "Out-Fact architecture (Get-MachineArchitecture)\nAssert-SupportedPlatform\nOut-Fact supported 'yes'");

        assertEquals(0, result.exitCode(), result.output());
        assertEquals("X64", result.fact("architecture"));
        assertEquals("yes", result.fact("supported"));
    }

    @Test
    void aDataFolderThatCannotBeUsedIsExplainedAndNotReportedAsABug() throws Exception {
        Files.writeString(work.resolve("a-file-in-the-way"), "x");
        List<String> roots = new ArrayList<>(List.of(work.resolve("a-file-in-the-way").resolve("FTC-Sim").toString()));
        String unusedDrive = IntStream.rangeClosed('D', 'Z').mapToObj(letter -> (char) letter + ":\\")
                .filter(drive -> !Files.exists(Path.of(drive))).findFirst().orElse(null);
        if (unusedDrive != null) {
            roots.add(unusedDrive + "FTC-Sim");
        }

        for (String root : roots) {
            Result result = Stage0.run(Map.of(), "-ResolveOnly", "-NonInteractive", "-IgnoreSystemJdks", "-CacheRoot", root,
                    "-ManifestPath", manifest.toString());

            assertEquals(91, result.exitCode(), root + "\n" + result.output());
            assertTrue(result.output().contains("FTC-Sim could not use its data folder " + root), result.output());
            assertTrue(result.output().contains("[FTCSIM-ACCESSDENIED, exit code 91]"), result.output());
        }
        Result invalid = Stage0.run(Map.of(), "-ResolveOnly", "-NonInteractive", "-IgnoreSystemJdks", "-CacheRoot", "C:\\bad|name",
                "-ManifestPath", manifest.toString());
        assertEquals(93, invalid.exitCode(), invalid.output());
        assertTrue(invalid.output().contains("is not a valid Windows path"), invalid.output());
    }

    @Test
    void aMissingApplicationJarIsReportedWithItsOwnExitCodeBeforeAnythingIsStarted() throws Exception {
        Path home = FixtureJdk.at(work.resolve("jdk25")).build();
        Path missing = work.resolve("lib").resolve("app.jar");
        Path cache = work.resolve("cache");

        Result result = Stage0.run(Map.of("JAVA_HOME", home.toString()), "-NoProvision", "-NonInteractive", "-CacheRoot", cache.toString(),
                "-ManifestPath", manifest.toString(), "-DiscoverySources", "JavaHome", "-AppJar", missing.toString());

        assertEquals(92, result.exitCode(), result.output());
        assertTrue(result.output().contains("FTC-Sim's program file is missing: " + missing), result.output());
        assertTrue(result.output().contains("Unzip the FTC-Sim download again"), result.output());
        assertFalse(Files.exists(cache.resolve("logs")), "nothing was started, so no log was begun");
    }

    @Test
    void everyFailureKindHasItsOwnExitCodeBetween80And94AndIsRaisedBySomeScript() throws IOException {
        String messages = Files.readString(Stage0.scripts().resolve("ftcsim").resolve("Messages.ps1"));
        Matcher table = Pattern.compile("(?m)^\\s{4}(\\w+)\\s*= @\\{ Code = (\\d+);").matcher(messages);
        Map<String, Integer> codes = new LinkedHashMap<>();
        while (table.find()) {
            codes.put(table.group(1), Integer.parseInt(table.group(2)));
        }
        assertEquals(new TreeSet<>(IntStream.rangeClosed(80, 94).boxed().toList()), new TreeSet<>(codes.values()), codes.toString());

        Pattern raised = Pattern.compile("(?:Stop-Ftc|Write-FtcFailure|New-FtcException)\\s+'(\\w+)'");
        TreeSet<String> used = new TreeSet<>();
        try (Stream<Path> scripts = Files.walk(Stage0.scripts())) {
            for (Path script : scripts.filter(path -> path.toString().endsWith(".ps1")).toList()) {
                Matcher matcher = raised.matcher(Files.readString(script));
                while (matcher.find()) {
                    assertTrue(codes.containsKey(matcher.group(1)), script.getFileName() + " raises the unknown failure " + matcher.group(1));
                    used.add(matcher.group(1));
                }
            }
        }
        TreeSet<String> neverRaised = new TreeSet<>(codes.keySet());
        neverRaised.removeAll(used);
        neverRaised.remove("Unexpected");
        assertEquals(new TreeSet<String>(), neverRaised, "a failure kind that nothing raises is a check that was never written");
    }
}
