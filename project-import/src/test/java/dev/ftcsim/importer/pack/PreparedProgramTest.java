package dev.ftcsim.importer.pack;

import static dev.ftcsim.importer.Fixtures.OP_MODE;
import static dev.ftcsim.importer.Fixtures.TELEOP;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.robot.OpModeDescriptor;
import dev.ftcsim.contracts.robot.OpModeKind;
import dev.ftcsim.importer.Fixtures;
import dev.ftcsim.importer.ProjectImporter;
import dev.ftcsim.importer.manifest.PackageManifest;
import dev.ftcsim.importer.resources.ResourceBuilder;
import dev.ftcsim.importer.scan.Finding;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreparedProgramTest {

    /** A small but complete package: the team's code, one library, and generated resources. */
    private static PreparedProgram build(Path temp, Path root) throws IOException {
        return build(temp, root, false);
    }

    private static PreparedProgram build(Path temp, Path root, boolean withJavaResources) throws IOException {
        Path classes = Fixtures.classFolder(temp.resolve("classes"), Map.of(
                "org/team/Drive", Fixtures.annotated("org/team/Drive", OP_MODE, TELEOP, Map.of("name", "Drive"))));
        Path robotCore = Fixtures.classJar(temp.resolve("RobotCore.jar"), Map.of(OP_MODE, Fixtures.plainClass(OP_MODE, "java/lang/Object")));
        Path aar = Fixtures.aar(temp.resolve("lib.aar"), "com.acme", "int string hello 0x7f010000\n", Map.of(
                "res/values/values.xml", Fixtures.values("<string name=\"hello\">Hello</string>"), "assets/readme.txt", "assets"));
        Path generated = temp.resolve("generated");
        new ResourceBuilder().addAar(aar).writeTo(generated);

        PackageBuilder builder = new PackageBuilder(root);
        builder.addProgram("TeamCode", List.of(classes));
        builder.addLibrary("org.firstinspires.ftc:RobotCore:11.1.0", robotCore);
        builder.addResources(generated);
        if (withJavaResources) {
            builder.addJavaResources(Fixtures.jar(temp.resolve("java-res.jar"), Map.of("decode-dark.png", new byte[] {1, 2, 3})));
        }
        builder.finish(
                new PackageManifest.Project("Demo", ":TeamCode", "org.team", "b".repeat(64), 12),
                new PackageManifest.Sdk("11.1.0", List.of("org.firstinspires.ftc:RobotCore:11.1.0")),
                new PackageManifest.OpModes(1, List.of(new OpModeDescriptor("Drive", "", OpModeKind.TELEOP, "org.team.Drive"))),
                List.of(new Finding("THREAD_USE", "org.team.Drive", "run()V", "Creates a java.lang.Thread.")));
        return PreparedProgram.open(root);
    }

    @Test
    void aBuiltPackageOpensAndSaysWhichEntriesGoWhere(@TempDir Path temp) throws IOException {
        Path root = temp.resolve("package");

        PreparedProgram program = build(temp, root);

        assertEquals("11.1.0", program.sdkVersion());
        assertEquals(List.of(root.resolve("program/TeamCode.jar")), program.programClasspath());
        assertEquals(
                List.of(root.resolve("resources/r.jar"), root.resolve("resources"), root.resolve("libs/001-org.firstinspires.ftc_RobotCore_11.1.0.jar")),
                program.libraryClasspath());
        assertEquals(
                List.of(root.resolve("program/TeamCode.jar"), root.resolve("libs/001-org.firstinspires.ftc_RobotCore_11.1.0.jar")),
                program.applicationClasspath());
        assertEquals(
                List.of(
                        root.resolve("program/TeamCode.jar"),
                        root.resolve("resources/r.jar"),
                        root.resolve("resources"),
                        root.resolve("libs/001-org.firstinspires.ftc_RobotCore_11.1.0.jar")),
                program.robotClasspath());
        assertEquals(1, program.opModes().size());
        assertEquals("THREAD_USE", program.warnings().get(0).code());
        assertEquals(
                List.of("libs/001-org.firstinspires.ftc_RobotCore_11.1.0.jar", "program/TeamCode.jar", "resources/assets/readme.txt", "resources/ftcsim-resources.tsv", "resources/r.jar"),
                program.manifest().files().stream().map(PackageManifest.PackageFile::path).toList());
    }

    @Test
    void theJavaResourcesJarComesAfterTheLibrariesOnBothClassPathsAndIsHashedLikeTheRest(@TempDir Path temp) throws IOException {
        Path root = temp.resolve("package");

        PreparedProgram program = build(temp, root, true);

        Path javaResources = root.resolve("resources/java-res.jar");
        Path library = root.resolve("libs/001-org.firstinspires.ftc_RobotCore_11.1.0.jar");
        assertEquals(
                List.of(root.resolve("program/TeamCode.jar"), library, javaResources), program.applicationClasspath());
        assertEquals(
                List.of(root.resolve("program/TeamCode.jar"), root.resolve("resources/r.jar"), root.resolve("resources"), library, javaResources),
                program.robotClasspath());
        assertEquals(javaResources, program.libraryClasspath().get(program.libraryClasspath().size() - 1));
        assertEquals("resources/java-res.jar", program.manifest().resources().javaResources());
        assertTrue(program.manifest().files().stream().anyMatch(file -> file.path().equals("resources/java-res.jar")));
        assertEquals(ProjectImporter.IMPORTER_REVISION, program.importerRevision());
        assertTrue(program.warnings().stream().noneMatch(warning -> warning.code().equals(Finding.Codes.JAVA_RESOURCES_MISSING)));
        try (URLClassLoader loader = new URLClassLoader(program.robotClasspath().stream().map(PreparedProgramTest::url).toArray(URL[]::new), null);
                InputStream image = loader.getResourceAsStream("decode-dark.png")) {
            assertEquals(3, image.readAllBytes().length);
        }
    }

    private static URL url(Path path) {
        try {
            return path.toUri().toURL();
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    @Test
    void aPackageOfTheFirstImporterRevisionStillOpensAndSaysWhatItLacks(@TempDir Path temp) throws IOException {
        Path root = temp.resolve("package");
        build(temp, root);
        Path manifest = root.resolve("manifest.json");
        Files.writeString(manifest, Fixtures.asVersion1Manifest(Files.readString(manifest)));

        PreparedProgram program = PreparedProgram.open(root);

        assertEquals(1, program.importerRevision());
        assertTrue(program.importerRevision() < ProjectImporter.IMPORTER_REVISION);
        assertEquals(List.of("THREAD_USE", Finding.Codes.JAVA_RESOURCES_MISSING), program.warnings().stream().map(Finding::code).toList());
        Finding missing = program.warnings().get(1);
        assertTrue(missing.detail().contains("revision 1") && missing.detail().contains("Prepare the program again"), missing.detail());
        assertEquals(1, program.manifest().warnings().size(), "the finding is made on reading, not part of what the package recorded");
        assertEquals(
                List.of(root.resolve("program/TeamCode.jar"), root.resolve("resources/r.jar"), root.resolve("resources"), root.resolve("libs/001-org.firstinspires.ftc_RobotCore_11.1.0.jar")),
                program.robotClasspath());
    }

    @Test
    void aChangedByteIsNamedWithTheFileAndBothHashes(@TempDir Path temp) throws IOException {
        Path root = temp.resolve("package");
        PreparedProgram program = build(temp, root);
        Path victim = root.resolve("libs/001-org.firstinspires.ftc_RobotCore_11.1.0.jar");
        byte[] bytes = Files.readAllBytes(victim);
        bytes[bytes.length / 2] ^= 1;
        Files.write(victim, bytes);

        PackageException failure = assertThrows(PackageException.class, () -> PreparedProgram.open(root));

        String listed = program.manifest().files().get(0).sha256();
        assertTrue(failure.getMessage().contains("libs/001-org.firstinspires.ftc_RobotCore_11.1.0.jar has SHA-256 "), failure.getMessage());
        assertTrue(failure.getMessage().contains("the manifest says " + listed), failure.getMessage());
    }

    @Test
    void missingChangedSizeAndForeignFilesAreAllReported(@TempDir Path temp) throws IOException {
        Path root = temp.resolve("package");
        build(temp, root);
        Files.delete(root.resolve("resources/assets/readme.txt"));
        Files.writeString(root.resolve("program/TeamCode.jar"), "short", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("resources/extra.class"), "smuggled", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("desktop.ini"), "Windows wrote this", StandardCharsets.UTF_8);

        PackageException failure = assertThrows(PackageException.class, () -> PreparedProgram.open(root));

        assertTrue(failure.getMessage().contains("resources/assets/readme.txt is listed in the manifest but missing"), failure.getMessage());
        assertTrue(failure.getMessage().contains("program/TeamCode.jar has 5 bytes"), failure.getMessage());
        assertTrue(failure.getMessage().contains("resources/extra.class is in the package but not in the manifest"), failure.getMessage());
        assertFalse(failure.getMessage().contains("desktop.ini"), "files that Windows adds on its own are not foreign");
    }

    @Test
    void aFolderWithoutAManifestIsNotAPackage(@TempDir Path temp) throws IOException {
        PackageException failure = assertThrows(PackageException.class, () -> PreparedProgram.open(Files.createDirectory(temp.resolve("empty"))));

        assertTrue(failure.getMessage().contains("has no manifest.json"), failure.getMessage());
    }

    @Test
    void aMangledManifestIsReportedWithTheFolderAndTheProperty(@TempDir Path temp) throws IOException {
        Path root = temp.resolve("package");
        build(temp, root);
        Path manifest = root.resolve("manifest.json");
        Files.writeString(manifest, Files.readString(manifest).replace("\"formatVersion\" : 2", "\"formatVersion\" : 7"));

        PackageException failure = assertThrows(PackageException.class, () -> PreparedProgram.open(root));

        assertTrue(failure.getMessage().startsWith(root.toString()), failure.getMessage());
        assertTrue(failure.getMessage().contains("formatVersion is 7") && failure.getMessage().contains("newer FTC-Sim"), failure.getMessage());
    }

    @Test
    void aPackageFolderMustBeNewOrEmpty(@TempDir Path temp) throws IOException {
        Path occupied = Files.createDirectory(temp.resolve("occupied"));
        Files.writeString(occupied.resolve("old.txt"), "an earlier package");

        assertThrows(IOException.class, () -> new PackageBuilder(occupied));
    }

    @Test
    void libraryFileNamesKeepTheNameButAreSafeOnWindows() {
        assertEquals("org.firstinspires.ftc_RobotCore_11.1.0", PackageBuilder.fileName("org.firstinspires.ftc:RobotCore:11.1.0"));
        assertEquals("_TeamCode", PackageBuilder.fileName(":TeamCode"));
        assertEquals("_hidden", PackageBuilder.fileName(".hidden"));
        assertEquals(80, PackageBuilder.fileName("x".repeat(200)).length());
    }

    @Test
    void thePackageAsAZipIsTheSameBytesEveryTimeAndUnpacksToTheSamePackage(@TempDir Path temp) throws IOException {
        PreparedProgram program = build(temp, temp.resolve("package"));
        Path first = temp.resolve("one.zip");
        Path second = temp.resolve("two.zip");

        PackageZip.create(program, first);
        PackageZip.create(program, second);
        PreparedProgram unpacked = PackageZip.extract(first, temp.resolve("unpacked"));

        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second));
        assertEquals(program.manifest(), unpacked.manifest());
        try (ZipFile zip = new ZipFile(first.toFile())) {
            List<String> names = zip.stream().map(ZipEntry::getName).toList();
            assertEquals(names.stream().sorted().toList(), names, "entries are in name order");
            assertTrue(names.contains("manifest.json"));
            assertTrue(zip.stream().allMatch(entry -> entry.getTime() == zip.stream().findFirst().orElseThrow().getTime()), "one fixed time stamp");
        }
    }

    private static Path zipWith(Path zip, Path package_, Map<String, byte[]> replace, List<String> drop, Map<String, byte[]> add) throws IOException {
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream archive = new ZipOutputStream(out)) {
            try (var files = Files.walk(package_)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String name = package_.relativize(file).toString().replace('\\', '/');
                    if (!drop.contains(name)) {
                        archive.putNextEntry(new ZipEntry(name));
                        archive.write(replace.containsKey(name) ? replace.get(name) : Files.readAllBytes(file));
                        archive.closeEntry();
                    }
                }
            }
            for (Map.Entry<String, byte[]> entry : add.entrySet()) {
                archive.putNextEntry(new ZipEntry(entry.getKey()));
                archive.write(entry.getValue());
                archive.closeEntry();
            }
        }
        return zip;
    }

    @Test
    void aZipThatWouldWriteWhatTheManifestDoesNotListOrGrowBeyondItIsRejectedAndLeavesNothing(@TempDir Path temp) throws IOException {
        Path root = temp.resolve("package");
        build(temp, root);
        byte[] more = "more than listed".getBytes(StandardCharsets.UTF_8);

        Path unlisted = zipWith(temp.resolve("unlisted.zip"), root, Map.of(), List.of(), Map.of("../evil.txt", more));
        Path inflated = zipWith(temp.resolve("inflated.zip"), root, Map.of("resources/assets/readme.txt", more), List.of(), Map.of());
        Path incomplete = zipWith(temp.resolve("incomplete.zip"), root, Map.of(), List.of("program/TeamCode.jar"), Map.of());
        Path manifestless = zipWith(temp.resolve("manifestless.zip"), root, Map.of(), List.of("manifest.json"), Map.of());

        assertTrue(assertThrows(PackageException.class, () -> PackageZip.extract(unlisted, temp.resolve("a"))).getMessage().contains("../evil.txt"));
        assertTrue(assertThrows(PackageException.class, () -> PackageZip.extract(inflated, temp.resolve("b"))).getMessage().contains("is longer than the 6 bytes"));
        assertTrue(assertThrows(PackageException.class, () -> PackageZip.extract(incomplete, temp.resolve("c"))).getMessage().contains("lacks program/TeamCode.jar"));
        assertTrue(assertThrows(PackageException.class, () -> PackageZip.extract(manifestless, temp.resolve("d"))).getMessage().contains("has no manifest.json"));
        for (String target : List.of("a", "b", "c", "d")) {
            assertFalse(Files.exists(temp.resolve(target)), "nothing of a rejected zip is left in " + target);
        }
    }

    @Test
    void aZipIsNotUnpackedOverAFolderThatHasThingsInIt(@TempDir Path temp) throws IOException {
        PreparedProgram program = build(temp, temp.resolve("package"));
        Path zip = temp.resolve("package.zip");
        PackageZip.create(program, zip);
        Path occupied = Files.createDirectory(temp.resolve("occupied"));
        Files.writeString(occupied.resolve("keep.txt"), "mine");

        PackageException failure = assertThrows(PackageException.class, () -> PackageZip.extract(zip, occupied));

        assertTrue(failure.getMessage().contains("not empty"));
        assertEquals("mine", Files.readString(occupied.resolve("keep.txt")), "what was there is not deleted");
    }
}
