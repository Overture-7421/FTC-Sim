package dev.ftcsim.link;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;

/** The process behind a link, as the link needs it: to wait for it, to stop it and to say what became of it. */
interface WorkerHandle {

    long pid();

    boolean isAlive();

    /** How the process ended, or empty while it runs. */
    OptionalInt exitCode();

    /** Waits for the process to end; true if it has. */
    boolean awaitExit(Duration timeout) throws InterruptedException;

    /** Ends exactly this process now. */
    void destroyForcibly();

    /**
     * What a person should read when the worker is gone: {@code what happened}, how the process ended, and the last
     * lines it wrote to its error output. A process that is still running is stopped first. May wait a moment for
     * the output to be complete.
     */
    String describeEnd(String whatHappened);

    Optional<Path> logFile();

    /** Closes the log and removes what the launch left in temporary places. Safe to call again. */
    void release();
}
