package dev.ftcsim.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static dev.ftcsim.link.WorkerLauncher.requireRepresentable;

import dev.ftcsim.contracts.session.SessionInfo;
import dev.ftcsim.contracts.session.SessionRequest;
import dev.ftcsim.link.wire.Handshake;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** The launcher with real processes: stand-ins for the worker that behave well or badly. */
@Timeout(120)
class WorkerLauncherTest {

    @TempDir
    Path temp;

    private WorkerLauncher launcher(String mode) {
        return new WorkerLauncher(classpath(), temp.resolve("log directory with spaces"))
                .mainClass(FakeWorker.class.getName(), List.of(mode))
                .nativeDirectory(temp.resolve("natives"))
                .connectTimeout(Duration.ofSeconds(60))
                .shutdownGrace(Duration.ofMillis(700));
    }

    private static List<Path> classpath() {
        return Stream.of(FakeWorker.class, Handshake.class, SessionRequest.class).map(WorkerLauncherTest::locationOf).distinct().toList();
    }

    private static Path locationOf(Class<?> type) {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private Path onlyLog() throws IOException {
        try (Stream<Path> files = Files.list(temp.resolve("log directory with spaces"))) {
            List<Path> logs = files.filter(file -> file.getFileName().toString().matches("worker-.*(?<!\\.crash)\\.log")).toList();
            assertEquals(1, logs.size(), logs.toString());
            return logs.get(0);
        }
    }

    /** What the stand-in printed on its line that starts with {@code key}, after the pumps have caught up with it. */
    private static String logged(Path log, String key) throws IOException, InterruptedException {
        Pattern line = Pattern.compile("\\[stdout] " + Pattern.quote(key) + " (.*)");
        for (int attempt = 0; attempt < 200; attempt++) {
            Matcher matcher = line.matcher(Files.readString(log));
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the log has no '" + key + "' line: " + Files.readString(log));
    }

    @Test
    void startingAWorkerKeepsOnlyTheLogsOfTheNewestLaunchesIncludingItsOwn() throws Exception {
        Path logs = temp.resolve("log directory with spaces");
        Files.createDirectories(logs);
        for (int i = 1; i <= 14; i++) {
            Path old = Files.writeString(logs.resolve("worker-old" + i + ".log"), "log " + i);
            Files.setLastModifiedTime(old, FileTime.fromMillis(1_000L * i));
        }
        Files.writeString(logs.resolve("notes.txt"), "not a worker log");

        RemoteSimulatorLink link = launcher("serve").launch();
        try {
            List<String> kept;
            try (Stream<Path> files = Files.list(logs)) {
                kept = files.map(file -> file.getFileName().toString()).sorted().toList();
            }

            List<String> expected = new ArrayList<>(List.of("notes.txt", link.workerLog().orElseThrow().getFileName().toString()));
            for (int i = 6; i <= 14; i++) {
                expected.add("worker-old" + i + ".log");
            }
            assertEquals(WorkerLauncher.LOGS_KEPT, expected.size() - 1);
            assertEquals(expected.stream().sorted().toList(), kept);
        } finally {
            link.close();
        }
    }

    @Test
    void aWorkerThatCrashesBeforeItConnectsFailsTheLaunchWithItsErrorOutputAndLeavesNothingBehind() throws Exception {
        IOException failure = assertThrows(IOException.class, () -> launcher("crash").launch());

        String message = failure.getMessage();
        assertTrue(message.contains("ended before it connected"), message);
        assertTrue(message.contains("exited with code 3"), message);
        assertTrue(message.contains("fatal: cannot start the simulation") && message.contains("caused by: nothing works"), message);
        Path log = onlyLog();
        assertTrue(message.contains(log.toString()), message);
        assertTrue(Files.readString(log).contains("[stderr] caused by: nothing works"));
        try (Stream<Path> files = Files.list(log.getParent())) {
            assertEquals(List.of(log.getFileName().toString()), files.map(file -> file.getFileName().toString()).toList(), "no argument file is left");
        }
    }

    @Test
    void aWorkerThatNeverConnectsIsStoppedWhenTheTimeoutEnds() throws Exception {
        IOException failure = assertThrows(IOException.class, () -> launcher("silent").connectTimeout(Duration.ofSeconds(2)).launch());

        assertTrue(failure.getMessage().contains("did not connect within 2 seconds"), failure.getMessage());
        assertTrue(failure.getMessage().contains("has been stopped"), failure.getMessage());
        assertFalse(alive(Long.parseLong(logged(onlyLog(), "pid"))));
    }

    @Test
    void aWorkerOfAnotherProtocolVersionFailsTheLaunchAndIsStopped() throws Exception {
        IOException failure = assertThrows(IOException.class, () -> launcher("old-version").launch());

        assertTrue(failure.getMessage().contains("protocol version 99"), failure.getMessage());
        assertFalse(alive(Long.parseLong(logged(onlyLog(), "pid"))));
    }

    @Test
    void aWorkerThatServesIsReachedAndEndsWithItsExitCodeZeroAndNoStorageLeftWhenTheLinkIsClosed() throws Exception {
        RemoteSimulatorLink link = launcher("serve").launch();
        RecordingListener events = new RecordingListener();
        link.addListener(events);
        long pid = link.workerPid().orElseThrow();
        try {
            link.open(Messages.request("hornet-2026.json"));
            link.init("Mecanum Drive");

            assertEquals("hornet-2026.json", events.next(SessionInfo.class).robot().name());
            assertEquals("init Mecanum Drive", events.next(String.class));
            Path storage = Path.of(logged(link.workerLog().orElseThrow(), "storage"));
            assertTrue(Files.isDirectory(storage), "the worker was given a storage directory of its own");
            assertEquals(temp.resolve("natives").toString(), logged(link.workerLog().orElseThrow(), "natives"));

            link.close();

            assertEquals(OptionalInt.of(0), link.workerExitCode(), "it ended because it was asked to, not because it was killed");
            assertFalse(alive(pid));
            assertFalse(Files.exists(storage), "the launch cleans up what it made");
            assertTrue(Files.readString(link.workerLog().orElseThrow()).contains("[stdout] closing"));
        } finally {
            link.close();
        }
    }

    @Test
    void closingEndsAWorkerThatIgnoresTheRequestToEndWhenTheGraceIsOver() throws Exception {
        RemoteSimulatorLink link = launcher("ignore").launch();
        long pid = link.workerPid().orElseThrow();
        try {
            long started = System.nanoTime();
            link.close();
            long took = (System.nanoTime() - started) / 1_000_000;

            assertTrue(took >= 600, "it waited for the worker for the grace period: " + took + " ms");
            assertTrue(took < 20_000, "and then ended it: " + took + " ms");
            assertFalse(alive(pid));
            assertNotEquals(OptionalInt.of(0), link.workerExitCode());
            assertTrue(link.workerExitCode().isPresent());
        } finally {
            link.close();
        }
    }

    @Test
    void aWorkerThatDiesWhileItServesFailsTheLinkOnceWithHowItDiedAndWhatItLastWrote() throws Exception {
        RemoteSimulatorLink link = launcher("explode").launch();
        RecordingListener events = new RecordingListener();
        link.addListener(events);
        long pid = link.workerPid().orElseThrow();
        try {
            link.start();

            String message = events.next(String.class);

            assertTrue(message.contains("exited with code 9"), message);
            assertTrue(message.contains("kaboom: the physics engine crashed"), message);
            assertTrue(message.contains(link.workerLog().orElseThrow().toString()), message);
            assertFalse(alive(pid));
        } finally {
            link.close();
        }
        assertTrue(events.received.isEmpty(), "failed is reported once: " + events.received);
    }

    @Test
    void pathsWithCharactersBeyondAsciiReachTheWorkerIntact() throws Exception {
        Path natives = temp.resolve("natives of Zoë and Ñandú");
        List<Path> classpath = new ArrayList<>(classpath());
        classpath.add(temp.resolve("entry of Zoë"));
        String joined = classpath.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));
        RemoteSimulatorLink link = new WorkerLauncher(classpath, temp.resolve("logs of Zoë"))
                .mainClass(FakeWorker.class.getName(), List.of("serve"))
                .nativeDirectory(natives)
                .launch();
        try {
            assertEquals(natives.toString(), logged(link.workerLog().orElseThrow(), "natives"));
            assertEquals(Integer.toString(joined.length()), logged(link.workerLog().orElseThrow(), "classpath length"));
        } finally {
            link.close();
        }
    }

