package dev.ftcsim.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.ftcsim.contracts.robot.OpModeDescriptor;
import dev.ftcsim.importer.manifest.PackageManifest;
import dev.ftcsim.importer.pack.PackageException;
import dev.ftcsim.importer.pack.PackageZip;
import dev.ftcsim.importer.pack.PreparedProgram;
import dev.ftcsim.importer.scan.Finding;
import dev.ftcsim.importer.snapshot.SourceManifest;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Imports Hornet, the acceptance project, through its own Gradle build: slow, and it needs a JDK and the Android SDK,
 * so it runs only when {@code -Pftcsim.hornet=<path to the checkout>} is given.
 */
@Tag("hornet")
class HornetImportTest {
    private static final String HOW_TO_ENABLE = "Skipped: the end-to-end import of Hornet is slow and needs the Android SDK. "
            + "Run it with -Pftcsim.hornet=<path to the Hornet checkout> (and optionally -Pftcsim.jdk=<JDK 17 or 21 home> "
            + "-Pftcsim.androidSdk=<Android SDK folder>, which default to this JVM and ANDROID_HOME).";

    @Test
    @Timeout(1200)
    void hornetImportsUnmodifiedAndTheStoredPackageIsComplete(@TempDir Path temp) throws Exception {
        String checkout = System.getProperty("ftcsim.hornet");
        if (checkout == null) {
            System.out.println("HORNET> " + HOW_TO_ENABLE);
        }
        assumeTrue(checkout != null, HOW_TO_ENABLE);
        Path hornet = Path.of(checkout);
        Path jdk = Path.of(System.getProperty("ftcsim.jdk", System.getProperty("java.home")));
        String sdkLocation = System.getProperty("ftcsim.androidSdk", System.getenv("ANDROID_HOME"));
        assumeTrue(sdkLocation != null, "No Android SDK: give -Pftcsim.androidSdk=<folder> or set ANDROID_HOME");

        Map<String, String> before = SourceManifest.scan(hornet, ImportProgress.SILENT).hashes();
        List<ImportStage> stages = new ArrayList<>();
        List<String> output = new ArrayList<>();
        ImportProgress progress = new ImportProgress() {
            @Override
            public void stage(ImportStage stage) {
                stages.add(stage);
                System.out.println("HORNET> == " + stage.label());
            }

            @Override
            public void message(String text) {
                output.add(text);
            }

            @Override
            public boolean isCancelled() {
                return false;
            }
        };
        Path packageDirectory = temp.resolve("package");
        Path zip = temp.resolve("hornet.zip");
        ImportRequest request = ImportRequest.of(
                        hornet, temp.resolve("work"), packageDirectory, jdk, Path.of(sdkLocation), BuildTrust.TRUST_BUILD_SCRIPTS)
                .withPackageZip(zip);

        long started = System.nanoTime();
        ImportResult result = ProjectImporter.importProject(request, progress);
        System.out.println("HORNET> import took " + (System.nanoTime() - started) / 1_000_000_000 + " s");

        // The original is untouched: hashed independently of the importer, before and after.
        assertEquals(before, SourceManifest.scan(hornet, ImportProgress.SILENT).hashes());
        assertEquals(before, result.originalFiles().hashes());
        assertEquals(List.of(ImportStage.values()), stages);
        assertTrue(output.stream().anyMatch(line -> line.contains("BUILD SUCCESSFUL")), "Gradle's own output reaches the progress sink");

        PreparedProgram program = PreparedProgram.open(packageDirectory);
        PackageManifest manifest = program.manifest();
        assertEquals("11.1.0", program.sdkVersion());
        assertEquals(":TeamCode", manifest.project().applicationModule());

        List<OpModeDescriptor> opModes = program.opModes();
        System.out.println("HORNET> " + opModes.size() + " OpModes from the bytecode scan:");
        opModes.forEach(opMode -> System.out.println("HORNET>   " + opMode));
        assertTrue(opModes.size() == 10 || opModes.size() == 11, "OpModes found: " + opModes);
        assertTrue(opModes.stream().anyMatch(opMode -> opMode.name().equals("Teleop - Blue")));
        assertTrue(opModes.stream().anyMatch(opMode -> opMode.className().equals("org.firstinspires.ftc.teamcode.OpModes.CloseAutoBlue")));

        List<String> libraries = manifest.libraries().stream().map(PackageManifest.Library::id).toList();
        for (String expected : List.of("com.pedropathing:ftc:", "org.solverslib:core:", "org.solverslib:pedroPathing:", "com.bylazar:fullpanels:")) {
            assertTrue(libraries.stream().anyMatch(id -> id.startsWith(expected)), expected + " is among " + libraries);
        }
        assertTrue(manifest.sdk().components().contains("org.firstinspires.ftc:RobotCore:11.1.0"));

        // AGP's class jars of the libraries hold no Java resources: they are in the package's own jar, found through a class loader as on a device.
        assertEquals("resources/java-res.jar", manifest.resources().javaResources());
        assertEquals(ProjectImporter.IMPORTER_REVISION, program.importerRevision());
        Path javaResources = program.directory().resolve(manifest.resources().javaResources());
        try (ZipFile jar = new ZipFile(javaResources.toFile())) {
            List<String> names = Collections.list(jar.entries()).stream().map(ZipEntry::getName).sorted().toList();
            System.out.println("HORNET> java-res.jar: " + names.size() + " files, " + Files.size(javaResources) + " bytes; outside META-INF:");
            names.stream().filter(name -> !name.startsWith("META-INF/")).forEach(name -> System.out.println("HORNET>   " + name));
            System.out.println("HORNET> in META-INF: " + names.stream().filter(name -> name.startsWith("META-INF/")).toList());
            assertTrue(names.contains("decode-dark.png") && names.contains("decode-light.png"), names.toString());
        }
        try (URLClassLoader loader = new URLClassLoader(
                program.robotClasspath().stream().map(HornetImportTest::url).toArray(URL[]::new), null)) {
            for (String image : List.of("decode-dark.png", "decode-light.png")) {
                try (InputStream in = loader.getResourceAsStream(image)) {
                    assertNotNull(in, image + " is found on the robot class path");
                    System.out.println("HORNET> " + image + ": " + in.readAllBytes().length + " bytes through the class loader");
                }
            }
        }

        Map<String, Long> findings = new TreeMap<>(program.warnings().stream().collect(Collectors.groupingBy(Finding::code, Collectors.counting())));
        System.out.println("HORNET> findings by kind: " + findings);
        program.warnings().stream().limit(40).forEach(finding -> System.out.println("HORNET>   " + finding));
        assertTrue(
                program.warnings().stream().anyMatch(finding ->
                        finding.code().equals(Finding.Codes.OPMODE_REGISTRAR) && finding.subject().startsWith("com.bylazar.panels.")),
                "Panels adds an OpMode in code, which the scan cannot list but points out");
        System.out.println("HORNET> libraries: " + libraries.size() + ", files in the package: " + manifest.files().size());

        // The zip unpacks into the same package, and a changed byte is caught by name.
        Path unpacked = temp.resolve("unpacked");
        PreparedProgram again = PackageZip.extract(zip, unpacked);
        assertEquals(manifest, again.manifest());
        Path victim = unpacked.resolve(manifest.libraries().get(0).path());
        byte[] bytes = Files.readAllBytes(victim);
        bytes[bytes.length / 2] ^= 1;
        Files.write(victim, bytes);
        PackageException damaged = assertThrows(PackageException.class, () -> PreparedProgram.open(unpacked));
        assertTrue(damaged.getMessage().contains(manifest.libraries().get(0).path()), damaged.getMessage());
        assertFalse(Files.exists(temp.resolve("work").resolve("snapshot").resolve("local.properties")), "no local.properties is written");
        assertTrue(Files.size(result.gradleLog()) > 0);
    }

    private static URL url(Path path) {
        try {
            return path.toUri().toURL();
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    @Test
    void anUntrustedImportStartsNothing(@TempDir Path temp) throws IOException {
        Path project = Files.createDirectory(temp.resolve("project"));
        Files.writeString(project.resolve("settings.gradle"), "include ':TeamCode'");
        ImportRequest request = ImportRequest.of(
                project, temp.resolve("work"), temp.resolve("package"), temp, temp, BuildTrust.DO_NOT_TRUST);

        ImportException refused = assertThrows(ImportException.class, () -> ProjectImporter.importProject(request, ImportProgress.SILENT));

        assertEquals(ImportException.Failure.NOT_TRUSTED, refused.failure());
        assertFalse(Files.exists(temp.resolve("work")), "nothing was copied");
        assertFalse(Files.exists(temp.resolve("package")));
    }
}
