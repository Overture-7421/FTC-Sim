package dev.ftcsim.ui.host;

import dev.ftcsim.contracts.session.SimulatorLink;
import java.io.IOException;

/**
 * What the application around the window provides to it: a way to a simulator and a way to prepare programs. The
 * window knows nothing about worker processes, JDKs or Gradle; the application's {@code main} builds one of these,
 * and the window's own {@code main} builds one on a fake link for developing the interface.
 */
public interface AppHost {

    /**
     * Opens the link the window uses to reach a simulator. Called off the UI thread when the window opens, and called
     * again, for a link that is made anew, when the person asks to try again after the link could not be opened or after
     * the session failed: a link that failed is closed by the window and never asked again. The window closes the link it
     * holds when it closes. It may start a process, so it may take a while and it may fail, and the message of the
     * exception is shown to the user as it is.
     */
    SimulatorLink openLink() throws IOException;

    /** Prepares team projects. */
    ProgramPreparer preparer();

    /**
     * Whether a run needs a prepared program, which the Setup screen then asks for. A real simulator always does; the
     * stand-in that serves the window's own development runs no robot program and says no.
     */
    default boolean programRequired() {
        return true;
    }
}
