package dev.ftcsim.ui.controls

import dev.ftcsim.contracts.robot.GamepadState
import dev.ftcsim.ui.input.GamepadRouter
import dev.ftcsim.ui.input.GamepadRouting
import dev.ftcsim.ui.input.HeldKeys

/** One connected controller as the Controls screen lists it. */
data class ControllerInfo(val id: Int, val name: String, val guid: String, val hasGamepadMapping: Boolean)

/**
 * What one gamepad shows right now: where it listens, what the robot program would be told, and the sticks and
 * triggers before any dead zone (left x and y, right x and y, left and right trigger). Values compare equal when
 * nothing moved, so a resting controller does not make the screen draw again.
 */
data class PadView(val resolved: GamepadRouting.Resolved, val state: GamepadState, val raw: List<Float>) {
    companion object {
        val IDLE = PadView(GamepadRouting.Resolved(GamepadRouting.Source.NONE, -1, "nothing (set to None)", null), GamepadState.NEUTRAL, List(6) { 0f })
    }
}

/** Everything the Controls screen polls: the connected controllers, both gamepads and the keys held in the test area. */
data class LiveControls(val controllers: List<ControllerInfo>, val pads: List<PadView>, val heldKeys: Set<Int>) {
    companion object {
        val EMPTY = LiveControls(emptyList(), listOf(PadView.IDLE, PadView.IDLE), emptySet())

        fun read(router: GamepadRouter, keys: HeldKeys): LiveControls = LiveControls(
            router.controllers().map { ControllerInfo(it.id(), it.displayName(), it.guid(), it.mapped()) },
            (1..2).map { gamepad ->
                val preview = router.preview(gamepad)
                PadView(preview.resolved(), preview.state(), preview.raw().toList())
            },
            keys.held(),
        )
    }
}
