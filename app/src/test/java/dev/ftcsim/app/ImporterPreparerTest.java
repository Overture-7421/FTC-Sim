package dev.ftcsim.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.importer.BuildTrust;
import dev.ftcsim.importer.ImportException;
import dev.ftcsim.importer.ImportProgress;
import dev.ftcsim.importer.ImportRequest;
import dev.ftcsim.importer.ImportStage;
import dev.ftcsim.importer.pack.PreparedProgram;
import dev.ftcsim.importer.scan.Finding;
import dev.ftcsim.importer.snapshot.SourceManifest;
import dev.ftcsim.ui.host.ProgramPreparer;
import dev.ftcsim.ui.host.ProgramPreparer.Job;
import dev.ftcsim.ui.host.ProgramPreparer.Prepared;
import dev.ftcsim.ui.host.ProgramPreparer.Request;
import dev.ftcsim.ui.host.ProgramPreparer.Step;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(120)
class ImporterPreparerTest {

    /** What the window hears, in order. */
    private static final class Recorder implements ProgramPreparer.Listener {
        final List<Step> steps = Collections.synchronizedList(new ArrayList<>());
        final List<String> output = Collections.synchronizedList(new ArrayList<>());
        final List<String> order = Collections.synchronizedList(new ArrayList<>());
        final Set<String> threads = ConcurrentHashMap.newKeySet();
        final CountDownLatch ended = new CountDownLatch(1);
        final AtomicInteger terminals = new AtomicInteger();
        volatile Prepared finished;
        volatile ProgramPreparer.Failure failure;

        @Override
        public void step(Step step) {
            threads.add(Thread.currentThread().getName());
            order.add("step");
            steps.add(step);
        }

        @Override
        public void output(String line) {
            threads.add(Thread.currentThread().getName());
            order.add("output");
            output.add(line);
        }

        @Override
        public void finished(Prepared program) {
            threads.add(Thread.currentThread().getName());
            order.add("finished");
            finished = program;
            terminals.incrementAndGet();
            ended.countDown();
        }

        @Override
        public void failed(ProgramPreparer.Failure latest) {
            threads.add(Thread.currentThread().getName());
            order.add("failed");
            failure = latest;
            terminals.incrementAndGet();
            ended.countDown();
        }

        Recorder await() throws InterruptedException {
            assertTrue(ended.await(60, TimeUnit.SECONDS), "the preparation did not end; heard " + order);
            return this;
        }

        List<String> labels() {
            return steps.stream().map(step -> step.number() + "/" + step.of() + " " + step.label()).toList();
        }
    }

    @TempDir
    Path root;

    private FakeMachine pc;
    private AppFolders folders;
    private Path project;
    private Path jdk;
    private Path sdk;
    private AppClasspath ownJars;
    private ImporterPreparer preparer;

    private final AtomicInteger builds = new AtomicInteger();
    private volatile ImportRequest lastRequest;
    private volatile ImporterPreparer.Importer build = FakeBuild::run;

    @BeforeEach
    void aComputerWithAJdkAnAndroidSdkAndAProject() throws IOException {
        pc = new FakeMachine(root.resolve("pc"));
        jdk = FakeMachine.jdk(pc.programFiles("Android", "Android Studio", "jbr"), "21.0.9", "x86_64", "JetBrains s.r.o.");
        sdk = Files.createDirectories(pc.localAppData("Android", "Sdk"));
        folders = new AppFolders(pc.localAppData("FTC-Sim"));
        project = FakeBuild.project(root.resolve("projects").resolve("RobotCode"));
        Path shim = Files.writeString(root.resolve("android-shim.jar"), "jar");
        Path adapters = Files.writeString(root.resolve("sdk-common.jar"), "jar");
        ownJars = new AppClasspath(List.of(shim), List.of(shim, adapters));
        preparer = new ImporterPreparer(folders, pc.machine(), JdkPatterns::bundled, () -> ownJars, (request, progress) -> {
            builds.incrementAndGet();
            lastRequest = request;
            return build.run(request, progress);
        });
    }

