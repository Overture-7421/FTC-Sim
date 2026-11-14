package dev.ftcsim.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.launcher.Stage0.Result;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The runtime is pinned to one exact release and its checksum: what that does to a PC that has some other build of the same
 * Java, to a release service that no longer matches the pin, and how a maintainer moves the pin. The fake release service
 * and the fixture JDKs impersonate the release the shipped manifest pins, so none of this needs the network.
 */
class Stage0PinTest {

    @TempDir(factory = WorkDirFactory.class)
    Path work;

    private FakePc pc;

    @BeforeEach
    void startAPcWithoutJava() throws Exception {
        pc = FakePc.start(work);
    }

    @AfterEach
    void stopThePc() {
        pc.close();
    }

    private static Path toolScript() {
        return Stage0.scripts().resolveSibling("tools").resolve("Update-JdkPin.ps1");
    }

    private Result updateTool(Path manifest, String... options) {
        String[] arguments = new String[options.length + 2];
        arguments[0] = "-ManifestPath";
        arguments[1] = manifest.toString();
        System.arraycopy(options, 0, arguments, 2, options.length);
        return Stage0.start(toolScript(), Map.of(), arguments).await();
    }

    @Test
    void theShippedManifestPinsOneExactReleaseOfThePinnedMajorAndItsChecksum() {
        TestManifest.Pin pin = TestManifest.shippedPin();

        assertTrue(pin.release().matches("jdk-\\d+\\.\\d+\\.\\d+\\+\\d+"), pin.release());
        assertEquals(TestManifest.PINNED_MAJOR, pin.major());
        assertTrue(pin.release().startsWith("jdk-" + pin.major() + "."), "the release is a release of the pinned major version: " + pin.release());
        assertTrue(pin.sha256().matches("[0-9a-f]{64}"), pin.sha256());
    }

