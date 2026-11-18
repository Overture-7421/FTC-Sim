package dev.ftcsim.app;

import dev.ftcsim.contracts.session.SimulatorLink;
import dev.ftcsim.link.WorkerLauncher;
import dev.ftcsim.ui.host.AppHost;
import dev.ftcsim.ui.host.ProgramPreparer;
import dev.ftcsim.ui.host.ProjectMappings;
import dev.ftcsim.ui.host.UpdateService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * What the window runs on: a simulator in a worker process of its own, and the project importer. The window knows
 * neither; it holds the {@link SimulatorLink} and the {@link ProgramPreparer} that this hands it.
 *
 * <p>The worker runs on the very Java that runs this program (the runtime folder that comes with the distribution; a
 * worker on another Java would be a different simulator), from the jars that {@link AppClasspath} lists, with its natives
 * and its logs in the per-user folders of {@link AppFolders}.
 */
public final class RealHost implements AppHost, AutoCloseable {

    /**
     * Options for the worker's Java besides the ones the worker's launcher always sets. A robot program formats numbers and dates
     * with the default locale and time zone, so they are fixed: what a robot prints must not depend on the regional
     * settings of the computer. The worker has no window.
     */
    static final List<String> WORKER_JVM_ARGUMENTS =
            List.of("-Duser.language=en", "-Duser.country=US", "-Duser.timezone=UTC", "-Djava.awt.headless=true");

    private final AppFolders folders;
    private final Source<AppClasspath> classpath;
    private final Path java;
    private final ImporterPreparer preparer;
    private final ProjectMappings mappings;
    private Duration connectTimeout = Duration.ofSeconds(60);
    private Duration shutdownGrace = Duration.ofSeconds(3);
    private UpdateService updates = UpdateService.NONE;

    /** The host of the computer this program runs on, whose jars are where {@link AppClasspath#locate()} finds them. */
    public static RealHost forThisMachine() {
        Machine machine = Machine.real();
        return new RealHost(AppFolders.forThisUser(machine), machine, AppClasspath::locate, thisJava());
    }

    /** A host with its folders and jars given, for a program that does not start from an installation, such as a test. */
    public RealHost(AppFolders folders, Machine machine, AppClasspath classpath) {
        this(folders, machine, () -> classpath, thisJava());
    }

    RealHost(AppFolders folders, Machine machine, Source<AppClasspath> classpath, Path java) {
        this.folders = folders;
        this.classpath = classpath;
        this.java = java;
        this.preparer = ImporterPreparer.forThisMachine(folders, machine, classpath);
        this.mappings = new ProjectMappingsImpl(folders);
    }

    /** How long to wait for the worker to connect after it started; the default suits a user, a test may want longer. */
    public RealHost connectTimeout(Duration timeout) {
        this.connectTimeout = timeout;
        return this;
    }

    /** How long closing the link waits for the worker to end by itself before ending it. */
    public RealHost shutdownGrace(Duration grace) {
        this.shutdownGrace = grace;
        return this;
    }

    @Override
    public SimulatorLink openLink() throws IOException {
        AppClasspath jars;
        try {
            jars = classpath.get();
        } catch (IOException incomplete) {
            throw new IOException("FTC-Sim cannot find the program files of its simulator: " + incomplete.getMessage()
                    + ". Install FTC-Sim again.", incomplete);
        }
        if (!Files.isRegularFile(java)) {
            throw new IOException("The Java that comes with FTC-Sim is damaged: " + java + " is missing. Install FTC-Sim again.");
        }
        try {
            Files.createDirectories(folders.natives());
            return new WorkerLauncher(jars.worker(), folders.logs())
                    .javaExecutable(java)
                    .nativeDirectory(folders.natives())
                    .simulatorClasspath(jars.robot())
                    .jvmArguments(WORKER_JVM_ARGUMENTS)
                    .connectTimeout(connectTimeout)
                    .shutdownGrace(shutdownGrace)
                    .launch();
        } catch (IOException failure) {
            throw new IOException("The simulator could not be started. " + failure.getMessage()
                    + "\nIf this keeps happening, install FTC-Sim again and give the files in " + folders.logs() + " to whoever helps you.", failure);
        }
    }

    @Override
    public ProgramPreparer preparer() {
        return preparer;
    }

    @Override
    public ProjectMappings mappings() {
        return mappings;
    }

    /** How this host keeps the application up to date; none unless given one (a development build has no version to update from). */
    public RealHost updates(UpdateService service) {
        this.updates = service;
        return this;
    }

    @Override
    public UpdateService updates() {
        return updates;
    }

    /** Stops a preparation that is under way. The link is the window's to close. */
    @Override
    public void close() {
        preparer.close();
    }

    /** The Java this program runs on, which is the one the worker runs on. */
    static Path thisJava() {
        return Path.of(System.getProperty("java.home"), "bin", "java.exe");
    }
}
