package dev.ftcsim.ui.practice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProblemsTest {
    @Test
    fun aFailedSessionAndAnErrorOfTheProgramAreErrorsAndEverythingElseIsAWarning() {
        val status = Fixtures.status(
            warnings = "Low battery",
            error = "java.lang.IllegalStateException: boom",
            problems = listOf("hub 2 refused a command"),
        )

        val problems = Problems.of(status, "the worker died")

        assertEquals(
            listOf(
                Problem(Severity.ERROR, "Session", "the worker died"),
                Problem(Severity.ERROR, "Robot program", "java.lang.IllegalStateException: boom"),
                Problem(Severity.WARNING, "Driver Station", "Low battery"),
                Problem(Severity.WARNING, "Simulator", "hub 2 refused a command"),
            ),
            problems,
        )
    }

    @Test
    fun aHealthySessionHasNoProblemsAndNoStatusYetIsFine() {
        assertTrue(Problems.of(Fixtures.status(), null).isEmpty())
        assertTrue(Problems.of(null, null).isEmpty())
        assertEquals(1, Problems.of(null, "no worker").size)
    }

    @Test
    fun severitiesSortWithErrorsFirstAndAreNamedInWords() {
        assertTrue(Severity.ERROR < Severity.WARNING)
        assertEquals("Error", Severity.ERROR.label)
        assertEquals("Warning", Severity.WARNING.label)
    }
}

class FormatTest {
    @Test
    fun simulatedTimeIsMinutesSecondsAndMilliseconds() {
        assertEquals("00:00.000", formatSimTime(0))
        assertEquals("00:12.345", formatSimTime(12_345_000_000L))
        assertEquals("02:05.000", formatSimTime(125_000_000_000L))
        assertEquals("00:00.004", formatSimTime(4_166_666L))
        assertEquals("61:01.500", formatSimTime(3_661_500_000_000L))
    }

    @Test
    fun speedsAreWrittenTheWayPeopleSayThem() {
        assertEquals("1x", formatSpeed(1.0))
        assertEquals("4x", formatSpeed(4.0))
        assertEquals("0.5x", formatSpeed(0.5))
        assertEquals("0.25x", formatSpeed(0.25))
        assertEquals("1.5x", formatSpeed(1.5))
        assertEquals("0.98x", formatAchieved(0.984))
    }

    @Test
    fun theSpeedMenuRunsFromAQuarterToFourTimes() {
        assertEquals(0.25, SPEEDS.first())
        assertEquals(4.0, SPEEDS.last())
        assertEquals(SPEEDS.sorted(), SPEEDS)
    }

    @Test
    fun theMachineKeepsUpWhenItIsWithinFivePercentOfTheRequestedSpeed() {
        assertTrue(Timing(0, 0.96).keepsUp(1.0))
        assertFalse(Timing(0, 0.9).keepsUp(1.0))
        assertTrue(Timing(0, 3.9).keepsUp(4.0))
    }
}

class PracticeUiTest {
    @Test
    fun everyMenuAndDialogIsAModalAndNothingElseIs() {
        val ui = PracticeUi()
        assertFalse(ui.modalOpen)

        ui.opModeMenuOpen = true
        assertTrue(ui.modalOpen)
        ui.opModeMenuOpen = false
        ui.moreMenuOpen = true
        assertTrue(ui.modalOpen)
        ui.moreMenuOpen = false
        ui.restartMenuOpen = true
        assertTrue(ui.modalOpen)
        ui.restartMenuOpen = false
        assertFalse(ui.modalOpen)

        ui.panel = Panel.PROBLEMS
        assertFalse(ui.modalOpen, "switching tabs does not take the keys")
    }
}
