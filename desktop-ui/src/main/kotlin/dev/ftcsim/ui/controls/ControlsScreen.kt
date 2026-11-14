package dev.ftcsim.ui.controls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ftcsim.ui.common.ScreenPage
import dev.ftcsim.ui.input.GamepadRouter
import dev.ftcsim.ui.input.ViewportInput
import dev.ftcsim.ui.settings.SettingsStore
import kotlinx.coroutines.delay

private const val PREVIEW_PERIOD_MILLIS = 33L

/**
 * The Controls screen: where each of the two gamepads listens, a live preview of what it reports, dead zones, and the
 * keys of the keyboard gamepad. Both gamepads exist whatever is plugged in; one with nothing assigned stays neutral.
 * The preview is polled while the screen is shown, so a controller plugged in or removed appears and disappears in the
 * source menus without any action.
 */
@Composable
fun ControlsScreen(
    model: ControlsModel,
    settings: SettingsStore,
    router: GamepadRouter,
    input: ViewportInput,
    modifier: Modifier = Modifier,
) {
    val config = settings.state.collectAsState().value.gamepads
    val live by produceState(LiveControls.EMPTY, router, input) {
        while (true) {
            value = LiveControls.read(router, input.keys())
            delay(PREVIEW_PERIOD_MILLIS)
        }
    }
    ScreenPage("Controls", modifier, maxWidth = 1120.dp) {
        Text(
            "Gamepad 1 and gamepad 2 always exist. A gamepad with nothing assigned stays untouched. " +
                "The robot is driven only while the Practice screen is shown.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            GamepadCard(1, config.pad(1), live.pads[0], live.controllers, model, Modifier.weight(1f))
            GamepadCard(2, config.pad(2), live.pads[1], live.controllers, model, Modifier.weight(1f))
        }
        KeyboardCard(model, config.keyboard(), input, live.heldKeys)
    }
}
