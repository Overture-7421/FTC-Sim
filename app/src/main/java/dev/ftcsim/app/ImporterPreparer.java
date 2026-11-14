package dev.ftcsim.app;

import dev.ftcsim.contracts.robot.OpModeDescriptor;
import dev.ftcsim.importer.BuildTrust;
import dev.ftcsim.importer.ImportException;
import dev.ftcsim.importer.ImportProgress;
import dev.ftcsim.importer.ImportRequest;
import dev.ftcsim.importer.ImportResult;
import dev.ftcsim.importer.ImportStage;
import dev.ftcsim.importer.ProjectImporter;
import dev.ftcsim.importer.io.Trees;
import dev.ftcsim.importer.manifest.PackageManifest;
import dev.ftcsim.importer.pack.PackageException;
import dev.ftcsim.importer.pack.PreparedProgram;
import dev.ftcsim.importer.scan.Finding;
import dev.ftcsim.importer.snapshot.SourceManifest;
import dev.ftcsim.ui.host.ProgramPreparer;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The window's preparer, over the project importer. It finds what the importer asks its caller to find (the JDK that
 * runs the project's own build, the Android SDK, folders to work in), says what the importer is doing in the window's
 * terms, and keeps what was prepared so that the same source is not built twice.
 *
 * <p>A preparation is steps: 1, looking at the project and this computer; then the importer's stages. A project whose
 * source (every source and build file, by hash) is the same as that of a program prepared earlier is not built again:
 * it is answered at once with the step {@value #UNCHANGED}, and no trust, JDK or Android SDK is needed for that. Otherwise
 * the program is made in a folder of its own and moves to its place only when it is complete, replacing a damaged
 * leftover of the same name; the programs of other sources are not touched. One project is prepared at a time.
 */
final class ImporterPreparer implements ProgramPreparer, AutoCloseable {

    /** What is asked of the importer, so that a test can stand in for a build of a real project. */
    @FunctionalInterface
    interface Importer {
        ImportResult run(ImportRequest request, ImportProgress progress);
    }

    /** The importer's stages come after the step of looking at the project and the computer. */
    static final int STEPS = ImportStage.values().length + 1;

    static final String CHECKING = "Looking at the project and this computer";
    static final String UNCHANGED = "Already prepared, source unchanged";

    private static final String NO_JDK = "Preparing a project needs a JDK, version " + JdkDiscovery.OLDEST + " to " + JdkDiscovery.NEWEST
            + ", to run the project's own build, and none was found on this computer. Android Studio includes a suitable JDK, "
            + "so installing it is enough; otherwise install Temurin 21 (the Windows x64 JDK from adoptium.net) and prepare again. "
            + "This is not the Java that FTC-Sim itself runs on, which comes with FTC-Sim.";

    private final AppFolders folders;
    private final Machine machine;
    private final Source<List<String>> jdkPatterns;
    private final Source<AppClasspath> classpath;
    private final Importer importer;
    private final ProgramInspector inspector = new ProgramInspector();
    private final ExecutorService events = Executors.newSingleThreadExecutor(daemon("ftcsim-preparation-events"));
    private final AtomicReference<Run> running = new AtomicReference<>();

    ImporterPreparer(AppFolders folders, Machine machine, Source<List<String>> jdkPatterns, Source<AppClasspath> classpath, Importer importer) {
        this.folders = folders;
        this.machine = machine;
        this.jdkPatterns = jdkPatterns;
        this.classpath = classpath;
        this.importer = importer;
    }

    /** The preparer of this computer, which builds with the real importer. */
    static ImporterPreparer forThisMachine(AppFolders folders, Machine machine, Source<AppClasspath> classpath) {
        return new ImporterPreparer(folders, machine, JdkPatterns::bundled, classpath, ProjectImporter::importProject);
    }

    @Override
    public Inspection inspect(Path folder) {
        return inspector.inspect(folder);
    }

    @Override
    public Job prepare(Request request, Listener listener) {
        Delivery delivery = new Delivery(listener, events);
        Run run = new Run(request, delivery);
        Thread thread = new Thread(run, "ftcsim-prepare");
        thread.setDaemon(true);
        run.thread = thread;
        if (!running.compareAndSet(null, run)) {
            delivery.failed(new Failure(
                    "BUSY", "Another project is being prepared. Wait for it to finish, or cancel it, and try again.", List.of(), null));
            return () -> {};
        }
        thread.start();
        return run::cancel;
    }

    /** Cancels a preparation that is under way and gives it a moment to stop the build it started. */
    @Override
    public void close() {
        Run run = running.get();
        if (run != null) {
            run.cancel();
            try {
                run.thread.join(Duration.ofSeconds(10));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        events.shutdown();
    }

    private static ThreadFactory daemon(String name) {
        return task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    /** A preparation that ends the way the application says, not the way the importer would. */
    private static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final transient Failure failure;

        Refused(String code, String message, List<String> details) {
            super(message);
            this.failure = new Failure(code, message, details, null);
        }

        Refused(String code, String message) {
            this(code, message, List.of());
        }
    }

    private final class Run implements Runnable {
        private final Request request;
        private final Delivery delivery;
        private volatile boolean cancelled;
        private volatile Thread thread;

        private final ImportProgress progress = new ImportProgress() {
            @Override
            public void stage(ImportStage stage) {
                delivery.step(new Step(2 + stage.ordinal(), STEPS, stage.label()));
            }

            @Override
            public void message(String text) {
                delivery.output(text);
            }

            @Override
            public boolean isCancelled() {
                return cancelled;
            }
        };

        Run(Request request, Delivery delivery) {
            this.request = request;
            this.delivery = delivery;
        }

        void cancel() {
            cancelled = true;
        }

        @Override
        public void run() {
            Prepared prepared = null;
            Failure failure = null;
            try {
                prepared = prepare();
            } catch (ImportException refused) {
                failure = new Failure(refused.failure().name(), refused.getMessage(), refused.diagnostics(), refused.logFile().orElse(null));
            } catch (Refused refused) {
                failure = refused.failure;
            } catch (IOException | UncheckedIOException unreadable) {
                failure = new Failure(
                        "IO_ERROR", "A file could not be read or written while the project was prepared: " + unreadable, List.of(), null);
            } catch (Throwable unexpected) {
                failure = new Failure(
                        "INTERNAL",
                        "FTC-Sim failed in a way it did not expect while it prepared the project: " + unexpected
                                + ". The lines below say where.",
                        stackTrace(unexpected),
                        null);
            }
            // Free to prepare again before the listener hears, so that it can start the next one from its callback.
            running.set(null);
            if (prepared != null) {
                delivery.finished(prepared);
            } else {
                delivery.failed(failure);
            }
        }

        private Prepared prepare() throws IOException {
            delivery.step(new Step(1, STEPS, CHECKING));
            Path project = request.projectFolder().toAbsolutePath().normalize();
            Inspection inspection = inspector.inspect(project);
            if (inspection.kind() != Kind.PROJECT) {
                throw new Refused("NOT_A_GRADLE_PROJECT", inspection.summary());
            }
            SourceManifest sources = SourceManifest.scan(project, progress);
            String fingerprint = sources.fingerprint();
            delivery.output("The project has " + sources.fileCount() + " source and build files; their fingerprint starts " + fingerprint.substring(0, 12) + ".");
            Prepared earlier = earlier(folders.program(inspection.name(), fingerprint), fingerprint);
            if (earlier != null) {
                delivery.step(new Step(STEPS, STEPS, UNCHANGED));
                return earlier;
            }

            progress.checkCancelled();
            JdkDiscovery.Jdk jdk = jdk();
            delivery.output("The project's build runs on " + jdk.describe() + ".");
            Path sdk = new AndroidSdkDiscovery(machine).find();
            delivery.output("The Android SDK is " + sdk + ".");
            AppClasspath ownJars = ownJars();
            progress.checkCancelled();

            Path programs = folders.programs();
            Files.createDirectories(programs);
            Path fresh = programs.resolve(".making-" + HexFormat.of().formatHex(randomBytes()));
            ImportRequest importing = ImportRequest.of(
                            project,
                            folders.workspace(project),
                            fresh,
                            jdk.home(),
                            sdk,
                            request.trusted() ? BuildTrust.TRUST_BUILD_SCRIPTS : BuildTrust.DO_NOT_TRUST)
                    .withSimulatorClasspath(ownJars.robot());
            try {
                return publish(importer.run(importing, progress), fresh);
            } finally {
                try {
                    Trees.delete(fresh);
                } catch (IOException | RuntimeException stuck) {
                    delivery.output("The unfinished folder " + fresh + " could not be removed: " + stuck);
                }
            }
        }

        /** What was prepared from this source earlier, if it is still whole. */
        private Prepared earlier(Path folder, String fingerprint) {
            if (!Files.isDirectory(folder)) {
                return null;
            }
            try {
                PreparedProgram program = PreparedProgram.open(folder);
                if (!program.manifest().project().sourceFingerprint().equals(fingerprint)) {
                    return null;
                }
                return describe(program.manifest(), folder, logOf(folder));
            } catch (PackageException damaged) {
                delivery.output("The program prepared earlier in " + folder + " cannot be used (" + ProgramInspector.firstProblem(damaged)
                        + "), so it is made again.");
                return null;
            }
        }

        /** The log of the build that made a program, which a program prepared before logs were kept does not have. */
        private Path logOf(Path folder) {
            Path log = folders.buildLog(folder);
            if (!Files.isRegularFile(log)) {
                try {
                    Files.writeString(log, "This program was prepared earlier and the log of its build was not kept. Delete " + folder
                            + " and prepare the project again to get one.\n");
                } catch (IOException unwritable) {
                    // The log is only a pointer; the program is fine.
                }
            }
            return log;
        }

        private JdkDiscovery.Jdk jdk() {
            List<String> patterns;
            try {
                patterns = jdkPatterns.get();
            } catch (IOException incomplete) {
                throw new Refused("INCOMPLETE_INSTALL", "FTC-Sim is incomplete: " + incomplete.getMessage() + ". Install FTC-Sim again.");
            }
            JdkDiscovery.Search search = new JdkDiscovery(machine, patterns).search();
            return search.best().orElseThrow(() -> new Refused("NO_JDK", NO_JDK, search.rejected()));
        }

        private AppClasspath ownJars() {
            try {
                return classpath.get();
            } catch (IOException incomplete) {
                throw new Refused(
                        "INCOMPLETE_INSTALL",
                        "FTC-Sim cannot find its own program files, which the check of the project's code needs: " + incomplete.getMessage()
                                + ". Install FTC-Sim again.");
            }
        }

        /** Moves the finished program to the place its source gives it, replacing a leftover of the same name, and keeps its build log beside it. */
        private Prepared publish(ImportResult result, Path fresh) throws IOException {
            PackageManifest manifest = result.program().manifest();
            Path folder = folders.program(manifest.project().name(), manifest.project().sourceFingerprint());
            try {
                Trees.delete(folder);
            } catch (IOException inUse) {
                throw new Refused(
                        "IO_ERROR",
                        "The program prepared earlier in " + folder + " could not be replaced, probably because a session still uses it. "
                                + "Close the session and prepare again. (" + inUse + ")");
            }
            Files.move(fresh, folder, StandardCopyOption.ATOMIC_MOVE);
            Path log = folders.buildLog(folder);
            try {
                Files.copy(result.gradleLog(), log, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException | RuntimeException uncopied) {
                delivery.output("The build log could not be copied next to the program (" + uncopied + "); it stays in " + result.gradleLog() + ".");
                log = result.gradleLog();
            }
            return describe(manifest, folder, log);
        }
    }

    private static Prepared describe(PackageManifest manifest, Path folder, Path log) {
        return new Prepared(
                folder,
                manifest.project().name(),
                manifest.sdk().version(),
                manifest.opModes().list().stream().map(OpModeDescriptor::name).toList(),
                manifest.warnings().stream().map(ImporterPreparer::sentence).toList(),
                log);
    }

    /** A finding as a sentence that says where: the class and method it was seen in, then what it is. */
    static String sentence(Finding finding) {
        if (finding.subject().isEmpty()) {
            return finding.detail();
        }
        String member = finding.member();
        int parenthesis = member.indexOf('(');
        String method = member.isEmpty() ? "" : "." + (parenthesis < 0 ? member : member.substring(0, parenthesis)) + "()";
        return finding.subject() + method + ": " + finding.detail();
    }

    private static List<String> stackTrace(Throwable failure) {
        StringWriter text = new StringWriter();
        failure.printStackTrace(new PrintWriter(text));
        return text.toString().lines().limit(12).toList();
    }

    private static byte[] randomBytes() {
        byte[] bytes = new byte[4];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }
}
