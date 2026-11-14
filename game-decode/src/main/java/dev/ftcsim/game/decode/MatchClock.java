package dev.ftcsim.game.decode;

import dev.ftcsim.contracts.field.MatchPhase;

/**
 * Where the match is and when the parts of it began. AUTO lasts 30 s from its start; whatever happens before TELEOP
 * is called starts counts as AUTO, which includes the 8 s between the periods.
 */
final class MatchClock {

    static final long AUTO_NANOS = 30_000_000_000L;
    static final long SETTLE_LIMIT_NANOS = 5_000_000_000L;
    private static final long NONE = Long.MIN_VALUE;

    private MatchPhase phase = MatchPhase.PRACTICE;
    private long autoStart = NONE;
    private long teleopStart = NONE;
    private long ended = NONE;

    void enter(MatchPhase next, long timeNanos) {
        phase = next;
        switch (next) {
            case AUTONOMOUS -> autoStart = timeNanos;
            case TELEOP -> teleopStart = timeNanos;
            case ENDED -> ended = timeNanos;
            case PRACTICE -> { }
        }
    }

    MatchPhase phase() {
        return phase;
    }

    /** Artifacts that score now are AUTO artifacts. */
    boolean scoresAsAuto() {
        return phase == MatchPhase.AUTONOMOUS;
    }

    /** What is on the field now would be counted for TELEOP if the match ended. */
    boolean countsForTeleop() {
        return phase == MatchPhase.PRACTICE || phase == MatchPhase.TELEOP || phase == MatchPhase.ENDED;
    }

    boolean autoStarted() {
        return autoStart != NONE;
    }

    /** AUTO is over: its 30 s have passed, or TELEOP has begun early. */
    boolean autoIsOver(long nowNanos) {
        return autoStarted() && (nowNanos - autoStart >= AUTO_NANOS || teleopStart != NONE);
    }

    boolean teleopStarted() {
        return teleopStart != NONE;
    }

    boolean hasEnded() {
        return ended != NONE;
    }

    /** The match has been over for the whole of the time the field gives the pieces to settle. */
    boolean settleTimeIsUp(long nowNanos) {
        return hasEnded() && nowNanos - ended >= SETTLE_LIMIT_NANOS;
    }
}
