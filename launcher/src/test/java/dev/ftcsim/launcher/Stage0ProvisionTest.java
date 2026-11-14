package dev.ftcsim.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.launcher.Stage0.Result;
import dev.ftcsim.launcher.Stage0.Running;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Provisioning through the real FTC-Sim.ps1 on a simulated PC with no Java: the JDK is downloaded from a fake Adoptium,
 * verified, unpacked and installed into a throw-away cache. Nothing here touches the network or the real %LOCALAPPDATA%.
 */
class Stage0ProvisionTest {

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

    private Path newerJdk() throws Exception {
        return FixtureJdk.at(work.resolve("jdk26")).major(26).build();
    }

    @Test
    void thePinnedJdkIsInstalledInTheCacheEvenWhenANewerJdkIsOnTheMachine() throws Exception {
        Result result = pc.resolve(Map.of("JAVA_HOME", newerJdk().toString()), "-DiscoverySources", "JavaHome,Cache");

        assertEquals(0, result.exitCode(), result.output());
        assertEquals("Cache", result.json("source"));
        assertEquals(pc.destination().toString(), result.json("home"));
        assertTrue(result.jsonFlag("installedNow"), result.output());
        assertTrue(result.jsonFlag("pinnedMatch"), result.output());
        assertEquals(FakeAdoptium.OPENJDK_VERSION.substring(0, FakeAdoptium.OPENJDK_VERSION.indexOf('+')), result.json("version"));
        String record = Files.readString(pc.destination().resolve("ftcsim-install.json"));
        for (String expected : List.of("\"vendor\"\\s*:\\s*\"temurin\"", "\"release\"\\s*:\\s*\"" + Pattern.quote(FakeAdoptium.RELEASE_NAME) + "\"",
                "\"sha256\"\\s*:\\s*\"" + pc.release.sha256() + "\"", "\"sizeBytes\"\\s*:\\s*" + pc.release.size(),
                "\"source\"\\s*:\\s*\"" + Pattern.quote(pc.service.origin() + pc.release.path()) + "\"", "\"installedUtc\"\\s*:\\s*\"20")) {
            assertTrue(Pattern.compile(expected).matcher(record).find(), expected + " in " + record);
        }
        assertEquals(List.of(pc.destination().getFileName().toString()), pc.cacheContents("jdks"));
        assertEquals(List.of(), pc.cacheContents("tmp"));
        assertEquals(1, pc.service.apiRequests().size());
        assertEquals(1, pc.service.downloads().size());
    }

