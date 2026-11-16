package dev.ftcsim.ui.practice

import dev.ftcsim.contracts.robot.OpModeDescriptor
import dev.ftcsim.contracts.robot.OpModeKind
import dev.ftcsim.contracts.robot.RobotPhase
import dev.ftcsim.contracts.session.MatchState
import dev.ftcsim.contracts.session.MatchStatus
import dev.ftcsim.contracts.session.SessionInfo

/** Whether the window has a link to a simulator, which the application opens off the UI thread. */
sealed interface LinkState {
    /** The link is being opened. */
    data object Connecting : LinkState

    /** There is a link; what it can do is told by its events. */
    data object Connected : LinkState

    /** The link could not be opened; [message] is the application's own words for why. */
    data class Unavailable(val message: String) : LinkState
}

/** How far the session the Practice screen asked for has got. */
enum class Connection {
    /** No session has been asked for yet; the screen offers to set one up. */
    NONE,

    /** The session has been asked for and has not opened: the link is being opened, or has not answered. */
    OPENING,

    /** The session is running; [SessionState.info] is known. */
    OPEN,

    /** The session or the worker could not go on. */
    FAILED,
}

/** The facts about the session that change rarely: whether it is open, what it offers and what the driver picked. */
data class SessionState(
    val connection: Connection = Connection.NONE,
    val info: SessionInfo? = null,
    val selectedOpMode: String? = null,
) {
    val opModes: List<OpModeDescriptor> get() = info?.opModes().orEmpty()
}

/** What the run is doing, from the latest status: it changes when the driver presses a button, not 30 times a second. */
data class RunState(
    val phase: RobotPhase = RobotPhase.IDLE,
    val paused: Boolean = false,
    val speed: Double = 1.0,
)

/** The two numbers of the status strip that change with every status. */
data class Timing(val simTimeNanos: Long = 0L, val achievedSpeed: Double = 0.0) {
    /** Whether the machine keeps up with the speed that was asked for, within 5 %. */
    fun keepsUp(requested: Double): Boolean = achievedSpeed >= requested * KEEP_UP_FRACTION

    private companion object {
        const val KEEP_UP_FRACTION = 0.95
    }
}

/**
 * Where a timed match is, as the status reports it and shown to people: the state and, in a period that has a length,
 * the whole seconds left of it. The simulator keeps the time in simulated time, so pausing or slowing a run slows
 * the countdown with it; nothing here has a clock. Equal values are not announced again, which is what lets the
 * 30 Hz status change the screen once a second.
 */
data class MatchDisplay(
    val state: MatchState = MatchState.NONE,
    val secondsLeft: Long = 0L,
    val periodSeconds: Long = 0L,
) {
    /** In one of the three periods, which have a length. */
    val timed: Boolean get() = state == MatchState.AUTONOMOUS || state == MatchState.TRANSITION || state == MatchState.TELEOP

    /** Armed or under way: the match is what the robot is doing, and free practice waits. */
    val inProgress: Boolean get() = state == MatchState.ARMED || timed

    /** How much of the period has passed, from 0 to 1. */
    val fraction: Float
        get() = if (periodSeconds <= 0L) 0f else (1f - secondsLeft.toFloat() / periodSeconds).coerceIn(0f, 1f)

    companion object {
        val NONE = MatchDisplay()

        private const val NANOS = 1_000_000_000L

        fun of(status: MatchStatus): MatchDisplay {
            val timed = status.state() == MatchState.AUTONOMOUS || status.state() == MatchState.TRANSITION ||
                status.state() == MatchState.TELEOP
            if (!timed) return MatchDisplay(status.state())
            val left = (status.phaseDurationNanos() - status.phaseElapsedNanos()).coerceAtLeast(0L)
            return MatchDisplay(
                status.state(),
                secondsLeft = (left + NANOS - 1) / NANOS,
                periodSeconds = maxOf(1L, (status.phaseDurationNanos() + NANOS / 2) / NANOS),
            )
        }
    }
}

/** The OpModes a match was armed with, as the window chose them; the simulator does not say them back. */
data class MatchPlan(val autonomous: String?, val teleOp: String) {
    companion object {
        /**
         * What a match would most likely be played with: the OpMode that is picked in the top bar if it fits a period,
         * otherwise the first OpMode of each kind; null when there is no teleop OpMode, which a match needs. A program
         * with no autonomous OpMode gives a match with no autonomous period.
         */
        fun suggest(opModes: List<OpModeDescriptor>, picked: String?): MatchPlan? {
            val teleOps = opModes.filter { it.kind() == OpModeKind.TELEOP }
            val autonomous = opModes.filter { it.kind() == OpModeKind.AUTONOMOUS }
            val teleOp = teleOps.firstOrNull { it.name() == picked } ?: teleOps.firstOrNull() ?: return null
            val start = autonomous.firstOrNull { it.name() == picked } ?: autonomous.firstOrNull()
            return MatchPlan(start?.name(), teleOp.name())
        }
    }
}

/**
 * Which controls of the Practice screen do something right now. Init and Start and Stop follow the phase of the
 * OpMode the way the Driver Station's do: Init only from idle with an OpMode picked, Start only after Init, Stop any
 * time after Init; the OpMode cannot be changed while one is initialised or running. While a match is armed or under
 * way those belong to the match, whose own controls take their place: Start match only once it is armed, and Stop
 * (which gives up an armed match and ends one under way) until it is over.
 *
 * @property matchReason why a match cannot be armed now, in words, or null when it can
 */
data class Controls(
    val selectOpMode: Boolean,
    val init: Boolean,
    val start: Boolean,
    val stop: Boolean,
    val pause: Boolean,
    val reset: Boolean,
    val restart: Boolean,
    val matchReason: String?,
    val startMatch: Boolean,
    val stopMatch: Boolean,
) {
    val arm: Boolean get() = matchReason == null

    companion object {
        /**
         * @param building whether the project's code is being built, which a restart waits for; the session goes on meanwhile
         */
        fun of(session: SessionState, run: RunState, match: MatchDisplay = MatchDisplay.NONE, building: Boolean = false): Controls {
            val open = session.connection == Connection.OPEN && session.info != null
            val idle = run.phase == RobotPhase.IDLE
            val matching = match.inProgress
            val picked = session.selectedOpMode != null && session.opModes.any { it.name() == session.selectedOpMode }
            return Controls(
                selectOpMode = open && idle && !matching && session.opModes.isNotEmpty(),
                init = open && idle && !matching && picked,
                start = open && !matching && run.phase == RobotPhase.INIT,
                stop = open && !matching && !idle,
                pause = open,
                reset = open,
                // A session that failed is what a restart is for: the code may be what was wrong.
                restart = (open || session.connection == Connection.FAILED) && !building,
                matchReason = when {
                    !open -> "No session"
                    match.state == MatchState.ENDED -> "Reset the world to play another match"
                    !idle -> "Stop the OpMode first"
                    session.opModes.none { it.kind() == OpModeKind.TELEOP } -> "No TeleOp OpMode"
                    else -> null
                },
                startMatch = open && match.state == MatchState.ARMED,
                stopMatch = open && matching,
            )
        }
    }
}
