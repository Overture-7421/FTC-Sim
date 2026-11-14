package dev.ftcsim.ui.nav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NavigatorTest {
    private val navigator = Navigator()

    private fun now() = navigator.state.value

    @Test
    fun theApplicationStartsAtHomeWithNowhereToGoBackTo() {
        assertEquals(Destination.HOME, now().current)
        assertFalse(now().canGoBack)
        assertFalse(navigator.back())
    }

    @Test
    fun theRailListsHomePrepareSetupControlsPracticeAndSettingsInThatOrder() {
        assertEquals(
            listOf("Home", "Prepare", "Setup", "Controls", "Practice", "Settings"),
            Destination.entries.map { it.label },
        )
    }

    @Test
    fun goingSomewhereRemembersWhereYouWereAndBackReturns() {
        navigator.go(Destination.SETUP)
        navigator.go(Destination.PRACTICE)

        assertEquals(Destination.PRACTICE, now().current)
        assertEquals(listOf(Destination.HOME, Destination.SETUP), now().back)

        assertTrue(navigator.back())
        assertEquals(Destination.SETUP, now().current)
        assertTrue(navigator.back())
        assertEquals(Destination.HOME, now().current)
        assertFalse(navigator.back())
    }

    @Test
    fun goingWhereYouAlreadyAreChangesNothing() {
        navigator.go(Destination.SETUP)
        val before = now()

        navigator.go(Destination.SETUP)

        assertEquals(before, now())
    }

    @Test
    fun aPlaceVisitedAgainMovesToTheFrontOfTheHistoryInsteadOfRepeating() {
        navigator.go(Destination.SETUP)
        navigator.go(Destination.PRACTICE)
        navigator.go(Destination.CONTROLS)
        navigator.go(Destination.PRACTICE)

        assertEquals(Destination.PRACTICE, now().current)
        assertEquals(listOf(Destination.HOME, Destination.SETUP, Destination.CONTROLS), now().back)

        navigator.back()
        assertEquals(Destination.CONTROLS, now().current)
        assertEquals(listOf(Destination.HOME, Destination.SETUP), now().back)
    }

    @Test
    fun backNeverLeadsToTheScreenYouAreOnAndTheHistoryCannotGrowWithoutEnd() {
        repeat(50) {
            navigator.go(Destination.SETUP)
            navigator.go(Destination.HOME)
        }

        assertTrue(now().back.size < Destination.entries.size)
        assertFalse(now().current in now().back)
    }
}
