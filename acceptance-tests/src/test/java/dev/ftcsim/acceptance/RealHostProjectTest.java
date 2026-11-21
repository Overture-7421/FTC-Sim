package dev.ftcsim.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.ftcsim.app.AppFolders;
import dev.ftcsim.app.RealHost;
import dev.ftcsim.contracts.field.Alliance;
import dev.ftcsim.contracts.field.StartPose;
import dev.ftcsim.contracts.session.FieldChoice;
import dev.ftcsim.contracts.session.SessionRequest;
import dev.ftcsim.link.RemoteSimulatorLink;
import dev.ftcsim.ui.host.ProgramPreparer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * What a team does with the application, on a copy of the Hornet checkout: a change to a source file gives a new program on the
 * next build, only the newest programs are kept, and the programs
 * open on a fresh worker each time, which is what Restart code does (opening them again in one worker is measured, because
 * that is why: it keeps part of every old world). Needs {@code -Pftcsim.hornet=<checkout>}
 * (and, where discovery would not find them, {@code -Pftcsim.jdk} and {@code -Pftcsim.androidSdk}); runs nothing otherwise.
 */
@Tag("two-process")
@Timeout(3600)
class RealHostProjectTest {
    private static final String HOW_TO_RUN = "Skipped: give -Pftcsim.hornet=<path to the Hornet checkout> "
            + "(optionally -Pftcsim.jdk=<JDK 17 to 21 home> -Pftcsim.androidSdk=<Android SDK folder>).";

    private static final Duration BUILD = Duration.ofMinutes(40);
    private static final Set<String> NOT_COPIED = Set.of(".git", ".gradle", ".idea", ".kotlin", "build", ".cxx", ".vscode");
    private static final Pattern HEAP_USED = Pattern.compile("used (\\d+)K");
    private static final int RESTARTS = 8;

    @TempDir
    Path temp;

    @Test
    void everyRestartPicksUpWhatChangedOnAFreshWorker() throws Exception {
        String checkout = System.getProperty("ftcsim.hornet");
        if (checkout == null) {
            System.out.println("REAL-HOST-PROJECT> " + HOW_TO_RUN);
        }
        assumeTrue(checkout != null, HOW_TO_RUN);
        Path project = copy(Path.of(checkout), temp.resolve("Hornet"));
        RealHost host = WorkerFixtures.realHost(temp, WorkerFixtures.machine());
        try {
            ProgramPreparer preparer = host.preparer();
            PrepareEvents first = prepare(preparer, project);
            Path programOne = first.prepared.folder();
            assertNotEquals("Already prepared, source unchanged", first.lastStep());

            touchASource(project, "first");
            PrepareEvents second = prepare(preparer, project);
            Path programTwo = second.prepared.folder();
            assertNotEquals(programOne, programTwo, "a change to the code is a new program");
            assertTrue(Files.isDirectory(programOne) && Files.isDirectory(programTwo));

            touchASource(project, "second");
            PrepareEvents third = prepare(preparer, project);
            Path programThree = third.prepared.folder();
            assertNotEquals(programTwo, programThree);
            assertTrue(Files.isDirectory(programThree) && Files.isDirectory(programTwo), "the two newest programs are kept");
            assertFalse(Files.exists(programOne), "the oldest program is deleted, so that programs do not pile up");
            assertFalse(Files.exists(Path.of(programOne + ".build.log")), "and so is its build log");
            try (Stream<Path> kept = Files.list(new AppFolders(temp.resolve("FTC-Sim")).programs())) {
                assertEquals(2, kept.filter(Files::isDirectory).filter(path -> !path.getFileName().toString().startsWith(".")).count());
            }

            measureRepeatedOpensInOneWorker(host, List.of(programTwo, programThree));
            restartOnAFreshWorker(host, programTwo, programThree);
        } finally {
            host.close();
        }
    }

