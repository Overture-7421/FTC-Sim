package dev.ftcsim.simulator;

import dev.ftcsim.contracts.field.MatchPhase;
import dev.ftcsim.contracts.robot.RobotPhase;
import dev.ftcsim.contracts.session.MatchState;
import dev.ftcsim.contracts.session.MatchStatus;
import dev.ftcsim.core.kernel.SimulationKernel;
import dev.ftcsim.field.FieldRun;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.LongConsumer;

/**
 * A timed match, played the way the Driver Station and the field control system play it, in simulated time only:
 * the autonomous OpMode runs for {@link #AUTONOMOUS_NANOS} and is stopped, the driver-controlled OpMode is
 * initialised during the {@link #TRANSITION_NANOS} that follow, then started, and stopped {@link #TELEOP_NANOS}
 * after that. Every boundary is a callback scheduled for its exact simulated instant, so a match is the same
 * however the caller slices time.
 *
 * <p>The field is told when AUTONOMOUS, TELEOP and ENDED begin; it has no phase for the pause between the periods,
 * which its rules count as autonomous. The match goes on whatever the robot program does: a press that the robot
 * controller refuses becomes a problem in the status, as a dead robot does not stop a real match.
 *
 * <p>Not thread-safe: the simulator thread only, like the session that owns it.
 */
final class TimedMatch {

    static final long AUTONOMOUS_NANOS = 30_000_000_000L;
    static final long TRANSITION_NANOS = 8_000_000_000L;
    static final long TELEOP_NANOS = 120_000_000_000L;

    private final SimulationKernel kernel;
    private final DriverStation station;
    private final FieldRun field;
    private final List<String> problems = new ArrayList<>();

    private MatchState state = MatchState.NONE;
    private boolean hasAutonomous;
    private String teleOpMode = "";
    private long periodStart;
    private long periodLength;
    private long generation;

    /** @param field the field being played on, or null on a bare floor, where the match has nothing to tell */
    TimedMatch(SimulationKernel kernel, DriverStation station, FieldRun field) {
        this.kernel = kernel;
        this.station = station;
        this.field = field;
    }

    /**
     * Initialises the first OpMode of the match and waits for {@link #start}.
     *
     * @param autonomousOpMode the autonomous OpMode, or an empty name for a match with no autonomous period
     * @throws IllegalStateException if a match is armed, under way or over, or an OpMode is initialised or running
     * @throws IllegalArgumentException if an OpMode does not exist
     */
    void arm(String autonomousOpMode, String teleOpMode) {
        Objects.requireNonNull(autonomousOpMode, "autonomousOpMode (an empty name means no autonomous period)");
        Objects.requireNonNull(teleOpMode, "teleOpMode");
        switch (state) {
            case NONE -> { }
            case ARMED -> throw refused("A match is already armed. Start it, or press Stop to give it up.");
            case ENDED -> throw refused("The match has been played. Reset the session to play another.");
            case AUTONOMOUS, TRANSITION, TELEOP -> throw refused("A match is in progress. Press Stop to end it first.");
        }
        RobotPhase robot = station.phase();
        if (robot != RobotPhase.IDLE) {
            throw refused("An OpMode is " + (robot == RobotPhase.INIT ? "initialised" : "running") + ". Press Stop before arming a match.");
        }
        boolean autonomous = !autonomousOpMode.isEmpty();
        if (autonomous) {
            requireOpMode(autonomousOpMode);
        }
        if (teleOpMode.isEmpty()) {
            throw new IllegalArgumentException("A match needs a driver-controlled OpMode.");
        }
        requireOpMode(teleOpMode);

        String first = autonomous ? autonomousOpMode : teleOpMode;
        station.init(first);
        if (station.phase() != RobotPhase.INIT) {
            throw refused("The OpMode '" + first + "' did not initialise; its error is in the telemetry.");
        }
        hasAutonomous = autonomous;
        this.teleOpMode = teleOpMode;
        state = MatchState.ARMED;
    }

