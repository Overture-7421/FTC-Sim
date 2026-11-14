package dev.ftcsim.contracts.session;

import dev.ftcsim.contracts.robot.GamepadState;
import dev.ftcsim.contracts.snapshot.WorldSnapshot;

/**
 * The port between whoever shows a simulation and the simulator that runs it. The simulator may be in this process
 * or in a worker process of its own; nothing crossing this interface can tell. Calls never block on the
 * simulation: commands are queued and applied on the simulator's thread, and what comes back arrives on listeners
 * from a thread of the link's own, so a listener must hand the data to its own thread before drawing anything.
 */
public interface SimulatorLink extends AutoCloseable {

    /** What a link tells its listeners. Every method is called from the link's thread, never concurrently. */
    interface Listener {

        /** The session asked for with {@link #open} is running. */
        void opened(SessionInfo info);

        /** The world after a physics step; only the latest matters, so a slow listener loses nothing it needs. */
        void snapshot(WorldSnapshot snapshot);

        /** The latest status, a few tens of times per second. */
        void status(SessionStatus status);

        /**
         * The session cannot be shown or cannot go on: a session that was asked for could not be opened, or the
         * worker stopped. There is nothing more to see until the next {@link #open}, with a message a person can act on.
         */
        void failed(String message);

        /**
         * A command was refused and nothing else changed: the session, if there is one, goes on exactly as it was.
         * The message says why in words a person can act on, for instance that a match cannot be armed while an OpMode
         * runs. A listener that cannot tell the difference from {@link #failed} would wrongly end a session that is fine.
         */
        void refused(String message);

        /**
         * What can be asked for before a session is open: sent once right after the link is connected, and again
         * whenever it could change. A listener added later is told the latest one at once.
         */
        void catalogue(Catalogue catalogue);
    }

    void addListener(Listener listener);

    /** Closes any session that is open and opens the one requested; the result arrives on the listeners. */
    void open(SessionRequest request);

    /** Presses Init on the Driver Station with the OpMode selected. */
    void init(String opMode);

    void start();

    void stop();

    /**
     * Prepares a timed match from the idle state: initialises the autonomous OpMode and waits for {@link #startMatch}.
     * Free practice with {@link #init}, {@link #start} and {@link #stop} stays available when no match is armed. A
     * request the simulator refuses (an OpMode is running, a match was already armed or has been played, an OpMode
     * does not exist) comes back on the listeners as {@code refused}, and the session goes on as it was.
     *
     * @param autoOpMode   the autonomous OpMode, or an empty name for a match with no autonomous period
     * @param teleOpMode   the driver-controlled OpMode
     */
    void armMatch(String autoOpMode, String teleOpMode);

    /**
     * Starts the armed match: the periods are timed by the simulator in simulated time, and the field is told when
     * each begins. {@link #stop} during the match ends it at once. Refused through {@code refused} unless a match is
     * armed.
     */
    void startMatch();

    /** What a gamepad reports from now on; only the latest state matters. */
    void setGamepad(int gamepad, GamepadState state);

    /** A person at the field does one of the actions the field's description lists. */
    void humanAction(String actionId);

    void setPaused(boolean paused);

    /** Simulated seconds per wall-clock second. */
    void setSpeed(double speed);

    /**
     * Starts the same session again from its beginning: same program, profile, field and seed, at the speed set last.
     * The listeners are told with {@code opened}, as for {@link #open}.
     */
    void reset();

    /** Closes the session and releases whatever the link holds, including a worker process. */
    @Override
    void close();
}
