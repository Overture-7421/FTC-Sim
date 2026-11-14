package dev.ftcsim.ui.practice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ftcsim.contracts.field.Score
import dev.ftcsim.contracts.robot.OpModeDescriptor
import dev.ftcsim.contracts.robot.OpModeKind
import dev.ftcsim.contracts.session.MatchState
import dev.ftcsim.ui.common.AppIcons

private val BANNER_WIDTH = 320.dp

/**
 * The match as a scoreboard over the top centre of the viewport: the period and what is left of it, taken from the
 * status and nothing else, and when the match is over its final score. Nothing is shown when there is no match.
 */
@Composable
fun MatchBanner(match: MatchDisplay, score: Score, modifier: Modifier = Modifier) {
    if (match.state == MatchState.NONE) return
    Surface(
        modifier,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 2.dp,
    ) {
        Column(
            Modifier.width(BANNER_WIDTH).padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            when {
                match.timed -> {
                    Title(match.state)
                    Text(
                        formatCountdown(match.secondsLeft),
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.semantics { contentDescription = "${match.secondsLeft} seconds left" },
                    )
                    LinearProgressIndicator(progress = { match.fraction }, modifier = Modifier.fillMaxWidth())
                }
                match.state == MatchState.ARMED -> {
                    Title(match.state)
                    Text("Press Start match when you are ready.", style = MaterialTheme.typography.bodyMedium)
                }
                else -> {
                    Title(match.state)
                    Text("Final score ${score.total()}", style = MaterialTheme.typography.headlineMedium)
                    Text("Reset the session to play another match.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** The state of the match, announced by screen readers whenever it changes. */
@Composable
private fun Title(state: MatchState) {
    Text(
        matchStateLabel(state),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/**
 * Asks which OpModes make the match: an optional autonomous one and the driver-controlled one, the choices of the
 * program's own OpMode kinds, with the picked OpMode or else the first of each kind chosen already. The simulator
 * decides whether it can be armed; if it cannot, it says why, as a snackbar.
 *
 * @param picked the OpMode picked in the top bar, which the choices start from
 * @param onArm called with the choice when the person arms the match
 */
@Composable
fun MatchDialog(opModes: List<OpModeDescriptor>, picked: String?, onArm: (MatchPlan) -> Unit, onDismiss: () -> Unit) {
    val suggested = remember(opModes, picked) { MatchPlan.suggest(opModes, picked) }
    var autonomous by remember(suggested) { mutableStateOf(suggested?.autonomous) }
    var teleOp by remember(suggested) { mutableStateOf(suggested?.teleOp) }
    val autonomousModes = opModes.filter { it.kind() == OpModeKind.AUTONOMOUS }.map { it.name() }
    val teleOpModes = opModes.filter { it.kind() == OpModeKind.TELEOP }.map { it.name() }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(AppIcons.Flag, contentDescription = null) },
        title = { Text("Play a match") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ChoiceGroup(
                    "Autonomous period", listOf<String?>(null) + autonomousModes, autonomous,
                    { it ?: "None: the match starts with the driver-controlled period" }, { autonomous = it },
                )
                if (teleOpModes.isEmpty()) {
                    Text("This program has no TeleOp OpMode, which a match needs.", color = MaterialTheme.colorScheme.error)
                } else {
                    ChoiceGroup("Driver-controlled period", teleOpModes, teleOp, { it }, { teleOp = it })
                }
                Text(
                    "The simulator times the periods in simulated time, so pausing the simulation or changing its speed changes the countdown with it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { teleOp?.let { onArm(MatchPlan(autonomous, it)) } }, enabled = teleOp != null) { Text("Arm") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A titled list of radio buttons, one of which is chosen; the whole row is the target. */
@Composable
private fun <T> ChoiceGroup(title: String, options: List<T>, selected: T?, label: (T) -> String, onSelect: (T) -> Unit) {
    Column(Modifier.selectableGroup()) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        options.forEach { option ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = option == selected, role = Role.RadioButton, onClick = { onSelect(option) }).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = option == selected, onClick = null)
                Spacer(Modifier.width(12.dp))
                Text(label(option), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
