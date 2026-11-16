package dev.ftcsim.ui.controls

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import dev.ftcsim.ui.common.runTallComposeUiTest as runComposeUiTest
import dev.ftcsim.contracts.robot.GamepadState
import dev.ftcsim.ui.common.FtcSimTheme
import dev.ftcsim.ui.input.GamepadRouter
import dev.ftcsim.ui.input.KeyboardMap
import dev.ftcsim.ui.input.PadAssignment
import dev.ftcsim.ui.input.PadControl
import dev.ftcsim.ui.input.PadReading
import dev.ftcsim.ui.input.ViewportInput
import dev.ftcsim.ui.render.OrbitCamera
import dev.ftcsim.ui.settings.SettingsStore
import java.awt.event.KeyEvent
import java.util.concurrent.Executor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The Controls screen with the real router and settings; every key and click is a synthetic Compose event. */
@OptIn(ExperimentalTestApi::class)
class ControlsScreenTest {
    private class Rig {
        val settings = SettingsStore(null, Executor { it.run() })
        val input = ViewportInput(OrbitCamera())
        val sent = ArrayList<Pair<Int, GamepadState>>()
        val router = GamepadRouter({ gamepad, state -> sent += gamepad to state }, input.keys())
        val model = ControlsModel(settings)

        init {
            input.setKeyboardKeys { settings.state.value.gamepads.keyboard().isMapped(it) }
            settings.onChange { router.configure(it.gamepads) }
        }

        fun controller(id: Int, name: String, leftX: Float = 0f): PadReading {
            val axes = floatArrayOf(leftX, 0f, 0f, 0f, -1f, -1f)
            return PadReading(id, name, "guid-$id", true, axes, BooleanArray(15))
        }

        fun keyboard(): KeyboardMap = settings.state.value.gamepads.keyboard()
    }

    private fun ComposeUiTest.show(rig: Rig) {
        setContent {
            FtcSimTheme(darkTheme = true) { ControlsScreen(rig.model, rig.settings, rig.router, rig.input) }
        }
    }

    private fun ComposeUiTest.poll() {
        mainClock.advanceTimeBy(100)
        waitForIdle()
    }

    @Test
    fun bothGamepadsAlwaysExistAndSayWhatTheyListenTo() = runComposeUiTest {
        show(Rig())
        poll()

        onNodeWithText("Gamepad 1").assertExists()
        onNodeWithText("Gamepad 2").assertExists()
        onNodeWithText("Listening to: Keyboard").assertExists()
        onNodeWithText("Listening to: nothing (no controller is free)").assertExists()
    }

    @Test
    fun aControllerPluggedInAppearsInTheMenuAndTakesTheGamepadByItself() = runComposeUiTest {
        val rig = Rig()
        show(rig)
        poll()

        rig.router.padsPolled(listOf(rig.controller(0, "Wireless Controller", leftX = 1f)))
        poll()

        onNodeWithText("Listening to: Wireless Controller").assertExists()
        onNodeWithContentDescription("Gamepad 2 listens to: Automatic").performClick()
        onNodeWithText("Wireless Controller").assertExists()
    }

    @Test
    fun aGamepadCanBeSetToTheKeyboardToNothingOrToANamedController() = runComposeUiTest {
        val rig = Rig()
        show(rig)
        rig.router.padsPolled(listOf(rig.controller(2, "Wireless Controller")))
        poll()

        onNodeWithContentDescription("Gamepad 2 listens to: Automatic").performClick()
        onNodeWithText("Wireless Controller").performClick()
        waitForIdle()
        assertEquals(PadAssignment.controller("guid-2", "Wireless Controller"), rig.settings.state.value.gamepads.pad(2).assignment())

        onNodeWithContentDescription("Gamepad 1 listens to: Automatic").performClick()
        onNodeWithText("None").performClick()
        waitForIdle()
        assertEquals(PadAssignment.NONE, rig.settings.state.value.gamepads.pad(1).assignment())
        poll()
        onNodeWithText("Listening to: nothing (set to None)").assertExists()
    }

    @Test
    fun theSameControllerOnBothGamepadsIsReportedAndTheSecondOneStaysNeutral() = runComposeUiTest {
        val rig = Rig()
        show(rig)
        rig.router.padsPolled(listOf(rig.controller(0, "Wireless Controller")))
        rig.model.assign(1, PadAssignment.controller("guid-0", "Wireless Controller"))
        rig.model.assign(2, PadAssignment.controller("guid-0", "Wireless Controller"))
        poll()

        onAllNodesWithText("Gamepads 1 and 2 are both set to the same controller", substring = true).assertCountEquals(2)
        assertEquals(GamepadState.NEUTRAL, rig.sent.last { it.first == 2 }.second)
    }

