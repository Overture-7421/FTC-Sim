package dev.ftcsim.ui.project

import dev.ftcsim.contracts.robot.RobotPhase
import dev.ftcsim.contracts.session.SessionInfo
import dev.ftcsim.ui.practice.Hold
import dev.ftcsim.ui.practice.PracticeSession
import dev.ftcsim.ui.practice.RunState
import dev.ftcsim.ui.practice.Severity
import dev.ftcsim.ui.practice.Timing
import dev.ftcsim.ui.settings.SettingsStore
import java.nio.file.Path
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update

/** What the Robot page says about finding the project's motors. */
sealed interface ScanState {
    /** Nothing to say. */
    data object Quiet : ScanState

    /** [opMode] is being run once, initialised and stopped; [automatic] when the person chose it on an earlier run. */
    data class Scanning(val opMode: String, val automatic: Boolean) : ScanState

    /** The scan ended: [found] names were asked for that the mapping does not place, or [failure] says why nothing could be found. */
    data class Done(val opMode: String, val found: Int, val failure: String?) : ScanState
}

/**
 * Finds out which hardware the program asks for, so that the Robot page can list those names. The robot program only
 * names its hardware when an OpMode runs, so when the person picks an OpMode this runs it once the way a driver would,
 * Init and then Stop, and the simulator reports the names the mapping does not place (`SessionStatus.unmapped`).
 * The OpMode is remembered with the project, and a session that opens later (Restart code, a reset) runs the same one
 * by itself, so the names follow the code.
 *
 * It uses only the session's commands and what it reports, so it takes no time of its own: how long a scan takes is
 * how long the simulation takes to initialise the OpMode, and a scan that does not come to an end can be cancelled. The
 * OpMode picked in the top bar is left as it was.
 *
 * @param project the project the simulator runs; a session is only taken to be the project's when its program is the project's
 */
