package dev.ftcsim.ui.practice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ftcsim.contracts.field.HumanAction
import dev.ftcsim.ui.render.OrbitCamera

private val CAMERA_PRESETS = listOf(
    OrbitCamera.Preset.TOP to "Top",
    OrbitCamera.Preset.DRIVER to "Driver",
    OrbitCamera.Preset.CHASE to "Chase",
    OrbitCamera.Preset.FREE to "Free",
)

/** The camera presets, floating over a corner of the viewport. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraPresetBar(selected: OrbitCamera.Preset, onSelect: (OrbitCamera.Preset) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier.semantics { contentDescription = "Camera" },
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        shape = MaterialTheme.shapes.large,
    ) {
        SingleChoiceSegmentedButtonRow {
            CAMERA_PRESETS.forEachIndexed { index, (preset, label) ->
                SegmentedButton(
                    selected = preset == selected,
                    onClick = { onSelect(preset) },
                    shape = SegmentedButtonDefaults.itemShape(index, CAMERA_PRESETS.size),
                    label = { Text(label, maxLines = 1) },
                )
            }
        }
    }
}

/** What a person at the field can do, one chip each. */
@Composable
fun HumanActionChips(actions: List<HumanAction>, enabled: Boolean, onAction: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.forEach { action ->
            AssistChip(onClick = { onAction(action.id()) }, enabled = enabled, label = { Text(action.label()) })
        }
    }
}

/** Says where the keyboard goes: to the robot while the viewport is focused, nowhere otherwise. */
@Composable
fun KeyboardHint(focused: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            if (focused) "Keyboard input is on; the keys are set under Controls" else "Click the viewport to drive with the keyboard",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/**
 * Shown over the viewport while there is no link to the simulator, no session, while a session opens, and when it could
 * not go on. A link that cannot be had is the application's problem and says so first; the rest is about the session.
 *
 * @param onRetry asks for the session that failed again
 * @param onReconnect tries to open the link to the simulator again
 */
@Composable
fun SessionOverlay(
    link: LinkState,
    state: SessionState,
    failure: String?,
    onRetry: () -> Unit,
    onReconnect: () -> Unit,
    onSetup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val connecting = link is LinkState.Connecting
    when {
        link is LinkState.Unavailable -> Scrim(modifier, 0.45f) {
            LinkNotice(link, waitingForMore = false, onReconnect = onReconnect, modifier = Modifier.padding(24.dp).widthIn(max = 520.dp))
        }
        state.connection == Connection.OPEN -> Unit
        state.connection == Connection.NONE && !connecting -> Scrim(modifier, 0.45f) {
            ElevatedCard(Modifier.padding(24.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("No practice session yet", style = MaterialTheme.typography.titleMedium)
                    Text("Choose a field, a robot and a program, then start a run.", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = onSetup) { Text("Set up a run") }
                }
            }
        }
        state.connection == Connection.NONE || state.connection == Connection.OPENING ->
            Scrim(modifier, 0.35f) { Busy(if (connecting) "Connecting to the simulator..." else "Opening the session") }
        else -> Scrim(modifier, 0.45f) {
            ElevatedCard(Modifier.padding(24.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("The session could not go on", style = MaterialTheme.typography.titleMedium)
                    Text(failure ?: "No reason was given.", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onRetry) { Text("Try again") }
                        OutlinedButton(onClick = onSetup) { Text("Change the setup") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Scrim(modifier: Modifier, alpha: Float, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize().background(Color.Black.copy(alpha = alpha)), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun Busy(text: String) {
    ElevatedCard {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator(Modifier.size(28.dp))
            Text(text, style = MaterialTheme.typography.titleMedium)
        }
    }
}