    /**
     * Opens the programs over and over on one link and prints what the worker holds after each. It asserts nothing about
     * the numbers: it is the measurement that is why the window restarts on a fresh worker (docs/architecture/app.md).
     */
    private void measureRepeatedOpensInOneWorker(RealHost host, List<Path> programs) throws Exception {
        RemoteSimulatorLink link = (RemoteSimulatorLink) host.openLink();
        long pid = link.workerPid().orElseThrow();
        LinkRecorder ui = new LinkRecorder(link, Duration.ofMinutes(5));
        try {
            List<Long> heap = new ArrayList<>();
            for (int open = 1; open <= RESTARTS; open++) {
                openSession(link, ui, open, programs.get(open % programs.size()));
                long used = heapUsedAfterCollection(pid);
                heap.add(used);
                System.out.println("REAL-HOST-PROJECT> one worker, after open " + open + ": heap in use " + used / 1024 + " MB, working set " + workingSetMegabytes(pid) + " MB");
            }
            assertEquals(List.of(), ui.failures);
            assertEquals(List.of(), ui.refusals);
            System.out.println("REAL-HOST-PROJECT> one worker: heap in use grew by " + (heap.get(heap.size() - 1) - heap.get(0)) / 1024 + " MB over " + RESTARTS + " opens");
        } finally {
            link.close();
            ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    /** What Restart code does: the session is asked for on a link made anew and the old worker ends. */
    private void restartOnAFreshWorker(RealHost host, Path first, Path second) throws Exception {
        long previous = -1;
        for (int restart = 1; restart <= 3; restart++) {
            RemoteSimulatorLink link = (RemoteSimulatorLink) host.openLink();
            long pid = link.workerPid().orElseThrow();
            assertNotEquals(previous, pid, "a restart is a new worker process");
            LinkRecorder ui = new LinkRecorder(link, Duration.ofMinutes(5));
            try {
                openSession(link, ui, 1, restart % 2 == 0 ? first : second);
                System.out.println("REAL-HOST-PROJECT> fresh worker " + restart + ": heap in use " + heapUsedAfterCollection(pid) / 1024 + " MB, working set "
                        + workingSetMegabytes(pid) + " MB");
                assertEquals(List.of(), ui.failures);
                assertEquals(List.of(), ui.refusals);
            } finally {
                link.close();
                if (restart < 3) {
                    assertEquals(OptionalInt.of(0), link.workerExitCode(), "the old worker exits cleanly when its link is closed");
                    assertFalse(WorkerFixtures.alive(pid), "no old worker is left");
                }
                ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
            }
            previous = pid;
        }
    }

    /** Asks for session number {@code number} of this link and waits until it is open. */
    private static void openSession(RemoteSimulatorLink link, LinkRecorder ui, int number, Path program) throws InterruptedException {
        ui.waitFor(() -> !ui.catalogues.isEmpty(), "the catalogue");
        FieldChoice field = ui.catalogues.get(0).fields().stream().filter(choice -> choice.id().equals("decode")).findFirst().orElseThrow();
        StartPose start = field.startPoses().get(0);
        link.open(new SessionRequest(
                program.toString(), field.id(), Alliance.BLUE, 1L, start.x(), start.y(), start.headingRadians(), 1.0));
        ui.waitFor(() -> ui.opened.size() >= number, "session " + number + " to open");
        assertFalse(ui.opened.get(number - 1).opModes().isEmpty(), "the program lists its OpModes");
    }

    private static PrepareEvents prepare(ProgramPreparer preparer, Path project) throws InterruptedException {
        PrepareEvents events = new PrepareEvents();
        preparer.prepare(new ProgramPreparer.Request(project, true), events);
        return events.await(BUILD);
    }

    /** Adds a comment to the first Java file of the project's code: a change the build must notice and cannot object to. */
    private static void touchASource(Path project, String note) throws IOException {
        try (Stream<Path> files = Files.walk(project)) {
            Path source = files
                    .filter(path -> path.toString().endsWith(".java") && path.toString().contains("TeamCode"))
                    .sorted()
                    .findFirst()
                    .orElseThrow();
            Files.writeString(source, "\n// " + note + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        }
    }

    private static Path copy(Path from, Path to) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) throws IOException {
                if (!dir.equals(from) && NOT_COPIED.contains(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.copy(file, to.resolve(from.relativize(file).toString()));
                return FileVisitResult.CONTINUE;
            }
        });
        return to;
    }

    /** What the Java runtime of the worker says it holds after it collected garbage, in kilobytes. */
    private static long heapUsedAfterCollection(long pid) throws IOException, InterruptedException {
        String jcmd = Path.of(System.getProperty("java.home"), "bin", "jcmd.exe").toString();
        run(List.of(jcmd, Long.toString(pid), "GC.run"));
        Matcher matcher = HEAP_USED.matcher(run(List.of(jcmd, Long.toString(pid), "GC.heap_info")));
        assertTrue(matcher.find(), "jcmd did not say how much of the heap is in use");
        return Long.parseLong(matcher.group(1));
    }

    private static long workingSetMegabytes(long pid) throws IOException, InterruptedException {
        String text = run(List.of("powershell", "-NoProfile", "-Command", "(Get-Process -Id " + pid + ").WorkingSet64")).trim();
        return Long.parseLong(text) / (1024 * 1024);
    }

    private static String run(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(2, TimeUnit.MINUTES), "did not end: " + command);
        assertEquals(0, process.exitValue(), command + " said: " + output);
        return output;
    }
}
