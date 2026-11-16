package dev.ftcsim.ui.controls

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ftcsim.ui.input.GamepadConfig
import dev.ftcsim.ui.input.KeyboardMap
import dev.ftcsim.ui.input.PadAssignment
import dev.ftcsim.ui.input.PadControl
import dev.ftcsim.ui.settings.SettingsStore
import java.awt.event.KeyEvent

/** What became of a key pressed while a control waits for its new key. */
sealed interface RebindResult {
    /** The control has the key now. */
    data class Bound(val map: KeyboardMap) : RebindResult

    /** The person backed out. */
    data object Cancelled : RebindResult

    /** The key cannot be used; [message] says why and the control keeps waiting. */
    data class Refused(val message: String) : RebindResult
}

/** The rules for giving a control of the keyboard gamepad a new key. */
object KeyRebinding {
    fun capture(map: KeyboardMap, control: PadControl, keyCode: Int): RebindResult {
        if (keyCode == KeyEvent.VK_ESCAPE) return RebindResult.Cancelled
        if (keyCode == KeyEvent.VK_UNDEFINED) return RebindResult.Refused("Cannot use that key")
        val reserved = KeyboardMap.reservedReason(keyCode)
        if (reserved.isPresent) {
            return RebindResult.Refused("${KeyboardMap.keyName(keyCode)} cannot be used: ${reserved.get()}.")
        }
        val owner = map.controlsOf(keyCode).firstOrNull { it != control }
        if (owner != null) {
            return RebindResult.Refused("${KeyboardMap.keyName(keyCode)} is already on ${owner.label()}")
        }
        return RebindResult.Bound(map.with(control, keyCode))
    }
}

/**
 * What the Controls screen changes: where each gamepad listens, its dead zones and the keys of the keyboard gamepad.
 * Every change goes straight into the settings, which save it; the router follows the settings. Waiting for a new
 * key is the only state kept here.
 */
@Stable
class ControlsModel(private val settings: SettingsStore) {
    /** The control that waits for its new key, or null. */
    var rebinding by mutableStateOf<PadControl?>(null)
        private set

    /** What the last attempt to bind a key said, or null. */
    var message by mutableStateOf<String?>(null)
        private set

    fun assign(gamepad: Int, assignment: PadAssignment) = changePad(gamepad) { it.withAssignment(assignment) }

    fun setStickDeadzone(gamepad: Int, value: Float) = changePad(gamepad) { it.withStickDeadzone(value) }

    fun setTriggerDeadzone(gamepad: Int, value: Float) = changePad(gamepad) { it.withTriggerDeadzone(value) }

    fun beginRebind(control: PadControl) {
        rebinding = control
        message = null
    }

    fun cancelRebind() {
        rebinding = null
    }

    /** Offers a key to the control that waits for one; false when none does. */
    fun offerKey(keyCode: Int): Boolean {
        val control = rebinding ?: return false
        when (val result = KeyRebinding.capture(settings.state.value.gamepads.keyboard(), control, keyCode)) {
            is RebindResult.Bound -> {
                settings.update { it.copy(gamepads = it.gamepads.withKeyboard(result.map)) }
                message = "${control.label()} is now ${KeyboardMap.keyName(keyCode)}."
                rebinding = null
            }
            RebindResult.Cancelled -> {
                message = null
                rebinding = null
            }
            is RebindResult.Refused -> message = result.message
        }
        return true
    }

    fun clearBinding(control: PadControl) {
        settings.update { it.copy(gamepads = it.gamepads.withKeyboard(it.gamepads.keyboard().with(control, 0))) }
        message = "${control.label()} has no key now."
        if (rebinding == control) rebinding = null
    }

    fun restoreDefaultKeys() {
        settings.update { it.copy(gamepads = it.gamepads.withKeyboard(KeyboardMap.DEFAULT)) }
        message = "The keys are back to their defaults."
        rebinding = null
    }

    private fun changePad(gamepad: Int, change: (GamepadConfig.Pad) -> GamepadConfig.Pad) {
        settings.update { it.copy(gamepads = it.gamepads.withPad(gamepad, change(it.gamepads.pad(gamepad)))) }
    }
}
