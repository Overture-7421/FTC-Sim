package dev.ftcsim.ui.practice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ftcsim.contracts.robot.OpModeDescriptor
import dev.ftcsim.contracts.robot.OpModeKind
import dev.ftcsim.contracts.session.MatchState
import dev.ftcsim.ui.common.AppIcons
import dev.ftcsim.ui.common.Tip

/**
 * The top app bar of the Practice screen: the OpMode to run with Init, Start and Stop beside it, enabled the way the
 * Driver Station enables them, and Match, which opens the dialog that arms a timed match; then pause, speed and reset.
 * While a match is armed or under way its own controls take the place of those: Start match, once it is armed, and
 * Stop, which gives the match up or ends it. The free-practice buttons are not shown then, rather than left dead.
 *
 * @param focusViewport called after Init, Start or Stop, so that the keyboard goes to the robot again
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PracticeTopBar(session: PracticeSession, ui: PracticeUi, focusViewport: () -> Unit) {
    val state by session.session.collectAsState()
    val run by session.run.collectAsState()
    val match by session.match.collectAsState()
    val plan by session.matchPlan.collectAsState()
    val controls = Controls.of(state, run, match)
    TopAppBar(
        expandedHeight = 56.dp,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (match.inProgress) {
                    MatchButtons(match.state == MatchState.ARMED, plan, controls, session, focusViewport)
                } else {
                    OpModeSelector(
                        state.opModes, state.selectedOpMode, controls.selectOpMode, ui.opModeMenuOpen,
                        onExpandedChange = { ui.opModeMenuOpen = it },
                        onSelect = { session.selectOpMode(it) },
                    )
                    FilledTonalButton(
                        onClick = { session.init(); focusViewport() },
                        enabled = controls.init,
                        contentPadding = ButtonDefaults.ContentPadding,
                    ) { Text("Init") }
                    Button(
                        onClick = { session.start(); focusViewport() },
                        enabled = controls.start,
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text("Start")
                    }
                    StopButton(controls.stop, "Stop the OpMode") { session.stop(); focusViewport() }
                    MatchButton(controls) { ui.matchDialogOpen = true }
                }
            }
        },
        actions = {
            Tip(if (run.paused) "Resume the simulation" else "Pause the simulation") {
                IconToggleButton(
                    checked = run.paused,
                    onCheckedChange = { session.togglePause() },
                    enabled = controls.pause,
                ) {
                    Icon(
                        if (run.paused) Icons.Filled.PlayArrow else AppIcons.Pause,
                        contentDescription = if (run.paused) "Resume the simulation" else "Pause the simulation",
                    )
                }
            }
            SpeedMenu(run.speed, controls.pause, ui.speedMenuOpen, { ui.speedMenuOpen = it }) { session.setSpeed(it) }
            Tip("Reset the session") {
                IconButton(onClick = { ui.resetDialogOpen = true }, enabled = controls.reset) {
                    Icon(AppIcons.Replay, contentDescription = "Reset the session")
                }
            }
        },
    )
}

@Composable
private fun StopButton(enabled: Boolean, tip: String, onClick: () -> Unit) {
    Tip(tip) {
        Button(
            onClick = onClick,
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        ) {
            Icon(AppIcons.Stop, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            Text("Stop")
        }
    }
}

/** Opens the match dialog; when a match cannot be armed the button is off and says why, in its tooltip and to screen readers. */
@Composable
private fun MatchButton(controls: Controls, onClick: () -> Unit) {
    val reason = controls.matchReason
    Tip(reason ?: "Play a timed match, with an autonomous and a driver-controlled period") {
        OutlinedButton(
            onClick = onClick,
            enabled = controls.arm,
            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            modifier = Modifier.semantics { if (reason != null) stateDescription = reason },
        ) {
            Icon(AppIcons.Flag, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            Text("Match")
        }
    }
}

@Composable
private fun MatchButtons(armed: Boolean, plan: MatchPlan?, controls: Controls, session: PracticeSession, focusViewport: () -> Unit) {
    if (plan != null) {
        Text(
            matchPlanText(plan),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 420.dp),
        )
    }
    if (armed) {
        Button(
            onClick = { session.startMatch(); focusViewport() },
            enabled = controls.startMatch,
            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            Text("Start match")
        }
    }
    StopButton(controls.stopMatch, if (armed) "Give up the armed match" else "End the match now") { session.stop(); focusViewport() }
}

@Composable
private fun OpModeSelector(
    opModes: List<OpModeDescriptor>,
    selected: String?,
    enabled: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
) {
    Box {
        OutlinedButton(
            onClick = { onExpandedChange(true) },
            enabled = enabled,
            modifier = Modifier.widthIn(min = 150.dp, max = 260.dp),
        ) {
            Text(
                selected ?: "Select an OpMode",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose an OpMode")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            opModes.forEach { mode ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(mode.name())
                            Text(
                                if (mode.kind() == OpModeKind.AUTONOMOUS) "Autonomous" else "TeleOp",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    leadingIcon = {
                        if (mode.name() == selected) Icon(Icons.Filled.Check, contentDescription = "Selected")
                    },
                    onClick = {
                        onSelect(mode.name())
                        onExpandedChange(false)
                    },
                )
            }
        }
    }
}

@Composable
private fun SpeedMenu(
    speed: Double,
    enabled: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (Double) -> Unit,
) {
    Box {
        TextButton(onClick = { onExpandedChange(true) }, enabled = enabled) {
            Icon(AppIcons.Speed, contentDescription = "Simulation speed", modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            Text(formatSpeed(speed))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            SPEEDS.forEach { option ->
                DropdownMenuItem(
                    text = { Text(formatSpeed(option)) },
                    leadingIcon = {
                        if (option == speed) Icon(Icons.Filled.Check, contentDescription = "Current speed")
                    },
                    onClick = {
                        onSelect(option)
                        onExpandedChange(false)
                    },
                )
            }
        }
    }
}
