package dev.ftcsim.ui.project

import dev.ftcsim.ui.practice.PracticeSession
import dev.ftcsim.ui.settings.SettingsStore
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * The OpMode a project opens with: the one the person chose last, in the top bar or as the autonomous of a match, kept
 * with the project in the user's settings. A session that opens for the project (the first, a restart, a reset)
 * picks it again, if the program still has an OpMode of that name; the person's own choices are what is kept, not the
 * OpMode a session picks by itself.
 *
 * @param project the project the simulator runs; a session is only taken to be the project's when its program is the project's
 */
class OpModeMemory(
    private val session: PracticeSession,
    private val settings: SettingsStore,
    private val project: StateFlow<OpenProject?>,
) {
    /** Follows the sessions and the choices, for as long as the caller lets it; never returns. */
    suspend fun run() = coroutineScope {
        launch { restoreOnEveryOpening() }
        launch { keepEveryChoice() }
    }

    private suspend fun restoreOnEveryOpening() {
        combine(session.openings, project) { count, open -> count to open }
            .filter { (count, open) -> count > 0 && open != null && open.program.toString() == session.requested?.program() }
            .distinctUntilChanged { before, after -> before.first == after.first }
            .collect { (_, open) ->
                val remembered = settings.state.value.project(open!!.project.folder)?.opMode ?: return@collect
                session.selectOpMode(remembered, picked = false)
            }
    }

    private suspend fun keepEveryChoice() {
        session.picks.collect { name ->
            val open = project.value ?: return@collect
            if (open.program.toString() != session.requested?.program()) return@collect
            settings.update { it.withOpMode(open.project.folder, name) }
        }
    }
}
