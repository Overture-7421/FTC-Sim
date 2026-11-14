package dev.ftcsim.launcher;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** A complete stage-0 manifest with test-sized timeouts, pointing at a local fixture server instead of Adoptium. */
final class TestManifest {

    /** The Java major version the manifests of these tests pin; a test that needs a wrong one names it explicitly. */
    static final int PINNED_MAJOR = 25;

    /** The exact release the shipped manifest pins. */
    record Pin(String release, String sha256, int major) {

        /** The build as a runtime reports it in java.runtime.version before any vendor suffix, such as 25.0.5+8. */
        String build() {
            return release.replaceFirst("^jdk-", "");
        }
    }

    private final List<String> origins = new ArrayList<>();
    private final List<String> registryRoots = new ArrayList<>();
    private final List<String[]> directoryPatterns = new ArrayList<>();
    private String apiBase;
    private String exactRelease;
    private String exactSha256;
    private String imageType = "jdk";
    private int attempts = 3;
    private int readTimeoutSeconds = 10;
    private int probeTimeoutSeconds = 20;
    private int lockWaitSeconds = 30;
    private long extractedSizeEstimate = 1_000_000;
    private long maxExtractedBytes = 2_000_000_000L;
    private int maxEntries = 60_000;

    static TestManifest forServer(String origin) {
        TestManifest manifest = new TestManifest();
        manifest.apiBase = origin;
        manifest.origins.add(origin);
        return manifest;
    }

    /** For tests that must never reach the network: the API origin is allowed but nothing listens there. */
    static TestManifest offline() {
        try (ServerSocket unused = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return forServer("http://127.0.0.1:" + unused.getLocalPort());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    TestManifest allow(String origin) {
        origins.add(origin);
        return this;
    }

    TestManifest exactRelease(String release, String sha256) {
        this.exactRelease = release;
        this.exactSha256 = sha256;
        return this;
    }

    /** What the manifest pins: "jdk" (the shipped choice) or "jre". */
    TestManifest imageType(String imageType) {
        this.imageType = imageType;
        return this;
    }

    TestManifest attempts(int attempts) {
        this.attempts = attempts;
        return this;
    }

    TestManifest readTimeoutSeconds(int seconds) {
        this.readTimeoutSeconds = seconds;
        return this;
    }

    TestManifest probeTimeoutSeconds(int seconds) {
        this.probeTimeoutSeconds = seconds;
        return this;
    }

    TestManifest lockWaitSeconds(int seconds) {
        this.lockWaitSeconds = seconds;
        return this;
    }

    TestManifest limits(long maxExtractedBytes, int maxEntries) {
        this.maxExtractedBytes = maxExtractedBytes;
        this.maxEntries = maxEntries;
        return this;
    }

    TestManifest extractedSizeEstimate(long bytes) {
        this.extractedSizeEstimate = bytes;
        return this;
    }

    TestManifest directoryPattern(String source, String pattern) {
        directoryPatterns.add(new String[] {source, pattern});
        return this;
    }

    TestManifest registryRoot(String root) {
        registryRoots.add(root);
        return this;
    }

    Path write(Path directory) throws IOException {
        String patterns = directoryPatterns.stream()
                .map(entry -> "{ \"source\": " + json(entry[0]) + ", \"pattern\": " + json(entry[1]) + " }")
                .collect(Collectors.joining(",\n      "));
        String text = """
                {
                  "schemaVersion": 1,
                  "runtime": {
                    "vendor": "temurin", "major": %d, "exactRelease": %s, "exactSha256": %s,
                    "os": "windows", "architecture": "x64", "imageType": %s, "extractedSizeEstimateBytes": %d
                  },
                  "source": {
                    "apiBase": %s,
                    "latestPath": "/v3/assets/latest/{major}/hotspot?architecture={architecture}&image_type={imageType}&os={os}&vendor=eclipse",
                    "releasePath": "/v3/assets/release_name/eclipse/{release}?architecture={architecture}&heap_size=normal&image_type={imageType}&os={os}&project=jdk",
                    "allowedOrigins": [%s]
                  },
                  "network": {
                    "attempts": %d, "retryDelaysSeconds": [0], "connectTimeoutSeconds": 5, "readTimeoutSeconds": %d,
                    "maxRedirects": 5, "lockWaitSeconds": %d
                  },
                  "limits": { "maxExtractedBytes": %d, "maxEntries": %d },
                  "probe": { "timeoutSeconds": %d },
                  "application": { "jvmArguments": [%s] },
                  "discovery": {
                    "registryRoots": [%s],
                    "registryValueNames": ["JavaHome", "Path", "InstallationPath", "InstallDir"],
                    "directoryPatterns": [
                      %s
                    ]
                  }
                }
                """.formatted(
                PINNED_MAJOR,
                exactRelease == null ? "null" : json(exactRelease),
                exactSha256 == null ? "null" : json(exactSha256),
                json(imageType),
                extractedSizeEstimate,
                json(apiBase),
                origins.stream().map(TestManifest::json).collect(Collectors.joining(", ")),
                attempts,
                readTimeoutSeconds,
                lockWaitSeconds,
                maxExtractedBytes,
                maxEntries,
                probeTimeoutSeconds,
                applicationOptions().stream().map(TestManifest::json).collect(Collectors.joining(", ")),
                registryRoots.stream().map(TestManifest::json).collect(Collectors.joining(", ")),
                patterns);
        return Files.writeString(Files.createTempFile(directory, "manifest-", ".json"), text);
    }

    /** The Java options the shipped manifest starts the application with; the manifests of these tests carry the same. */
    static List<String> applicationOptions() {
        try {
            Matcher section = Pattern.compile("\"jvmArguments\"\\s*:\\s*\\[([^\\]]*)\\]")
                    .matcher(Files.readString(Stage0.scripts().resolve("jdk-manifest.json")));
            if (!section.find()) {
                throw new IllegalStateException("the shipped manifest has no application.jvmArguments");
            }
            List<String> options = new ArrayList<>();
            Matcher option = Pattern.compile("\"([^\"]+)\"").matcher(section.group(1));
            while (option.find()) {
                options.add(option.group(1));
            }
            return options;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The release the shipped manifest pins. The fixtures (the fake release service, the fixture JDKs) impersonate exactly
     * this release, so updating the pin changes no test; a manifest that pins nothing fails every test that asks.
     */
    static Pin shippedPin() {
        try {
            String text = Files.readString(Stage0.scripts().resolve("jdk-manifest.json"));
            return new Pin(pinned(text, "\"exactRelease\"\\s*:\\s*\"([^\"]+)\""), pinned(text, "\"exactSha256\"\\s*:\\s*\"([0-9a-f]{64})\""),
                    Integer.parseInt(pinned(text, "\"major\"\\s*:\\s*(\\d+)")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String pinned(String manifestText, String pattern) {
        Matcher matcher = Pattern.compile(pattern).matcher(manifestText);
        if (!matcher.find()) {
            throw new IllegalStateException("the shipped manifest does not pin a release: no match for " + pattern);
        }
        return matcher.group(1);
    }

    private static String json(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
