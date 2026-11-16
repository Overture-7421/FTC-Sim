package dev.ftcsim.worker;

import dev.ftcsim.contracts.robot.GamepadState;
import dev.ftcsim.contracts.session.SessionInfo;
import dev.ftcsim.contracts.session.SessionStatus;
import dev.ftcsim.contracts.snapshot.WorldSnapshot;

/**
 * A simulation that is running, as the worker serves it: what to tell the UI about it once, what to do to it, and
 * what it shows. Every method may be called from any thread.
 */
interface RunningSession extends AutoCloseable {

    SessionInfo info();

    /**
     * Presses Init and returns once the simulation has done so.
     *
     * @throws IllegalStateException    with the reason, if the simulation refuses: a match is armed or under way
     * @throws IllegalArgumentException with the reason, if the robot program declares no such OpMode
     */
    void init(String opMode);

    /**
     * Presses Start and returns once the simulation has done so.
     *
     * @throws IllegalStateException with the reason, if the simulation refuses: no OpMode is initialised, or a match is
     *                               armed or under way
     */
    void start();

    /** Presses Stop. Nothing about it can be refused, so it does not wait for the simulation. */
    void stop();

    /**
     * A person at the field does one of the actions the field's description lists; returns once the simulation has
     * done it.
     *
     * @throws IllegalStateException    with the reason, if there is no field
     * @throws IllegalArgumentException with the reason, if the field does not know the action
     */
    void humanAction(String actionId);

    /**
     * Prepares a timed match and returns once the simulation has done so.
     *
     * @throws IllegalStateException    with the reason, if the simulation refuses: an OpMode is running, a match is armed or over
     * @throws IllegalArgumentException with the reason, if an OpMode does not exist
     */
    void armMatch(String autoOpMode, String teleOpMode);

    /**
     * Starts the armed match and returns once the simulation has done so.
     *
     * @throws IllegalStateException with the reason, if no match is armed
     */
    void startMatch();

    /**
     * Stops the robot program and puts the world back as it began, with the robot program still loaded; returns once the
     * simulation has done so.
     */
    void reset();

    void setGamepad(int gamepad, GamepadState state);

    void setPaused(boolean paused);

    void setSpeed(double speed);

    /** The latest status; null until the simulation has made one. */
    SessionStatus status();

    /** The world as of the latest physics step; null until there is one. */
    WorldSnapshot snapshot();

    /** What stopped the simulation, or null while it is going well. */
    Throwable failure();

    /** Stops the simulation and releases what it held. Waits for it, which can take as long as the robot program does. */
    @Override
    void close();
}
