package dev.ftcsim.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(120)
class RealHostTest {

    @TempDir
    Path root;

    private AppFolders folders() {
        return new AppFolders(root.resolve("FTC-Sim"));
    }

    @Test
    void theWorkerRunsOnTheVeryJavaThatRunsTheApplicationAndTheLocaleAndTimeZoneAreFixed() {
        Path java = RealHost.thisJava();

        assertEquals(Path.of(System.getProperty("java.home")).resolve("bin").resolve("java.exe"), java);
        assertTrue(Files.isRegularFile(java), java.toString());
        assertTrue(RealHost.WORKER_JVM_ARGUMENTS.containsAll(List.of("-Duser.language=en", "-Duser.country=US", "-Duser.timezone=UTC")));
    }

    @Test
    void theHostHasOnePreparerSoThatOnlyOneProjectIsPreparedAtATime() {
        try (RealHost host = new RealHost(folders(), new Machine(name -> null), () -> {
            throw new IOException("not needed here");
        }, RealHost.thisJava())) {
            assertSame(host.preparer(), host.preparer());
        }
    }

    @Test
    void whenTheJarsOfTheSimulatorCannotBeFoundTheMessageSaysSoAndHowToFixIt() {
        RealHost host = new RealHost(folders(), new Machine(name -> null), () -> {
            throw new IOException("ftcsim-classpath.properties is not next to app.jar");
        }, RealHost.thisJava());

        String message = assertThrows(IOException.class, host::openLink).getMessage();

        assertTrue(message.contains("cannot find the program files of its simulator") && message.contains("is not next to app.jar"), message);
        assertTrue(message.contains("Install FTC-Sim again"), message);
    }

    @Test
    void aJavaThatIsNotThereIsADamagedInstallationNotAMysteryOfTheWorker() throws IOException {
        Path jar = Files.writeString(root.resolve("a.jar"), "jar");
        Path missingJava = root.resolve("jre").resolve("bin").resolve("java.exe");
        RealHost host = new RealHost(folders(), new Machine(name -> null), () -> new AppClasspath(List.of(jar), List.of(jar)), missingJava);

        String message = assertThrows(IOException.class, host::openLink).getMessage();

        assertTrue(message.contains("The Java that comes with FTC-Sim is damaged") && message.contains("java.exe is missing"), message);
    }

    @Test
    void aWorkerThatEndsBeforeItConnectsIsReportedWithWhatItWroteAndWhereTheLogsAre() throws IOException {
        Path notAJar = Files.writeString(root.resolve("not-the-worker.jar"), "this is not a jar");
        AppFolders folders = folders();
        RealHost host = new RealHost(folders, new Machine(name -> null), new AppClasspath(List.of(notAJar), List.of(notAJar)))
                .connectTimeout(Duration.ofMinutes(1));

        String message = assertThrows(IOException.class, host::openLink).getMessage();

        assertTrue(message.startsWith("The simulator could not be started. The simulator worker ended before it connected"), message);
        assertTrue(message.contains("dev.ftcsim.worker.WorkerMain"), "what the worker wrote about it: " + message);
        assertTrue(message.contains("install FTC-Sim again") && message.contains(folders.logs().toString()), message);
        assertTrue(Files.isDirectory(folders.natives()), "the natives folder is made on demand");
        try (Stream<Path> logs = Files.list(folders.logs())) {
            assertFalse(logs.toList().isEmpty(), "the launch left its log");
        }
    }
}
