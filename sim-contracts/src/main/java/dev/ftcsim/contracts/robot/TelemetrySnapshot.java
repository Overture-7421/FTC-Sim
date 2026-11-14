package dev.ftcsim.contracts.robot;

import java.util.List;

/**
 * What the robot program has asked the driver station to display, plus the messages the SDK raises on its own.
 *
 * @param lines    the OpMode's telemetry as the driver station would list it, one entry per line
 * @param warnings global warning text, empty when there is none
 * @param error    the SDK's global error text; when there is none, the stack trace of user code that crashed, which the
 *                 driver station shows as a pop-up; empty when neither exists
 */
public record TelemetrySnapshot(List<String> lines, String warnings, String error) {

    public TelemetrySnapshot {
        lines = List.copyOf(lines);
    }
}
