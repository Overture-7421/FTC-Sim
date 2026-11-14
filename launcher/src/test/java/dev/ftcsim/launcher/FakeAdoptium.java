package dev.ftcsim.launcher;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A local stand-in for the Adoptium API and the GitHub download host. It answers in the real response shapes
 * (the 'latest' list and the 'release_name' object) and can misbehave on demand: cut a download short, ignore Range
 * requests, corrupt the bytes, answer 503, or redirect to an origin the manifest does not allow.
 */
final class FakeAdoptium implements AutoCloseable {

    /** The release served unless a test publishes another: the one the shipped manifest pins, as the real Adoptium API names it. */
    private static final TestManifest.Pin PIN = TestManifest.shippedPin();
    static final String RELEASE_NAME = PIN.release();
    static final String OPENJDK_VERSION = PIN.build() + "-LTS";
    static final String ARCHIVE_NAME = "OpenJDK" + PIN.major() + "U-jdk_x64_windows_hotspot_" + PIN.build().replace('+', '_') + ".zip";

    record Request(String method, String rawPath, String range) {
    }

    record Release(String name, String openjdkVersion, String sha256, int size, String fileName, String path) {
    }

    private static final Pattern RANGE = Pattern.compile("bytes=(\\d+)-");

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final Map<String, byte[]> binaries = new ConcurrentHashMap<>();
    private final AtomicBoolean cutDone = new AtomicBoolean();
    private final AtomicInteger failuresLeft = new AtomicInteger();
    private volatile Release release;
    private volatile int cutFirstDownloadAfter = -1;
    private volatile boolean stallInsteadOfClosing;
    private volatile boolean honorRange = true;
    private volatile boolean misreportRanges;
    private volatile boolean corruptDownload;
    private volatile long downloadDelayMillis;
    private volatile String advertisedSha256;
    private volatile String advertisedLink;
    private volatile String advertisedOs = "windows";
    private volatile String advertisedArchitecture = "x64";
    private volatile String advertisedImageType = "jdk";
    private volatile String apiResponse;
    private volatile int apiStatus;
    private volatile long announcedLengthDelta;

    private FakeAdoptium(HttpServer server) {
        this.server = server;
    }

