package dev.ftcsim.ui.nav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NavigatorTest {
    private val navigator = Navigator()

    private fun now() = navigator.state.value

    @Test
    fun theApplicationStartsAtProjectsWithNowhereToGoBackTo() {
        assertEquals(Destination.PROJECTS, now().current)
        assertFalse(now().canGoBack)
        assertFalse(navigator.back())
    }

    @Test
    fun theRailListsProjectsControlsSimulatorAndSettingsInThatOrder() {
        assertEquals(
            listOf("Projects", "Controls", "Simulator", "Settings"),
            Destination.entries.map { it.label },
        )
    }

    @Test
    fun goingSomewhereRemembersWhereYouWereAndBackReturns() {
        navigator.go(Destination.SETTINGS)
        navigator.go(Destination.PRACTICE)

        assertEquals(Destination.PRACTICE, now().current)
        assertEquals(listOf(Destination.PROJECTS, Destination.SETTINGS), now().back)

        assertTrue(navigator.back())
        assertEquals(Destination.SETTINGS, now().current)
        assertTrue(navigator.back())
        assertEquals(Destination.PROJECTS, now().current)
        assertFalse(navigator.back())
    }

    @Test
    fun goingWhereYouAlreadyAreChangesNothing() {
        navigator.go(Destination.SETTINGS)
        val before = now()

        navigator.go(Destination.SETTINGS)

        assertEquals(before, now())
    }

    @Test
    fun aPlaceVisitedAgainMovesToTheFrontOfTheHistoryInsteadOfRepeating() {
        navigator.go(Destination.SETTINGS)
        navigator.go(Destination.PRACTICE)
        navigator.go(Destination.CONTROLS)
        navigator.go(Destination.PRACTICE)

        assertEquals(Destination.PRACTICE, now().current)
        assertEquals(listOf(Destination.PROJECTS, Destination.SETTINGS, Destination.CONTROLS), now().back)

        navigator.back()
        assertEquals(Destination.CONTROLS, now().current)
        assertEquals(listOf(Destination.PROJECTS, Destination.SETTINGS), now().back)
    }

    @Test
    fun backNeverLeadsToTheScreenYouAreOnAndTheHistoryCannotGrowWithoutEnd() {
        repeat(50) {
            navigator.go(Destination.SETTINGS)
            navigator.go(Destination.PROJECTS)
        }

        assertTrue(now().back.size < Destination.entries.size)
        assertFalse(now().current in now().back)
    }
}
