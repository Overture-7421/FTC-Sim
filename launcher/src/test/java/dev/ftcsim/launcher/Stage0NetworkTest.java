package dev.ftcsim.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.launcher.Stage0.Result;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the driver sees when the network misbehaves: nothing reachable, flaky or broken servers, redirects and metadata
 * that point somewhere they should not, and proxies. Every failure must keep its own exit code and leave the cache usable.
 */
class Stage0NetworkTest {

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

    private Result resolveThroughProxy(String proxyUrl, String origin) throws Exception {
        Path manifest = TestManifest.forServer(origin).attempts(2).write(work);
        return Stage0.run(Map.of(), "-ResolveOnly", "-NonInteractive", "-IgnoreSystemJdks", "-CacheRoot", pc.cache.toString(),
                "-ManifestPath", manifest.toString(), "-ProxyUrl", proxyUrl);
    }

    @Test
    void anUnreachableServerGivesTheOfflineExitCodeAndLeavesTheCacheAlone() throws Exception {
        pc.manifest.attempts(2);
        Files.createDirectories(pc.partialDownload().getParent());
        Files.writeString(pc.partialDownload(), "bytes of an earlier download");
        pc.service.close();

        Result result = pc.resolve(Map.of());

        assertEquals(83, result.exitCode(), result.output());
        assertTrue(result.output().contains("nothing could be reached"), result.output());
        assertTrue(result.output().contains("FTC-Sim contacts: " + pc.service.origin()), result.output());
        assertTrue(result.output().contains("[FTCSIM-OFFLINE, exit code 83]"), result.output());
        assertEquals(List.of(), pc.cacheContents("jdks"));
        assertEquals(List.of(pc.partialDownload().getFileName().toString()), pc.cacheContents("tmp"));
        assertEquals("bytes of an earlier download", Files.readString(pc.partialDownload()));
    }

    @Test
    void transientServerErrorsAreRetriedUntilTheDownloadWorks() throws Exception {
        pc.service.failDownloadsWith503(2);

        Result result = pc.resolve(Map.of());

        assertEquals(0, result.exitCode(), result.output());
        assertEquals(3, pc.service.downloads().size());
        assertTrue(result.output().contains("HTTP 503"), result.output());
        assertEquals(pc.destination().toString(), result.json("home"));
    }

    @Test
    void aServerThatKeepsFailingGivesTheDownloadFailedExitCode() throws Exception {
        pc.manifest.attempts(2);
        pc.service.failDownloadsWith503(100);

        Result result = pc.resolve(Map.of());

        assertEquals(84, result.exitCode(), result.output());
        assertTrue(result.output().contains("HTTP 503"), result.output());
        assertEquals(2, pc.service.downloads().size());
        pc.assertNothingInstalled();
    }

    @Test
    void aStalledDownloadTimesOutAndIsResumedFromWhereItStopped() throws Exception {
        pc.manifest.readTimeoutSeconds(2);
        pc.service.stallFirstDownloadAfter(700_000);

        Result result = pc.resolve(Map.of());

        assertEquals(0, result.exitCode(), result.output());
        List<FakeAdoptium.Request> downloads = pc.service.downloads();
        assertEquals(2, downloads.size());
        assertNull(downloads.get(0).range());
        assertEquals("bytes=700000-", downloads.get(1).range());
    }

