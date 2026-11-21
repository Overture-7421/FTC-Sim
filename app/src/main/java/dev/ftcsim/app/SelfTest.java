package dev.ftcsim.app;

import dev.ftcsim.contracts.session.Catalogue;
import dev.ftcsim.contracts.session.SessionInfo;
import dev.ftcsim.contracts.session.SessionStatus;
import dev.ftcsim.contracts.session.SimulatorLink;
import dev.ftcsim.contracts.snapshot.WorldSnapshot;
import dev.ftcsim.link.RemoteSimulatorLink;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * {@code Main --self-test}: shows in a few seconds, with no window, whether this installation can run FTC-Sim on this
 * computer, and says what is wrong when it cannot. It checks the Java it runs on (the runtime folder of the installation,
 * x64, and the options the launcher passes), that the jars of the window and its native libraries can be reached, that the
 * class path file is whole, that the real worker starts on the same Java, tells what can be asked of it and ends with its
 * link, that the physics engine loads, and that no process is left behind. Everything is written to standard output, one
 * line a check.
 *
 * <p>A distribution has no robot program (those come from a team's project, with the FTC SDK), so no session can be
 * opened in the worker; the physics engine is therefore loaded the way the worker loads it, from the worker's own jars
 * into the natives folder of the user, and one world is created and stepped. Nothing else of the user's folders is
 * touched: there is no configuration, no program and no import.
 */
final class SelfTest {

    static final String OPTION = "--self-test";
    static final String PASSED = "FTC-Sim self-test passed";

    private static final Duration WORKER_PATIENCE = Duration.ofSeconds(90);
    private static final Duration CLOSE_PATIENCE = Duration.ofSeconds(10);
    private static final Duration PROCESS_PATIENCE = Duration.ofSeconds(10);

    /** What the window cannot start without: classes of its own, of Compose, Skia, LWJGL and the importer, and the libraries that load natives. */
    private static final List<String> WINDOW_CLASSES = List.of(
            "dev.ftcsim.ui.App", "androidx.compose.ui.window.WindowState", "org.jetbrains.skiko.SkiaLayer", "org.lwjgl.glfw.GLFW",
            "dev.ftcsim.importer.ProjectImporter", "com.fasterxml.jackson.databind.ObjectMapper");
    private static final List<String> WINDOW_NATIVES = List.of(
            "skiko-windows-x64.dll", "windows/x64/org/lwjgl/lwjgl.dll", "windows/x64/org/lwjgl/glfw/glfw.dll",
            "windows/x64/org/lwjgl/opengl/lwjgl_opengl.dll");
    private static final String PHYSICS_WORLD = "dev.ftcsim.physics.PhysicsWorld";

    /** The Java that is running, so that a test can describe another one. */
    record Jvm(int major, String version, String vendor, String arch, String os, Path home, List<String> options) {

        static Jvm current() {
            return new Jvm(
                    Runtime.version().feature(), System.getProperty("java.version"), System.getProperty("java.vendor"),
                    System.getProperty("os.arch"), System.getProperty("os.name"), Path.of(System.getProperty("java.home")),
                    ManagementFactory.getRuntimeMXBean().getInputArguments());
        }

        String describe() {
            return "Java " + version + " (" + vendor + "), " + arch + ", " + os + ", " + home;
        }
    }

    private final PrintStream out;
    private final Jvm jvm;
    private final Machine machine;
    private final Source<RuntimeDescription> description;
    private final Source<AppClasspath> classpath;
    private final Optional<Path> installedRuntime;
    private final List<String> problems = new ArrayList<>();

    /** @param installedRuntime where the runtime folder of the installation is; empty when not running from an installation */
    SelfTest(PrintStream out, Jvm jvm, Machine machine, Source<RuntimeDescription> description, Source<AppClasspath> classpath, Optional<Path> installedRuntime) {
        this.out = out;
        this.jvm = jvm;
        this.machine = machine;
        this.description = description;
        this.classpath = classpath;
        this.installedRuntime = installedRuntime;
    }

    /** The self test of this computer: the Java that runs it, the user's folders and the class path file of the installation. */
    static SelfTest forThisMachine(PrintStream out) {
        return new SelfTest(out, Jvm.current(), Machine.real(), RuntimeDescription::bundled, AppClasspath::locate, RuntimeDescription.installedRuntime());
    }

    /** @return 0 when every check passed, 1 when one did not; the output says which */
    int run() {
        out.println("FTC-Sim self-test");
        RuntimeDescription runtime = attempt("runtime description", description);
        if (runtime != null) {
            checkJava(runtime);
        }
        checkRuntimeFolder();
        checkWindow();
        AppClasspath jars = attempt("class path file", classpath);
        if (jars == null) {
            skip("the worker and the physics engine need the class path file");
        } else {
            ok("class path file: " + jars.worker().size() + " worker jars and " + jars.robot().size() + " robot world jars, all present");
            AppFolders folders = AppFolders.forThisUser(machine);
            checkWorker(jars, folders);
            checkPhysics(jars, folders);
        }
        if (problems.isEmpty()) {
            out.println(PASSED);
            return 0;
        }
        out.println("FTC-Sim self-test FAILED, " + problems.size() + " problem(s): " + String.join("; ", problems));
        return 1;
    }

    private <T> T attempt(String what, Source<T> source) {
        try {
            return source.get();
        } catch (IOException | RuntimeException failure) {
            fail(what, failure.getMessage());
            return null;
        }
    }

    private void checkJava(RuntimeDescription runtime) {
        if (jvm.major() != runtime.major()) {
            fail("Java version", jvm.describe() + "; FTC-Sim runs on Java " + runtime.major() + " only");
        } else if (!(jvm.arch().equals("amd64") || jvm.arch().equals("x86_64")) || !jvm.os().toLowerCase(Locale.ROOT).startsWith("windows")) {
            fail("Java platform", jvm.describe() + "; FTC-Sim needs 64-bit x64 Windows");
        } else {
            ok(jvm.describe());
        }
        List<String> missing = runtime.jvmArguments().stream().filter(option -> !jvm.options().contains(option)).toList();
        if (missing.isEmpty()) {
            ok("Java options: " + String.join(" ", runtime.jvmArguments()));
        } else {
            fail("Java options", "the launcher did not pass " + String.join(" ", missing));
        }
    }

    private void checkRuntimeFolder() {
        if (installedRuntime.isEmpty()) {
            skip("the Java is not the runtime folder of an installation: this program does not run from an installation's lib folder");
            return;
        }
        Path expected = installedRuntime.get();
        if (!Files.isRegularFile(expected.resolve("bin").resolve("java.exe"))) {
            fail("runtime folder", expected + " has no bin\\java.exe: the runtime that comes with FTC-Sim is missing or damaged");
            return;
        }
        boolean same;
        try {
            same = Files.isSameFile(expected, jvm.home());
        } catch (IOException | RuntimeException unreadable) {
            same = false;
        }
        if (same) {
            ok("runtime folder: " + expected + " is the Java that runs FTC-Sim");
        } else {
            fail("runtime folder", "FTC-Sim runs on " + jvm.home() + " and not on the runtime folder of its installation, " + expected);
        }
    }

    private void checkWindow() {
        ClassLoader loader = SelfTest.class.getClassLoader();
        List<String> missing = new ArrayList<>();
        for (String name : WINDOW_CLASSES) {
            try {
                Class.forName(name, false, loader);
            } catch (ClassNotFoundException | LinkageError absent) {
                missing.add(name);
            }
        }
        for (String resource : WINDOW_NATIVES) {
            if (loader.getResource(resource) == null) {
                missing.add(resource);
            }
        }
        if (missing.isEmpty()) {
            ok("the window's classes and its native libraries (Skia, LWJGL) are on the class path");
        } else {
            fail("window files", "not on the class path: " + String.join(", ", missing));
        }
    }

    private void checkWorker(AppClasspath jars, AppFolders folders) {
        try (RealHost host = new RealHost(folders, machine, jars)) {
            host.connectTimeout(WORKER_PATIENCE).shutdownGrace(CLOSE_PATIENCE);
            RemoteSimulatorLink link = null;
            long pid = 0;
            try {
                long started = System.nanoTime();
                SimulatorLink opened = host.openLink();
                if (!(opened instanceof RemoteSimulatorLink remote)) {
                    opened.close();
                    fail("worker", "the host did not start a worker process");
                    return;
                }
                link = remote;
                pid = link.workerPid().orElse(0);
                Catalogue catalogue = awaitCatalogue(link);
                long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                String command = ProcessHandle.of(pid).flatMap(handle -> handle.info().command()).orElse("");
                if (!command.equalsIgnoreCase(RealHost.thisJava().toAbsolutePath().normalize().toString())) {
                    fail("worker", "it runs on " + command + ", not on the Java of the application");
                } else if (catalogue.fields().isEmpty()) {
                    fail("worker", "its catalogue is empty");
                } else {
                    ok("worker (pid " + pid + ") started on the same Java in " + millis + " ms and told its catalogue (fields: "
                            + catalogue.fields().size() + ")");
                }
            } catch (IOException | RuntimeException failure) {
                fail("worker", failure.getMessage());
            } finally {
                if (link != null) {
                    checkWorkerEnded(link, pid);
                }
            }
        }
    }

    private Catalogue awaitCatalogue(RemoteSimulatorLink link) throws IOException {
        AtomicReference<Catalogue> catalogue = new AtomicReference<>();
        AtomicReference<String> failure = new AtomicReference<>();
        CountDownLatch told = new CountDownLatch(1);
        link.addListener(new SimulatorLink.Listener() {
            @Override
            public void catalogue(Catalogue latest) {
                catalogue.set(latest);
                told.countDown();
            }

            @Override
            public void failed(String message) {
                failure.set(message);
                told.countDown();
            }

            @Override
            public void opened(SessionInfo info) {}

            @Override
            public void snapshot(WorldSnapshot snapshot) {}

            @Override
            public void status(SessionStatus status) {}

            @Override
            public void refused(String message) {}
        });
        try {
            if (!told.await(WORKER_PATIENCE.toSeconds(), TimeUnit.SECONDS) || catalogue.get() == null) {
                throw new IOException(failure.get() == null ? "the worker did not tell what can be asked of it" : failure.get());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for the worker", interrupted);
        }
        return catalogue.get();
    }

    private void checkWorkerEnded(RemoteSimulatorLink link, long pid) {
        link.close();
        boolean exitedCleanly = link.workerExitCode().orElse(-1) == 0;
        boolean left = waitForNoChildren();
        if (!exitedCleanly) {
            String code = link.workerExitCode().isPresent() ? String.valueOf(link.workerExitCode().getAsInt()) : "none";
            fail("worker end", "it ended with exit code " + code + " instead of closing with its link");
        } else if (left) {
            fail("worker end", "a process of FTC-Sim is still running after the link closed (worker pid " + pid + ")");
        } else {
            ok("worker ended with its link (exit code 0) and no process is left");
        }
    }

    /** @return whether a child process is still alive after the patience; such a process is ended, so that it does not outlive the test */
    private boolean waitForNoChildren() {
        long deadline = System.nanoTime() + PROCESS_PATIENCE.toNanos();
        while (true) {
            List<ProcessHandle> alive = ProcessHandle.current().descendants().filter(ProcessHandle::isAlive).toList();
            if (alive.isEmpty()) {
                return false;
            }
            if (System.nanoTime() - deadline > 0) {
                alive.forEach(ProcessHandle::destroyForcibly);
                return true;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return true;
            }
        }
    }

    private void checkPhysics(AppClasspath jars, AppFolders folders) {
        URL[] urls = jars.worker().stream().map(SelfTest::url).toArray(URL[]::new);
        try (URLClassLoader loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
            Class<?> type = Class.forName(PHYSICS_WORLD, true, loader);
            Object world = type.getMethod("create", Path.class).invoke(null, folders.natives());
            try {
                String engine = String.valueOf(type.getMethod("engineInfo").invoke(world));
                type.getMethod("step").invoke(world);
                ok("physics engine loaded and stepped: " + engine + "; unpacked to " + unpackedLibrary(folders.natives()));
            } finally {
                ((AutoCloseable) world).close();
            }
        } catch (InvocationTargetException failure) {
            fail("physics engine", describe(failure.getCause()));
        } catch (Exception | LinkageError failure) {
            fail("physics engine", describe(failure));
        }
    }

    private static Path unpackedLibrary(Path natives) throws IOException {
        try (Stream<Path> files = Files.walk(natives, 2)) {
            return files.filter(file -> file.getFileName().toString().equals("joltjni.dll")).findFirst()
                    .orElseThrow(() -> new IOException("no joltjni.dll was unpacked into " + natives));
        }
    }

    private static URL url(Path jar) {
        try {
            return jar.toUri().toURL();
        } catch (MalformedURLException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String describe(Throwable failure) {
        StringBuilder text = new StringBuilder(failure.getClass().getSimpleName());
        if (failure.getMessage() != null) {
            text.append(": ").append(failure.getMessage());
        }
        if (failure.getCause() != null) {
            text.append(" (").append(describe(failure.getCause())).append(')');
        }
        return text.toString();
    }

    private void ok(String text) {
        out.println("  ok    " + text);
    }

    private void skip(String text) {
        out.println("  skip  " + text);
    }

    private void fail(String what, String why) {
        problems.add(what);
        out.println("  FAIL  " + what + ": " + why);
    }
}
