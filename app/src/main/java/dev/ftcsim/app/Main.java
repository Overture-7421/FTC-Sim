package dev.ftcsim.app;

import dev.ftcsim.app.update.AppVersion;
import dev.ftcsim.app.update.GitHubUpdater;
import dev.ftcsim.app.update.Version;
import dev.ftcsim.ui.App;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/** FTC-Sim as a user runs it: the window on a worker process and the project importer. */
public final class Main {
    private static final int USAGE = 2;

    private Main() {}

    /**
     * Options are the window's: {@code --exit-after=<seconds>}, {@code --no-focus}, {@code --no-update-check} and
     * {@code --settings=<file>}; or
     * {@value SelfTest#OPTION} alone, which opens no window and checks that this installation works. Whatever the
     * program prints is also written to a log in the logs folder of the user, see {@link AppLog}.
     */
    public static void main(String[] args) {
        startLog();
        if (List.of(args).contains(SelfTest.OPTION)) {
            if (args.length != 1) {
                System.err.println(SelfTest.OPTION + " takes no other option");
                System.exit(USAGE);
            }
            System.exit(SelfTest.forThisMachine(System.out).run());
        }
        RealHost host = RealHost.forThisMachine();
        // A build made for a release keeps itself up to date; a development build has no version to compare and never looks.
        GitHubUpdater updater = null;
        if (AppVersion.isRelease()) {
            updater = GitHubUpdater.forThisInstallation(AppFolders.forThisUser(Machine.real()), Version.parse(AppVersion.current()));
            host.updates(updater);
        }
        try {
            App.run(host, args);
        } finally {
            host.close();
        }
        // The installer of a newer version, if the person chose one, runs only now that nothing of this installation is in use.
        if (updater != null) {
            updater.startPendingInstaller();
        }
        // Closing the window ends the program, whatever threads the libraries behind the window leave running.
        System.exit(0);
    }

    /** Sends standard output and standard error to the log as well; a log that cannot be written is said and the program goes on without it. */
    private static void startLog() {
        Path logs = AppFolders.forThisUser(Machine.real()).logs();
        try {
            AppLog log = AppLog.create(logs, LocalDateTime.now());
            System.setOut(log.tee(System.out));
            System.setErr(log.tee(System.err));
            System.out.println("FTC-Sim log: " + log.file());
        } catch (IOException | RuntimeException failure) {
            System.err.println("FTC-Sim cannot write its log in " + logs + " (" + failure + "); what it prints is shown here only.");
        }
    }
}