    /**
     * Starts the armed match now. The period lengths are measured from this instant, not from when the OpMode was
     * initialised.
     *
     * @throws IllegalStateException if no match is armed
     */
    void start() {
        switch (state) {
            case ARMED -> { }
            case NONE -> throw refused("No match is armed. Arm one first.");
            case ENDED -> throw refused("The match has been played. Reset the session to play another.");
            case AUTONOMOUS, TRANSITION, TELEOP -> throw refused("The match is already under way.");
        }
        long at = kernel.clock().now();
        if (hasAutonomous) {
            enter(MatchState.AUTONOMOUS, at, AUTONOMOUS_NANOS);
            press("start the autonomous OpMode", station::start);
            tell(MatchPhase.AUTONOMOUS, at);
            scheduleAt(at + AUTONOMOUS_NANOS, this::autonomousEnded);
        } else {
            beginTeleOp(at);
        }
    }

    /**
     * Stop was pressed: a match that is armed is given up, and one under way ends here and now. The robot itself is
     * stopped by the caller.
     */
    void stopPressed() {
        switch (state) {
            case ARMED -> {
                generation++;
                state = MatchState.NONE;
            }
            case AUTONOMOUS, TRANSITION, TELEOP -> {
                generation++;
                finish(kernel.clock().now());
            }
            case NONE, ENDED -> { }
        }
    }

    /** Init and Start belong to the match while there is one armed or under way. */
    void requireNoMatchUnderway(String press) {
        switch (state) {
            case ARMED -> throw refused(press + " is not available while a match is armed. Start the match, or press Stop to give it up.");
            case AUTONOMOUS, TRANSITION, TELEOP -> throw refused(press + " is not available during a match. Press Stop to end the match first.");
            case NONE, ENDED -> { }
        }
    }

    MatchStatus status() {
        return switch (state) {
            case NONE -> MatchStatus.NONE;
            case ARMED, ENDED -> new MatchStatus(state, 0, 0);
            case AUTONOMOUS, TRANSITION, TELEOP ->
                    new MatchStatus(state, Math.clamp(kernel.clock().now() - periodStart, 0L, periodLength), periodLength);
        };
    }

    /** What went wrong with the robot while the match was being run, oldest first. */
    List<String> problems() {
        return List.copyOf(problems);
    }

    private void autonomousEnded(long at) {
        press("stop the autonomous OpMode", station::stop);
        press("initialise the driver-controlled OpMode '" + teleOpMode + "'", () -> station.init(teleOpMode));
        enter(MatchState.TRANSITION, at, TRANSITION_NANOS);
        scheduleAt(at + TRANSITION_NANOS, this::beginTeleOp);
    }

    private void beginTeleOp(long at) {
        enter(MatchState.TELEOP, at, TELEOP_NANOS);
        press("start the driver-controlled OpMode '" + teleOpMode + "'", station::start);
        tell(MatchPhase.TELEOP, at);
        scheduleAt(at + TELEOP_NANOS, this::teleOpEnded);
    }

    /** The field is told first: its match is over at {@code at}, whatever the robot program takes to stop. */
    private void teleOpEnded(long at) {
        finish(at);
        press("stop the driver-controlled OpMode", station::stop);
    }

    private void finish(long at) {
        state = MatchState.ENDED;
        tell(MatchPhase.ENDED, at);
    }

    private void enter(MatchState next, long at, long length) {
        state = next;
        periodStart = at;
        periodLength = length;
    }

    private void tell(MatchPhase phase, long at) {
        if (field != null) {
            field.enterPhase(phase, at);
        }
    }

    private void press(String what, Runnable button) {
        try {
            button.run();
        } catch (RuntimeException refusal) {
            problems.add("the match could not " + what + ": " + refusal.getMessage());
        }
    }

    /** Runs {@code boundary} with its own instant, unless the match was stopped in the meantime. */
    private void scheduleAt(long instant, LongConsumer boundary) {
        long expected = generation;
        kernel.schedule(instant, () -> {
            if (generation == expected) {
                boundary.accept(instant);
            }
        });
    }

    private void requireOpMode(String name) {
        if (station.opModes().stream().noneMatch(opMode -> opMode.name().equals(name))) {
            throw new IllegalArgumentException("The robot program declares no OpMode named '" + name + "'.");
        }
    }

    private static IllegalStateException refused(String why) {
        return new IllegalStateException(why);
    }
}