    @Test
    void aServerThatMisreportsTheResumePositionDoesNotPoisonThePartialDownload() throws Exception {
        pc.service.cutFirstDownloadAfter(700_000).misreportRanges(true);

        Result result = pc.resolve(Map.of());

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("The server resumed at the wrong position"), result.output());
        List<FakeAdoptium.Request> downloads = pc.service.downloads();
        assertEquals(3, downloads.size());
        assertEquals("bytes=700000-", downloads.get(1).range());
        assertNull(downloads.get(2).range(), "after the bad resume the partial file must be dropped and the download restarted");
        assertEquals(pc.destination().toString(), result.json("home"));
    }

    @Test
    void aRedirectWithinTheAllowlistIsFollowed() throws Exception {
        pc.service.advertiseLink(pc.service.origin() + "/redirect-ok");

        Result result = pc.resolve(Map.of());

        assertEquals(0, result.exitCode(), result.output());
        assertEquals(List.of("/redirect-ok", pc.release.path()),
                pc.service.requests().stream().map(FakeAdoptium.Request::rawPath).filter(path -> !path.startsWith("/v3/")).toList());
    }

    @Test
    void aRedirectToAnotherHostIsRefusedBeforeAnythingIsContacted() throws Exception {
        pc.service.advertiseLink(pc.service.origin() + "/redirect-foreign");

        Result result = pc.resolve(Map.of());

        assertEquals(84, result.exitCode(), result.output());
        assertTrue(result.output().contains("Refusing to contact http://localhost:"), result.output());
        assertTrue(result.output().contains("not one of the allowed origins"), result.output());
        assertEquals(List.of(), pc.service.downloads(), "the foreign host must not be asked for the archive");
        pc.assertNothingInstalled();
    }

    @Test
    void aDownloadAddressOutsideTheAllowlistIsRejectedAsBadMetadata() throws Exception {
        pc.service.advertiseLink("http://localhost:" + URI.create(pc.service.origin()).getPort() + pc.release.path());

        Result result = pc.resolve(Map.of());

        assertEquals(86, result.exitCode(), result.output());
        assertTrue(result.output().contains("download address that is not allowed"), result.output());
        assertEquals(List.of(), pc.service.requests().stream().filter(request -> !request.rawPath().startsWith("/v3/")).toList());
        pc.assertNothingInstalled();
    }

    @Test
    void anAssetForAnotherPlatformIsRejected() throws Exception {
        for (String[] platform : new String[][] {{"windows", "aarch64"}, {"linux", "x64"}, {"windows", "x86"}}) {
            pc.service.advertiseAsset(platform[0], platform[1]);

            Result result = pc.resolve(Map.of());

            assertEquals(86, result.exitCode(), result.output());
            assertTrue(result.output().contains("offered no windows/x64 jdk package, only: " + platform[0] + "/" + platform[1] + " jdk"),
                    result.output());
            pc.assertNothingInstalled();
        }
        assertEquals(List.of(), pc.service.downloads());
    }

    @Test
    void aMalformedAnswerIsAMetadataErrorAndNotACrash() throws Exception {
        String noChecksum = "[{\"binary\":{\"os\":\"windows\",\"architecture\":\"x64\",\"image_type\":\"jdk\",\"package\":{\"name\":\"a.zip\"}},"
                + "\"release_name\":\"" + FakeAdoptium.RELEASE_NAME + "\",\"version\":{\"major\":" + TestManifest.PINNED_MAJOR
                + ",\"openjdk_version\":\"" + FakeAdoptium.OPENJDK_VERSION + "\"}}]";
        for (String answer : List.of(noChecksum, "{\"unexpected\":true}", "[]", "<html>Please log in to the school network</html>")) {
            pc.service.respondToApiWith(answer);

            Result result = pc.resolve(Map.of());

            assertEquals(86, result.exitCode(), answer + "\n" + result.output());
            assertTrue(result.output().contains("[FTCSIM-METADATAINVALID, exit code 86]"), result.output());
            pc.assertNothingInstalled();
        }
    }

    @Test
    void aProxyThatDemandsCredentialsIsReportedAsSuch() throws Exception {
        try (FakeProxy proxy = FakeProxy.requiringCredentials()) {
            Result result = resolveThroughProxy(proxy.url(), "https://api.adoptium.net");

            assertEquals(84, result.exitCode(), result.output());
            assertTrue(result.output().contains("the proxy server requires credentials (HTTP 407)"), result.output());
            assertTrue(result.output().contains("FTC-Sim contacts: https://api.adoptium.net"), result.output());
            assertFalse(proxy.requestLines().isEmpty(), "the request must go through the proxy that -ProxyUrl names");
            assertTrue(proxy.requestLines().stream().allMatch(line -> line.startsWith("CONNECT api.adoptium.net:443 ")), proxy.requestLines().toString());
            pc.assertNothingInstalled();
        }
    }

    @Test
    void aProxyThatCannotBeReachedIsReportedAsOffline() throws Exception {
        String closedProxy;
        try (ServerSocket unused = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedProxy = "http://127.0.0.1:" + unused.getLocalPort();
        }

        Result result = resolveThroughProxy(closedProxy, "https://api.adoptium.net");

        assertEquals(83, result.exitCode(), result.output());
        assertTrue(result.output().contains("nothing could be reached"), result.output());
        assertTrue(result.output().contains("uses the proxy settings of Windows"), result.output());
        pc.assertNothingInstalled();
    }

    @Test
    void aProxyAddressThatIsNotAnAddressIsABadArgument() throws Exception {
        Result result = resolveThroughProxy("not a proxy address", "https://api.adoptium.net");

        assertEquals(93, result.exitCode(), result.output());
        assertTrue(result.output().contains("The proxy address 'not a proxy address' is not valid"), result.output());
    }

    @Test
    void tls12IsEnabledOnTopOfAnOldDefaultAndTheSystemDefaultIsLeftAlone() throws Exception {
        Path manifest = TestManifest.offline().write(work);

        Result result = Stage0.scenario(work, manifest, String.join("\n",
                "[System.Net.ServicePointManager]::SecurityProtocol = [System.Net.SecurityProtocolType]'Ssl3, Tls'",
                "Initialize-Networking",
                "Out-Fact legacy ([string][System.Net.ServicePointManager]::SecurityProtocol)",
                "[System.Net.ServicePointManager]::SecurityProtocol = [System.Net.SecurityProtocolType]::SystemDefault",
                "Initialize-Networking",
                "Out-Fact modern ([string][System.Net.ServicePointManager]::SecurityProtocol)"));

        assertEquals(0, result.exitCode(), result.output());
        assertEquals("Ssl3, Tls, Tls12", result.fact("legacy"));
        assertEquals("SystemDefault", result.fact("modern"));
    }
}