    @AfterEach
    void close() {
        preparer.close();
    }

    private Recorder prepare(boolean trusted) throws InterruptedException {
        Recorder events = new Recorder();
        preparer.prepare(new Request(project, trusted), events);
        return events.await();
    }

    private String sourceFingerprint() throws IOException {
        return SourceManifest.scan(project, ImportProgress.SILENT).fingerprint();
    }

    @Test
    void aProjectIsPreparedInStepsAndTheListenerHearsEverythingInOrderFromOneThreadAndOneEnd() throws Exception {
        Recorder events = prepare(true);

        assertNull(events.failure, String.valueOf(events.failure));
        List<String> expected = new ArrayList<>(List.of("1/7 " + ImporterPreparer.CHECKING));
        for (ImportStage stage : ImportStage.values()) {
            expected.add((2 + stage.ordinal()) + "/7 " + stage.label());
        }
        assertEquals(expected, events.labels());
        assertTrue(events.output.contains("fake GRADLE_EXPORT"), "the importer's output lines reach the window: " + events.output);
        assertTrue(events.output.stream().anyMatch(line -> line.contains(jdk.toString())), "the JDK it chose is said: " + events.output);
        assertTrue(events.output.stream().anyMatch(line -> line.contains(sdk.toString())), "and the Android SDK: " + events.output);
        assertEquals("finished", events.order.get(events.order.size() - 1));
        assertEquals(1, events.terminals.get());
        assertEquals(1, events.threads.size(), "one thread of the preparer's own: " + events.threads);
        assertFalse(events.threads.contains(Thread.currentThread().getName()));

        Prepared program = events.finished;
        assertEquals(folders.program("RobotCode", sourceFingerprint()), program.folder());
        assertTrue(program.folder().getFileName().toString().matches("RobotCode-[0-9a-f]{12}"), program.folder().toString());
        assertEquals("RobotCode", program.name());
        assertEquals("11.1.0", program.sdkVersion());
        assertEquals(List.of("Drive", "Park"), program.opModes());
        assertEquals(
                List.of("org.team.Drive.run(): Creates a java.lang.Thread.", "local is a link in the project folder and was not followed."),
                program.warnings());
        assertEquals(folders.buildLog(program.folder()), program.log());
        assertEquals("BUILD SUCCESSFUL (fake)\n", Files.readString(program.log()));
        assertEquals(2, PreparedProgram.open(program.folder()).opModes().size(), "the folder holds a complete, valid package");
        try (Stream<Path> programs = Files.list(folders.programs())) {
            assertEquals(
                    List.of(program.folder().getFileName().toString(), program.log().getFileName().toString()),
                    programs.map(path -> path.getFileName().toString()).sorted().toList(),
                    "the folder it was made in is gone");
        }
    }

    @Test
    void theImporterIsAskedForWhatTheApplicationFoundAndTheProjectIsOnlyRead() throws Exception {
        SourceManifest before = SourceManifest.scan(project, ImportProgress.SILENT);

        prepare(true);

        ImportRequest request = lastRequest;
        assertEquals(project, request.projectDirectory());
        assertEquals(BuildTrust.TRUST_BUILD_SCRIPTS, request.trust());
        assertEquals(jdk, request.jdkHome());
        assertEquals(sdk, request.androidSdk());
        assertEquals(ownJars.robot(), request.simulatorClasspath(), "the compatibility check is made against the simulator's own jars");
        assertEquals(folders.workspace(project), request.workspace());
        assertEquals(folders.programs(), request.packageDirectory().getParent());
        assertTrue(request.packageDirectory().getFileName().toString().startsWith(".making-"), request.packageDirectory().toString());
        assertTrue(before.compareWith(SourceManifest.scan(project, ImportProgress.SILENT)).isEmpty(), "the project is as it was");
    }

