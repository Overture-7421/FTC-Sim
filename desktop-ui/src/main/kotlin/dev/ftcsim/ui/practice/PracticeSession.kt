package dev.ftcsim.ui.practice

import dev.ftcsim.contracts.field.Score
import dev.ftcsim.contracts.robot.GamepadState
import dev.ftcsim.contracts.robot.TelemetrySnapshot
import dev.ftcsim.contracts.session.Catalogue
import dev.ftcsim.contracts.session.Readout
import dev.ftcsim.contracts.session.SessionInfo
import dev.ftcsim.contracts.session.SessionRequest
import dev.ftcsim.contracts.session.SessionStatus
import dev.ftcsim.contracts.session.SimulatorLink
import dev.ftcsim.contracts.snapshot.WorldSnapshot
import dev.ftcsim.ui.render.OrbitCamera
import dev.ftcsim.ui.scene.SceneModel
import dev.ftcsim.ui.scene.ViewportFeed
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The practice run as the UI sees it: it asks a [SimulatorLink] to open the session a person chose, turns what comes
 * back into state flows that the screens collect, forwards the snapshots to the viewport, and passes the driver's
 * commands and gamepads on. The link calls the [SimulatorLink.Listener] methods from its own thread, one at a time, so
 * they do nothing but store; every state is split by how often it changes, so that the 30 Hz status only wakes the
 * parts of the screen that show something that changed.
 *
 * It lives as long as the application, not as long as the Practice screen is shown, so leaving the screen does not
 * stop the run, and it exists before the link does: the application opens the link off the UI thread and hands it over
 * with [attach]. A session asked for before then is opened as soon as there is a link. A link that is replaced is
 * dropped with [detach], and nothing it says afterwards is heard. No session exists until [open] is called.
 *
 * A [SimulatorLink.Listener.refused] command is only announced on [refusals]; the session goes on as it was. Only
 * `failed` ends a session.
 */
