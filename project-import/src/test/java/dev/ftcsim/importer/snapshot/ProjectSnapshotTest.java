package dev.ftcsim.importer.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.ftcsim.importer.ImportException;
import dev.ftcsim.importer.ImportProgress;
import dev.ftcsim.importer.ImportStage;
import dev.ftcsim.importer.io.Sha256;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectSnapshotTest {

    private static void write(Path root, String relative, String text) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    /** A project the way Android Studio leaves it: sources, build scripts, and everything the tools generated. */
    private static Path project(Path temp) throws IOException {
        Path project = temp.resolve("project");
        write(project, "settings.gradle", "include ':TeamCode'");
        write(project, "build.gradle", "// root");
        write(project, "gradle/wrapper/gradle-wrapper.properties", "distributionUrl=x");
        write(project, "TeamCode/build.gradle", "// module");
        write(project, "TeamCode/src/main/java/org/team/Drive.java", "class Drive {}");
        write(project, "TeamCode/src/main/java/org/team/build/Tools.java", "package org.team.build; class Tools {}");
        write(project, "TeamCode/src/main/res/values/strings.xml", "<resources/>");
        write(project, "README.md", "readme");
        write(project, "local.properties", "sdk.dir=C\\:\\\\Users\\\\student\\\\Android\\\\Sdk");
        write(project, "TeamCode.iml", "<module/>");
        write(project, "build/outputs/apk/app.apk", "binary");
        write(project, "TeamCode/build/intermediates/classes/Drive.class", "binary");
        write(project, ".gradle/8.9/cache.bin", "cache");
        write(project, ".git/objects/ab/cdef", "object");
        write(project, ".idea/workspace.xml", "<ide/>");
        write(project, "TeamCode/.cxx/log.txt", "native");
        return project;
    }

    private static Map<String, String> filesOf(Path root) throws IOException {
        Map<String, String> hashes = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                hashes.put(root.relativize(file).toString().replace('\\', '/'), Sha256.of(file));
            }
        }
        return hashes;
    }

    @Test
    void copiesSourceAndBuildFilesAndLeavesTheOutputsOfToolsAndTheMachinesOwnFilesBehind(@TempDir Path temp) throws IOException {
        Path project = project(temp);

        ProjectSnapshot.Copy copy = ProjectSnapshot.create(project, temp.resolve("work/snapshot"), ImportProgress.SILENT);

        assertEquals(
                List.of(
                        "README.md",
                        "TeamCode/build.gradle",
                        "TeamCode/src/main/java/org/team/Drive.java",
                        "TeamCode/src/main/java/org/team/build/Tools.java",
                        "TeamCode/src/main/res/values/strings.xml",
                        "build.gradle",
                        "gradle/wrapper/gradle-wrapper.properties",
                        "settings.gradle"),
                List.copyOf(copy.manifest().hashes().keySet()));
        assertEquals(copy.manifest().hashes(), filesOf(copy.directory()), "the copy holds exactly what the manifest says");
        assertFalse(Files.exists(copy.directory().resolve("local.properties")), "the author's SDK location is not carried over");
    }

    @Test
    void theManifestIsTheHashOfTheOriginalAndSeesAnyChangeToIt(@TempDir Path temp) throws IOException {
        Path project = project(temp);
        SourceManifest before = ProjectSnapshot.create(project, temp.resolve("snapshot"), ImportProgress.SILENT).manifest();
        assertEquals(Sha256.of(project.resolve("settings.gradle")), before.hashes().get("settings.gradle"));
        assertTrue(SourceManifest.scan(project, ImportProgress.SILENT).compareWith(before).isEmpty());

        write(project, "TeamCode/src/main/java/org/team/Drive.java", "class Drive { int changed; }");
        write(project, "TeamCode/src/main/java/org/team/Extra.java", "class Extra {}");
        Files.delete(project.resolve("README.md"));
        write(project, "TeamCode/build/intermediates/more.class", "outputs may change freely");

        SourceManifest.Differences differences = before.compareWith(SourceManifest.scan(project, ImportProgress.SILENT));

        assertEquals(List.of("TeamCode/src/main/java/org/team/Drive.java"), differences.changed());
        assertEquals(List.of("TeamCode/src/main/java/org/team/Extra.java"), differences.added());
        assertEquals(List.of("README.md"), differences.removed());
        assertTrue(differences.describe().contains("changed") && differences.describe().contains("Extra.java"));
    }

    @Test
    void importingAgainBringsTheCopyUpToDateAndKeepsWhatTheBuildLeftThere(@TempDir Path temp) throws IOException {
        Path project = project(temp);
        Path snapshot = temp.resolve("snapshot");
        ProjectSnapshot.create(project, snapshot, ImportProgress.SILENT);
        write(snapshot, "TeamCode/build/intermediates/classes/Drive.class", "compiled in the copy");
        write(snapshot, ".gradle/state.bin", "gradle state");

        Files.delete(project.resolve("README.md"));
        write(project, "TeamCode/src/main/java/org/team/Drive.java", "class Drive { int v2; }");
        ProjectSnapshot.Copy copy = ProjectSnapshot.create(project, snapshot, ImportProgress.SILENT);

        assertFalse(Files.exists(snapshot.resolve("README.md")), "a file that left the project leaves the copy");
        assertEquals("class Drive { int v2; }", Files.readString(snapshot.resolve("TeamCode/src/main/java/org/team/Drive.java")));
        assertEquals("compiled in the copy", Files.readString(snapshot.resolve("TeamCode/build/intermediates/classes/Drive.class")));
        assertTrue(Files.exists(snapshot.resolve(".gradle/state.bin")));
        Map<String, String> sources = new TreeMap<>(filesOf(snapshot));
        sources.keySet().removeIf(path -> path.startsWith("TeamCode/build/") || path.startsWith(".gradle/"));
        assertEquals(copy.manifest().hashes(), sources);
    }

    @Test
    void aWorkspaceInsideTheProjectIsRefusedBeforeAnythingIsWritten(@TempDir Path temp) throws IOException {
        Path project = project(temp);

        ImportException refused = assertThrows(
                ImportException.class, () -> ProjectSnapshot.create(project, project.resolve("work/snapshot"), ImportProgress.SILENT));

        assertEquals(ImportException.Failure.INVALID_REQUEST, refused.failure());
        assertFalse(Files.exists(project.resolve("work")));
    }

    @Test
    void aLinkIsReportedAndNotFollowed(@TempDir Path temp) throws IOException, InterruptedException {
        Path project = project(temp);
        Path outside = Files.createDirectory(temp.resolve("outside"));
        write(outside, "secret.txt", "not part of the project");
        Process junction = new ProcessBuilder("cmd", "/c", "mklink", "/J", project.resolve("TeamCode/linked").toString(), outside.toString())
                .redirectErrorStream(true).start();
        junction.getInputStream().readAllBytes();
        assumeTrue(junction.waitFor() == 0, "this machine does not allow creating a junction");

        ProjectSnapshot.Copy copy = ProjectSnapshot.create(project, temp.resolve("snapshot"), ImportProgress.SILENT);

        assertEquals(List.of("TeamCode/linked"), copy.skippedLinks());
        assertFalse(Files.exists(copy.directory().resolve("TeamCode/linked")));
        assertTrue(Files.exists(outside.resolve("secret.txt")), "the target of the link is untouched");
    }

    @Test
    void aCancelledCopyStopsAtOnce(@TempDir Path temp) throws IOException {
        Path project = project(temp);
        ImportProgress cancelled = new ImportProgress() {
            @Override
            public void stage(ImportStage stage) {}

            @Override
            public void message(String text) {}

            @Override
            public boolean isCancelled() {
                return true;
            }
        };

        ImportException stopped = assertThrows(ImportException.class, () -> ProjectSnapshot.create(project, temp.resolve("snapshot"), cancelled));

        assertEquals(ImportException.Failure.CANCELLED, stopped.failure());
    }
}
