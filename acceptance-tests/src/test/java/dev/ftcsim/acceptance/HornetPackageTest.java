package dev.ftcsim.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.ftcsim.contracts.robot.OpModeDescriptor;
import dev.ftcsim.importer.BuildTrust;
import dev.ftcsim.importer.ImportProgress;
import dev.ftcsim.importer.ImportRequest;
import dev.ftcsim.importer.ImportResult;
import dev.ftcsim.importer.ImportStage;
import dev.ftcsim.importer.ProjectImporter;
import dev.ftcsim.importer.pack.PreparedProgram;
import dev.ftcsim.importer.scan.Finding;
import dev.ftcsim.importer.snapshot.SourceManifest;
import dev.ftcsim.simulator.RobotProgram;
import dev.ftcsim.simulator.RobotWorldLauncher;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Hornet, imported from its unmodified source through its own Gradle build, as a package that the real SDK host
 * boots on a simulated robot controller. Runs only when {@code -Pftcsim.hornet=<path to the Hornet checkout>} is
 * given, because the import needs a JDK, the Android SDK and about a minute.
 */
class HornetPackageTest {
    private static final String HOW_TO_ENABLE = "Skipped: give -Pftcsim.hornet=<path to the Hornet checkout> to import Hornet and boot "
            + "the SDK host on the package (optionally -Pftcsim.jdk=<JDK home> -Pftcsim.androidSdk=<Android SDK folder>).";

    @Test
    @Timeout(1200)
    void theSdkHostListsHornetsOpModesFromThePreparedPackage() throws Exception {
        String checkout = System.getProperty("ftcsim.hornet");
        if (checkout == null) {
            System.out.println("HORNET-PACKAGE> " + HOW_TO_ENABLE);
        }
        assumeTrue(checkout != null, HOW_TO_ENABLE);
        String sdk = System.getProperty("ftcsim.androidSdk", System.getenv("ANDROID_HOME"));
        assumeTrue(sdk != null, "No Android SDK: give -Pftcsim.androidSdk=<folder> or set ANDROID_HOME");

        Path temp = Files.createTempDirectory("ftcsim-hornet-package");
        try {
            listTheOpModes(checkout, sdk, temp);
        } finally {
            // Panels reads its images through jar: URLs, and the JDK keeps such a jar open until the JVM ends, while Windows
            // will not delete an open file; a temporary folder that cannot be emptied is left to the operating system.
            deleteWhatCanBeDeleted(temp);
        }
    }