    @Test
    void aPathThatTheCodePageCannotWriteIsRefusedBeforeTheWorkerIsStartedWithTheLetterThatIsTheProblem() throws IOException {
        Charset codePage = Charset.forName("windows-1252");

        requireRepresentable(codePage, "C:\\Users\\Zoë\\Ñandú");
        IOException refused = assertThrows(IOException.class, () -> requireRepresentable(codePage, "C:\\Users\\日本\\FTC-Sim"));

        assertTrue(refused.getMessage().contains("'日'") && refused.getMessage().contains("U+65E5"), refused.getMessage());
    }

    @Test
    void aClassPathLongerThanAWindowsCommandLineReachesTheWorkerIntact() throws Exception {
        List<Path> classpath = new ArrayList<>(classpath());
        for (int i = 0; i < 400; i++) {
            classpath.add(temp.resolve("an entry that has a long name and a space " + "x".repeat(150) + i));
        }
        String joined = classpath.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));
        assertTrue(joined.length() > 60_000, "longer than a command line can be: " + joined.length());
        RemoteSimulatorLink link = new WorkerLauncher(classpath, temp.resolve("log directory with spaces"))
                .mainClass(FakeWorker.class.getName(), List.of("serve"))
                .launch();
        try {
            assertEquals(Integer.toString(joined.length()), logged(link.workerLog().orElseThrow(), "classpath length"));
        } finally {
            link.close();
        }
    }
}