    @Test
    void whetherTheUserTrustsTheProjectIsPassedOnAsTheyGaveIt() throws Exception {
        prepare(false);

        assertEquals(BuildTrust.DO_NOT_TRUST, lastRequest.trust());
    }

    @Test
    void theSameSourceIsNotBuiltTwiceAndNeedsNeitherTrustNorAJdkNorAnAndroidSdk() throws Exception {
        Prepared first = prepare(true).finished;
        assertEquals(1, builds.get());
        Files.delete(jdk.resolve("release"));
        Files.delete(sdk);

        Recorder again = prepare(false);

        assertNull(again.failure, String.valueOf(again.failure));
        assertEquals(1, builds.get(), "the importer was not asked again");
        assertEquals(List.of("1/7 " + ImporterPreparer.CHECKING, "7/7 Already prepared, source unchanged"), again.labels());
        assertEquals(first.folder(), again.finished.folder());
        assertEquals(first.opModes(), again.finished.opModes());
        assertEquals(first.warnings(), again.finished.warnings());
        assertEquals(first.log(), again.finished.log());
        assertEquals("BUILD SUCCESSFUL (fake)\n", Files.readString(again.finished.log()));
    }

    @Test
    void aProgramPreparedBeforeBuildLogsWereKeptStillComesBackWithALogThatSaysSo() throws Exception {
        Prepared first = prepare(true).finished;
        Files.delete(first.log());

        Prepared again = prepare(true).finished;

        assertEquals(1, builds.get());
        assertTrue(Files.readString(again.log()).contains("log of its build was not kept"), Files.readString(again.log()));
    }

    @Test
    void changedSourceIsBuiltAgainIntoAnotherFolderAndTheOlderProgramIsLeftAlone() throws Exception {
        Prepared first = prepare(true).finished;
        FakeBuild.write(project.resolve("TeamCode").resolve("src").resolve("main").resolve("java").resolve("Drive.java"), "class Drive { int changed; }");

        Prepared second = prepare(true).finished;

        assertEquals(2, builds.get());
        assertNotEquals(first.folder(), second.folder());
        assertEquals(2, PreparedProgram.open(first.folder()).opModes().size(), "the earlier program is still there and whole");
        assertEquals(2, PreparedProgram.open(second.folder()).opModes().size());
    }

    private Prepared prepareVersion(int version) throws Exception {
        FakeBuild.write(project.resolve("TeamCode").resolve("src").resolve("main").resolve("java").resolve("Drive.java"), "class Drive { int v" + version + "; }");
        return prepare(true).finished;
    }

    @Test
    void onlyTheTwoNewestProgramsOfAProjectAreKeptAndAnUnlistedProgramIsNeverTouched() throws Exception {
        Path stranger = Files.createDirectories(folders.programs().resolve("RobotCode-000000000000"));
        Prepared first = prepareVersion(1);
        Prepared second = prepareVersion(2);
        assertTrue(Files.isDirectory(first.folder()) && Files.isDirectory(second.folder()));

        Prepared third = prepareVersion(3);

        assertFalse(Files.exists(first.folder()), "the oldest is removed");
        assertFalse(Files.exists(folders.buildLog(first.folder())), "and so is its build log");
        assertTrue(Files.isDirectory(second.folder()) && Files.isDirectory(third.folder()));
        assertTrue(Files.isDirectory(stranger), "a program that was not made by this list stays");
        try (var leftovers = Files.list(folders.programs())) {
            assertTrue(leftovers.noneMatch(path -> path.getFileName().toString().startsWith(".discarding-")), "nothing is left half removed");
        }
    }