    private static void listTheOpModes(String checkout, String sdk, Path temp) throws Exception {
        List<Path> simulator = RobotWorld.classpath("ftcsim.simulatorClasspath");
        Map<String, String> originalBefore = SourceManifest.scan(Path.of(checkout), ImportProgress.SILENT).hashes();
        ImportRequest request = ImportRequest.of(
                        Path.of(checkout),
                        temp.resolve("work"),
                        temp.resolve("package"),
                        Path.of(System.getProperty("ftcsim.jdk", System.getProperty("java.home"))),
                        Path.of(sdk),
                        BuildTrust.TRUST_BUILD_SCRIPTS)
                .withSimulatorClasspath(simulator);
        List<ImportStage> stages = new ArrayList<>();
        long importStarted = System.nanoTime();
        ImportResult imported = ProjectImporter.importProject(request, new ImportProgress() {
            @Override
            public void stage(ImportStage stage) {
                stages.add(stage);
            }

            @Override
            public void message(String text) {}

            @Override
            public boolean isCancelled() {
                return false;
            }
        });
        PreparedProgram prepared = imported.program();
        report("import took " + (System.nanoTime() - importStarted) / 1_000_000 + " ms");
        assertEquals(List.of(ImportStage.values()), stages);
        assertEquals(originalBefore, SourceManifest.scan(Path.of(checkout), ImportProgress.SILENT).hashes(), "the original project is untouched");
        assertEquals(originalBefore, imported.originalFiles().hashes());
        report("the package holds " + prepared.manifest().files().size() + " files, " + prepared.manifest().libraries().size()
                + " libraries, SDK " + prepared.sdkVersion() + ", importer revision " + prepared.importerRevision());

        // The libraries' class jars hold no images or provider files, the package's Java resources do: Panels loads decode-dark.png this way.
        try (URLClassLoader loader = new URLClassLoader(prepared.robotClasspath().stream().map(HornetPackageTest::url).toArray(URL[]::new), null)) {
            for (String image : List.of("decode-dark.png", "decode-light.png")) {
                try (InputStream in = loader.getResourceAsStream(image)) {
                    assertNotNull(in, image + " is found on the robot class path");
                    int bytes = in.readAllBytes().length;
                    report(image + ": " + bytes + " bytes through a class loader over the robot class path");
                    assertTrue(bytes > 1000, image);
                }
            }
        }

        Map<String, Long> findings = new TreeMap<>(
                prepared.warnings().stream().collect(Collectors.groupingBy(Finding::code, Collectors.counting())));
        report("pre-flight findings by kind against the real Android shim: " + findings);
        prepared.warnings().stream().filter(finding -> !finding.code().equals(Finding.Codes.NATIVE_LIBRARY_PACKAGED))
                .limit(30).forEach(finding -> report("  " + finding));
        assertFalse(findings.containsKey(Finding.Codes.ANDROID_CHECK_SKIPPED), "the shim classes were given, so the Android check ran");

        List<Path> robot = new ArrayList<>(simulator);
        robot.addAll(prepared.robotClasspath());
        RobotProgram program = new RobotProgram(robot, prepared.applicationClasspath(), "Hornet from " + checkout);
        Path storage = Files.createTempDirectory("ftcsim-hornet-sdcard");
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        long bootStarted = System.nanoTime();
        try (RobotWorldLauncher.RobotWorld world = RobotWorldLauncher.launch(
                program, new DeclineNativesHooks(), storage, log::add, TestRobot.controlHubOnly(System::nanoTime).environment())) {
            List<OpModeDescriptor> sdkList = world.host().opModes();
            report("the SDK host booted on the package and listed its OpModes in " + (System.nanoTime() - bootStarted) / 1_000_000 + " ms");
            report("the real SDK host lists " + sdkList.size() + " OpModes of the package:");
            sdkList.forEach(opMode -> report("  " + opMode));
            List<OpModeDescriptor> scanned = prepared.opModes();
            List<OpModeDescriptor> registeredInCode = sdkList.stream().filter(opMode -> !scanned.contains(opMode)).toList();
            report("the bytecode scan of the importer found " + scanned.size());
            report("listed by the host but declared in no class file (registered in code): " + registeredInCode);
            List<Finding> registrars = prepared.warnings().stream()
                    .filter(finding -> finding.code().equals(Finding.Codes.OPMODE_REGISTRAR))
                    .toList();
            report("methods the importer points out as registering OpModes: " + registrars.stream().map(finding -> finding.subject() + "." + finding.member()).toList());

            assertEquals(scanned, sdkList.stream().filter(scanned::contains).toList(), "the host lists every OpMode of the scan, in the same order");
            assertTrue(scanned.size() == 10 || scanned.size() == 11, "OpModes found by the scan: " + scanned);
            assertFalse(registrars.isEmpty(), "OpModes registered in code, which no scan can list, are pointed out");
            assertTrue(
                    registeredInCode.stream().allMatch(opMode -> opMode.className().startsWith("com.bylazar.panels.")),
                    "the only OpModes beyond the scan come from Panels' registrar: " + registeredInCode);
        } finally {
            deleteTree(storage);
        }
        List<String> problems = log.stream().filter(line -> line.startsWith("W/") || line.startsWith("E/")).toList();
        report("SDK log lines at boot: " + log.size() + ", warnings and errors: " + problems.size());
        // The SDK looks at every class of the application, its own included, and a class whose Android types the shim lacks cannot load.
        String lacking = "NoClassDefFoundError: ";
        Map<String, Long> missingTypes = problems.stream()
                .filter(line -> line.contains(lacking))
                .collect(Collectors.groupingBy(
                        line -> line.substring(line.indexOf(lacking) + lacking.length()).trim().replace('/', '.'), TreeMap::new, Collectors.counting()));
        report(missingTypes.size() + " types that the shim lacks were needed by classes the SDK tried to load:");
        missingTypes.forEach((type, failures) -> report("  " + type + " (" + failures + " failures logged)"));
        Set<String> shown = new HashSet<>();
        for (int i = 0; i < log.size() && shown.size() < 4; i++) {
            String line = log.get(i);
            boolean headline = (line.startsWith("W/") || line.startsWith("E/"))
                    && !line.contains(": \tat ") && !line.contains(": \t... ")
                    && !line.contains("NoClassDefFoundError") && !line.contains("ClassNotFoundException");
            if (headline && shown.add(line)) {
                report("  other: " + line);
                for (int next = i + 1; next < Math.min(log.size(), i + 120); next++) {
                    if (log.get(next).contains("Caused by:")) {
                        report("      " + log.get(next));
                        report("      " + log.get(Math.min(next + 1, log.size() - 1)));
                    }
                }
            }
        }
    }

    private static void report(String line) {
        System.out.println("HORNET-PACKAGE> " + line);
    }

    private static URL url(Path path) {
        try {
            return path.toUri().toURL();
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void deleteWhatCanBeDeleted(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException stillOpen) {
                    report("left behind: " + path);
                }
            }
        }
    }
}
