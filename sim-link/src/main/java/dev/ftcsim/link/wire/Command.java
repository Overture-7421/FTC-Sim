package dev.ftcsim.link.wire;

import dev.ftcsim.contracts.robot.GamepadState;
import dev.ftcsim.contracts.session.SessionRequest;
import java.util.Objects;

/** What the UI side tells the worker; one record for every command of {@code SimulatorLink}, plus the way out. */
public sealed interface Command {

    /** Closes any session and opens the one requested. */
    record Open(SessionRequest request) implements Command {
        public Open {
            Objects.requireNonNull(request, "request");
        }
    }

    /** Presses Init with the named OpMode selected. */
    record Init(String opMode) implements Command {
        public Init {
            Objects.requireNonNull(opMode, "opMode");
        }
    }

    record Start() implements Command {}

    record Stop() implements Command {}

    /** What gamepad 1 or 2 reports from now on. The pad number is the worker's to judge: it refuses one that does not exist. */
    record SetGamepad(int gamepad, GamepadState state) implements Command {
        public SetGamepad {
            Objects.requireNonNull(state, "state");
        }
    }

    /** A person at the field does one of the actions the field's description lists. */
    record PerformHumanAction(String actionId) implements Command {
        public PerformHumanAction {
            Objects.requireNonNull(actionId, "actionId");
        }
    }

    record SetPaused(boolean paused) implements Command {}

    record SetSpeed(double speed) implements Command {
        public SetSpeed {
            if (!(speed > 0.0) || Double.isInfinite(speed)) {
                throw new IllegalArgumentException("speed must be positive and finite: " + speed);
            }
        }
    }

    /** Starts the same session again from its beginning. */
    record Reset() implements Command {}

    /** Prepares a timed match: initialises the autonomous OpMode, or the driver-controlled one when the name is empty. */
    record ArmMatch(String autoOpMode, String teleOpMode) implements Command {
        public ArmMatch {
            Objects.requireNonNull(autoOpMode, "autoOpMode");
            Objects.requireNonNull(teleOpMode, "teleOpMode");
        }
    }

    /** Starts the armed match. */
    record StartMatch() implements Command {}

    /** Ends the worker: it closes the session and exits. */
    record Close() implements Command {}
}