    @Test
    void reusingAnOlderProgramMakesItTheNewestSoItIsNotTheOneThatGoes() throws Exception {
        Prepared first = prepareVersion(1);
        Prepared second = prepareVersion(2);
        FakeBuild.write(project.resolve("TeamCode").resolve("src").resolve("main").resolve("java").resolve("Drive.java"), "class Drive { int v1; }");
        assertEquals(first.folder(), prepare(true).finished.folder(), "the same source gives the first program again");

        prepareVersion(3);

        assertTrue(Files.isDirectory(first.folder()), "it was the most recently used");
        assertFalse(Files.exists(second.folder()));
    }

    @Test
    void aProgramThatASessionStillHasOpenStaysUntilTheNextTimeAndIsThenRemoved() throws Exception {
        Prepared first = prepareVersion(1);
        prepareVersion(2);
        Path jar = first.folder().resolve("program").resolve("TeamCode.jar");

        try (var open = Files.newInputStream(jar)) {
            prepareVersion(3);
            assertTrue(Files.isDirectory(first.folder()), "Windows would not let go of it, so it was left");
            assertTrue(open.read() >= -1);
        }
        prepareVersion(4);

        assertFalse(Files.exists(first.folder()), "removed once nothing holds it");
    }

    @Test
    void aDamagedProgramOfTheSameSourceIsReplacedOnlyOnceTheNewOneIsComplete() throws Exception {
        Prepared first = prepare(true).finished;
        Path damaged = first.folder().resolve("program").resolve("TeamCode.jar");
        Files.writeString(damaged, "damaged");
        build = (request, progress) -> {
            throw new ImportException(ImportException.Failure.COMPILATION_FAILED, "does not compile");
        };

        Recorder failing = prepare(true);

        assertEquals("COMPILATION_FAILED", failing.failure.code());
        assertEquals("damaged", Files.readString(damaged), "the old folder was not touched while there was nothing better");
        assertTrue(failing.output.stream().anyMatch(line -> line.contains("cannot be used")), failing.output.toString());

        build = FakeBuild::run;
        Prepared repaired = prepare(true).finished;

        assertEquals(first.folder(), repaired.folder());
        assertEquals(2, PreparedProgram.open(repaired.folder()).opModes().size(), "now whole again");
    }

