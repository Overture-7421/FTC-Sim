package dev.ftcsim.ui.host;

import java.nio.file.Path;
import java.util.List;

/**
 * Turns a team's project into a prepared program that the simulator can run, and says what is going on while it does.
 * The application around the window implements it, because that is the part that knows about JDKs, the Android SDK
 * and Gradle; the window only shows progress and asks for what it needs. Every method returns at once. Events reach
 * the {@link Listener} from a thread of the preparer's own, one at a time and in order, so a listener hands them on to
 * its own thread before touching anything.
 */
public interface ProgramPreparer {

    /** What a folder holds, found by looking at it and running nothing. Quick, but touches the disk: call it off the UI thread. */
    Inspection inspect(Path folder);

    /**
     * Starts preparing the project. Preparing runs the project's own Gradle build and so its build scripts on this
     * computer: the caller has to have told the user and have their agreement, which {@link Request#trusted()} says.
     */
    Job prepare(Request request, Listener listener);

    /** What a folder is. */
    enum Kind {
        /** A team's project: it has to be prepared before it can run. */
        PROJECT,
        /** A program that was prepared earlier: it can be used as it is. */
        PREPARED_PROGRAM,
        /** Neither. */
        NOT_RECOGNISED
    }

    /**
     * @param kind    what the folder holds
     * @param name    a short name for it, such as the folder's name or the program's
     * @param summary one sentence for people: for a prepared program its SDK version and the number of OpModes, for a
     *                project what is about to happen to it, for anything else why it is not one
     */
    record Inspection(Kind kind, String name, String summary) {}

    /**
     * @param projectFolder the team's project, the folder with {@code settings.gradle}; it is only read
     * @param trusted       whether the user agreed that the project's build scripts run on this computer
     */
    record Request(Path projectFolder, boolean trusted) {}

    /** A preparation that is under way. */
    interface Job {
        /** Asks it to stop at the next safe point; it ends with {@link Listener#failed} whose code is {@code CANCELLED}. */
        void cancel();
    }

    /**
     * One step of the preparation.
     *
     * @param number 1 for the first
     * @param of     how many there are in all
     * @param label  a short sentence for people, such as "Building the project with its own Gradle build"
     */
    record Step(int number, int of, String label) {}

    /** What a preparation reports. Exactly one of {@link #finished} and {@link #failed} comes last. */
    interface Listener {
        /** A new step starts. */
        void step(Step step);

        /** A line of detail: a file being copied, a line of the project's Gradle output. */
        void output(String line);

        void finished(Prepared program);

        void failed(Failure failure);
    }

    /**
     * A program that is ready to run.
     *
     * @param folder     the prepared program, which a session request names as its program
     * @param name       a short name for it
     * @param sdkVersion the FTC SDK version it was built against, such as {@code 11.1.0}
     * @param opModes    the names of the OpModes found in it
     * @param warnings   pointers to code that may not work in the simulator, one sentence each; not claims that it fails
     * @param log        the complete output of the project's build
     */
    record Prepared(Path folder, String name, String sdkVersion, List<String> opModes, List<String> warnings, Path log) {
        public Prepared {
            opModes = List.copyOf(opModes);
            warnings = List.copyOf(warnings);
        }
    }

    /**
     * A preparation that did not produce a program.
     *
     * @param code    what went wrong in one word, such as {@code NO_ANDROID_SDK}, {@code COMPILATION_FAILED} or
     *                {@code CANCELLED}; for choosing help, never for showing
     * @param message what happened and what to do about it, in words for a student or mentor
     * @param details the lines of the build's output that show why, possibly none
     * @param log     the complete output of the project's build, when it got that far; may be null
     */
    record Failure(String code, String message, List<String> details, Path log) {
        public Failure {
            details = List.copyOf(details);
        }
    }
}
