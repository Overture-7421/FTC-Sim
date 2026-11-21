package dev.ftcsim.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.ftcsim.contracts.session.Catalogue;
import dev.ftcsim.contracts.session.FieldChoice;
import dev.ftcsim.contracts.session.SessionInfo;
import dev.ftcsim.contracts.session.SessionStatus;
import dev.ftcsim.contracts.session.SimulatorLink;
import dev.ftcsim.contracts.snapshot.WorldSnapshot;
import dev.ftcsim.link.RemoteSimulatorLink;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The real worker, started the way the application starts it: from the class path file that Gradle wrote, on the Java
 * that runs this test, with its logs and natives in the folders of a user. It runs no robot program, so it needs no
 * physics engine: it connects, says what can be asked for, and ends when the link closes.
 */
@Tag("two-process")
@Timeout(300)
class RealHostWorkerTest {

    @TempDir
    Path root;

    @Test
    void theApplicationStartsTheRealWorkerOnItsOwnJavaAndEndsItWhenTheLinkCloses() throws Exception {
        String property = System.getProperty(AppClasspath.FILE_PROPERTY);
        assumeTrue(property != null, "run through Gradle, which writes the class path file and names it in " + AppClasspath.FILE_PROPERTY);
        AppFolders folders = new AppFolders(root.resolve("FTC-Sim"));
        try (RealHost host = new RealHost(folders, Machine.real(), AppClasspath.read(Path.of(property)))) {
            host.connectTimeout(Duration.ofMinutes(3)).shutdownGrace(Duration.ofSeconds(60));

            SimulatorLink opened = host.openLink();
            RemoteSimulatorLink link = (RemoteSimulatorLink) opened;
            long pid = link.workerPid().orElseThrow();
            try {
                AtomicReference<Catalogue> catalogue = new AtomicReference<>();
                AtomicReference<String> failure = new AtomicReference<>();
                link.addListener(new SimulatorLink.Listener() {
                    @Override
                    public void opened(SessionInfo info) {}

                    @Override
                    public void snapshot(WorldSnapshot latest) {}

                    @Override
                    public void status(SessionStatus latest) {}

                    @Override
                    public void catalogue(Catalogue latest) {
                        catalogue.set(latest);
                    }

                    @Override
                    public void refused(String message) {}

                    @Override
                    public void failed(String message) {
                        failure.set(message);
                    }
                });
                long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
                while (catalogue.get() == null && System.nanoTime() < deadline) {
                    TimeUnit.MILLISECONDS.sleep(10);
                }

                assertTrue(catalogue.get() != null, "the worker told what can be asked for; it failed with " + failure.get());
                assertEquals(List.of("none", "decode", "biobuzz"), catalogue.get().fields().stream().map(FieldChoice::id).toList());
                        String command = ProcessHandle.of(pid).orElseThrow().info().command().orElse("");
                assertEquals(
                        RealHost.thisJava().toAbsolutePath().normalize().toString().toLowerCase(),
                        command.toLowerCase(),
                        "the worker runs on the Java of the application, not on another");
                assertTrue(Files.isDirectory(folders.natives()));

                link.close();

                assertEquals(OptionalInt.of(0), link.workerExitCode(), "the worker ended because it was asked to");
                assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "no worker process is left");
                assertTrue(Files.isRegularFile(link.workerLog().orElseThrow()));
                assertEquals(folders.logs(), link.workerLog().orElseThrow().getParent(), "the log is in the user's log folder");
            } finally {
                link.close();
                ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
            }
        }
    }
}
