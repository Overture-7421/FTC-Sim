package dev.ftcsim.ui.controls

import dev.ftcsim.ui.input.KeyboardMap
import dev.ftcsim.ui.input.PadAssignment
import dev.ftcsim.ui.input.PadControl
import dev.ftcsim.ui.settings.SettingsStore
import java.awt.event.KeyEvent
import java.util.concurrent.Executor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ControlsModelTest {
    private val settings = SettingsStore(null, Executor { it.run() })
    private val model = ControlsModel(settings)

    private fun keyboard() = settings.state.value.gamepads.keyboard()

    @Test
    fun aKeyIsBoundToTheControlThatWaitsForOne() {
        model.beginRebind(PadControl.A)
        assertEquals(PadControl.A, model.rebinding)

        assertTrue(model.offerKey(KeyEvent.VK_P))

        assertEquals(KeyEvent.VK_P, keyboard().keyOf(PadControl.A))
        assertNull(model.rebinding)
        assertEquals("A is now P.", model.message)
    }

    @Test
    fun keysAreNotTakenWhenNothingWaitsForOne() {
        assertFalse(model.offerKey(KeyEvent.VK_P))

        assertEquals(KeyboardMap.DEFAULT, keyboard())
    }

    @Test
    fun escapeCancelsAndLeavesTheKeyAlone() {
        model.beginRebind(PadControl.A)

        assertTrue(model.offerKey(KeyEvent.VK_ESCAPE))

        assertNull(model.rebinding)
        assertEquals(KeyEvent.VK_K, keyboard().keyOf(PadControl.A))
    }

    @Test
    fun aKeyAlreadyInUseIsRefusedWithTheNameOfItsOwnerAndTheControlKeepsWaiting() {
        model.beginRebind(PadControl.A)

        model.offerKey(KeyEvent.VK_W)

        assertEquals(PadControl.A, model.rebinding)
        assertEquals(KeyEvent.VK_K, keyboard().keyOf(PadControl.A))
        assertTrue(model.message!!.contains("Left stick up"), model.message)
        assertTrue(keyboard().conflicts().isEmpty(), "a refusal never leaves a conflict behind")
    }

    @Test
    fun aControlMayKeepItsOwnKeyWhenItIsPressedAgain() {
        model.beginRebind(PadControl.A)

        model.offerKey(KeyEvent.VK_K)

        assertNull(model.rebinding)
        assertEquals(KeyEvent.VK_K, keyboard().keyOf(PadControl.A))
    }

    @Test
    fun keysThatMoveFocusOrPressButtonsAreRefusedWithAReason() {
        model.beginRebind(PadControl.A)

        for (key in listOf(KeyEvent.VK_TAB, KeyEvent.VK_ENTER, KeyEvent.VK_SPACE, KeyEvent.VK_SHIFT, KeyEvent.VK_DELETE)) {
            model.offerKey(key)
            assertEquals(PadControl.A, model.rebinding, KeyEvent.getKeyText(key))
            assertNotNull(model.message)
        }
        assertEquals(KeyEvent.VK_K, keyboard().keyOf(PadControl.A))
    }

    @Test
    fun aBindingCanBeClearedAndTheDefaultsComeBack() {
        model.clearBinding(PadControl.A)
        assertEquals(0, keyboard().keyOf(PadControl.A))
        assertEquals("A has no key now.", model.message)

        model.beginRebind(PadControl.B)
        model.restoreDefaultKeys()

        assertEquals(KeyboardMap.DEFAULT, keyboard())
        assertNull(model.rebinding)
    }

    @Test
    fun assignmentsAndDeadZonesGoStraightIntoTheSettings() {
        model.assign(2, PadAssignment.controller("guid", "Pad"))
        model.setStickDeadzone(1, 0.25f)
        model.setTriggerDeadzone(1, 0.1f)

        val gamepads = settings.state.value.gamepads
        assertEquals(PadAssignment.controller("guid", "Pad"), gamepads.pad(2).assignment())
        assertEquals(PadAssignment.AUTOMATIC, gamepads.pad(1).assignment())
        assertEquals(0.25f, gamepads.pad(1).stickDeadzone())
        assertEquals(0.1f, gamepads.pad(1).triggerDeadzone())
    }
}
