package dev.ftcsim.ui.practice

import dev.ftcsim.contracts.session.MatchState
import dev.ftcsim.contracts.session.MatchStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MatchDisplayTest {
    private fun display(state: MatchState, elapsedNanos: Long, durationNanos: Long) =
        MatchDisplay.of(MatchStatus(state, elapsedNanos, durationNanos))

    @Test
    fun withoutAMatchThereIsNothingToShow() {
        assertEquals(MatchDisplay.NONE, MatchDisplay.of(MatchStatus.NONE))
        assertFalse(MatchDisplay.NONE.timed)
        assertFalse(MatchDisplay.NONE.inProgress)
    }

    @Test
    fun anArmedMatchIsInProgressButHasNoCountdown() {
        val armed = MatchDisplay.of(MatchStatus(MatchState.ARMED, 0, 0))

        assertTrue(armed.inProgress)
        assertFalse(armed.timed)
        assertEquals(0L, armed.secondsLeft)
    }

    @Test
    fun aPeriodCountsDownInWholeSecondsRoundedUpSoThatZeroMeansOver() {
        assertEquals(30L, display(MatchState.AUTONOMOUS, 0L, 30_000_000_000L).secondsLeft)
        assertEquals(30L, display(MatchState.AUTONOMOUS, 1_000L, 30_000_000_000L).secondsLeft, "a moment into the period it is still 30")
        assertEquals(26L, display(MatchState.AUTONOMOUS, 4_200_000_000L, 30_000_000_000L).secondsLeft)
        assertEquals(1L, display(MatchState.TELEOP, 119_999_000_000L, 120_000_000_000L).secondsLeft)
        assertEquals(0L, display(MatchState.TELEOP, 120_000_000_000L, 120_000_000_000L).secondsLeft)
    }

    @Test
    fun aDurationThatIsNotWholeSecondsOrAnOverlongElapsedTimeDoesNotBreakTheCountdown() {
        assertEquals(0L, display(MatchState.TRANSITION, 9_000_000_000L, 8_000_000_000L).secondsLeft)
        assertEquals(8L, display(MatchState.TRANSITION, 0L, 7_999_999_999L).periodSeconds)
    }

    @Test
    fun theFractionOfThePeriodThatHasPassedGoesFromZeroToOne() {
        assertEquals(0f, display(MatchState.AUTONOMOUS, 0L, 30_000_000_000L).fraction, 1e-6f)
        assertEquals(0.5f, display(MatchState.TELEOP, 60_000_000_000L, 120_000_000_000L).fraction, 1e-6f)
        assertEquals(1f, display(MatchState.TELEOP, 120_000_000_000L, 120_000_000_000L).fraction, 1e-6f)
        assertEquals(0f, MatchDisplay(MatchState.ENDED).fraction)
    }

    @Test
    fun theThreePeriodsAreTimedAndTheOtherStatesAreNot() {
        for (state in MatchState.entries) {
            val timed = state == MatchState.AUTONOMOUS || state == MatchState.TRANSITION || state == MatchState.TELEOP
            assertEquals(timed, display(state, 0L, 10_000_000_000L).timed, "$state")
            assertEquals(timed || state == MatchState.ARMED, display(state, 0L, 10_000_000_000L).inProgress, "$state")
        }
    }

    @Test
    fun theMatchIsDescribedInWordsForTheStatusStrip() {
        assertNull(matchSummary(MatchDisplay.NONE))
        assertEquals("Match armed", matchSummary(MatchDisplay(MatchState.ARMED)))
        assertEquals("Match: Autonomous 0:24 left", matchSummary(MatchDisplay(MatchState.AUTONOMOUS, 24L, 30L)))
        assertEquals("Match: TeleOp 2:00 left", matchSummary(MatchDisplay(MatchState.TELEOP, 120L, 120L)))
        assertEquals("Match ended", matchSummary(MatchDisplay(MatchState.ENDED)))
    }

    @Test
    fun theOpModesOfAMatchAreNamedInOneLine() {
        assertEquals("", matchPlanText(null))
        assertEquals("Autonomous: Red auto, TeleOp: Drive", matchPlanText(MatchPlan("Red auto", "Drive")))
        assertEquals("No autonomous period, TeleOp: Drive", matchPlanText(MatchPlan(null, "Drive")))
        assertEquals("2:00", formatCountdown(120))
        assertEquals("0:07", formatCountdown(7))
    }
}
