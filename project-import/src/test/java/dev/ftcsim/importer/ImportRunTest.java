package dev.ftcsim.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.ftcsim.contracts.robot.OpModeDescriptor;
import dev.ftcsim.contracts.robot.OpModeKind;
import dev.ftcsim.importer.ImportException.Failure;
import dev.ftcsim.importer.manifest.PackageManifest;
import dev.ftcsim.importer.pack.PackageZip;
import dev.ftcsim.importer.pack.PreparedProgram;
import dev.ftcsim.importer.scan.Finding;
import dev.ftcsim.importer.snapshot.SourceManifest;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The whole import, with a fake Gradle wrapper in place of the real one so that it runs in a second: the project is
 * copied, the "build" reports what it produced, and the importer turns that into a package. The real thing, with
 * Hornet, is {@code HornetImportTest}.
 */
class ImportRunTest {

    /** Collects what the importer reports, and cancels when asked to. */
    private static final class Recorder implements ImportProgress {
        final List<ImportStage> stages = Collections.synchronizedList(new ArrayList<>());
        final List<String> messages = Collections.synchronizedList(new ArrayList<>());
        private final BooleanSupplier cancel;

        Recorder(BooleanSupplier cancel) {
            this.cancel = cancel;
        }

        Recorder() {
            this(() -> false);
        }

        @Override
        public void stage(ImportStage stage) {
            stages.add(stage);
        }

        @Override
        public void message(String text) {
            messages.add(text);
        }

        @Override
        public boolean isCancelled() {
            return cancel.getAsBoolean();
        }
    }

    @BeforeAll
    static void aJdkRunsTheFakeBuild() {
        assumeTrue(
                Files.isRegularFile(Path.of(System.getProperty("java.home"), "bin", "javac.exe")),
                "the importer needs a JDK to run the build, and these tests use the one that runs them");
    }

    @Test
    void importsAProjectIntoAVerifiedPackageAndLeavesTheOriginalAlone(@TempDir Path temp) throws Exception {
        FakeProject project = new FakeProject(temp).ready();
        Map<String, String> before = SourceManifest.scan(project.root, ImportProgress.SILENT).hashes();
        Recorder progress = new Recorder();
        Path zip = temp.resolve("out/practice.zip");

        ImportResult result = ProjectImporter.importProject(project.request(temp).withPackageZip(zip), progress);

        assertEquals(List.of(ImportStage.values()), progress.stages);
        assertTrue(progress.messages.stream().anyMatch(line -> line.startsWith("fake gradle started in ")), "the build's output reaches the sink");
        assertEquals(before, SourceManifest.scan(project.root, ImportProgress.SILENT).hashes());
        assertEquals(before, result.originalFiles().hashes());
        assertFalse(Files.exists(temp.resolve("work/snapshot/local.properties")));
        assertEquals(before, readOriginalList(temp.resolve("work/original-sha256.txt")));

        PreparedProgram program = result.program();
        PackageManifest manifest = program.manifest();
        assertEquals("11.1.0", program.sdkVersion());
        assertEquals("project", manifest.project().name());
        assertEquals(":TeamCode", manifest.project().applicationModule());
        assertEquals("org.team", manifest.project().namespace());
        assertEquals(before.size(), manifest.project().sourceFiles());
        assertEquals(fingerprintOf(before), manifest.project().sourceFingerprint());
        assertEquals(List.of("program/TeamCode.jar", "program/Common.jar"), manifest.program(), "the module with OpModes is program");
        assertEquals(
                List.of(
                        new PackageManifest.Library("libs/001-_Samples.jar", ":Samples"),
                        new PackageManifest.Library("libs/002-org.firstinspires.ftc_RobotCore_11.1.0.jar", "org.firstinspires.ftc:RobotCore:11.1.0"),
                        new PackageManifest.Library("libs/003-com.pedropathing_ftc_2.1.1.jar", "com.pedropathing:ftc:2.1.1")),
                manifest.libraries(),
                "the module with only a disabled sample is a library, in class path order");
        assertEquals(
                List.of(
                        new OpModeDescriptor("Common Auto", "", OpModeKind.AUTONOMOUS, "org.common.CommonAuto"),
                        new OpModeDescriptor("Park", "", OpModeKind.AUTONOMOUS, "org.team.Park"),
                        new OpModeDescriptor("Drive", "Main", OpModeKind.TELEOP, "org.team.Drive")),
                program.opModes());
        assertEquals(3, manifest.opModes().count());

        List<String> codes = program.warnings().stream().map(Finding::code).toList();
        assertTrue(codes.contains(Finding.Codes.NATIVE_LIBRARY_PACKAGED));
        Finding bitmap = program.warnings().stream().filter(finding -> finding.code().equals(Finding.Codes.ANDROID_CLASS_MISSING)).findFirst().orElseThrow();
        assertEquals("org.team.Drive", bitmap.subject());
        assertEquals("run()V", bitmap.member());
        assertFalse(codes.contains(Finding.Codes.ANDROID_CHECK_SKIPPED), "the simulator's classes were given");

        try (URLClassLoader loader = new URLClassLoader(new URL[] {program.directory().resolve("resources/r.jar").toUri().toURL()}, null)) {
            loader.loadClass("org.team.R$string").getField("team_name");
            loader.loadClass("org.team.R$string").getField("app_name");
            loader.loadClass("org.firstinspires.ftc.robotcore.R$string").getField("app_name");
        }
        List<String> table = Files.readAllLines(program.directory().resolve("resources/ftcsim-resources.tsv"));
        assertTrue(table.stream().anyMatch(line -> line.endsWith("\tteam_name\tTeam 7421")), table.toString());
        assertTrue(table.stream().anyMatch(line -> line.endsWith("\tapp_name\tRobot Controller")), table.toString());
        assertEquals("<schema/>", Files.readString(program.directory().resolve("resources/assets/sdk/schema.xsd")));
        assertEquals("assets of the team", Files.readString(program.directory().resolve("resources/assets/team.txt")));

        String log = Files.readString(result.gradleLog());
        assertTrue(log.contains("ANDROID_HOME=" + project.sdk), log);
        assertTrue(log.contains("JAVA_HOME=" + System.getProperty("java.home")), log);
        assertTrue(log.contains("--init-script") && log.contains("--no-daemon") && log.contains("-Pftcsim.export.file="), log);
        assertTrue(log.contains("-Dorg.gradle.java.home=" + System.getProperty("java.home")), log);
        assertFalse(log.contains("--offline"));

        assertEquals(zip, result.packageZip().orElseThrow());
        assertEquals(manifest, PackageZip.extract(zip, temp.resolve("drivers-copy")).manifest());
    }

