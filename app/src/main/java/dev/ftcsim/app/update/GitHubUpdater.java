package dev.ftcsim.app.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ftcsim.app.AppFolders;
import dev.ftcsim.ui.host.UpdateService;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps FTC-Sim up to date from its releases on GitHub. {@link #checkNow} reads the latest release; when it is newer than
 * this version it is offered, and only when the person asks ({@link #install}) is its installer fetched, checked against the
 * SHA-256 the release publishes and kept, for {@link #startPendingInstaller} to run after the window has closed: the installer
 * replaces the files this program runs from, so it must not start before they are free.
 *
 * <p>What is fetched is limited to HTTPS on GitHub's own hosts (plain HTTP only to the computer itself, for tests). An
 * installer with no published checksum, or one that does not match it, is refused and deleted. The checksum comes from the
 * same release as the installer, so it guards against a damaged or truncated download, not against a release that was
 * tampered with: the installer is not signed.
 *
 * <p>Looking for a newer version fails quietly (no network is not a problem to tell the person about); fetching the installer
 * of a version that was offered fails loudly, with the reason.
 */
public final class GitHubUpdater implements UpdateService {
    /** The repository the releases are in. */
    public static final String REPOSITORY = "Overture-7421/FTC-Sim";
    /** The installer's name in a release: {@code FTC-Sim-<version>-Setup.exe}. */
    public static final Pattern INSTALLER_NAME = Pattern.compile("FTC-Sim-(\\d[0-9A-Za-z.+-]*)-Setup\\.exe");
    /** The file of a release that lists the SHA-256 of its other files, one {@code <hex>  <name>} line each. */
    public static final String CHECKSUMS_NAME = "SHA256SUMS.txt";

    private static final URI GITHUB_API = URI.create("https://api.github.com");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(15);
    private static final int BUFFER = 64 * 1024;

    /** What the person has to be asked to install, as found in a release. */
    record Release(Version version, String tag, URI installer, String installerName, long installerSize, URI checksums) {}

    /** Runs an installer; a test stands in for it. */
    interface InstallerLauncher {
        void launch(Path installer) throws IOException;
    }

    private final URI apiBase;
    private final String repository;
    private final Version current;
    private final Path updates;
    private final InstallerLauncher launcher;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private final List<Consumer<Status>> listeners = new CopyOnWriteArrayList<>();
    private volatile Status status = Status.NONE;
    private volatile Release offered;
    private volatile Path pending;

    /** The updater of this installation, looking in {@link #REPOSITORY}. */
    public static GitHubUpdater forThisInstallation(AppFolders folders, Version current) {
        return new GitHubUpdater(GITHUB_API, REPOSITORY, current, folders.updates(), GitHubUpdater::runInstaller);
    }

    GitHubUpdater(URI apiBase, String repository, Version current, Path updates, InstallerLauncher launcher) {
        this.apiBase = apiBase;
        this.repository = repository;
        this.current = current;
        this.updates = updates;
        this.launcher = launcher;
        this.http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(CONNECT_TIMEOUT).build();
    }

    @Override
    public Status status() {
        return status;
    }

    @Override
    public void addListener(Consumer<Status> listener) {
        listeners.add(listener);
    }

    @Override
    public void removeListener(Consumer<Status> listener) {
        listeners.remove(listener);
    }

    private void set(Status next) {
        status = next;
        for (Consumer<Status> listener : listeners) {
            listener.accept(next);
        }
    }

    @Override
    public void checkNow() {
        Kind kind = status.kind();
        if (kind == Kind.DOWNLOADING || kind == Kind.RESTARTING) {
            return;
        }
        try {
            Release latest = latestRelease();
            if (latest.version().compareTo(current) > 0) {
                offered = latest;
                set(Status.available(latest.version().toString()));
            } else {
                offered = null;
                set(Status.NONE);
            }
        } catch (IOException | RuntimeException failure) {
            System.err.println("FTC-Sim could not look for a newer version: " + failure.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void install() {
        Release release = offered;
        if (release == null || status.kind() == Kind.DOWNLOADING || status.kind() == Kind.RESTARTING) {
            return;
        }
        String version = release.version().toString();
        set(Status.downloading(version, 0));
        try {
            pending = download(release);
            set(Status.restarting(version));
        } catch (IOException | RuntimeException failure) {
            System.err.println("FTC-Sim could not fetch " + release.installerName() + ": " + failure.getMessage());
            set(Status.failed(version, failure.getMessage() == null ? failure.toString() : failure.getMessage()));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            set(Status.failed(version, "interrupted"));
        }
    }

    @Override
    public void dismiss() {
        if (status.kind() == Kind.AVAILABLE || status.kind() == Kind.FAILED) {
            offered = null;
            set(Status.NONE);
        }
    }

    /**
     * Runs the installer that {@link #install} prepared, if it did, and returns at once without waiting for it. Call it
     * when the window has closed and nothing of this installation is in use any more.
     */
    public void startPendingInstaller() {
        Path installer = pending;
        if (installer == null) {
            return;
        }
        pending = null;
        try {
            launcher.launch(installer);
        } catch (IOException failure) {
            System.err.println("FTC-Sim could not start its installer " + installer + ": " + failure.getMessage());
        }
    }

    /** What the installer of an update is run with: it shows its progress, asks nothing, and the person cannot cancel half way. */
    static List<String> installerCommand(Path installer) {
        return List.of(installer.toString(), "/SILENT", "/NOCANCEL", "/SUPPRESSMSGBOXES", "/CLOSEAPPLICATIONS");
    }

    private static void runInstaller(Path installer) throws IOException {
        new ProcessBuilder(installerCommand(installer))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    // ---- the network -------------------------------------------------------------------------------------------------

    private Release latestRelease() throws IOException, InterruptedException {
        URI url = apiBase.resolve("/repos/" + repository + "/releases/latest");
        JsonNode release = json.readTree(getText(url));
        if (release.path("draft").asBoolean(false) || release.path("prerelease").asBoolean(false)) {
            throw new IOException("the latest release is not a final one");
        }
        Version version = Version.parse(release.path("tag_name").asText(""));
        JsonNode installer = null;
        URI checksums = null;
        for (JsonNode asset : release.path("assets")) {
            String name = asset.path("name").asText("");
            if (INSTALLER_NAME.matcher(name).matches()) {
                installer = asset;
            } else if (name.equals(CHECKSUMS_NAME)) {
                checksums = allowed(URI.create(asset.path("browser_download_url").asText("")));
            }
        }
        if (installer == null || checksums == null) {
            throw new IOException("release " + version + " has no installer with its checksums");
        }
        return new Release(
                version, release.path("tag_name").asText(), allowed(URI.create(installer.path("browser_download_url").asText(""))),
                installer.path("name").asText(), installer.path("size").asLong(-1), checksums);
    }

    /** Fetches the installer of {@code release} into the updates folder, checks it, and returns where it is. */
    private Path download(Release release) throws IOException, InterruptedException {
        Files.createDirectories(updates);
        removeOldInstallers();
        String expected = publishedChecksum(release);
        Path part = updates.resolve(release.installerName() + ".part");
        Path installer = updates.resolve(release.installerName());
        MessageDigest digest = sha256();
        HttpRequest request = request(release.installer(), DOWNLOAD_TIMEOUT);
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            requireOk(response.statusCode(), release.installer());
            long total = response.headers().firstValueAsLong("Content-Length").orElse(release.installerSize());
            try (DigestInputStream in = new DigestInputStream(body, digest); OutputStream out = Files.newOutputStream(part)) {
                byte[] buffer = new byte[BUFFER];
                long done = 0;
                int shown = -1;
                for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                    out.write(buffer, 0, read);
                    done += read;
                    int percent = total > 0 ? (int) Math.min(99, done * 100 / total) : 0;
                    if (percent != shown) {
                        shown = percent;
                        set(Status.downloading(release.version().toString(), percent));
                    }
                }
            }
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(part);
            throw failure;
        }
        String actual = HexFormat.of().formatHex(digest.digest());
        if (!actual.equalsIgnoreCase(expected)) {
            Files.deleteIfExists(part);
            throw new IOException("the download does not match the checksum the release publishes, so it was thrown away");
        }
        Files.move(part, installer, StandardCopyOption.REPLACE_EXISTING);
        return installer;
    }

    /** The SHA-256 the release publishes for its installer. */
    private String publishedChecksum(Release release) throws IOException, InterruptedException {
        String sums = getText(release.checksums());
        for (String line : sums.split("\\R")) {
            Matcher entry = Pattern.compile("\\s*([0-9A-Fa-f]{64})\\s+\\*?(.+?)\\s*").matcher(line);
            if (entry.matches() && entry.group(2).equals(release.installerName())) {
                return entry.group(1).toLowerCase(Locale.ROOT);
            }
        }
        throw new IOException("the release publishes no checksum for " + release.installerName());
    }

    private void removeOldInstallers() throws IOException {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(updates, "FTC-Sim-*-Setup.exe*")) {
            for (Path file : files) {
                Files.deleteIfExists(file);
            }
        }
    }

    private String getText(URI url) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = http.send(request(url, REQUEST_TIMEOUT), HttpResponse.BodyHandlers.ofByteArray());
        requireOk(response.statusCode(), url);
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private HttpRequest request(URI url, Duration timeout) {
        return HttpRequest.newBuilder(allowed(url))
                .timeout(timeout)
                .header("User-Agent", "FTC-Sim/" + current)
                .header("Accept", "application/vnd.github+json, application/octet-stream, text/plain")
                .GET()
                .build();
    }

    private static void requireOk(int status, URI url) throws IOException {
        if (status != 200) {
            throw new IOException("GitHub answered " + status + " for " + url.getPath());
        }
    }

    /** The address, if FTC-Sim may fetch from it: HTTPS on GitHub, or plain HTTP to the computer itself when that is where the API is. */
    private URI allowed(URI url) {
        String host = url.getHost() == null ? "" : url.getHost().toLowerCase(Locale.ROOT);
        boolean github = "https".equals(url.getScheme()) && (host.equals("github.com") || host.endsWith(".github.com")
                || host.endsWith(".githubusercontent.com"));
        boolean local = "http".equals(url.getScheme()) && isLoopback(host) && isLoopback(apiBase.getHost());
        if (!github && !local) {
            throw new IllegalArgumentException("FTC-Sim does not fetch from " + url);
        }
        return url;
    }

    private static boolean isLoopback(String host) {
        return host != null && (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]"));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Every Java runtime has SHA-256", impossible);
        }
    }
}