class HardwareScan(
    private val session: PracticeSession,
    private val settings: SettingsStore,
    private val project: StateFlow<OpenProject?>,
) {
    private data class Request(val opMode: String)

    private sealed interface Wait {
        data object Reached : Wait

        data object Cancelled : Wait

        data class Failed(val why: String) : Wait
    }

    private val mutableState = MutableStateFlow<ScanState>(ScanState.Quiet)
    private val requests = Channel<Request>(Channel.CONFLATED)
    private val cancels = MutableStateFlow(0)
    private var cancelsSeen = 0

    val state: StateFlow<ScanState> = mutableState.asStateFlow()

    init {
        // A scan that will run by itself holds the controls from the moment the session opens, not from the moment the scan notices it.
        session.whenOpened { info ->
            val open = project.value
            if (open != null && ours(open) && remembered(info, open.project.folder) != null) session.hold(Hold.SCANNING)
        }
    }

    /** Whether the session that was asked for runs the program of [open], which is when its hardware is the project's to find. */
    private fun ours(open: OpenProject) = open.program.toString() == session.requested?.program()

    /** The OpMode a session that opens runs by itself: the one remembered with the project, if the program still declares it. */
    private fun remembered(info: SessionInfo, folder: Path): String? {
        val names = info.opModes().map { it.name() }
        return settings.state.value.project(folder)?.scanOpMode?.takeIf { it in names }
    }

    /** Runs [opMode] to find the hardware. */
    fun scan(opMode: String) {
        requests.trySend(Request(opMode))
    }

    /** Gives up a scan that is under way. */
    fun cancel() {
        cancels.update { it + 1 }
    }

    /** Clears what a scan came to. */
    fun dismiss() {
        mutableState.value = ScanState.Quiet
    }

    /** Follows the sessions that open, for as long as the caller lets it; never returns. */
    suspend fun run() {
        combine(session.openings, project) { count, open -> count to open }
            .filter { (count, open) -> count > 0 && open != null && ours(open) }
            .distinctUntilChanged { before, after -> before.first == after.first }
            .collectLatest { (_, open) ->
                val info = session.session.value.info
                if (info != null && open != null) follow(info, open.project.folder)
            }
    }

    private suspend fun follow(info: SessionInfo, folder: Path) {
        mutableState.value = ScanState.Quiet
        val names = info.opModes().map { it.name() }
        if (names.isEmpty()) return
        val remembered = remembered(info, folder)
        var next: Request? = remembered?.let { Request(it) }
        var automatic = remembered != null
        while (true) {
            val request = next ?: requests.receive()
            scanWith(request.opMode, automatic, folder)
            automatic = false
            next = null
        }
    }

    private suspend fun scanWith(opMode: String, automatic: Boolean, folder: Path) {
        mutableState.value = ScanState.Scanning(opMode, automatic)
        // The scan presses Init and Stop itself, so the driver's buttons wait until it is over.
        val opening = session.hold(Hold.SCANNING)
        try {
            cancelsSeen = cancels.value
            val failure = scanOnce(opMode)
            if (failure == null) settings.update { it.withScanOpMode(folder, opMode) }
            val found = session.unmapped.value.size
            mutableState.value = when {
                failure == CANCELLED -> ScanState.Quiet
                failure == null && found == 0 && automatic -> ScanState.Quiet
                else -> ScanState.Done(opMode, found, failure)
            }
        } finally {
            session.release(Hold.SCANNING, opening)
        }
    }

    /** Runs [opMode] from idle to initialised and back to idle; null when that worked, else why not. */
    private suspend fun scanOnce(opMode: String): String? {
        val idle = waitFor { run, _ -> if (run.phase == RobotPhase.IDLE) Wait.Reached else null }
        if (idle is Wait.Cancelled) return CANCELLED
        if (idle is Wait.Failed) return idle.why
        val begun = session.timing.value.simTimeNanos
        session.init(opMode)
        val initialised = waitFor { run, timing ->
            when {
                run.phase == RobotPhase.INIT -> Wait.Reached
                timing.simTimeNanos - begun > BUDGET_NANOS && run.phase == RobotPhase.IDLE -> Wait.Failed(didNotStart(opMode))
                else -> null
            }
        }
        val settled = if (initialised is Wait.Reached) settle(opMode) else initialised
        val stopAt = session.timing.value.simTimeNanos
        if (session.run.value.phase != RobotPhase.IDLE) session.stop()
        val stopped = waitFor { run, timing ->
            when {
                run.phase == RobotPhase.IDLE -> Wait.Reached
                timing.simTimeNanos - stopAt > BUDGET_NANOS -> Wait.Failed("$opMode did not stop.")
                else -> null
            }
        }
        return when {
            settled is Wait.Cancelled || stopped is Wait.Cancelled -> CANCELLED
            settled is Wait.Failed -> settled.why
            stopped is Wait.Failed -> stopped.why
            else -> null
        }
    }

    /**
     * The phase is Init as soon as the OpMode's thread starts, while its code is still building the robot and asking for
     * hardware, so a Stop at once would cut the code off before most of its devices. The names are complete when no new
     * one has been asked for during [SETTLE_NANOS] of simulated time; an OpMode that ends meanwhile died.
     */
    private suspend fun settle(opMode: String): Wait {
        val reached = session.timing.value.simTimeNanos
        var names = session.unmapped.value
        var changedAt = reached
        return waitFor { run, timing ->
            val now = timing.simTimeNanos
            if (session.unmapped.value != names) {
                names = session.unmapped.value
                changedAt = now
            }
            when {
                run.phase == RobotPhase.IDLE -> Wait.Failed(didNotStart(opMode))
                now - changedAt >= SETTLE_NANOS || now - reached >= BUDGET_NANOS -> Wait.Reached
                else -> null
            }
        }
    }

    private fun didNotStart(opMode: String): String {
        val error = session.problems.value.firstOrNull { it.severity == Severity.ERROR }?.text?.lineSequence()?.firstOrNull()
            ?: thrownByTheProgram()
        return "$opMode did not initialise" + (error?.let { ": $it" } ?: ".")
    }

    /** The message of the exception the program last died of, from the console, without the log tag and the class name. */
    private fun thrownByTheProgram(): String? {
        val lines = session.console.view().lines
        val at = lines.indexOfLast { "threw an uncaught exception" in it }
        return lines.getOrNull(at + 1)?.takeIf { at >= 0 }?.let { THROWN.matchEntire(it)?.groupValues?.get(1) }?.takeIf { it.isNotBlank() }
    }

    /** Waits until [check] says something, the simulator refuses a command, or the person cancels. */
    private suspend fun waitFor(check: (RunState, Timing) -> Wait?): Wait = merge(
        cancels.filter { it != cancelsSeen }.map<Int, Wait> { Wait.Cancelled },
        session.refusals.map<String, Wait> { Wait.Failed(it) },
        combine(session.run, session.timing) { run, timing -> check(run, timing) }.filterNotNull(),
    ).first()

    private companion object {
        val THROWN = Regex("""^\w/[^:]+: (?:[\w.$]+: )?(.*)$""")

        const val CANCELLED = "cancelled"

        /** How much simulated time an OpMode gets to initialise or stop before the scan gives up on it. */
        const val BUDGET_NANOS = 10_000_000_000L

        /** How long no new name may be asked for before an initialising OpMode is taken to have asked for all of them. */
        const val SETTLE_NANOS = 2_000_000_000L
    }
}