    @Test
    void anotherBuildOfThePinnedJavaIsNotUsedAndTheStudentIsToldWhyBeforeThePinnedOneIsDownloadedByItsName() throws Exception {
        pc.manifest.exactRelease(pc.release.name(), pc.release.sha256());
        Path foreign = FixtureJdk.at(work.resolve("Oracle JDK 25")).version("25.0.1", "25.0.1+8-LTS-27").build();
        Path older = FixtureJdk.at(work.resolve("Android Studio").resolve("jbr")).major(21).build();
        String windows = System.getenv("WINDIR");
        String path = foreign.resolve("bin") + ";" + older.resolve("bin") + ";" + windows + "\\System32;" + windows;

        Result result = pc.resolve(Map.of("PATH", path), "-DiscoverySources", "Cache,Path");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("Java 25.0.1 (Eclipse Adoptium, amd64) at " + foreign + " is not used: build 25.0.1+8-LTS-27 is not the pinned build "
                + pc.release.name().replaceFirst("^jdk-", "") + "."), result.output());
        assertTrue(result.output().contains("Java 21.0.1 (Eclipse Adoptium, amd64) at " + older + " is not used: Java 21 is not the pinned runtime (Java 25)."), result.output());
        assertTrue(result.output().contains("FTC-Sim runs on one exact Java build (" + pc.release.name() + ") so that it behaves the same on every PC. "
                + "It keeps its own copy and leaves the Java on this PC alone."), result.output());
        assertTrue(result.output().indexOf("is not used") < result.output().indexOf("preparing its Java runtime"), "told before the download starts");
        assertEquals("Cache", result.json("source"));
        assertTrue(result.jsonFlag("installedNow") && result.jsonFlag("pinnedMatch"), result.output());
        assertEquals(1, pc.service.apiRequests().size(), pc.service.requests().toString());
        assertTrue(pc.service.apiRequests().get(0).rawPath().startsWith("/v3/assets/release_name/eclipse/" + pc.release.name().replace("+", "%2B") + "?"),
                "the pinned release is asked for by name: " + pc.service.apiRequests());
        assertTrue(Files.isRegularFile(foreign.resolve("bin").resolve("java.exe")), "the Java of the PC is left alone");
    }

    @Test
    void theRightBuildOfThePinnedJavaOnThePcIsUsedWithoutAnyNetworkAndWithoutAnyComplaint() throws Exception {
        pc.manifest.exactRelease(pc.release.name(), pc.release.sha256());
        Path pinned = FixtureJdk.at(work.resolve("jdk")).build();
        Path foreign = FixtureJdk.at(work.resolve("oracle")).version("25.0.1", "25.0.1+8-LTS-27").build();
        String windows = System.getenv("WINDIR");
        String path = foreign.resolve("bin") + ";" + pinned.resolve("bin") + ";" + windows + "\\System32;" + windows;

        Result result = pc.resolve(Map.of("PATH", path), "-DiscoverySources", "Path");

        assertEquals(0, result.exitCode(), result.output());
        assertEquals(pinned.toString(), result.json("home"));
        assertFalse(result.output().contains("is not used"), "a runtime that is found needs no excuses for the ones passed over: " + result.output());
        assertEquals(List.of(), pc.service.requests());
    }

    @Test
    void aPinnedChecksumThatTheServiceDoesNotConfirmIsRefusedBeforeAnythingIsDownloaded() throws Exception {
        pc.manifest.exactRelease(pc.release.name(), "0".repeat(64));

        Result result = pc.resolve(Map.of());

        assertEquals(86, result.exitCode(), result.output());
        assertTrue(result.output().contains("The release checksum " + pc.release.sha256() + " differs from the checksum pinned in the manifest."), result.output());
        assertEquals(List.of(), pc.service.downloads());
        pc.assertNothingInstalled();
    }

    @Test
    void aPinnedReleaseThatTheServiceAnswersAnotherReleaseForIsNeverReplacedByThatOne() throws Exception {
        pc.manifest.exactRelease("jdk-25.0.99+9", pc.release.sha256());

        Result result = pc.resolve(Map.of());

        assertEquals(86, result.exitCode(), result.output());
        assertTrue(result.output().contains("The release service returned " + pc.release.name() + " but the manifest pins jdk-25.0.99+9."), result.output());
        assertEquals(List.of(), pc.service.downloads());
        pc.assertNothingInstalled();
    }

    @Test
    void aPinnedReleaseThatTheServiceNoLongerKnowsIsAnOutdatedFtcSimAndNotABrokenNetwork() throws Exception {
        pc.manifest.exactRelease(pc.release.name(), pc.release.sha256());
        pc.service.answerApiWithStatus(404);

        Result result = pc.resolve(Map.of());

        assertEquals(86, result.exitCode(), result.output());
        assertTrue(result.output().contains("no longer offers the runtime release " + pc.release.name() + " that this FTC-Sim is pinned to"), result.output());
        assertTrue(result.output().contains("FTC-Sim needs an update"), result.output());
        assertEquals(1, pc.service.apiRequests().size(), "an answer that will not change is not asked for again");
        pc.assertNothingInstalled();
    }

    @Test
    void theUpdateToolShowsTheNewestReleaseAfterCheckingItsChecksumAgainstTheBytesAndChangesNothingWithoutApply() throws Exception {
        Path manifest = pc.manifest.write(work);
        String before = Files.readString(manifest);

        Result result = updateTool(manifest);

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("exactRelease: " + pc.release.name()), result.output());
        assertTrue(result.output().contains("exactSha256:  " + pc.release.sha256()), result.output());
        assertTrue(result.output().contains("the service's checksum and the downloaded bytes agree"), result.output());
        assertTrue(result.output().contains("Nothing was changed"), result.output());
        assertEquals(before, Files.readString(manifest));
        assertEquals(1, pc.service.downloads().size());
    }

    @Test
    void theUpdateToolWritesOnlyTheTwoPinnedValuesAndTheManifestThenProvisionsThatReleaseByName() throws Exception {
        Path manifest = pc.manifest.write(work);
        String before = Files.readString(manifest);

        Result result = updateTool(manifest, "-Apply");

        assertEquals(0, result.exitCode(), result.output());
        String expected = before.replace("\"exactRelease\": null", "\"exactRelease\": \"" + pc.release.name() + "\"")
                .replace("\"exactSha256\": null", "\"exactSha256\": \"" + pc.release.sha256() + "\"");
        assertEquals(expected, Files.readString(manifest), "everything else in the manifest stays as it was");
        Result again = updateTool(manifest, "-Apply");
        assertEquals(0, again.exitCode(), again.output());
        assertEquals(expected, Files.readString(manifest), "applying the same release again changes nothing");

        Result provisioned = Stage0.run(Map.of(), "-ResolveOnly", "-NonInteractive", "-IgnoreSystemJdks", "-CacheRoot", pc.cache.toString(),
                "-ManifestPath", manifest.toString());

        assertEquals(0, provisioned.exitCode(), provisioned.output());
        assertTrue(provisioned.jsonFlag("installedNow") && provisioned.jsonFlag("pinnedMatch"), provisioned.output());
        assertTrue(pc.service.apiRequests().stream().anyMatch(request -> request.rawPath().startsWith("/v3/assets/release_name/")), pc.service.apiRequests().toString());
    }

    @Test
    void theUpdateToolRefusesAReleaseTheServiceDoesNotConfirmAndABrokenDownloadAndLeavesTheManifestAlone() throws Exception {
        Path manifest = pc.manifest.write(work);
        String before = Files.readString(manifest);

        Result unknown = updateTool(manifest, "-Release", "jdk-25.0.99+9", "-Apply");
        pc.service.corruptDownload(true);
        Result corrupted = updateTool(manifest, "-Apply");

        assertEquals(1, unknown.exitCode(), unknown.output());
        assertTrue(unknown.output().contains("The release service returned " + pc.release.name() + " but the manifest pins jdk-25.0.99+9."), unknown.output());
        assertEquals(1, corrupted.exitCode(), corrupted.output());
        assertTrue(corrupted.output().contains("[ChecksumMismatch]") && corrupted.output().contains("Nothing was changed."), corrupted.output());
        assertEquals(before, Files.readString(manifest));
    }
}
