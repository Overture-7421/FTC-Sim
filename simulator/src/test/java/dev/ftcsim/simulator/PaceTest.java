package dev.ftcsim.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PaceTest {
    private static final long MS = 1_000_000L;

    @Test
    void simulatedTimeFollowsWallClockTimeAtTheChosenSpeedInBothDirections() {
        Pace pace = new Pace(2.0, 250 * MS);
        pace.anchor(1_000 * MS, 100 * MS);

        assertEquals(100 * MS, pace.simAt(1_000 * MS));
        assertEquals(300 * MS, pace.simAt(1_100 * MS));
        assertEquals(1_100 * MS, pace.wallAt(300 * MS));
        assertEquals(1_000 * MS, pace.wallAt(100 * MS));
    }

    @Test
    void changingTheSpeedKeepsTheCurrentInstantWhereItIsAndBendsOnlyTheFuture() {
        Pace pace = new Pace(1.0, 250 * MS);
        pace.anchor(0, 0);

        pace.setSpeed(0.5, 1_000 * MS, 1_000 * MS);

        assertEquals(1_000 * MS, pace.simAt(1_000 * MS));
        assertEquals(1_500 * MS, pace.simAt(2_000 * MS));
    }

    @Test
    void aSmallLagIsReportedAndKeptButALagBeyondTheLimitReanchorsTheRunToThePresent() {
        Pace pace = new Pace(1.0, 250 * MS);
        pace.anchor(0, 0);

        assertEquals(100 * MS, pace.lag(1_100 * MS, 1_000 * MS));
        assertEquals(1_100 * MS, pace.simAt(1_100 * MS));

        assertEquals(0, pace.lag(2_000 * MS, 1_000 * MS), "the machine fell 1 s behind, so the run slows down instead of racing");
        assertEquals(1_000 * MS, pace.simAt(2_000 * MS), "from now on simulated time is measured from where the run really is");
        assertEquals(-5 * MS, pace.lag(2_000 * MS, 1_005 * MS), "ahead of real time is a negative lag");
    }

    @Test
    void aSpeedOrLimitThatIsNotPositiveIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new Pace(0.0, MS));
        assertThrows(IllegalArgumentException.class, () -> new Pace(1.0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Pace(1.0, MS).setSpeed(-1.0, 0, 0));
    }
}
