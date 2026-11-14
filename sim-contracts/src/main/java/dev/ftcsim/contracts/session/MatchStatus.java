package dev.ftcsim.contracts.session;

import java.util.Objects;

/**
 * How far a timed match has got, in simulated time.
 *
 * @param state               the period the match is in
 * @param phaseElapsedNanos   simulated time since that period began; only the timed periods ({@link MatchState#AUTONOMOUS},
 *                            {@link MatchState#TRANSITION}, {@link MatchState#TELEOP}) report it, the others report 0
 * @param phaseDurationNanos  how long that period lasts; 0 for the periods that have no length
 */
public record MatchStatus(MatchState state, long phaseElapsedNanos, long phaseDurationNanos) {

    /** No match: what free practice reports. */
    public static final MatchStatus NONE = new MatchStatus(MatchState.NONE, 0, 0);

    public MatchStatus {
        Objects.requireNonNull(state, "state");
        if (phaseElapsedNanos < 0 || phaseDurationNanos < 0) {
            throw new IllegalArgumentException("a match period cannot have negative times: " + phaseElapsedNanos + ", " + phaseDurationNanos);
        }
    }
}
