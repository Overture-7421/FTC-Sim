package dev.ftcsim.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.launcher.Stage0.Result;
import dev.ftcsim.launcher.Stage0.Running;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The archive extractor and the final move into the cache, exercised directly through the stage-0 libraries: hostile
 * archives must be rejected before anything is written, and a slow antivirus scanner must not fail an install.
 */
class Stage0ArchiveTest {

    /** The folder every fixture archive keeps its files in, like the one inside a Temurin zip. */
    private static final String TOP = FakePc.TOP_LEVEL;

    @TempDir(factory = WorkDirFactory.class)
    Path work;

    private Path manifest;
    private Path zips;
    private Path stages;

    @BeforeEach
    void prepare() throws Exception {
        manifest = TestManifest.offline().write(work);
        zips = Files.createDirectories(work.resolve("zips"));
        stages = Files.createDirectories(work.resolve("stages"));
    }

    private FixtureZip jdk() throws Exception {
        Path home = work.resolve("jdk");
        if (!Files.exists(home)) {
            FixtureJdk.at(home).build();
        }
        return FixtureZip.ofDirectory(home, TOP);
    }

    /** One scenario run that tries every archive in {@code cases} and reports "KIND: message" or "OK files bytes" per name. */
    private Result unpack(Map<String, byte[]> archives, long maxBytes, int maxEntries) throws IOException {
        List<String> body = new ArrayList<>(List.of(
                "function Try-Unpack([string]$name, [long]$maxBytes, [int]$maxEntries) {",
                "    $stage = [System.IO.Directory]::CreateDirectory((Join-Path " + Stage0.quote(stages) + " $name)).FullName",
                "    $stream = [System.IO.File]::Open((Join-Path " + Stage0.quote(zips) + " ($name + '.zip')), 'Open', 'Read', 'Read')",
                "    try { $r = Expand-ZipSafely $stream $stage $maxBytes $maxEntries; Out-Fact $name ('OK ' + $r.Files + ' ' + $r.Bytes) }",
                "    catch { Out-Fact $name ((Get-FtcKind $_) + ': ' + $_.Exception.Message) }",
                "    finally { $stream.Dispose() }",
                "}"));
        for (Map.Entry<String, byte[]> archive : archives.entrySet()) {
            Files.write(zips.resolve(archive.getKey() + ".zip"), archive.getValue());
            body.add("Try-Unpack '" + archive.getKey() + "' " + maxBytes + " " + maxEntries);
        }
        return Stage0.scenario(work, manifest, String.join("\n", body));
    }

    private record Hostile(FixtureZip zip, String reason) {
    }

