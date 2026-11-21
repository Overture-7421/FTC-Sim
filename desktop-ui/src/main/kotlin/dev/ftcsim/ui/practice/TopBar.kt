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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ftcsim.contracts.robot.OpModeDescriptor
import dev.ftcsim.contracts.robot.OpModeKind
import dev.ftcsim.contracts.robot.RobotPhase
import dev.ftcsim.contracts.session.MatchState
import dev.ftcsim.ui.common.AppIcons
import dev.ftcsim.ui.common.Tip
import dev.ftcsim.ui.project.ProjectActivity

/**
 * The top app bar of the Simulator screen: the OpMode to run, and one button that follows the Driver Station: Init, then
 * Start, then Stop, enabled the way the Driver Station enables them. Reset world stops the code and puts the robot and
 * the field back at the start at once, with no question asked; the arrow beside it offers Restart code, which rebuilds
 * the project and starts the session again with the latest code. Match, pause, speed and Stop are in the menu at the end. While a match is armed or under way its own controls take the place of the OpMode's: Start match, once it is
 * armed, and Stop, which gives the match up or ends it.
 *
 * @param startPosition where the robot starts, as the application's own control; nothing by default
 * @param onOpenDashboard called with the address of the robot program's dashboard when the button that opens it is
 *     pressed; the button is shown only while the program has a dashboard, and not at all when this is null
 * @param focusViewport called after Init, Start or Stop, so that the keyboard goes to the robot again
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PracticeTopBar(
    session: PracticeSession,
    ui: PracticeUi,
    restart: CodeRestart,
    focusViewport: () -> Unit,
    startPosition: @Composable () -> Unit = {},
    onOpenDashboard: ((String) -> Unit)? = null,
) {
    val state by session.session.collectAsState()
    val dashboardUrl by session.dashboardUrl.collectAsState()
    val run by session.run.collectAsState()
    val match by session.match.collectAsState()
    val plan by session.matchPlan.collectAsState()
    val activity by restart.activity.collectAsState()
    val holds by session.holds.collectAsState()
    val building = activity is ProjectActivity.Building
    val waiting = waitingFor(state.connection, building, holds)
    val controls = Controls.of(state, run, match, waiting)
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
                    MainButton(run.phase, controls, waiting, session, focusViewport)
                }
            }
        },
        actions = {
            startPosition()
            if (dashboardUrl.isNotEmpty() && onOpenDashboard != null) {
                Tip("Open $dashboardUrl, the dashboard of the robot program, in your browser") {
                    OutlinedButton(
                        onClick = { onOpenDashboard(dashboardUrl) },
                        modifier = Modifier.semantics { contentDescription = "Open the dashboard of the robot program in the browser" },
                    ) {
                        Text("Open dashboard")
                    }
                }
            }
            Tip(if (controls.reset) "Stop the code and put everything back at the start" else "No session") {
                FilledTonalButton(
                    onClick = { session.reset(); focusViewport() },
                    enabled = controls.reset,
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    modifier = Modifier.semantics { contentDescription = "Reset world: stop the code and put the robot and the field back at the start" },
                ) {
                    Icon(AppIcons.Replay, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text("Reset world")
                }
            }
            RestartMenu(restart, controls, building, ui, focusViewport)
            MoreMenu(run, controls, ui, session, focusViewport)
        },
    )
}

/**
 * The one button of a run: Init from idle, Start once initialised, Stop while it runs. While the run controls wait for
 * something ([waitingFor]) it is a disabled "[waiting]" with a spinner, so that nothing looks ready that is about to
 * be replaced or is being used by the application.
 */
@Composable
private fun MainButton(phase: RobotPhase, controls: Controls, waiting: String?, session: PracticeSession, focusViewport: () -> Unit) {
    if (waiting != null) {
        FilledTonalButton(
            onClick = {},
            enabled = false,
            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            modifier = Modifier.semantics { contentDescription = waiting },
        ) {
            CircularProgressIndicator(modifier = Modifier.size(ButtonDefaults.IconSize), strokeWidth = 2.dp)
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            Text(waiting)
        }
        return
    }
    when (phase) {
        RobotPhase.IDLE -> FilledTonalButton(onClick = { session.init(); focusViewport() }, enabled = controls.init) { Text("Init") }
        RobotPhase.INIT -> Button(
            onClick = { session.start(); focusViewport() },
            enabled = controls.start,
            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            Text("Start")
        }
        RobotPhase.RUNNING -> StopButton(controls.stop, "Stop the OpMode") { session.stop(); focusViewport() }
    }
}

/** What else resetting can mean: the robot code again, rebuilt from the project's source. */
@Composable
private fun RestartMenu(restart: CodeRestart, controls: Controls, building: Boolean, ui: PracticeUi, focusViewport: () -> Unit) {
    Box {
        Tip(if (controls.restart) "Restart code" else if (building) "Building" else "No session") {
            IconButton(onClick = { ui.restartMenuOpen = true }) { Icon(Icons.Filled.ArrowDropDown, contentDescription = "Restart options") }
        }
        DropdownMenu(expanded = ui.restartMenuOpen, onDismissRequest = { ui.restartMenuOpen = false }) {
            DropdownMenuItem(
                text = {
                    Column {
                        Text("Restart code")
                        Text("Build the project again", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                leadingIcon = { Icon(AppIcons.Restart, contentDescription = null) },
                enabled = controls.restart,
                onClick = { ui.restartMenuOpen = false; restart.restart(); focusViewport() },
            )
        }
    }
}

/** Match, pause, speed and Stop, which are used less than Init, Start and Restart code. */
@Composable
private fun MoreMenu(run: RunState, controls: Controls, ui: PracticeUi, session: PracticeSession, focusViewport: () -> Unit) {
    Box {
        Tip("More") {
            IconButton(onClick = { ui.moreMenuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
        }
        DropdownMenu(expanded = ui.moreMenuOpen, onDismissRequest = { ui.moreMenuOpen = false }) {
            DropdownMenuItem(
                text = {
                    Column {
                        Text("Match")
                        controls.matchReason?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                },
                leadingIcon = { Icon(AppIcons.Flag, contentDescription = null) },
                enabled = controls.arm,
                onClick = { ui.moreMenuOpen = false; ui.matchDialogOpen = true },
            )
            DropdownMenuItem(
                text = { Text(if (run.paused) "Resume" else "Pause") },
                leadingIcon = { Icon(if (run.paused) Icons.Filled.PlayArrow else AppIcons.Pause, contentDescription = null) },
                enabled = controls.pause,
                onClick = { ui.moreMenuOpen = false; session.togglePause() },
            )
            DropdownMenuItem(
                text = { Text("Stop") },
                leadingIcon = { Icon(AppIcons.Stop, contentDescription = null) },
                enabled = controls.stop,
                onClick = { ui.moreMenuOpen = false; session.stop(); focusViewport() },
            )
            HorizontalDivider()
            SPEEDS.forEach { option ->
                DropdownMenuItem(
                    text = { Text("Speed ${formatSpeed(option)}") },
                    leadingIcon = { if (option == run.speed) Icon(Icons.Filled.Check, contentDescription = "Current speed") },
                    enabled = controls.pause,
                    onClick = { ui.moreMenuOpen = false; session.setSpeed(option) },
                )
            }
        }
    }
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
internal fun OpModeSelector(
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