    @Test
    void aMachineWithOnlyJdk21GetsTheTemurin25DownloadAndIsToldWhyItsJavaHomeIsNotUsed() throws Exception {
        Path jdk21 = FixtureJdk.at(work.resolve("jdk21")).major(21).build();
        String windows = System.getenv("WINDIR");
        Map<String, String> environment = Map.of("JAVA_HOME", jdk21.toString(),
                "PATH", jdk21.resolve("bin") + ";" + windows + "\\System32;" + windows);

        Result result = pc.resolve(environment, "-DiscoverySources", "Cache,JavaHome,Path");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("JAVA_HOME (" + jdk21.resolve("bin").resolve("java.exe")
                + ") is not used: Java 21 is not the pinned runtime (Java 25)."), result.output());
        assertEquals("Cache", result.json("source"));
        assertEquals(pc.destination().toString(), result.json("home"));
        assertEquals(TestManifest.PINNED_MAJOR, result.jsonInt("major"));
        assertTrue(result.jsonFlag("installedNow") && result.jsonFlag("pinnedMatch"), result.output());
        assertEquals(1, pc.service.downloads().size());
        assertTrue(Files.isRegularFile(jdk21.resolve("bin").resolve("java.exe")), "the machine's own JDK is left alone");
    }

    @Test
    void aPcWithNoJavaAnywhereGetsItsOwnRuntimeOnFirstLaunch() throws Exception {
        String windows = System.getenv("WINDIR");
        Map<String, String> environment = Map.of("PATH", windows + "\\System32;" + windows);

        Result result = pc.launch(environment, "-DiscoverySources", "Cache,JavaHome,Path,Registry,CommonDirs,AndroidStudio");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("Java runtime ready: " + pc.destination()), result.output());
        assertTrue(result.hasLine("STUB-EXE " + pc.destination().resolve("bin").resolve("javaw.exe")), result.output());
        assertTrue(result.hasLine("STUB-ARG " + FakePc.dummyApplicationJar(work)), result.output());
        assertEquals(1, pc.service.downloads().size());
    }

    @Test
    void anyPolicyAlsoInstallsThePinnedJdkWhenNothingIsInstalled() throws Exception {
        Result result = pc.resolve(Map.of(), "-RuntimePolicy", "Any");

        assertEquals(0, result.exitCode(), result.output());
        assertEquals("Cache", result.json("source"));
        assertEquals(pc.destination().toString(), result.json("home"));
        assertTrue(result.jsonFlag("installedNow") && result.jsonFlag("pinnedMatch"), result.output());
    }

    @Test
    void anyPolicyReusesANewerInstalledJdkWithoutTheNetwork() throws Exception {
        Path newer = newerJdk();

        Result result = pc.resolve(Map.of("JAVA_HOME", newer.toString()), "-DiscoverySources", "JavaHome,Cache", "-RuntimePolicy", "Any");

        assertEquals(0, result.exitCode(), result.output());
        assertEquals("JavaHome", result.json("source"));
        assertEquals(newer.toString(), result.json("home"));
        assertFalse(result.jsonFlag("installedNow") || result.jsonFlag("pinnedMatch"), result.output());
        assertEquals(List.of(), pc.service.requests());
        assertFalse(Files.exists(pc.cache), "a reused JDK must not create the cache");
    }

    @Test
    void anInstalledJdkIsReusedWithoutTheNetworkAndTheApplicationGetsIt() throws Exception {
        Result first = pc.launch(Map.of());
        pc.service.close();
        Result second = pc.launch(Map.of());

        for (Result result : List.of(first, second)) {
            assertEquals(0, result.exitCode(), result.output());
            assertTrue(result.hasLine("STUB-EXE " + pc.destination().resolve("bin").resolve("javaw.exe")), result.output());
            assertTrue(result.output().contains("[Cache]"), result.output());
        }
        assertTrue(first.output().contains("preparing its Java runtime"), first.output());
        assertFalse(second.output().contains("preparing its Java runtime"), second.output());
    }

    @Test
    void aChecksumMismatchIsRejectedNothingIsInstalledAndTheNextRunIsNotPoisoned() throws Exception {
        pc.service.corruptDownload(true);
        Result corrupted = pc.resolve(Map.of());
        pc.service.corruptDownload(false).advertiseSha256("0".repeat(64));
        Result misadvertised = pc.resolve(Map.of());

        for (Result result : List.of(corrupted, misadvertised)) {
            assertEquals(85, result.exitCode(), result.output());
            assertTrue(result.output().contains("does not match the published SHA-256"), result.output());
            pc.assertNothingInstalled();
        }
        assertEquals(2, pc.service.downloads().size(), "a mismatch is final and must not loop");

        pc.service.advertiseSha256(null);
        Result good = pc.resolve(Map.of());
        assertEquals(0, good.exitCode(), good.output());
        assertEquals(pc.destination().toString(), good.json("home"));
    }

    @Test
    void aTruncatedDownloadIsResumedFromWhereItStopped() throws Exception {
        pc.service.cutFirstDownloadAfter(700_000);

        Result result = pc.resolve(Map.of());

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("trying again"), result.output());
        List<FakeAdoptium.Request> downloads = pc.service.downloads();
        assertEquals(2, downloads.size());
        assertNull(downloads.get(0).range());
        assertEquals("bytes=700000-", downloads.get(1).range());
        assertEquals(pc.destination().toString(), result.json("home"));
        assertEquals(List.of(), pc.cacheContents("tmp"));
    }

    @Test
    void aServerThatIgnoresRangeRequestsMakesTheDownloadStartOver() throws Exception {
        pc.service.cutFirstDownloadAfter(700_000).ignoreRange();

        Result result = pc.resolve(Map.of());

        assertEquals(0, result.exitCode(), result.output());
        assertEquals(2, pc.service.downloads().size());
        assertEquals("bytes=700000-", pc.service.downloads().get(1).range());
        assertEquals(pc.destination().toString(), result.json("home"));
    }

    @Test
    void aGarbledPartialDownloadFromACrashedRunIsDiscardedAfterTheChecksumFails() throws Exception {
        Files.createDirectories(pc.partialDownload().getParent());
        Files.write(pc.partialDownload(), new byte[300_000]);

        Result result = pc.resolve(Map.of());

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("The resumed download did not verify; starting over."), result.output());
        List<FakeAdoptium.Request> downloads = pc.service.downloads();
        assertEquals(2, downloads.size());
        assertEquals("bytes=300000-", downloads.get(0).range());
        assertNull(downloads.get(1).range());
        assertEquals(List.of(), pc.cacheContents("tmp"));
    }

    @Test
    void leftoversOfACrashedInstallAreSweptAndTheInstallRecovers() throws Exception {
        Path jdks = Files.createDirectories(pc.cache.resolve("jdks"));
        Path tmp = Files.createDirectories(pc.cache.resolve("tmp"));
        Files.createDirectories(tmp.resolve("s-deadbeef").resolve(FakePc.TOP_LEVEL).resolve("bin"));
        Files.writeString(tmp.resolve("s-deadbeef").resolve(FakePc.TOP_LEVEL).resolve("bin").resolve("java.exe"), "half written");
        Files.writeString(tmp.resolve("dl-0123456789ab.part"), "a download of some other release");
        Files.createDirectories(jdks.resolve("temurin-25.0.1_1-aaaaaaaa.broken-1234abcd").resolve("bin"));
        Files.createDirectories(pc.destination().resolve("bin"));
        Files.writeString(pc.destination().resolve("release"), "a partial install: there is no java.exe in it");

        Result result = pc.resolve(Map.of());

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.jsonFlag("installedNow"), result.output());
        assertEquals(List.of(pc.destination().getFileName().toString()), pc.cacheContents("jdks"));
        assertEquals(List.of(), pc.cacheContents("tmp"));
        assertTrue(Files.isRegularFile(pc.destination().resolve("bin").resolve("java.exe")));
    }

    @Test
    void twoConcurrentLaunchesDownloadOnceAndShareTheResult() throws Exception {
        pc.service.delayDownloads(4000);

        Running first = pc.startResolve(Map.of());
        pc.awaitDownloadRequest();
        Running second = pc.startResolve(Map.of());
        Result firstResult = first.await();
        Result secondResult = second.await();

        assertEquals(0, firstResult.exitCode(), firstResult.output());
        assertEquals(0, secondResult.exitCode(), secondResult.output());
        assertEquals(1, pc.service.downloads().size(), "the waiting launch must reuse the finished install");
        assertEquals(pc.destination().toString(), firstResult.json("home"));
        assertEquals(pc.destination().toString(), secondResult.json("home"));
        assertTrue(firstResult.jsonFlag("installedNow"), firstResult.output());
        assertFalse(secondResult.jsonFlag("installedNow"), secondResult.output());
        assertTrue(secondResult.output().contains("Another FTC-Sim window is preparing the Java runtime"), secondResult.output());
        assertEquals(List.of(pc.destination().getFileName().toString()), pc.cacheContents("jdks"));
        assertEquals(List.of(), pc.cacheContents("tmp"));
    }

    @Test
    void aLaunchThatCannotGetTheCacheLockGivesUpWithItsOwnExitCodeAndLeavesTheCacheAlone() throws Exception {
        pc.manifest.lockWaitSeconds(2);
        Path lock = Files.createDirectories(pc.cache.resolve("locks")).resolve("provision.lock");

        try (FileChannel held = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            assertTrue(held.isOpen());
            Result blocked = pc.resolve(Map.of());

            assertEquals(90, blocked.exitCode(), blocked.output());
            assertTrue(blocked.output().contains("Another FTC-Sim window has been preparing the Java runtime for more than 2 seconds"), blocked.output());
            assertEquals(List.of(), pc.service.requests());
            pc.assertNothingInstalled();
        }
        Result free = pc.resolve(Map.of());
        assertEquals(0, free.exitCode(), free.output());
    }

    @Test
    void theDiskSpaceCheckSaysHowMuchRoomIsNeededBeforeDownloadingAnything() throws Exception {
        pc.manifest.extractedSizeEstimate(Long.MAX_VALUE / 4);

        Result result = pc.resolve(Map.of());

        assertEquals(87, result.exitCode(), result.output());
        assertTrue(Pattern.compile("FTC-Sim needs about [\\d.,]+ MB free for its Java runtime but only [\\d.,]+ MB is free on the drive holding")
                .matcher(result.output()).find(), result.output());
        assertEquals(1, pc.service.apiRequests().size());
        assertEquals(List.of(), pc.service.downloads());
        pc.assertNothingInstalled();
    }

    @Test
    void anUnsafeArchiveIsRejectedWithItsOwnExitCodeAndNothingIsKept() throws Exception {
        pc.service.publish(FixtureZip.ofDirectory(work.resolve("fixture-jdk"), FakePc.TOP_LEVEL).file("../escaped.txt", "x").toBytes());

        Result result = pc.resolve(Map.of());

        assertEquals(88, result.exitCode(), result.output());
        assertTrue(result.output().contains("The archive entry '../escaped.txt' was rejected"), result.output());
        assertTrue(result.output().contains("[FTCSIM-UNSAFEARCHIVE, exit code 88]"), result.output());
        pc.assertNothingInstalled();
        assertFalse(Files.exists(work.resolve("escaped.txt")));
    }

    @Test
    void aRuntimeThatCannotBeStartedIsReportedAsBlockedAndItsDownloadIsKeptForARetry() throws Exception {
        String notAProgram = "this is not a program";
        FakeAdoptium.Release blocked = pc.service.publish(new FixtureZip().file(FakePc.TOP_LEVEL + "/bin/java.exe", notAProgram)
                .file(FakePc.TOP_LEVEL + "/bin/javac.exe", notAProgram).file(FakePc.TOP_LEVEL + "/release", "JAVA_VERSION=\"25.0.5\"\n").toBytes());

        Result result = pc.resolve(Map.of());

        assertEquals(89, result.exitCode(), result.output());
        assertTrue(result.output().contains("cannot be used: it cannot be started"), result.output());
        assertTrue(result.output().contains("[FTCSIM-RUNTIMEBLOCKED, exit code 89]"), result.output());
        assertTrue(result.output().contains("that runtime.exactRelease names in jdk-manifest.json") && !result.output().contains("JDK 25"), "the advice names the pinned build, not a Java version that the pin would refuse:\n" + result.output());
        assertEquals(List.of(), pc.cacheContents("jdks"));
        assertEquals(List.of("dl-" + blocked.sha256().substring(0, 12) + ".part"), pc.cacheContents("tmp"));
    }

    @Test
    void theCompilerIsCheckedOnlyWhenTheManifestPinsAJdkImageAndAKeptDownloadIsReused() throws Exception {
        pc.service.publish(FixtureZip.ofDirectory(FixtureJdk.at(work.resolve("jre")).withoutJavac().build(), FakePc.TOP_LEVEL).toBytes());

        Result jdkImage = pc.resolve(Map.of());

        assertEquals(89, jdkImage.exitCode(), jdkImage.output());
        assertTrue(jdkImage.output().contains("The compiler of the Java runtime FTC-Sim unpacked into"), jdkImage.output());

        pc.manifest.imageType("jre");
        pc.service.advertiseImageType("jre");
        Result jreImage = pc.resolve(Map.of());

        assertEquals(0, jreImage.exitCode(), jreImage.output());
        assertTrue(jreImage.jsonFlag("pinnedMatch"), jreImage.output());
        assertFalse(Files.exists(Path.of(jreImage.json("home")).resolve("bin").resolve("javac.exe")));
        assertEquals(1, pc.service.downloads().size(), "the download kept after the first failure is verified and reused");
    }

    @Test
    void noProvisionFailsClearlyWithoutTouchingTheNetworkOrTheCache() throws Exception {
        Result result = pc.resolve(Map.of(), "-NoProvision");

        assertEquals(82, result.exitCode(), result.output());
        assertTrue(result.output().contains("No usable Java 25 runtime was found (Pinned): no Java installation was found"), result.output());
        assertTrue(result.output().contains("automatic setup was switched off (-NoProvision)"), result.output());
        assertEquals(List.of(), pc.service.requests());
        assertFalse(Files.exists(pc.cache), "a refused provisioning must not create the cache");
    }
}
