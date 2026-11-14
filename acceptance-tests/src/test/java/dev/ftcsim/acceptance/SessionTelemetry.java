package dev.ftcsim.acceptance;

import dev.ftcsim.simulator.SimulationSession;

/** What a robot program shows on the Driver Station: telemetry lines of the form {@code caption : value}. */
final class SessionTelemetry {

    private SessionTelemetry() {}

    /** The text after "caption : " in the latest telemetry. */
    static String text(SimulationSession session, String caption) {
        return session.telemetry().lines().stream()
                .filter(line -> line.startsWith(caption + " : "))
                .map(line -> line.substring(caption.length() + 3))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no telemetry line '" + caption + "' in " + session.telemetry().lines()));
    }

    /** The number after "caption : " in the latest telemetry. */
    static double number(SimulationSession session, String caption) {
        return Double.parseDouble(text(session, caption));
    }
}