class PracticeSession(
    private val feed: ViewportFeed,
    private val camera: OrbitCamera,
) {
    private val mutableSession = MutableStateFlow(SessionState())
    private val mutableRun = MutableStateFlow(RunState())
    private val mutableTiming = MutableStateFlow(Timing())
    private val mutableTelemetry = MutableStateFlow(TelemetrySnapshot(emptyList(), "", ""))
    private val mutableHardware = MutableStateFlow<List<Readout>>(emptyList())
    private val mutableScore = MutableStateFlow(Score(emptyList()))
    private val mutableProblems = MutableStateFlow<List<Problem>>(emptyList())
    private val mutableMatch = MutableStateFlow(MatchDisplay.NONE)
    private val mutablePlan = MutableStateFlow<MatchPlan?>(null)
    private val mutableCatalogue = MutableStateFlow<Catalogue?>(null)
    private val mutableLink = MutableStateFlow<LinkState>(LinkState.Connecting)
    private val mutableRefusals = MutableSharedFlow<String>(extraBufferCapacity = REFUSALS_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val lock = Any()
    private val gamepads = arrayOf(GamepadState.NEUTRAL, GamepadState.NEUTRAL)
    private var simulator: SimulatorLink? = null
    private var request: SessionRequest? = null
    private var latestStatus: SessionStatus? = null
    private var failure: String? = null
    private var shownSimTime = -TIMING_PERIOD_NANOS
    @Volatile private var generation = 0L

    val session: StateFlow<SessionState> = mutableSession.asStateFlow()
    val run: StateFlow<RunState> = mutableRun.asStateFlow()
    val timing: StateFlow<Timing> = mutableTiming.asStateFlow()
    val telemetry: StateFlow<TelemetrySnapshot> = mutableTelemetry.asStateFlow()
    val hardware: StateFlow<List<Readout>> = mutableHardware.asStateFlow()
    val score: StateFlow<Score> = mutableScore.asStateFlow()
    val problems: StateFlow<List<Problem>> = mutableProblems.asStateFlow()

    /** Where the timed match is, from the status alone. */
    val match: StateFlow<MatchDisplay> = mutableMatch.asStateFlow()

    /** The OpModes the latest match was armed with, or null; only meaningful while the match is not [MatchDisplay.NONE]. */
    val matchPlan: StateFlow<MatchPlan?> = mutablePlan.asStateFlow()

    /** What can be asked for, once the link has told; null until then. Kept when the link is replaced. */
    val catalogue: StateFlow<Catalogue?> = mutableCatalogue.asStateFlow()

    /** Whether there is a link to a simulator: being opened, there, or not to be had. */
    val link: StateFlow<LinkState> = mutableLink.asStateFlow()

    /** Commands the link refused, in words for a person; the session is as it was. A slow collector loses the oldest. */
    val refusals: SharedFlow<String> = mutableRefusals.asSharedFlow()

    // The link ---------------------------------------------------------------------------------------------------

    /** The link is being opened or opened again. */
    fun connecting() {
        mutableLink.value = LinkState.Connecting
    }

    /** The link could not be opened, with the application's words for why. */
    fun unavailable(message: String) {
        mutableLink.value = LinkState.Unavailable(message)
    }

    /**
     * Starts using [newLink]; a session asked for while there was none is opened on it now. Commands only queue on a
     * link, so they are given under the lock, which keeps the order of the requests the same as the order of asking.
     * Returns the link that was in use before, if any, which is no longer heard and is for the caller to close.
     */
    fun attach(newLink: SimulatorLink): SimulatorLink? = synchronized(lock) {
        generation++
        newLink.addListener(Events(generation))
        val replaced = simulator
        simulator = newLink
        mutableLink.value = LinkState.Connected
        request?.takeIf { mutableSession.value.connection == Connection.OPENING }?.let(newLink::open)
        replaced
    }

    /** Stops using the link, which is being replaced or closed; what it says from now on is ignored. Returns it. */
    fun detach(): SimulatorLink? = synchronized(lock) {
        generation++
        val old = simulator
        simulator = null
        mutableLink.value = LinkState.Connecting
        old
    }

    /**
     * Drops the link and, if a session was asked for, goes back to waiting for it, for a link that is made anew. A link
     * that failed cannot be asked again, and a fresh one is the cleanest start. Returns the old link for the caller to close.
     */
    fun restart(): SimulatorLink? {
        val old = detach()
        request?.let(::open)
        return old
    }

    /** Asks for the session in [request], replacing the one that is open, if any; returns at once. */
    fun open(request: SessionRequest) {
        synchronized(lock) {
            this.request = request
            failure = null
            latestStatus = null
            mutableSession.value = SessionState(Connection.OPENING)
            mutableMatch.value = MatchDisplay.NONE
            mutablePlan.value = null
            refreshProblems()
            simulator?.open(request)
        }
        camera.setDriverHeading(OrbitCamera.driverHeadingFor(request.startHeading()))
    }

    // The link's thread ------------------------------------------------------------------------------------------

    /** Hears one link, and only for as long as it is the one in use. */
    private inner class Events(private val mine: Long) : SimulatorLink.Listener {
        private fun current() = mine == generation

        override fun opened(info: SessionInfo) {
            if (current()) onOpened(info)
        }

        override fun snapshot(snapshot: WorldSnapshot) {
            if (current()) feed.publish(snapshot)
        }

        override fun status(status: SessionStatus) {
            if (current()) onStatus(status)
        }

        override fun failed(message: String) {
            if (current()) onFailed(message)
        }

        override fun refused(message: String) {
            if (current()) mutableRefusals.tryEmit(message)
        }

        override fun catalogue(catalogue: Catalogue) {
            if (current()) mutableCatalogue.value = catalogue
        }
    }

    /** Called for the first session and again after every [reset], which is a new session with the same request. */
    private fun onOpened(info: SessionInfo) {
        val alliance = synchronized(lock) { request?.alliance() }
        feed.show(SceneModel.of(info.field(), info.robot(), SceneModel.robotRgb(alliance)))
        synchronized(lock) {
            failure = null
            mutableSession.update {
                val keep = it.selectedOpMode?.takeIf { name -> info.opModes().any { mode -> mode.name() == name } }
                SessionState(Connection.OPEN, info, keep ?: info.opModes().firstOrNull()?.name())
            }
            mutableMatch.value = MatchDisplay.NONE
            mutablePlan.value = null
            refreshProblems()
            // A new session starts with both gamepads untouched; tell it what they are doing now.
            for (index in gamepads.indices) {
                simulator?.setGamepad(index + 1, gamepads[index])
            }
        }
    }

    private fun onStatus(status: SessionStatus) {
        mutableRun.value = RunState(status.phase(), status.paused(), status.speed())
        val simTime = status.simTimeNanos()
        // The clock is for reading, not for measuring: ten updates a second are plenty, and every update redraws.
        if (simTime < shownSimTime || simTime - shownSimTime >= TIMING_PERIOD_NANOS) {
            shownSimTime = simTime
            mutableTiming.value = Timing(simTime, status.achievedSpeed())
        }
        mutableMatch.value = MatchDisplay.of(status.match())
        mutableTelemetry.value = status.telemetry()
        mutableHardware.value = status.hardware()
        mutableScore.value = status.score()
        synchronized(lock) {
            latestStatus = status
            refreshProblems()
        }
    }

    private fun onFailed(message: String) {
        synchronized(lock) {
            failure = message
            mutableSession.update { it.copy(connection = Connection.FAILED) }
            refreshProblems()
        }
    }

    private fun refreshProblems() {
        mutableProblems.value = Problems.of(latestStatus, failure)
    }

    // The driver --------------------------------------------------------------------------------------------------

    private inline fun send(command: (SimulatorLink) -> Unit) {
        val current = synchronized(lock) { simulator }
        if (current != null) command(current)
    }

    fun selectOpMode(name: String) {
        mutableSession.update { if (it.opModes.any { mode -> mode.name() == name }) it.copy(selectedOpMode = name) else it }
    }

    fun init() {
        mutableSession.value.selectedOpMode?.let { name -> send { it.init(name) } }
    }

    fun start() = send { it.start() }

    /** Stops the OpMode; during a match it gives up an armed one and ends one that is under way. */
    fun stop() = send { it.stop() }

    /**
     * Prepares a timed match from idle with the OpModes the person chose; the simulator times it. Whether it was
     * accepted is told by the match state of the status, or by a refusal.
     *
     * @param autonomous the autonomous OpMode, or null for a match without an autonomous period
     */
    fun armMatch(autonomous: String?, teleOp: String) {
        mutablePlan.value = MatchPlan(autonomous, teleOp)
        send { it.armMatch(autonomous.orEmpty(), teleOp) }
    }

    fun startMatch() = send { it.startMatch() }

    fun togglePause() = send { it.setPaused(!mutableRun.value.paused) }

    fun setSpeed(speed: Double) = send { it.setSpeed(speed) }

    /** Starts the same session over; the link answers with `opened` again, so the screen shows it opening meanwhile. */
    fun reset() {
        mutableSession.update { if (it.connection == Connection.OPEN) it.copy(connection = Connection.OPENING) else it }
        send { it.reset() }
    }

    fun humanAction(id: String) = send { it.humanAction(id) }

    /** What gamepad 1 or 2 reports from now on; remembered so that a session that opens later starts right. */
    fun setGamepad(gamepad: Int, state: GamepadState) {
        synchronized(lock) {
            gamepads[gamepad - 1] = state
            if (mutableSession.value.connection == Connection.OPEN) {
                simulator?.setGamepad(gamepad, state)
            }
        }
    }

    private companion object {
        const val TIMING_PERIOD_NANOS = 100_000_000L
        const val REFUSALS_BUFFER = 16
    }
}
