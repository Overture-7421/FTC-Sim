package dev.ftcsim.ui.practice

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import dev.ftcsim.ui.viewport.ViewportEngine
import dev.ftcsim.ui.viewport.ViewportSurface
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private val SIDE_PANEL_WIDTH = 400.dp

/**
 * The Practice screen: the 3D viewport with the camera presets over it, the Driver Station, hardware, score and
 * problem panels beside it, the controls in the top bar and the status strip below. A timed match shows as a
 * scoreboard over the viewport and takes over the top bar's controls. Menus and dialogs tell the engine's input gate,
 * because input from the keyboard and from controllers must stop while one is open. A command the simulator refuses
 * is a snackbar; the session goes on.
 *
 * @param onSetup called when the person asks to set up a run, from the empty state or after a failure
 * @param onRetry asks for the session that failed again
 * @param onReconnect tries to open the link to the simulator again, when that could not be done
 * @param corner what to show over the top end of the viewport, such as the performance overlay
 * @param viewport what fills the viewport area, given the focus requester that gives it the keyboard; the viewport
 *     surface unless a test puts something simpler there
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PracticeScreen(
    session: PracticeSession,
    engine: ViewportEngine,
    ui: PracticeUi,
    onRetry: () -> Unit,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
    onSetup: () -> Unit = {},
    corner: @Composable (Modifier) -> Unit = {},
    viewport: @Composable (FocusRequester, Modifier) -> Unit = { focus, area ->
        ViewportSurface(engine, focus, area, onFocusChange = { ui.viewportFocused = it })
    },
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val viewportFocus = remember { FocusRequester() }
    val focusViewport = { runCatching { viewportFocus.requestFocus() }; Unit }

    LaunchedEffect(ui, engine) {
        snapshotFlow { ui.modalOpen }.collect { engine.input.modalChanged(it) }
    }
    LaunchedEffect(session) {
        session.refusals.collectLatest { snackbar.showSnackbar(it, withDismissAction = true, duration = SnackbarDuration.Long) }
    }
    DisposableEffect(ui, engine) {
        onDispose {
            ui.closeModals()
            engine.input.modalChanged(false)
        }
    }
    ErrorNotice(session, ui, snackbar)

    Scaffold(
        modifier,
        topBar = { PracticeTopBar(session, ui, focusViewport) },
        bottomBar = { StatusStrip(session, engine, ui) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Row(Modifier.padding(padding).fillMaxSize()) {
            ViewportRegion(session, engine, ui, viewportFocus, viewport, corner, onSetup, onRetry, onReconnect, Modifier.weight(1f).fillMaxHeight())
            SidePanel(session, ui, Modifier.width(SIDE_PANEL_WIDTH).fillMaxHeight())
        }
    }
    if (ui.resetDialogOpen) {
        ResetDialog(
            onConfirm = {
                ui.resetDialogOpen = false
                session.reset()
                scope.launch { snackbar.showSnackbar("The session was reset") }
            },
            onDismiss = { ui.resetDialogOpen = false },
        )
    }
    if (ui.matchDialogOpen) {
        val state by session.session.collectAsState()
        MatchDialog(
            state.opModes, state.selectedOpMode,
            onArm = { plan ->
                ui.matchDialogOpen = false
                session.armMatch(plan.autonomous, plan.teleOp)
                focusViewport()
            },
            onDismiss = { ui.matchDialogOpen = false },
        )
    }
}

@Composable
private fun ViewportRegion(
    session: PracticeSession,
    engine: ViewportEngine,
    ui: PracticeUi,
    focus: FocusRequester,
    viewport: @Composable (FocusRequester, Modifier) -> Unit,
    corner: @Composable (Modifier) -> Unit,
    onSetup: () -> Unit,
    onRetry: () -> Unit,
    onReconnect: () -> Unit,
    modifier: Modifier,
) {
    val state by session.session.collectAsState()
    val link by session.link.collectAsState()
    val preset by engine.preset.collectAsState()
    val problems by session.problems.collectAsState()
    val humanActions = state.info?.field()?.humanActions().orEmpty()
    Box(modifier) {
        viewport(focus, Modifier.fillMaxSize())
        CameraPresetBar(preset, { engine.camera.select(it) }, Modifier.align(Alignment.TopStart).padding(12.dp))
        corner(Modifier.align(Alignment.TopEnd).padding(12.dp))
        if (state.connection == Connection.OPEN) MatchScoreboard(session, Modifier.align(Alignment.TopCenter).padding(top = 72.dp))
        KeyboardHint(ui.viewportFocused, Modifier.align(Alignment.BottomStart).padding(12.dp))
        if (humanActions.isNotEmpty()) {
            HumanActionChips(
                humanActions, state.connection == Connection.OPEN, session::humanAction,
                Modifier.align(Alignment.BottomEnd).padding(12.dp),
            )
        }
        SessionOverlay(link, state, problems.firstOrNull { it.source == "Session" }?.text, onRetry, onReconnect, onSetup)
    }
}

/** The match banner, collecting what only it shows, so that the countdown does not recompose the rest of the viewport. */
@Composable
private fun MatchScoreboard(session: PracticeSession, modifier: Modifier) {
    val match by session.match.collectAsState()
    val score by session.score.collectAsState()
    MatchBanner(match, score, modifier)
}

/** Raises a snackbar when the robot program or the session reports a new error, with a way to the Problems tab. */
@Composable
private fun ErrorNotice(session: PracticeSession, ui: PracticeUi, snackbar: SnackbarHostState) {
    val problems by session.problems.collectAsState()
    val error = problems.firstOrNull { it.severity == Severity.ERROR }
    LaunchedEffect(error) {
        if (error == null) {
            ui.announcedError = null
        } else if (error != ui.announcedError) {
            ui.announcedError = error
            val result = snackbar.showSnackbar(
                message = "${error.source}: ${error.text.lineSequence().first()}",
                actionLabel = "Show",
                withDismissAction = true,
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) {
                ui.panel = Panel.PROBLEMS
            }
        }
    }
}

@Composable
private fun ResetDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
        title = { Text("Reset the session?") },
        text = { Text("The OpMode stops, the robot returns to its start pose and the field is built again.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Reset") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