    static FakeAdoptium start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        FakeAdoptium fake = new FakeAdoptium(server);
        server.createContext("/", fake::handle);
        server.setExecutor(fake.executor);
        server.start();
        return fake;
    }

    String origin() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    List<Request> requests() {
        return List.copyOf(requests);
    }

    List<Request> downloads() {
        return requests.stream().filter(request -> request.rawPath().startsWith("/binaries/")).toList();
    }

    List<Request> apiRequests() {
        return requests.stream().filter(request -> request.rawPath().startsWith("/v3/")).toList();
    }

    /** Publishes the default release with deterministic pseudo-random bytes of the given size. */
    Release publish(int size) {
        byte[] bytes = new byte[size];
        new Random(42).nextBytes(bytes);
        return publish(bytes);
    }

    /** Publishes the default release with the given archive bytes. */
    Release publish(byte[] bytes) {
        return publish(RELEASE_NAME, OPENJDK_VERSION, ARCHIVE_NAME, bytes);
    }

    Release publish(String name, String openjdkVersion, String fileName, byte[] bytes) {
        String path = "/binaries/" + fileName;
        binaries.put(path, bytes);
        release = new Release(name, openjdkVersion, sha256(bytes), bytes.length, fileName, path);
        return release;
    }

    /** Cuts the next download off after the given number of bytes by dropping the connection; later downloads are complete. */
    FakeAdoptium cutFirstDownloadAfter(int bytes) {
        this.cutFirstDownloadAfter = bytes;
        this.stallInsteadOfClosing = false;
        this.cutDone.set(false);
        return this;
    }

    /** Like a cut, but the connection stays open and silent, as when the network drops without a reset. */
    FakeAdoptium stallFirstDownloadAfter(int bytes) {
        cutFirstDownloadAfter(bytes);
        this.stallInsteadOfClosing = true;
        return this;
    }

    FakeAdoptium ignoreRange() {
        this.honorRange = false;
        return this;
    }

    /** Makes resumed responses claim a start position one byte before the one that was asked for, like a faulty proxy. */
    FakeAdoptium misreportRanges(boolean misreport) {
        this.misreportRanges = misreport;
        return this;
    }

    FakeAdoptium corruptDownload(boolean corrupt) {
        this.corruptDownload = corrupt;
        return this;
    }

    FakeAdoptium failDownloadsWith503(int times) {
        this.failuresLeft.set(times);
        return this;
    }

    FakeAdoptium delayDownloads(long millis) {
        this.downloadDelayMillis = millis;
        return this;
    }

    /** Makes the metadata state a checksum other than the real one; null goes back to the real one. */
    FakeAdoptium advertiseSha256(String sha256) {
        this.advertisedSha256 = sha256;
        return this;
    }

    /** Makes the metadata link somewhere else; null goes back to the real download. */
    FakeAdoptium advertiseLink(String link) {
        this.advertisedLink = link;
        return this;
    }

    /** Makes the metadata describe a different operating system and architecture than the bytes really are. */
    FakeAdoptium advertiseAsset(String os, String architecture) {
        this.advertisedOs = os;
        this.advertisedArchitecture = architecture;
        return this;
    }

    /** Makes the metadata call the package a "jre" (the real service lists JDK and JRE images as separate binaries). */
    FakeAdoptium advertiseImageType(String imageType) {
        this.advertisedImageType = imageType;
        return this;
    }

    /** Answers every API request with this exact text; null goes back to the real shapes. */
    FakeAdoptium respondToApiWith(String json) {
        this.apiResponse = json;
        return this;
    }

    /** Answers every API request with this status and no body; 0 goes back to the real shapes. */
    FakeAdoptium answerApiWithStatus(int status) {
        this.apiStatus = status;
        return this;
    }

    /** Makes the download announce a Content-Length that differs from the metadata's size. */
    FakeAdoptium announceLengthDelta(long delta) {
        this.announcedLengthDelta = delta;
        return this;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        String rawPath = exchange.getRequestURI().getRawPath();
        String query = exchange.getRequestURI().getRawQuery();
        requests.add(new Request(exchange.getRequestMethod(), query == null ? rawPath : rawPath + "?" + query,
                exchange.getRequestHeaders().getFirst("Range")));
        try (exchange) {
            if (rawPath.startsWith("/v3/") && apiStatus != 0) {
                exchange.sendResponseHeaders(apiStatus, -1);
            } else if (rawPath.startsWith("/v3/assets/latest/") || rawPath.startsWith("/v3/assets/release_name/")) {
                json(exchange, apiResponse != null ? apiResponse
                        : rawPath.startsWith("/v3/assets/latest/") ? latestDocument() : releaseDocument());
            } else if (rawPath.startsWith("/binaries/")) {
                download(exchange);
            } else if (rawPath.equals("/redirect-ok")) {
                redirect(exchange, release.path());
            } else if (rawPath.equals("/redirect-foreign")) {
                redirect(exchange, "http://localhost:" + server.getAddress().getPort() + release.path());
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
        } catch (IOException e) {
            // The client may hang up on purpose, and cutting a response short ends in an exception here by design.
        }
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(302, -1);
    }

    private static void json(HttpExchange exchange, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private void download(HttpExchange exchange) throws IOException {
        if (downloadDelayMillis > 0) {
            try {
                Thread.sleep(downloadDelayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (failuresLeft.getAndUpdate(left -> Math.max(0, left - 1)) > 0) {
            exchange.sendResponseHeaders(503, -1);
            return;
        }
        byte[] data = binaries.get(exchange.getRequestURI().getPath());
        if (data == null) {
            exchange.sendResponseHeaders(404, -1);
            return;
        }
        if (corruptDownload) {
            data = data.clone();
            data[data.length / 2] ^= 0x55;
        }
        int start = 0;
        String range = exchange.getRequestHeaders().getFirst("Range");
        Matcher matcher = range == null ? null : RANGE.matcher(range);
        boolean partial = honorRange && matcher != null && matcher.matches();
        if (partial) {
            start = Integer.parseInt(matcher.group(1));
            int reported = misreportRanges ? start - 1 : start;
            exchange.getResponseHeaders().add("Content-Range", "bytes " + reported + "-" + (data.length - 1) + "/" + data.length);
        }
        int length = data.length - start;
        int toSend = length;
        boolean cut = false;
        if (cutFirstDownloadAfter >= 0 && !cutDone.getAndSet(true)) {
            toSend = Math.min(length, cutFirstDownloadAfter);
            cut = toSend < length;
        }
        exchange.getResponseHeaders().add("Accept-Ranges", "bytes");
        exchange.sendResponseHeaders(partial ? 206 : 200, length + (partial ? 0 : announcedLengthDelta));
        OutputStream out = exchange.getResponseBody();
        out.write(data, start, toSend);
        out.flush();
        if (cut) {
            abandonResponse();
        }
        out.close();
    }

    /** Ends a response without the bytes it promised: silently, until the client gives up, or by dropping the connection. */
    private void abandonResponse() {
        if (stallInsteadOfClosing) {
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // The server closes the connection when a handler fails with anything but an IOException, which handle() would swallow.
        throw new UncheckedIOException(new IOException("The test cut this download short"));
    }

    private String latestDocument() {
        Release current = release;
        return "[{\"binary\":{" + packageJson(current) + "},\"release_name\":\"" + current.name()
                + "\",\"vendor\":\"eclipse\",\"version\":" + versionJson(current) + "}]";
    }

    private String releaseDocument() {
        Release current = release;
        return "{\"binaries\":[{" + packageJson(current) + "}],\"release_name\":\"" + current.name()
                + "\",\"release_type\":\"ga\",\"vendor\":\"eclipse\",\"version_data\":" + versionJson(current) + "}";
    }

    private String packageJson(Release current) {
        String link = advertisedLink != null ? advertisedLink : origin() + current.path();
        String checksum = advertisedSha256 != null ? advertisedSha256 : current.sha256();
        return "\"architecture\":\"" + advertisedArchitecture + "\",\"image_type\":\"" + advertisedImageType + "\",\"os\":\"" + advertisedOs
                + "\",\"package\":{\"checksum\":\"" + checksum
                + "\",\"link\":\"" + link + "\",\"name\":\"" + current.fileName() + "\",\"size\":" + current.size() + "}";
    }

    private static String versionJson(Release current) {
        Matcher matcher = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)\\+(\\d+)").matcher(current.openjdkVersion());
        if (!matcher.find()) {
            throw new IllegalArgumentException(current.openjdkVersion());
        }
        return "{\"build\":" + matcher.group(4) + ",\"major\":" + matcher.group(1) + ",\"minor\":" + matcher.group(2)
                + ",\"openjdk_version\":\"" + current.openjdkVersion() + "\",\"security\":" + matcher.group(3) + "}";
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