    private static String fingerprintOf(Map<String, String> hashes) {
        return new SourceManifest(new TreeMap<>(hashes)).fingerprint();
    }

    @Test
    void theJavaResourcesOfTheProjectAndItsLibrariesAreMergedIntoOneJarAsTheAndroidPluginMergesThem(@TempDir Path temp) throws Exception {
        FakeProject project = new FakeProject(temp).ready();
        Recorder progress = new Recorder();

        PreparedProgram program = ProjectImporter.importProject(project.request(temp), progress).program();

        assertEquals("resources/java-res.jar", program.manifest().resources().javaResources());
        assertEquals(ProjectImporter.IMPORTER_REVISION, program.importerRevision());
        Path jar = program.directory().resolve("resources/java-res.jar");
        assertEquals(jar, program.robotClasspath().get(program.robotClasspath().size() - 1), "after the libraries");
        assertEquals(jar, program.applicationClasspath().get(program.applicationClasspath().size() - 1));
        Map<String, String> entries = new TreeMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                try (InputStream in = zip.getInputStream(entry)) {
                    entries.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.ISO_8859_1));
                }
            }
        }
        assertEquals(
                List.of("META-INF/services/com.acme.Provider", "common.txt", "conflict.txt", "decode-dark.png", "sdk/version.txt", "team.properties"),
                List.copyOf(entries.keySet()),
                "no classes, and none of the files the plugin excludes (MANIFEST.MF, LICENSE)");
        assertEquals("org.rc.RcProvider\ncom.pedropathing.PedroProvider\n", entries.get("META-INF/services/com.acme.Provider"), "joined, in class path order");
        assertEquals("from the app", entries.get("conflict.txt"), "the application's own file first, then the project's modules, then the libraries");
        assertEquals("11.1.0", entries.get("sdk/version.txt"));
        Finding conflict = program.warnings().stream().filter(finding -> finding.code().equals(Finding.Codes.JAVA_RESOURCE_CONFLICT)).findFirst().orElseThrow();
        assertEquals("conflict.txt", conflict.subject());
        assertTrue(conflict.detail().contains(":TeamCode") && conflict.detail().contains(":Common") && conflict.detail().contains("com.pedropathing:ftc:2.1.1"), conflict.detail());
        assertTrue(progress.messages.stream().anyMatch(line -> line.startsWith("Merged 6 Java resources") && line.contains("1 of them joined")), progress.messages.toString());
        try (URLClassLoader loader = new URLClassLoader(program.robotClasspath().stream().map(entry -> {
            try {
                return entry.toUri().toURL();
            } catch (IOException impossible) {
                throw new IllegalStateException(impossible);
            }
        }).toArray(URL[]::new), null)) {
            assertTrue(loader.getResource("decode-dark.png") != null && loader.getResource("team.properties") != null);
        }
    }

    @Test
    void aProjectWithoutJavaResourcesGetsNoJavaResourcesJar(@TempDir Path temp) throws Exception {
        FakeProject project = new FakeProject(temp).withoutJavaResources().ready();

        PreparedProgram program = ProjectImporter.importProject(project.request(temp), ImportProgress.SILENT).program();

        assertEquals("", program.manifest().resources().javaResources());
        assertFalse(Files.exists(program.directory().resolve("resources/java-res.jar")));
        assertTrue(program.warnings().stream().noneMatch(finding -> finding.code().equals(Finding.Codes.JAVA_RESOURCES_MISSING)), "it is a current package");
    }

    private static Map<String, String> readOriginalList(Path file) throws IOException {
        Map<String, String> hashes = new TreeMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            hashes.put(line.substring(66), line.substring(0, 64));
        }
        return hashes;
    }

    @Test
    void offlineAndGradleHomeReachTheBuild(@TempDir Path temp) throws Exception {
        FakeProject project = new FakeProject(temp).ready();
        Path home = Files.createDirectory(temp.resolve("gradle-home"));

        ImportResult result = ProjectImporter.importProject(project.request(temp).withOffline(true).withGradleUserHome(home), ImportProgress.SILENT);

        String log = Files.readString(result.gradleLog());
        assertTrue(log.contains("--offline"), log);
        assertTrue(log.contains("GRADLE_USER_HOME=" + home), log);
    }

    @Test
    void aProjectThatChangesWhileItIsImportedFailsAndNothingIsLeft(@TempDir Path temp) throws Exception {
        FakeProject project = new FakeProject(temp).control("touch", project("TeamCode/src/main/java/org/team/Drive.java", temp)).ready();

        ImportException failure = assertThrows(ImportException.class,
                () -> ProjectImporter.importProject(project.request(temp).withPackageZip(temp.resolve("p.zip")), ImportProgress.SILENT));

        assertEquals(Failure.ORIGINAL_CHANGED, failure.failure());
        assertTrue(failure.getMessage().contains("TeamCode/src/main/java/org/team/Drive.java"), failure.getMessage());
        assertFalse(Files.exists(temp.resolve("package")), "no package is kept");
        assertFalse(Files.exists(temp.resolve("p.zip")));
    }

    private static String project(String relative, Path temp) {
        return temp.resolve("project").resolve(relative).toString();
    }

    @Test
    void aFailedBuildIsDiagnosedFromItsOutputAndKeepsItsLog(@TempDir Path temp) throws Exception {
        Path output = temp.resolve("captured.txt");
        try (InputStream in = ImportRunTest.class.getResourceAsStream("gradle/captured/no-android-sdk.txt")) {
            Files.write(output, in.readAllBytes());
        }
        FakeProject project = new FakeProject(temp).control("printFile", output).control("exitCode", 1).ready();

        ImportException failure = assertThrows(ImportException.class, () -> ProjectImporter.importProject(project.request(temp), ImportProgress.SILENT));

        assertEquals(Failure.NO_ANDROID_SDK, failure.failure());
        assertTrue(Files.readString(failure.logFile().orElseThrow()).contains("SDK location not found"));
        assertFalse(Files.exists(temp.resolve("package")));
    }

    @Test
    void cancellingStopsTheBuildAndEverythingItStarted(@TempDir Path temp) throws Exception {
        Path pidFile = temp.resolve("pid");
        FakeProject project = new FakeProject(temp).control("pidFile", pidFile).control("sleepMillis", 120_000).ready();
        Recorder progress = new Recorder(() -> Files.exists(pidFile));

        long started = System.nanoTime();
        ImportException failure = assertThrows(ImportException.class, () -> ProjectImporter.importProject(project.request(temp), progress));

        assertEquals(Failure.CANCELLED, failure.failure());
        assertTrue(System.nanoTime() - started < Duration.ofSeconds(60).toNanos(), "cancelling does not wait for the build");
        assertGone(Long.parseLong(Files.readString(pidFile).trim()));
        assertFalse(Files.exists(temp.resolve("package")));
    }

    @Test
    void aBuildThatTakesTooLongIsStopped(@TempDir Path temp) throws Exception {
        Path pidFile = temp.resolve("pid");
        FakeProject project = new FakeProject(temp).control("pidFile", pidFile).control("sleepMillis", 120_000).ready();

        ImportException failure = assertThrows(
                ImportException.class,
                () -> ProjectImporter.importProject(project.request(temp).withGradleTimeout(Duration.ofSeconds(2)), ImportProgress.SILENT));

        assertEquals(Failure.TIMED_OUT, failure.failure());
        assertGone(Long.parseLong(Files.readString(pidFile).trim()));
    }

    private static void assertGone(long pid) throws InterruptedException {
        for (int i = 0; i < 100 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false); i++) {
            Thread.sleep(100);
        }
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "process " + pid + " is still running");
    }

    @Test
    void aProjectThatDoesNotUseTheFtcSdkIsNotAnFtcProject(@TempDir Path temp) throws Exception {
        FakeProject project = new FakeProject(temp);
        project.withExport(List.of(
                        "ftcsim-export\t1",
                        "application\t:TeamCode\torg.team",
                        "classes\t" + temp.resolve("builds/app-classes"),
                        "library\tmodule\tcom.pedropathing:ftc:2.1.1\t" + temp.resolve("builds/pedro.jar") + "\t-",
                        "module\t:TeamCode\tapplication\torg.team"))
                .ready();

        ImportException failure = assertThrows(ImportException.class, () -> ProjectImporter.importProject(project.request(temp), ImportProgress.SILENT));

        assertEquals(Failure.NOT_AN_FTC_PROJECT, failure.failure());
        assertFalse(Files.exists(temp.resolve("package")));
    }

    @Test
    void anUntrustedImportStartsNothing(@TempDir Path temp) throws Exception {
        FakeProject project = new FakeProject(temp).ready();
        ImportRequest untrusted = ImportRequest.of(
                project.root, temp.resolve("work"), temp.resolve("package"), temp, temp, BuildTrust.DO_NOT_TRUST);

        ImportException refused = assertThrows(ImportException.class, () -> ProjectImporter.importProject(untrusted, ImportProgress.SILENT));

        assertEquals(Failure.NOT_TRUSTED, refused.failure());
        assertFalse(Files.exists(temp.resolve("work")), "nothing was copied");
        assertFalse(Files.exists(temp.resolve("package")));
    }

    @Test
    void requestsThatCannotWorkAreRefusedBeforeAnythingIsCopiedOrStarted(@TempDir Path temp) throws Exception {
        FakeProject project = new FakeProject(temp).ready();
        Path jdk = Path.of(System.getProperty("java.home"));
        Path occupied = Files.createDirectory(temp.resolve("occupied"));
        Files.writeString(occupied.resolve("keep.txt"), "mine");
        Path jre = Files.createDirectories(temp.resolve("jre/bin"));
        Files.createFile(jre.resolve("java.exe"));
        BuildTrust trust = BuildTrust.TRUST_BUILD_SCRIPTS;

        assertEquals(Failure.INVALID_REQUEST, refusal(ImportRequest.of(project.root, temp.resolve("w1"), occupied, jdk, project.sdk, trust)));
        assertEquals("mine", Files.readString(occupied.resolve("keep.txt")), "a folder that was there is never cleaned up");
        assertEquals(Failure.INVALID_REQUEST, refusal(ImportRequest.of(project.root, project.root.resolve("work"), temp.resolve("p2"), jdk, project.sdk, trust)));
        assertEquals(Failure.INVALID_REQUEST, refusal(ImportRequest.of(project.root, temp.resolve("w3"), project.root.resolve("package"), jdk, project.sdk, trust)));
        assertEquals(Failure.INVALID_REQUEST, refusal(ImportRequest.of(project.root, temp.resolve("w4"), temp.resolve("p4"), temp.resolve("jre"), project.sdk, trust)));
        assertEquals(Failure.NO_ANDROID_SDK, refusal(ImportRequest.of(project.root, temp.resolve("w5"), temp.resolve("p5"), jdk, temp.resolve("nowhere"), trust)));
        Files.delete(project.root.resolve("gradle/wrapper/gradle-wrapper.jar"));
        assertEquals(Failure.NOT_A_GRADLE_PROJECT, refusal(ImportRequest.of(project.root, temp.resolve("w6"), temp.resolve("p6"), jdk, project.sdk, trust)));
        Files.delete(project.root.resolve("settings.gradle"));
        assertEquals(Failure.NOT_A_GRADLE_PROJECT, refusal(ImportRequest.of(project.root, temp.resolve("w7"), temp.resolve("p7"), jdk, project.sdk, trust)));
        assertEquals(Failure.INVALID_REQUEST, refusal(ImportRequest.of(temp.resolve("absent"), temp.resolve("w8"), temp.resolve("p8"), jdk, project.sdk, trust)));

        for (String name : List.of("w1", "w3", "w4", "w5", "w6", "w7", "w8", "p2", "p4", "p5", "p6", "p7", "p8")) {
            assertFalse(Files.exists(temp.resolve(name)), name + " was created although the request could not work");
        }
        assertFalse(Files.exists(project.root.resolve("work")) || Files.exists(project.root.resolve("package")), "nothing is written into the project");
    }

    private static Failure refusal(ImportRequest request) {
        return assertThrows(ImportException.class, () -> ProjectImporter.importProject(request, ImportProgress.SILENT)).failure();
    }
}