    @Test
    void hostileArchivesAreRejectedBeforeAnythingIsWritten() throws Exception {
        Map<String, Hostile> hostile = new LinkedHashMap<>();
        hostile.put("traversal-slash", new Hostile(jdk().file("../escaped.txt", "x"), "climbs out of the folder"));
        hostile.put("traversal-backslash", new Hostile(jdk().file("..\\escaped.txt", "x"), "climbs out of the folder"));
        hostile.put("traversal-inside-a-path", new Hostile(jdk().file(TOP + "/bin/../../../escaped.txt", "x"), "climbs out of the folder"));
        hostile.put("traversal-deep", new Hostile(jdk().file("../../../../../../escaped.txt", "x"), "climbs out of the folder"));
        hostile.put("absolute-slash", new Hostile(jdk().file("/escaped.txt", "x"), "absolute path"));
        hostile.put("absolute-backslash", new Hostile(jdk().file("\\escaped.txt", "x"), "absolute path"));
        hostile.put("unc-path", new Hostile(jdk().file("\\\\server\\share\\escaped.txt", "x"), "absolute path"));
        hostile.put("drive-letter", new Hostile(jdk().file(work.resolve("escaped.txt").toString(), "x"), "colon"));
        hostile.put("drive-relative", new Hostile(jdk().file("C:escaped.txt", "x"), "colon"));
        hostile.put("data-stream", new Hostile(jdk().file(TOP + "/bin/java.exe:evil", "x"), "colon"));
        hostile.put("device-name", new Hostile(jdk().file(TOP + "/NUL", "x"), "reserved Windows device name"));
        hostile.put("device-name-with-extension", new Hostile(jdk().file(TOP + "/lib/aux.txt", "x"), "reserved Windows device name"));
        hostile.put("trailing-dot", new Hostile(jdk().file(TOP + "/bin./java.exe", "x"), "ends with a dot or a space"));
        hostile.put("trailing-space", new Hostile(jdk().file(TOP + "/bin /java.exe", "x"), "ends with a dot or a space"));
        hostile.put("wildcard", new Hostile(jdk().file(TOP + "/bin/ja*va.exe", "x"), "does not allow in file names"));
        hostile.put("empty-segment", new Hostile(jdk().file(TOP + "//java.exe", "x"), "not a plain relative path"));
        hostile.put("unix-symbolic-link", new Hostile(jdk().file(TOP + "/lib/link", "..\\..\\..").asSymbolicLink(TOP + "/lib/link"),
                "link or a device"));
        hostile.put("windows-reparse-point", new Hostile(jdk().file(TOP + "/lib/junction", "C:\\Windows").asReparsePoint(TOP + "/lib/junction"),
                "reparse point"));
        hostile.put("same-file-twice-in-another-case", new Hostile(jdk().file(TOP.toUpperCase() + "/BIN/JAVA.EXE", "x"), "more than once"));
        Map<String, byte[]> archives = new LinkedHashMap<>();
        hostile.forEach((name, entry) -> archives.put(name, entry.zip().toBytes()));
        archives.put("not-a-zip", "this is not a zip archive at all".getBytes(StandardCharsets.UTF_8));
        archives.put("valid", jdk().toBytes());

        Result result = unpack(archives, 2_000_000_000L, 60_000);

        assertEquals(0, result.exitCode(), result.output());
        List<String> problems = new ArrayList<>();
        hostile.forEach((name, entry) -> {
            String outcome = result.fact(name);
            if (outcome == null || !outcome.startsWith("UnsafeArchive: ") || !outcome.contains(entry.reason())) {
                problems.add(name + " -> " + outcome);
            }
        });
        assertEquals(List.of(), problems, result.output());
        assertTrue(result.fact("not-a-zip").startsWith("UnsafeArchive: The downloaded archive could not be unpacked safely"), result.output());
        assertEquals("OK " + jdkFiles() + " " + jdkBytes(), result.fact("valid"));
        for (String name : hostile.keySet()) {
            try (Stream<Path> written = Files.list(stages.resolve(name))) {
                assertEquals(List.of(), written.toList(), name + " must not leave anything behind");
            }
        }
        try (Stream<Path> everything = Files.walk(work)) {
            assertTrue(everything.noneMatch(path -> path.getFileName().toString().equals("escaped.txt")), "nothing may escape the staging folder");
        }
    }

    private long jdkBytes() throws IOException {
        try (Stream<Path> files = Files.walk(work.resolve("jdk"))) {
            return files.filter(Files::isRegularFile).mapToLong(path -> path.toFile().length()).sum();
        }
    }

