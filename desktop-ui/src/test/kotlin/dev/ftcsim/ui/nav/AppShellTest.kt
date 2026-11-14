package dev.ftcsim.ui.nav

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.ftcsim.ui.common.FtcSimTheme
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalTestApi::class)
class AppShellTest {
    private val navigator = Navigator()

    private fun ComposeUiTest.show() = setContent {
        FtcSimTheme(darkTheme = true) {
            AppShell(navigator) { destination ->
                Column {
                    Text("Screen: ${destination.label}")
                    Button(onClick = {}) { Text("Content button") }
                }
            }
        }
    }

    @Test
    fun theRailListsEveryDestinationAndMarksTheCurrentOne() = runComposeUiTest {
        show()

        for (destination in Destination.entries) {
            onNodeWithText(destination.label).assertExists()
        }
        onNodeWithText("Home").assertIsSelected()
        onNodeWithText("Screen: Home").assertExists()
    }

    @Test
    fun choosingADestinationShowsItAndMarksItSelected() = runComposeUiTest {
        show()

        onNodeWithText("Controls").performClick()
        waitForIdle()

        onNodeWithText("Screen: Controls").assertExists()
        onNodeWithText("Controls").assertIsSelected()
        assertEquals(Destination.CONTROLS, navigator.state.value.current)
    }

    @Test
    fun backIsDisabledAtFirstAndReturnsToThePreviousDestinationAfterwards() = runComposeUiTest {
        show()
        onNodeWithContentDescription("Back").assertIsNotEnabled()

        onNodeWithText("Setup").performClick()
        waitForIdle()
        onNodeWithContentDescription("Back").assertIsEnabled()
        onNodeWithContentDescription("Back").performClick()
        waitForIdle()

        onNodeWithText("Screen: Home").assertExists()
        onNodeWithContentDescription("Back").assertIsNotEnabled()
    }

    @Test
    fun controlNumberKeysGoToTheDestinationsInTheOrderOfTheRailWhereverTheFocusIs() = runComposeUiTest {
        show()
        val digits = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six)
        assertEquals(digits.size, Destination.entries.size, "a digit for every destination")

        for ((index, destination) in Destination.entries.withIndex().reversed()) {
            onNodeWithText("Content button").requestFocus()
            onNodeWithText("Content button").performKeyInput {
                keyDown(Key.CtrlLeft)
                pressKey(digits[index])
                keyUp(Key.CtrlLeft)
            }
            waitForIdle()

            assertEquals(destination, navigator.state.value.current)
            onNodeWithText("Screen: ${destination.label}").assertExists()
        }
    }

    @Test
    fun altLeftGoesBackFromTheKeyboard() = runComposeUiTest {
        show()
        navigator.go(Destination.SETTINGS)
        waitForIdle()
        onNodeWithText("Content button").requestFocus()

        onNodeWithText("Content button").performKeyInput {
            keyDown(Key.AltLeft)
            pressKey(Key.DirectionLeft)
            keyUp(Key.AltLeft)
        }
        waitForIdle()

        assertEquals(Destination.HOME, navigator.state.value.current)
    }

    @Test
    fun theRailItemsCanBeReachedAndPressedWithoutAMouse() = runComposeUiTest {
        show()
        onNodeWithText("Practice").requestFocus()

        onNodeWithText("Practice").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        assertEquals(Destination.PRACTICE, navigator.state.value.current)
    }

    @Test
    fun theBackButtonExplainsItselfInATooltipWhenThePointerRestsOnIt() = runComposeUiTest {
        show()
        navigator.go(Destination.SETUP)
        waitForIdle()

        onNodeWithContentDescription("Back").performMouseInput { moveTo(center) }
        mainClock.advanceTimeBy(2_000)
        waitForIdle()

        onNodeWithText("Back (Alt+Left)").assertExists()
    }
}
