package dev.ftcsim.contracts.field;

/** Which part of a match the field is in; the points a piece is worth can depend on it. */
public enum MatchPhase {
    /** No match is being played; pieces score as in the match's main period and nothing ends. */
    PRACTICE,
    AUTONOMOUS,
    TELEOP,
    /** The match is over; what is on the field now is what gets counted. */
    ENDED
}
