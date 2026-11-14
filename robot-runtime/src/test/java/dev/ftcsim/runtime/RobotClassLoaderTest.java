package dev.ftcsim.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.ftcsim.contracts.hooks.Hooks;
import dev.ftcsim.runtime.fixture.NanoTimeProbe;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RobotClassLoaderTest {
    private static final String FIXTURE_PACKAGE = "dev.ftcsim.runtime.fixture.";

    private final RecordingHooks hooks = new RecordingHooks();

    @BeforeEach
    void installHooks() {
        Hooks.install(hooks);
    }

    @AfterEach
    void restoreHooks() {
        Hooks.installDefault();
    }

    private static List<Path> fixtureClasspath() {
        return Arrays.stream(System.getProperty("ftcsim.fixtureClasses").split(File.pathSeparator))
                .map(Path::of)
                .toList();
    }

    private static RobotClassLoader loader(List<Path> classpath, BytecodeTransformer transformer) throws IOException {
        return new RobotClassLoader(
                classpath,
                RobotClassLoaderTest.class.getClassLoader(),
                name -> name.startsWith("dev.ftcsim.contracts."),
                transformer);
    }

    @SuppressWarnings("unchecked")
    private static <T> T run(RobotClassLoader loader, String probe) throws Exception {
        Class<?> type = loader.loadClass(FIXTURE_PACKAGE + probe);
        return ((Callable<T>) type.getDeclaredConstructor().newInstance()).call();
    }

    @Test
    void robotClassesAreDefinedByTheRobotLoaderAndNotByTheHost() throws Exception {
        try (RobotClassLoader loader = loader(fixtureClasspath(), new HookRewriter())) {
            Class<?> robotCopy = loader.loadClass(NanoTimeProbe.class.getName());

            assertSame(loader, robotCopy.getClassLoader());
            assertNotSame(NanoTimeProbe.class, robotCopy);
        }
    }

    @Test
    void theHooksAreSharedSoTheRobotCallsTheOnesTheHostInstalled() throws Exception {
        try (RobotClassLoader loader = loader(fixtureClasspath(), new HookRewriter())) {
            assertSame(Hooks.class, loader.loadClass(Hooks.class.getName()));
        }
    }

    @Test
    void directClockCallsSeeTheHookedClock() throws Exception {
        try (RobotClassLoader loader = loader(fixtureClasspath(), new HookRewriter())) {
            assertEquals(987_654_321L, (long) run(loader, "NanoTimeProbe"));
            assertEquals(List.of("nanoTime"), hooks.calls);
        }
    }

    @Test
    void methodReferencesToTheClockAreRedirectedToo() throws Exception {
        try (RobotClassLoader loader = loader(fixtureClasspath(), new HookRewriter())) {
            assertEquals(987_654_321L, (long) run(loader, "MethodReferenceProbe"));
            assertEquals(List.of("nanoTime"), hooks.calls);
        }
    }

    @Test
    void sleepingYieldingAndWallClockReadsAreAllVirtual() throws Exception {
        try (RobotClassLoader loader = loader(fixtureClasspath(), new HookRewriter())) {
            String wallClock = run(loader, "WaitingProbe");

            assertEquals("123456", wallClock);
            assertEquals(
                    List.of("sleep(5)", "sleep(MILLISECONDS,7)", "sleep(1,5)", "yield", "currentTimeMillis"), hooks.calls);
        }
    }

    @Test
    void everyMonitorAcquisitionAndReleaseIsReportedWhateverWayCodeEndsWhileHoldingIt() throws Exception {
        try (RobotClassLoader loader = loader(fixtureClasspath(), new HookRewriter())) {
            assertEquals("done", run(loader, "LockProbe"));

            // Two blocks, one of which throws, and three methods, one of which throws.
            assertEquals(10, hooks.locks.size(), hooks.locks.toString());
            for (int i = 0; i < hooks.locks.size(); i += 2) {
                assertEquals("acquired", hooks.locks.get(i), hooks.locks.toString());
                assertEquals("released", hooks.locks.get(i + 1), hooks.locks.toString());
            }
        }
    }

    @Test
    void everyBackwardJumpIsReportedSoBusyLoopsCanBeGivenTimeToEnd() throws Exception {
        try (RobotClassLoader loader = loader(fixtureClasspath(), new HookRewriter())) {
            assertEquals(9, (int) run(loader, "LoopProbe"));

            // The counting loop and the condition loop jump back on each of their 3 and 4 iterations; the loop
            // that tests at the bottom evaluates its backward jump twice.
            assertEquals(9, hooks.backEdges);
        }
    }

    @Test
    void aMethodNamedAsAnEntryHookCallsTheHookBeforeAnythingElse() throws Exception {
        RewriteRules rules = new RewriteRules(
                List.of(), List.of(new RewriteRules.EntryHook(FIXTURE_PACKAGE.replace('.', '/') + "LockProbe", "call", "()Ljava/lang/String;", "threadStarted")));
        try (RobotClassLoader loader = loader(fixtureClasspath(), new HookRewriter(rules))) {
            assertEquals("done", run(loader, "LockProbe"));

            assertEquals(List.of("threadStarted"), hooks.calls);
        }
    }

    @Test
    void nativeLibraryRequestsReachTheHostInsteadOfTheJvm() throws Exception {
        try (RobotClassLoader loader = loader(fixtureClasspath(), new HookRewriter())) {
            assertEquals("loaded", run(loader, "NativeLibraryProbe"));
            assertEquals(List.of("loadLibrary(RobotCore)", "load(C:\\natives\\other.dll)"), hooks.calls);
        }
    }

    @Test
    void monitorWaitsAndNotificationsReachTheHostWhateverTheReceiverIsDeclaredAs() throws Exception {
        try (RobotClassLoader loader = loader(fixtureClasspath(), new HookRewriter())) {
            assertEquals("done", run(loader, "MonitorProbe"));
            assertEquals(
                    List.of("wait(0,0)", "wait(5,0)", "wait(6,7)", "notify", "notifyAll", "wait(1,0)", "notifyAll", "notifyAll"),
                    hooks.calls);
        }
    }

    @Test
    void aClassThatCannotBeInstrumentedFailsLoudlyInsteadOfLoadingUnmodified() throws Exception {
        BytecodeTransformer broken = (name, bytes) -> {
            throw new IllegalArgumentException("Unsupported class file major version 99");
        };
        try (RobotClassLoader loader = loader(fixtureClasspath(), broken)) {
            var failure = assertThrows(ClassNotFoundException.class, () -> loader.loadClass(NanoTimeProbe.class.getName()));
            assertEquals(true, failure.getMessage().contains("could not be instrumented"), failure.getMessage());
        }
    }

    @Test
    void resourcesAreReadFromTheRobotClasspath() throws Exception {
        try (RobotClassLoader loader = loader(fixtureClasspath(), new HookRewriter())) {
            try (InputStream stream = loader.getResourceAsStream("dev/ftcsim/runtime/fixture/NanoTimeProbe.class")) {
                assertNotNull(stream);
            }
            assertNotNull(loader.getResource("dev/ftcsim/runtime/fixture/NanoTimeProbe.class"));
        }
    }

    @Test
    void jarsWorkAsClassSourcesAndReportTheirCodeSource(@TempDir Path directory) throws Exception {
        Path jar = directory.resolve("robot.jar");
        String entry = FIXTURE_PACKAGE.replace('.', '/') + "NanoTimeProbe.class";
        try (InputStream original = RobotClassLoaderTest.class.getClassLoader().getResourceAsStream(entry);
                JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry(entry));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            original.transferTo(bytes);
            out.write(bytes.toByteArray());
            out.closeEntry();
        }

        try (RobotClassLoader loader = loader(List.of(jar), new HookRewriter())) {
            assertEquals(987_654_321L, (long) run(loader, "NanoTimeProbe"));
            Class<?> type = loader.loadClass(NanoTimeProbe.class.getName());
            assertEquals(jar.toUri().toURL(), type.getProtectionDomain().getCodeSource().getLocation());
            assertFalse(type.getPackage() == null);
        }
    }
}
