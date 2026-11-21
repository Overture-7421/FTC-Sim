package dev.ftcsim.contracts.session;

import dev.ftcsim.contracts.field.Score;
import dev.ftcsim.contracts.hardware.UnmappedDevice;
import dev.ftcsim.contracts.robot.RobotPhase;
import dev.ftcsim.contracts.robot.TelemetrySnapshot;
import java.util.List;
import java.util.Objects;

/**
 * What a person watching a run wants to know, at one moment: how far it has got, what the Driver Station would
 * show, the score, what the hardware is doing, and anything that weakens the run. Immutable, so it can be handed to
 * any thread.
 *
 * @param simTimeNanos  simulated time since the session started
 * @param speed         simulated seconds per wall-clock second that the run is asked to keep
 * @param achievedSpeed what it actually kept over the last moments; lower than {@code speed} means the machine
 *                      cannot keep up and the run is going slower than real time
 * @param paused        whether simulated time is standing still
 * @param hardware      one line per motor, servo and device the robot program has used, in a stable order
 * @param match         where a timed match is and how far into its period; {@link MatchStatus#NONE} in free practice
 * @param unmapped      what the robot program asked the hardware map for under a name the robot mapping does not place, in
 *                      the order it asked; empty when the mapping places everything the program uses
 * @param dashboardUrl  the address a browser on this machine opens to see the robot program's dashboard, when the program has one that listens on the machine; empty when it has none
 */
public record SessionStatus(
        long simTimeNanos,
        RobotPhase phase,
        TelemetrySnapshot telemetry,
        Score score,
        List<String> problems,
        List<Readout> hardware,
        double speed,
        double achievedSpeed,
        boolean paused,
        MatchStatus match,
        List<UnmappedDevice> unmapped,
        String dashboardUrl) {

    public SessionStatus {
        problems = List.copyOf(problems);
        hardware = List.copyOf(hardware);
        unmapped = List.copyOf(unmapped);
        Objects.requireNonNull(match, "match");
        Objects.requireNonNull(dashboardUrl, "dashboardUrl");
    }

    /** A status of a run whose mapping places everything its program uses. */
    public SessionStatus(
            long simTimeNanos,
            RobotPhase phase,
            TelemetrySnapshot telemetry,
            Score score,
            List<String> problems,
            List<Readout> hardware,
            double speed,
            double achievedSpeed,
            boolean paused,
            MatchStatus match) {
        this(simTimeNanos, phase, telemetry, score, problems, hardware, speed, achievedSpeed, paused, match, List.of(), "");
    }

    /** A status of free practice, with no timed match. */
    public SessionStatus(
            long simTimeNanos,
            RobotPhase phase,
            TelemetrySnapshot telemetry,
            Score score,
            List<String> problems,
            List<Readout> hardware,
            double speed,
            double achievedSpeed,
            boolean paused) {
        this(simTimeNanos, phase, telemetry, score, problems, hardware, speed, achievedSpeed, paused, MatchStatus.NONE);
    }
}