    @Test
    void whatTheImporterSaysWentWrongReachesTheWindowAsItIsAndNothingOfThePackageIsLeft() throws Exception {
        Path log = root.resolve("gradle.log");
        build = (request, progress) -> {
            try {
                FakeBuild.write(request.packageDirectory().resolve("partial.txt"), "half a package");
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
            throw new ImportException(
                    ImportException.Failure.COMPILATION_FAILED, "Your code does not compile.", List.of("e: Drive.java:3", "e: Drive.java:9"), log);
        };

        Recorder events = prepare(true);

        assertNull(events.finished);
        assertEquals("COMPILATION_FAILED", events.failure.code());
        assertEquals("Your code does not compile.", events.failure.message());
        assertEquals(List.of("e: Drive.java:3", "e: Drive.java:9"), events.failure.details());
        assertEquals(log, events.failure.log());
        assertEquals("failed", events.order.get(events.order.size() - 1));
        try (Stream<Path> programs = Files.list(folders.programs())) {
            assertEquals(List.of(), programs.toList(), "the unfinished folder is removed");
        }
    }

    @Test
    void aProjectThatIsNotTrustedIsRefusedByTheImporterItselfBeforeAnythingIsCopied() throws Exception {
        try (ImporterPreparer real = ImporterPreparer.forThisMachine(folders, pc.machine(), () -> ownJars)) {
            Recorder events = new Recorder();
            real.prepare(new Request(project, false), events);
            events.await();

            assertEquals("NOT_TRUSTED", events.failure.code());
            assertTrue(events.failure.message().contains("not trusted"), events.failure.message());
            assertFalse(Files.exists(folders.workspace(project)), "nothing was copied");
        }
    }

    @Test
    void withoutAJdkOf17To21TheFailureSaysWhereToGetOneAndWhatWasFoundInstead() throws Exception {
        Files.delete(jdk.resolve("release"));
        FakeMachine.jdk(pc.programFiles("Java", "jdk-25"), "25.0.1");

        Recorder events = prepare(true);

        assertEquals("NO_JDK", events.failure.code());
        assertTrue(events.failure.message().contains("Install Android Studio"), events.failure.message());
        assertTrue(events.failure.message().contains("Temurin 21"), events.failure.message());
        assertTrue(
                events.failure.details().stream().anyMatch(line -> line.contains("jdk-25") && line.contains("Java 25")),
                events.failure.details().toString());
        assertEquals(0, builds.get());
    }

    @Test
    void aFolderThatIsNotAProjectIsRefusedBeforeAnythingIsReadOfIt() throws Exception {
        Recorder events = new Recorder();
        preparer.prepare(new Request(Files.createDirectories(root.resolve("Documents")), true), events);
        events.await();

        assertEquals("NOT_A_GRADLE_PROJECT", events.failure.code());
        assertTrue(events.failure.message().contains("No settings.gradle here"), events.failure.message());
        assertEquals(0, builds.get());
    }

    @Test
    void anUnexpectedErrorIsAFailureWithTheLinesThatSayWhereAndThePreparerWorksAfterwards() throws Exception {
        build = (request, progress) -> {
            throw new IllegalStateException("boom");
        };

        Recorder events = prepare(true);

        assertEquals("INTERNAL", events.failure.code());
        assertTrue(events.failure.message().contains("boom"), events.failure.message());
        assertTrue(
                events.failure.details().stream().anyMatch(line -> line.contains("IllegalStateException: boom")),
                events.failure.details().toString());

        build = FakeBuild::run;
        assertNotNull(prepare(true).finished, "the next preparation is not refused as busy");
    }

    @Test
    void cancellingStopsAtTheNextSafePointAndEndsWithCancelled() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        build = (request, progress) -> {
            started.countDown();
            while (!progress.isCancelled()) {
                LockSupport.parkNanos(1_000_000);
            }
            progress.checkCancelled();
            throw new AssertionError("checkCancelled throws once cancelled");
        };
        Recorder events = new Recorder();

        Job job = preparer.prepare(new Request(project, true), events);
        assertTrue(started.await(60, TimeUnit.SECONDS));
        job.cancel();
        events.await();

        assertEquals("CANCELLED", events.failure.code());
        assertEquals(1, events.terminals.get());
    }

    @Test
    void aSecondPreparationWhileOneRunsIsRefusedAtOnceAndTheFirstGoesOn() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        build = (request, progress) -> {
            started.countDown();
            try {
                assertTrue(release.await(60, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return FakeBuild.run(request, progress);
        };
        Recorder first = new Recorder();
        preparer.prepare(new Request(project, true), first);
        assertTrue(started.await(60, TimeUnit.SECONDS));

        Recorder second = new Recorder();
        preparer.prepare(new Request(project, true), second);
        second.await();

        assertEquals("BUSY", second.failure.code());
        assertEquals(1, first.ended.getCount(), "the first has not ended");
        release.countDown();
        first.await();
        assertNotNull(first.finished);
        assertEquals(first.threads, second.threads, "one thread tells every listener");
    }

    @Test
    void aFindingBecomesASentenceThatSaysWhereItWasSeen() {
        assertEquals("Creates a thread.", ImporterPreparer.sentence(Finding.general("THREAD_USE", "Creates a thread.")));
        assertEquals(
                "org.team.Drive: Ships natives.",
                ImporterPreparer.sentence(new Finding("NATIVE_LIBRARY_PACKAGED", "org.team.Drive", "", "Ships natives.")));
        assertEquals(
                "org.team.Drive.<init>(): Opens a socket.",
                ImporterPreparer.sentence(new Finding("NETWORK_USE", "org.team.Drive", "<init>()V", "Opens a socket.")));
    }
}
