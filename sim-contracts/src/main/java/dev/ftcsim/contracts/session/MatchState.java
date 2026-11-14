package dev.ftcsim.contracts.session;

/**
 * Where a timed match is. Free practice never leaves {@link #NONE}. The simulator times the periods in simulated
 * time, so pausing or slowing a run slows the match with it; {@link MatchStatus} says how long the current one is.
 */
public enum MatchState {
    /** No match: free practice, or a match that was armed and then given up. */
    NONE,
    /** The OpModes are chosen and the first one is initialised; the match waits for its start. */
    ARMED,
    /** The autonomous period. */
    AUTONOMOUS,
    /** The pause between the periods, in which the driver-controlled OpMode is initialised. */
    TRANSITION,
    /** The driver-controlled period. */
    TELEOP,
    /** The match is over, or was stopped; the score still settles by the field's own rules. */
    ENDED
}
