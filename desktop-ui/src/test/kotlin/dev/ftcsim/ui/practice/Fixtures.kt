package dev.ftcsim.ui.practice

import dev.ftcsim.contracts.field.Alliance
import dev.ftcsim.contracts.field.HumanAction
import dev.ftcsim.contracts.field.Score
import dev.ftcsim.contracts.field.StartPose
import dev.ftcsim.contracts.hardware.UnmappedDevice
import dev.ftcsim.contracts.robot.GamepadState
import dev.ftcsim.contracts.robot.OpModeDescriptor
import dev.ftcsim.contracts.robot.OpModeKind
import dev.ftcsim.contracts.robot.RobotPhase
import dev.ftcsim.contracts.robot.TelemetrySnapshot
import dev.ftcsim.contracts.session.Catalogue
import dev.ftcsim.contracts.session.FieldChoice
import dev.ftcsim.contracts.session.MatchState
import dev.ftcsim.contracts.session.MatchStatus
import dev.ftcsim.contracts.session.MotorChoice
import dev.ftcsim.contracts.session.Readout
import dev.ftcsim.contracts.session.RobotInfo
import dev.ftcsim.contracts.session.RobotMapping
import dev.ftcsim.contracts.session.SessionInfo
import dev.ftcsim.contracts.session.SessionRequest
import dev.ftcsim.contracts.session.SessionStatus
import dev.ftcsim.contracts.session.SimulatorLink

/** A link that only writes down what it was asked, so tests can call the listener by hand from one thread. */
class StubLink : SimulatorLink {
    val calls: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
    val requests: MutableList<SessionRequest> = java.util.concurrent.CopyOnWriteArrayList()
    val gamepads = java.util.concurrent.ConcurrentHashMap<Int, GamepadState>()
    @Volatile var listener: SimulatorLink.Listener? = null

    override fun addListener(listener: SimulatorLink.Listener) {
        this.listener = listener
        calls += "addListener"
    }

    override fun open(request: SessionRequest) {
        requests += request
        calls += "open"
    }

    override fun init(opMode: String) {
        calls += "init $opMode"
    }

    override fun start() {
        calls += "start"
    }

    override fun stop() {
        calls += "stop"
    }

    override fun armMatch(autoOpMode: String, teleOpMode: String) {
        calls += "armMatch '$autoOpMode' '$teleOpMode'"
    }

    override fun startMatch() {
        calls += "startMatch"
    }

    override fun setGamepad(gamepad: Int, state: GamepadState) {
        gamepads[gamepad] = state
        calls += "gamepad $gamepad"
    }

    override fun humanAction(actionId: String) {
        calls += "humanAction $actionId"
    }

    override fun setPaused(paused: Boolean) {
        calls += "paused $paused"
    }

    override fun setSpeed(speed: Double) {
        calls += "speed $speed"
    }

    override fun reset() {
        calls += "reset"
    }

    override fun close() {
        calls += "close"
    }
}

object Fixtures {
    val opModes = listOf(
        OpModeDescriptor("Teleop A", "", OpModeKind.TELEOP, "team.TeleopA"),
        OpModeDescriptor("Auto B", "", OpModeKind.AUTONOMOUS, "team.AutoB"),
    )

    fun info(modes: List<OpModeDescriptor> = opModes) =
        SessionInfo(null, modes, RobotInfo("robot", 0.4, 0.4, 0.3, emptyList(), emptyList(), emptyList()))

    fun request(seed: Long = 0L, alliance: Alliance = Alliance.RED, startHeading: Double = 0.0) =
        SessionRequest(RobotMapping.empty("fake"), "", "none", alliance, seed, 0.0, 0.0, startHeading, 1.0)

    /** Two fields, one with named start poses and actions, and two motors, as a worker would list them. */
    fun catalogue() = Catalogue(
        listOf(
            FieldChoice("none", "Bare floor", emptyList(), emptyList()),
            FieldChoice(
                "decode", "DECODE",
                listOf(
                    StartPose("RED goal side", 1.2, -1.4, Math.PI / 2),
                    StartPose("BLUE goal side", -1.2, -1.4, Math.PI / 2),
                    StartPose("Centre", 0.0, 0.0, 0.0),
                ),
                listOf(HumanAction("feed", "Feed a piece")),
            ),
        ),
        listOf(MotorChoice("goBILDA-bare-6000", "Yellow Jacket"), MotorChoice("rev-hd-hex-6000", "REV HD Hex")),
    )

    fun match(state: MatchState, elapsedSeconds: Long = 0L, durationSeconds: Long = 0L) =
        MatchStatus(state, elapsedSeconds * 1_000_000_000L, durationSeconds * 1_000_000_000L)

    fun status(
        phase: RobotPhase = RobotPhase.IDLE,
        simTimeNanos: Long = 0L,
        speed: Double = 1.0,
        achievedSpeed: Double = 1.0,
        paused: Boolean = false,
        lines: List<String> = emptyList(),
        warnings: String = "",
        error: String = "",
        problems: List<String> = emptyList(),
        hardware: List<Readout> = emptyList(),
        score: Score = Score(emptyList()),
        match: MatchStatus = MatchStatus.NONE,
        unmapped: List<UnmappedDevice> = emptyList(),
    ) = SessionStatus(
        simTimeNanos, phase, TelemetrySnapshot(lines, warnings, error), score, problems, hardware, speed, achievedSpeed, paused, match,
        unmapped,
    )
}
