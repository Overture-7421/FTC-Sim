package dev.ftcsim.game.decode;

import dev.ftcsim.contracts.field.Score;
import java.util.List;

/**
 * The points of one alliance. The count of ARTIFACTS that were scored is kept as they happen; what the referee reads
 * at the end of AUTO and at the end of the match is latched when the rules say it is read, and until then the live
 * reading stands in for it.
 */
final class ScoreBoard {

    private static final int LEAVE = 3;
    private static final int CLASSIFIED = 3;
    private static final int OVERFLOW = 1;
    private static final int PATTERN = 2;
    private static final int DEPOT = 1;
    private static final int BASE_PARTIAL = 5;
    private static final int BASE_FULL = 10;

    private int classifiedAuto;
    private int overflowAuto;
    private int classifiedTeleop;
    private int overflowTeleop;
    private Boolean leaveLatched;
    private Integer autoPatternLatched;
    private Assessor.Assessment finalLatched;

    void record(boolean classified, boolean duringAuto) {
        if (duringAuto) {
            if (classified) {
                classifiedAuto++;
            } else {
                overflowAuto++;
            }
        } else if (classified) {
            classifiedTeleop++;
        } else {
            overflowTeleop++;
        }
    }

    boolean isLeaveLatched() {
        return leaveLatched != null;
    }

    void latchLeave(boolean left) {
        leaveLatched = left;
    }

    boolean isAutoPatternLatched() {
        return autoPatternLatched != null;
    }

    void latchAutoPattern(int matches) {
        autoPatternLatched = matches;
    }

    boolean isFinal() {
        return finalLatched != null;
    }

    void latchFinal(Assessor.Assessment assessment) {
        finalLatched = assessment;
    }

    /**
     * The score as it stands.
     *
     * @param live       what a referee would see on the field right now
     * @param clock      where the match is
     * @param autoIsOver whether the period AUTO has ended
     */
    Score score(Assessor.Assessment live, MatchClock clock, boolean autoIsOver) {
        boolean duringAuto = clock.autoStarted() && !autoIsOver;
        Assessor.Assessment end = finalLatched != null ? finalLatched : live;
        boolean teleop = clock.countsForTeleop();
        boolean left = leaveLatched != null ? leaveLatched : (duringAuto && live.leftLaunchLines());
        int autoPattern = autoPatternLatched != null ? autoPatternLatched : (duringAuto ? live.patternMatches() : 0);
        int teleopPattern = teleop ? end.patternMatches() : 0;
        int depot = teleop ? end.depotArtifacts() : 0;
        int base = teleop ? end.baseStatus() : 0;
        return new Score(List.of(
                new Score.Line("LEAVE", left ? 1 : 0, left ? LEAVE : 0),
                new Score.Line("AUTO CLASSIFIED", classifiedAuto, classifiedAuto * CLASSIFIED),
                new Score.Line("AUTO OVERFLOW", overflowAuto, overflowAuto * OVERFLOW),
                new Score.Line("AUTO PATTERN", autoPattern, autoPattern * PATTERN),
                new Score.Line("TELEOP CLASSIFIED", classifiedTeleop, classifiedTeleop * CLASSIFIED),
                new Score.Line("TELEOP OVERFLOW", overflowTeleop, overflowTeleop * OVERFLOW),
                new Score.Line("TELEOP PATTERN", teleopPattern, teleopPattern * PATTERN),
                new Score.Line("DEPOT", depot, depot * DEPOT),
                new Score.Line("BASE PARTIAL", base == 1 ? 1 : 0, base == 1 ? BASE_PARTIAL : 0),
                new Score.Line("BASE FULL", base == 2 ? 1 : 0, base == 2 ? BASE_FULL : 0)));
    }
}