    private long jdkFiles() throws IOException {
        try (Stream<Path> files = Files.walk(work.resolve("jdk"))) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    @Test
    void theEntryCountAndTheUnpackedSizeAreLimited() throws Exception {
        Map<String, byte[]> tooMany = new LinkedHashMap<>();
        tooMany.put("many-entries", jdk().file(TOP + "/a.txt", "a").file(TOP + "/b.txt", "b").toBytes());
        Map<String, byte[]> tooBig = new LinkedHashMap<>();
        tooBig.put("big", jdk().padding(TOP + "/lib/modules", 2_000_000).toBytes());

        Result entries = unpack(tooMany, 2_000_000_000L, 4);
        Result bytes = unpack(tooBig, 1_000_000L, 60_000);

        assertEquals("UnsafeArchive: The downloaded archive holds " + (jdkFiles() + 2) + " entries; FTC-Sim accepts at most 4.",
                entries.fact("many-entries"), entries.output());
        assertTrue(bytes.fact("big").startsWith("UnsafeArchive: The downloaded archive would unpack to "), bytes.output());
        assertTrue(bytes.fact("big").contains("more than the 1.0 MB FTC-Sim accepts"), bytes.output());
    }

    @Test
    void entriesBeyondTheClassicPathLimitAreUnpackedAndRemoved() throws Exception {
        String deepName = TOP + "/" + ("d".repeat(60) + "/").repeat(5) + "deep.txt";
        Files.write(zips.resolve("long.zip"), jdk().file(deepName, "deep").toBytes());

        Result result = Stage0.scenario(work, manifest, String.join("\n",
                "$stage = Join-Path " + Stage0.quote(stages) + " 'long'",
                "[void][System.IO.Directory]::CreateDirectory($stage)",
                "$stream = [System.IO.File]::Open(" + Stage0.quote(zips.resolve("long.zip")) + ", 'Open', 'Read', 'Read')",
                "try { $r = Expand-ZipSafely $stream $stage 2000000000 60000 } finally { $stream.Dispose() }",
                "$deep = $stage + '\\' + '" + deepName.replace('/', '\\') + "'",
                "Out-Fact length $deep.Length",
                "Out-Fact files $r.Files",
                "Out-Fact present ([System.IO.File]::Exists((ConvertTo-ExtendedPath $deep)))",
                "Remove-PathQuietly $stage",
                "Out-Fact removed (-not [System.IO.Directory]::Exists((ConvertTo-ExtendedPath $stage)))"));

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(Integer.parseInt(result.fact("length")) > 260, result.output());
        assertEquals(String.valueOf(jdkFiles() + 1), result.fact("files"));
        assertEquals("True", result.fact("present"), result.output());
        assertEquals("True", result.fact("removed"), result.output());
    }

    private Path stagedFolderWithAFile() throws IOException {
        Path source = Files.createDirectories(work.resolve("staged").resolve(TOP).resolve("bin"));
        Files.writeString(source.resolve("java.exe"), "not really a program");
        return work.resolve("staged").resolve(TOP);
    }

    @Test
    void movingAFolderThatAScannerHasOpenWaitsUntilItLetsGo() throws Exception {
        Path source = stagedFolderWithAFile();
        Path target = work.resolve("published");
        Path ready = work.resolve("ready.txt");
        InputStream scanner = Files.newInputStream(source.resolve("bin").resolve("java.exe"));
        Running running = Stage0.startScenario(work, manifest, "publish.ps1", String.join("\n",
                "$clock = [System.Diagnostics.Stopwatch]::StartNew()",
                "[System.IO.File]::WriteAllText(" + Stage0.quote(ready) + ", 'moving')",
                "Publish-Directory " + Stage0.quote(source) + " " + Stage0.quote(target),
                "Out-Fact waitedMs ([int]$clock.ElapsedMilliseconds)"));
        for (int i = 0; i < 600 && !Files.exists(ready); i++) {
            Thread.sleep(100);
        }
        Thread.sleep(1500);
        scanner.close();

        Result result = running.await();

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("Windows is still busy with the new files"), result.output());
        assertTrue(Integer.parseInt(result.fact("waitedMs")) >= 1000, "the move must have been retried: " + result.output());
        assertTrue(Files.isRegularFile(target.resolve("bin").resolve("java.exe")));
        assertFalse(Files.exists(source));
    }

    @Test
    void aFolderThatStaysLockedIsReportedAndNotHalfMoved() throws Exception {
        Path source = stagedFolderWithAFile();
        Path target = work.resolve("published");

        try (InputStream scanner = Files.newInputStream(source.resolve("bin").resolve("java.exe"))) {
            assertTrue(scanner.available() >= 0);
            Result result = Stage0.scenario(work, manifest, "Publish-Directory " + Stage0.quote(source) + " " + Stage0.quote(target) + " 3 100");

            assertEquals(3, result.exitCode(), result.output());
            assertEquals("AccessDenied", result.kind(), result.output());
            assertTrue(result.output().contains("A security program may still be scanning the new files"), result.output());
        }
        assertTrue(Files.isRegularFile(source.resolve("bin").resolve("java.exe")));
        assertFalse(Files.exists(target));
    }
}
