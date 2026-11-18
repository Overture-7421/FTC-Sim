package dev.ftcsim.app.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.ftcsim.ui.host.UpdateService.Kind;
import dev.ftcsim.ui.host.UpdateService.Status;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The updater against a stand-in for GitHub on the computer itself: what it offers, what it refuses, and that nothing is
 * installed until it is asked and the checksum has matched. No real network and no real installer are touched.
 */
class GitHubUpdaterTest {
    private static final byte[] INSTALLER = "the installer of 1.2.0".getBytes(StandardCharsets.UTF_8);
    private static final String NAME = "FTC-Sim-1.2.0-Setup.exe";

    @TempDir
    Path updates;

    private HttpServer server;
    private URI base;
    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    private final List<Path> launched = new ArrayList<>();
    private final List<Status> seen = new ArrayList<>();

    @BeforeEach
    void startGitHub() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::answer);
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopGitHub() {
        server.stop(0);
    }

    private void answer(HttpExchange exchange) throws IOException {
        byte[] body = files.get(exchange.getRequestURI().getPath());
        if (body == null) {
            exchange.sendResponseHeaders(404, -1);
        } else {
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /** Publishes a release as GitHub's API describes it, with the installer and the checksums of {@code installer}. */
    private void publish(String tag, byte[] installer, String checksumLine) throws Exception {
        String name = "FTC-Sim-" + tag.substring(1) + "-Setup.exe";
        files.put("/download/" + name, installer);
        files.put("/download/SHA256SUMS.txt", (checksumLine == null ? "" : checksumLine.replace("<name>", name) + "\n").getBytes(StandardCharsets.UTF_8));
        files.put("/repos/Overture-7421/FTC-Sim/releases/latest", String.format("""
                {"tag_name": "%s", "draft": false, "prerelease": false, "assets": [
                  {"name": "%s", "size": %d, "browser_download_url": "%s/download/%s"},
                  {"name": "FTC-Sim-%s.zip", "size": 5, "browser_download_url": "%s/download/zip"},
                  {"name": "SHA256SUMS.txt", "size": 100, "browser_download_url": "%s/download/SHA256SUMS.txt"}]}
                """, tag, name, installer.length, base, name, tag.substring(1), base, base).getBytes(StandardCharsets.UTF_8));
    }

    private void publishGood(String tag) throws Exception {
        publish(tag, INSTALLER, sha256(INSTALLER) + "  <name>");
    }

    private GitHubUpdater updater(String current) {
        GitHubUpdater updater = new GitHubUpdater(base, "Overture-7421/FTC-Sim", Version.parse(current), updates, launched::add);
        updater.addListener(seen::add);
        return updater;
    }

    @Test
    void aNewerReleaseIsOfferedAndNothingIsFetchedUntilThePersonAsks() throws Exception {
        publishGood("v1.2.0");
        GitHubUpdater updater = updater("1.1.0");

        updater.checkNow();

        assertEquals(Status.available("1.2.0"), updater.status());
        assertEquals(List.of(Status.available("1.2.0")), seen);
        assertFalse(Files.exists(updates.resolve(NAME)), "an offer fetches nothing");
        assertTrue(launched.isEmpty());
    }

    @Test
    void aReleaseThatIsNotNewerOffersNothing() throws Exception {
        publishGood("v1.2.0");
        for (String current : List.of("1.2.0", "1.3.0", "2.0.0")) {
            GitHubUpdater updater = updater(current);
            updater.checkNow();
            assertEquals(Status.NONE, updater.status(), "running " + current);
        }
    }

    @Test
    void lookingWithNoNetworkOrNoReleaseIsNotAProblemToTellThePersonAbout() throws Exception {
        GitHubUpdater noRelease = updater("1.1.0");
        noRelease.checkNow();
        assertEquals(Status.NONE, noRelease.status(), "GitHub answers 404");

        server.stop(0);
        GitHubUpdater offline = updater("1.1.0");
        offline.checkNow();
        assertEquals(Status.NONE, offline.status(), "nobody answers");
    }

    @Test
    void aReleaseWithoutItsInstallerOrItsChecksumsOrFromAnotherHostIsNotOffered() throws Exception {
        files.put("/repos/Overture-7421/FTC-Sim/releases/latest", """
                {"tag_name": "v1.2.0", "assets": [{"name": "FTC-Sim.zip", "size": 5, "browser_download_url": "%s/download/zip"}]}
                """.formatted(base).getBytes(StandardCharsets.UTF_8));
        GitHubUpdater updater = updater("1.1.0");
        updater.checkNow();
        assertEquals(Status.NONE, updater.status(), "no installer");

        files.put("/repos/Overture-7421/FTC-Sim/releases/latest", """
                {"tag_name": "v1.2.0", "assets": [
                  {"name": "%s", "size": 5, "browser_download_url": "https://evil.example.com/%s"},
                  {"name": "SHA256SUMS.txt", "size": 5, "browser_download_url": "%s/download/SHA256SUMS.txt"}]}
                """.formatted(NAME, NAME, base).getBytes(StandardCharsets.UTF_8));
        updater.checkNow();
        assertEquals(Status.NONE, updater.status(), "an installer from a host that is not GitHub");

        files.put("/repos/Overture-7421/FTC-Sim/releases/latest", """
                {"tag_name": "v1.2.0", "prerelease": true, "assets": []}
                """.getBytes(StandardCharsets.UTF_8));
        updater.checkNow();
        assertEquals(Status.NONE, updater.status(), "a pre-release is not the latest");
    }

    @Test
    void installingFetchesChecksAndKeepsTheInstallerAndOnlyRunsItWhenTheWindowHasClosed() throws Exception {
        publishGood("v1.2.0");
        GitHubUpdater updater = updater("1.1.0");
        updater.checkNow();

        updater.install();

        assertEquals(Status.restarting("1.2.0"), updater.status());
        assertEquals(Kind.DOWNLOADING, seen.get(1).kind(), "the progress is told: " + seen);
        assertTrue(seen.stream().anyMatch(s -> s.kind() == Kind.DOWNLOADING && s.percent() > 0), seen.toString());
        assertEquals(List.of(INSTALLER.length), List.of((int) Files.size(updates.resolve(NAME))));
        assertEquals(new String(INSTALLER, StandardCharsets.UTF_8), Files.readString(updates.resolve(NAME)));
        assertFalse(Files.exists(updates.resolve(NAME + ".part")));
        assertTrue(launched.isEmpty(), "the installer replaces the program's files, so it waits for the window to close");

        updater.startPendingInstaller();
        assertEquals(List.of(updates.resolve(NAME)), launched);
        updater.startPendingInstaller();
        assertEquals(1, launched.size(), "once");
    }

    @Test
    void aDownloadThatDoesNotMatchItsChecksumIsRefusedAndDeleted() throws Exception {
        publish("v1.2.0", INSTALLER, sha256("something else".getBytes(StandardCharsets.UTF_8)) + "  <name>");
        GitHubUpdater updater = updater("1.1.0");
        updater.checkNow();

        updater.install();

        assertEquals(Kind.FAILED, updater.status().kind());
        assertTrue(updater.status().message().contains("checksum"), updater.status().message());
        try (var left = Files.list(updates)) {
            assertEquals(List.of(), left.toList(), "nothing of it is kept");
        }
        updater.startPendingInstaller();
        assertTrue(launched.isEmpty(), "a refused installer is never run");
    }

    @Test
    void aReleaseThatPublishesNoChecksumForItsInstallerIsRefused() throws Exception {
        publish("v1.2.0", INSTALLER, sha256(INSTALLER) + "  some-other-file.zip");
        GitHubUpdater updater = updater("1.1.0");
        updater.checkNow();

        updater.install();

        assertEquals(Kind.FAILED, updater.status().kind());
        assertTrue(updater.status().message().contains("no checksum"), updater.status().message());
        assertFalse(Files.exists(updates.resolve(NAME)));
    }

    @Test
    void aFailedFetchCanBeTriedAgainAndDismissedAndANewInstallReplacesOldOnes() throws Exception {
        publishGood("v1.2.0");
        Files.writeString(updates.resolve("FTC-Sim-1.0.0-Setup.exe"), "an old installer");
        Files.writeString(updates.resolve("FTC-Sim-1.0.0-Setup.exe.part"), "an old half");
        files.remove("/download/" + NAME);
        GitHubUpdater updater = updater("1.1.0");
        updater.checkNow();

        updater.install();
        assertEquals(Kind.FAILED, updater.status().kind(), "the installer cannot be fetched");
        assertTrue(updater.status().message().contains("404"), updater.status().message());

        files.put("/download/" + NAME, INSTALLER);
        updater.install();
        assertEquals(Status.restarting("1.2.0"), updater.status(), "trying again works");
        try (var kept = Files.list(updates)) {
            assertEquals(List.of(NAME), kept.map(path -> path.getFileName().toString()).toList(), "older installers are removed");
        }

        GitHubUpdater another = updater("1.1.0");
        another.checkNow();
        assertEquals(Kind.AVAILABLE, another.status().kind());
        another.dismiss();
        assertEquals(Status.NONE, another.status());
        another.install();
        assertEquals(Status.NONE, another.status(), "nothing is offered after dismissing, so there is nothing to install");
    }

    @Test
    void theInstallerIsRunWithOptionsThatAskNothingAndCloseWhatHoldsItsFiles() {
        List<String> command = GitHubUpdater.installerCommand(Path.of("C:\\x\\FTC-Sim-1.2.0-Setup.exe"));

        assertEquals("C:\\x\\FTC-Sim-1.2.0-Setup.exe", command.get(0));
        assertTrue(command.containsAll(List.of("/SILENT", "/NOCANCEL", "/SUPPRESSMSGBOXES", "/CLOSEAPPLICATIONS")), command.toString());
        assertFalse(command.contains("/VERYSILENT"), "a silent update still shows that it is working");
        assertFalse(GitHubUpdater.INSTALLER_NAME.matcher("FTC-Sim-1.2.0.zip").matches(), "the zip is not the installer");
        assertTrue(GitHubUpdater.INSTALLER_NAME.matcher(NAME).matches());
    }
}
