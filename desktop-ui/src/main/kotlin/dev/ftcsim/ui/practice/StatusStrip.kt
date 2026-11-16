package dev.ftcsim.ui.practice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.ftcsim.contracts.robot.RobotPhase
import dev.ftcsim.ui.viewport.ViewportEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/** The strip under the viewport: where the session is, how long it has run in simulated time, how fast, and how many problems. */
@Composable
fun StatusStrip(session: PracticeSession, engine: ViewportEngine, ui: PracticeUi, extraProblems: StateFlow<List<Problem>>, modifier: Modifier = Modifier) {
    val state by session.session.collectAsState()
    val run by session.run.collectAsState()
    val match by session.match.collectAsState()
    val timing by session.timing.collectAsState()
    val own by session.problems.collectAsState()
    val extra by extraProblems.collectAsState()
    val problems = own + extra
    val fps by rememberFramesPerSecond(engine)
    val slow = state.connection == Connection.OPEN && !run.paused && !timing.keepsUp(run.speed)
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            Modifier.height(32.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            StripText(matchSummary(match).takeIf { state.connection == Connection.OPEN } ?: phaseText(state, run))
            if (state.connection == Connection.OPEN) {
                StripText("Sim ${formatSimTime(timing.simTimeNanos)}")
                StripText(
                    if (run.paused) "Paused" else "Speed ${formatAchieved(timing.achievedSpeed)} of ${formatSpeed(run.speed)}",
                    if (slow) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.weight(1f))
            if (problems.isNotEmpty()) {
                val errors = problems.any { it.severity == Severity.ERROR }
                TextButton(
                    onClick = { ui.showPanel(Panel.PROBLEMS) },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.height(28.dp),
                ) {
                    Text(
                        if (problems.size == 1) "1 problem" else "${problems.size} problems",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (errors) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            StripText(if (fps > 0) "$fps fps" else "Viewport at rest")
        }
    }
}

@Composable
private fun RowScope.StripText(text: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
}

private fun phaseText(state: SessionState, run: RunState): String = when (state.connection) {
    Connection.NONE -> "No session"
    Connection.OPENING -> "Opening the session"
    Connection.FAILED -> "Session failed"
    Connection.OPEN -> when (run.phase) {
        RobotPhase.IDLE -> "Idle"
        RobotPhase.INIT -> "Initialised: ${state.selectedOpMode.orEmpty()}"
        RobotPhase.RUNNING -> "Running: ${state.selectedOpMode.orEmpty()}"
    }
}

/** How many frames the render thread drew during the last second. */
@Composable
private fun rememberFramesPerSecond(engine: ViewportEngine): State<Int> = produceState(0, engine) {
    var previous = engine.framesDrawn()
    while (true) {
        delay(1_000)
        val now = engine.framesDrawn()
        value = (now - previous).toInt()
        previous = now
    }
}