    @Test
    fun deadZonesAreSlidersThatSaveAsTheyMove() = runComposeUiTest {
        val rig = Rig()
        show(rig)
        poll()

        onNodeWithContentDescription("Gamepad 1 stick dead zone").performSemanticsAction(SemanticsActions.SetProgress) { it(0.3f) }
        onNodeWithContentDescription("Gamepad 2 trigger dead zone").performSemanticsAction(SemanticsActions.SetProgress) { it(0.2f) }
        waitForIdle()

        assertEquals(0.3f, rig.settings.state.value.gamepads.pad(1).stickDeadzone(), 0.01f)
        assertEquals(0.2f, rig.settings.state.value.gamepads.pad(2).triggerDeadzone(), 0.01f)
    }

    @Test
    fun aKeyIsReboundByPressingItsButtonAndThenTheNewKey() = runComposeUiTest {
        val rig = Rig()
        show(rig)
        poll()

        onNodeWithContentDescription("Left stick up: W. Press to change.").performClick()
        waitForIdle()
        onNodeWithContentDescription("Press the new key for Left stick up").performKeyInput { pressKey(Key.P) }
        waitForIdle()

        assertEquals(KeyEvent.VK_P, rig.keyboard().keyOf(PadControl.LEFT_STICK_UP))
        onNodeWithContentDescription("Left stick up: P. Press to change.").assertExists()
        onNodeWithText("Left stick up is now P.").assertExists()
    }

    @Test
    fun aKeyThatIsTakenIsRefusedInPlaceAndEscapeGivesUp() = runComposeUiTest {
        val rig = Rig()
        show(rig)
        poll()

        onNodeWithContentDescription("A: K. Press to change.").performClick()
        waitForIdle()
        onNodeWithContentDescription("Press the new key for A").performKeyInput { pressKey(Key.W) }
        waitForIdle()

        onNodeWithText("W is already on Left stick up").assertExists()
        assertEquals(KeyEvent.VK_K, rig.keyboard().keyOf(PadControl.A))
        onNodeWithContentDescription("Press the new key for A").performKeyInput { pressKey(Key.Escape) }
        waitForIdle()

        onNodeWithContentDescription("A: K. Press to change.").assertExists()
        assertEquals(KeyEvent.VK_K, rig.keyboard().keyOf(PadControl.A))
    }

    @Test
    fun aBindingCanBeClearedAndTheDefaultsRestored() = runComposeUiTest {
        val rig = Rig()
        show(rig)
        poll()

        onNodeWithContentDescription("Clear the key of Right bumper").performClick()
        waitForIdle()
        assertEquals(0, rig.keyboard().keyOf(PadControl.RIGHT_BUMPER))
        onNodeWithContentDescription("Right bumper: Not set. Press to change.").assertExists()

        onNodeWithText("Restore the default keys").performClick()
        waitForIdle()
        assertEquals(KeyboardMap.DEFAULT, rig.keyboard())
    }

    @Test
    fun aKeyUsedTwiceInAFileIsFlaggedWithTheControlsThatShareIt() = runComposeUiTest {
        val rig = Rig()
        rig.settings.update { it.copy(gamepads = it.gamepads.withKeyboard(it.gamepads.keyboard().with(PadControl.B, KeyEvent.VK_K))) }
        show(rig)
        poll()

        onNodeWithText("K is on A and B").assertExists()
    }

    @Test
    fun theTestAreaTakesKeysOnlyWhileItHasFocusAndReleasesThemWhenFocusMovesOn() = runComposeUiTest {
        val rig = Rig()
        show(rig)
        poll()
        val area = onNodeWithContentDescription("Key test area")

        area.performKeyInput { pressKey(Key.W) }
        assertTrue(rig.input.keys().held().isEmpty(), "nothing is taken without focus")

        area.requestFocus()
        area.performKeyInput { keyDown(Key.W) }
        poll()
        assertEquals(setOf(KeyEvent.VK_W), rig.input.keys().held())
        onNodeWithText("Held: W").assertExists()
        assertEquals(-1f, rig.router.preview(1).state().leftStickY(), "the preview follows the keys")

        onNodeWithContentDescription("Gamepad 1 listens to: Automatic").requestFocus()
        poll()

        assertTrue(rig.input.keys().held().isEmpty(), "focus moved on, so the key is released")
    }
}
