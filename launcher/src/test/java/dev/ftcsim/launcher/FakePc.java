package dev.ftcsim.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.ftcsim.launcher.Stage0.Result;
import dev.ftcsim.launcher.Stage0.Running;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * One simulated PC without Java: an empty cache, a fake Adoptium service that serves a small fixture JDK, and a manifest
 * that points at it. Stage 0 runs against it exactly as it ships; discovery is limited to the cache unless a test names
 * its own sources, so the JDKs of the machine running the tests can never leak in.
 */
final class FakePc implements AutoCloseable {

    static final String TOP_LEVEL = FakeAdoptium.RELEASE_NAME;

    final Path work;
    final Path cache;
    final FakeAdoptium service;
    final FakeAdoptium.Release release;
    final TestManifest manifest;
    private final List<Running> started = new ArrayList<>();

    private FakePc(Path work, FakeAdoptium service, FakeAdoptium.Release release) {
        this.work = work;
        this.cache = work.resolve("cache");
        this.service = service;
        this.release = release;
        this.manifest = TestManifest.forServer(service.origin());
    }

    static FakePc start(Path work) throws IOException, InterruptedException {
        FakeAdoptium service = FakeAdoptium.start();
        try {
            Path jdk = FixtureJdk.at(work.resolve("fixture-jdk")).build();
            byte[] archive = FixtureZip.ofDirectory(jdk, TOP_LEVEL).padding(TOP_LEVEL + "/lib/modules", 1_500_000).toBytes();
            return new FakePc(work, service, service.publish(archive));
        } catch (IOException | InterruptedException | RuntimeException e) {
            service.close();
            throw e;
        }
    }

    /** Where the pinned fixture JDK is installed: jdks\temurin-25.0.5_8-first8hexOfTheChecksum. */
    Path destination() {
        String version = release.openjdkVersion().replaceAll("-.*", "").replace('+', '_');
        return cache.resolve("jdks").resolve("temurin-" + version + "-" + release.sha256().substring(0, 8));
    }

    /** The name the unfinished download is kept under, so a later run can resume it. */
    Path partialDownload() {
        return cache.resolve("tmp").resolve("dl-" + release.sha256().substring(0, 12) + ".part");
    }

    Result resolve(Map<String, String> environment, String... options) throws IOException {
        return startResolve(environment, options).await();
    }

    Running startResolve(Map<String, String> environment, String... options) throws IOException {
        return begin(environment, true, options);
    }

    /** A full launch: after preparing the runtime, stage 0 hands over to the fixture javaw.exe, which prints what it was given. */
    Result launch(Map<String, String> environment, String... options) throws IOException {
        return begin(environment, false, options).await();
    }

    /** The application's jar as stage 0 finds it; the fixture javaw.exe never reads it, and its folder has a space in its name, as installs do. */
    static Path dummyApplicationJar(Path work) throws IOException {
        Path jar = Files.createDirectories(work.resolve("FTC Sim").resolve("lib")).resolve("app.jar");
        return Files.exists(jar) ? jar : Files.writeString(jar, "not a jar");
    }

    private Running begin(Map<String, String> environment, boolean resolveOnly, String... options) throws IOException {
        List<String> arguments = new ArrayList<>(List.of("-NonInteractive", "-CacheRoot", cache.toString(),
                "-ManifestPath", manifest.write(work).toString()));
        if (resolveOnly) {
            arguments.add("-ResolveOnly");
        } else {
            arguments.addAll(List.of("-AppJar", dummyApplicationJar(work).toString()));
        }
        List<String> given = List.of(options);
        if (!given.contains("-DiscoverySources")) {
            arguments.add("-IgnoreSystemJdks");
        }
        arguments.addAll(given);
        Running running = Stage0.start(Stage0.scripts().resolve("FTC-Sim.ps1"), environment, arguments.toArray(String[]::new));
        started.add(running);
        return running;
    }

    /** Waits until the service has been asked for the archive, which means a launch is inside its provisioning step. */
    void awaitDownloadRequest() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (service.downloads().isEmpty()) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("Stage 0 never asked for the archive");
            }
            Thread.sleep(20);
        }
    }

    /** What is left in a cache folder: names only, no matter how deep. */
    List<String> cacheContents(String folder) throws IOException {
        Path directory = cache.resolve(folder);
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var entries = Files.list(directory)) {
            return entries.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    /** A failed provisioning must leave neither an install nor a leftover (a download that is kept for a resume counts as one). */
    void assertNothingInstalled() throws IOException {
        assertEquals(List.of(), cacheContents("jdks"));
        assertEquals(List.of(), cacheContents("tmp"));
    }

    @Override
    public void close() {
        started.forEach(Running::kill);
        service.close();
    }
}
