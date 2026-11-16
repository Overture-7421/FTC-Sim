package dev.ftcsim.ui.project

import dev.ftcsim.contracts.field.Alliance
import dev.ftcsim.contracts.session.RobotMapping
import dev.ftcsim.contracts.session.SessionRequest
import dev.ftcsim.ui.AppServices
import dev.ftcsim.ui.host.ProgramPreparer.Prepared
import dev.ftcsim.ui.host.ProjectMappings
import dev.ftcsim.ui.host.RunDefaults
import dev.ftcsim.ui.host.StartPosition
import dev.ftcsim.ui.nav.Destination
import dev.ftcsim.ui.nav.Navigator
import dev.ftcsim.ui.practice.CodeRestart
import dev.ftcsim.ui.practice.LinkState
import dev.ftcsim.ui.practice.Problem
import dev.ftcsim.ui.practice.Severity
import dev.ftcsim.ui.prepare.PrepareModel
import dev.ftcsim.ui.prepare.PrepareState
import dev.ftcsim.ui.prepare.Project
import dev.ftcsim.ui.settings.RecentProject
import dev.ftcsim.ui.settings.copy
import dev.ftcsim.ui.setup.SetupInput
import dev.ftcsim.ui.setup.SetupModel
import dev.ftcsim.ui.setup.SetupRules
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** The project the simulator is running: where it is, the program built from it and the robot file it runs with, if it has one. */
data class OpenProject(val project: Project, val program: Path, val robotFile: Path?, val keptByApp: Boolean) {
    /** Whether the project has no usable `ftcsim.json`, so that no motor is mapped on its robot. */
    val generic: Boolean get() = robotFile == null
}

/** What the runner is doing besides running the project that is open. */
sealed interface ProjectActivity {
    /** Nothing: the project that is open runs, or none is. */
    data object Idle : ProjectActivity

    /** The project is being built; the steps are the [PrepareModel]'s. [restarting] when it is a restart of the code that runs. */
    data class Building(val project: Project, val restarting: Boolean) : ProjectActivity

    /** The project is built and waits for the person to choose its game, which is asked once for each project. */
    data class NeedsGame(val project: Project, val program: Prepared, val stored: ProjectMappings.Found?) : ProjectActivity

    /** Opening the project did not work; [message] says why in words. */
    data class Failed(val project: Project, val message: String) : ProjectActivity
}

/** The project that [this] is about, or null for [ProjectActivity.Idle]. */
val ProjectActivity.project: Project?
    get() = when (this) {
        ProjectActivity.Idle -> null
        is ProjectActivity.Building -> project
        is ProjectActivity.NeedsGame -> project
        is ProjectActivity.Failed -> project
    }

/** What the application knows of the robot description of the project it runs. */
sealed interface RobotState {
    val project: Project

    /** The project has an `ftcsim.json`; [keptByApp] when it is FTC-Sim's own copy because the project's folder cannot be written to. */
    data class FromFile(override val project: Project, val file: Path, val keptByApp: Boolean) : RobotState

    /**
     * The project has no usable `ftcsim.json`: its robot has no motors mapped, and every motor the code asks for is unmapped.
     * [problem] says why a file that is there cannot be used, which the next save keeps as `ftcsim.old.json`.
     */
    data class Undescribed(override val project: Project, val problem: String? = null) : RobotState
}

/** Something short for the person to see once, as a snackbar; [showProblems] adds a way to the Problems tab. */
data class RunnerMessage(val text: String, val showProblems: Boolean = false)

/**
 * Opens projects and restarts their code: the one place that knows the way from a project folder to a session. Opening
 * builds the project (or reuses the program of the same source). When the build is done a project that has never run
 * here waits for the person to choose its game; otherwise the session starts by itself with what the settings form
 * holds. A project without `ftcsim.json` runs a robot with no motors mapped, and the Robot screen is shown so that it can be mapped.
 * Restarting does the same from the session that is on screen, keeping what it was started with, and never asks the
 * person anything: the old session goes on until the new one is ready, and a build that fails leaves it as it was.
 *
 * Nothing here watches anything. A build happens when the person asks for it, and the preparer's own check of the
 * source's hash is what tells a restart that needs a build from one that does not.
 *
 * It is called from the UI thread, the preparer's thread and its own [io] executor, which has several threads, so every
 * state is a flow that is only replaced, an event of a build that is no longer the current one is ignored, and the end of
 * a build waits for the form to have been filled before it uses it.
 *
 * @param executor where the disk is read and written, which is never the UI thread; work given to it after it was shut
 *     down, because the application is closing, is dropped
 */
class ProjectRunner(
    private val services: AppServices,
    private val prepare: PrepareModel,
    private val setup: SetupModel,
    private val navigator: Navigator,
    executor: Executor,
) : CodeRestart {
    private val io = Executor { task ->
        try {
            executor.execute(task)
        } catch (closing: RejectedExecutionException) {
            // The application is closing; there is nobody to tell.
        }
    }
    private val lock = Any()
    private val mutableOpen = MutableStateFlow<OpenProject?>(null)
    private val mutableActivity = MutableStateFlow<ProjectActivity>(ProjectActivity.Idle)
    private val mutableRobot = MutableStateFlow<RobotState?>(null)
    private val mutablePositions = MutableStateFlow<List<StartPosition>>(emptyList())
    private val mutableProblems = MutableStateFlow<List<Problem>>(emptyList())
    private val mutableMessages = MutableSharedFlow<RunnerMessage>(extraBufferCapacity = 16)
    private var buildProblems = emptyList<Problem>()
    private var robotProblem: Problem? = null
    private var token = 0L
    private var resuming = false

    /** The project being opened is the one from the last run of the application, not one the person chose now. */
    @Volatile private var atStart = false
    /** Ends when the form has been filled for the project that is being opened; the end of its build waits for it. */
    @Volatile private var formReady: CompletableFuture<Void> = CompletableFuture.completedFuture(null)

    /** The project the simulator was last started for, or null before the first. */
    val open: StateFlow<OpenProject?> = mutableOpen.asStateFlow()

    override val activity: StateFlow<ProjectActivity> = mutableActivity.asStateFlow()

    override val buildState: StateFlow<PrepareState> get() = prepare.state

    /** What the build and the robot file say that the person should know, next to what the robot program reports. */
    override val problems: StateFlow<List<Problem>> = mutableProblems.asStateFlow()

    /** The robot description of the project, null before one is opened. */
    val robot: StateFlow<RobotState?> = mutableRobot.asStateFlow()

    /** The start positions the team named in the open project's `ftcsim.json`, for every field; empty when it has none or no file. */
    val startPositions: StateFlow<List<StartPosition>> = mutablePositions.asStateFlow()

    /**
     * Writes [positions] into the open project's `ftcsim.json`, where teammates get them too, and shows them at once. Says why
     * when that cannot be done; the positions stay as they were.
     */
    fun saveStartPositions(positions: List<StartPosition>) {
        val current = mutableOpen.value ?: return
        io.execute {
            try {
                services.mappings.saveStartPositions(current.project.folder, positions)
                mutablePositions.value = positions.toList()
            } catch (failure: IOException) {
                announce("Could not save: ${failure.message}")
            }
        }
    }

    /** Short things for the person to see once. */
    val messages: SharedFlow<RunnerMessage> = mutableMessages.asSharedFlow()

    /** Says [text] once, as a snackbar. */
    fun announce(text: String, showProblems: Boolean = false) {
        mutableMessages.tryEmit(RunnerMessage(text, showProblems))
    }

    /**
     * Opens [project]: shows the build, builds the project and starts the session when it is built and its settings are
     * ready. Refused, with false, while a project is being built.
     */
    fun open(project: Project): Boolean {
        atStart = false
        return start(project, restarting = false, retried = false)
    }

    /**
     * Opens [project], the one that was open when the application last closed. Like [open], except that a project that is
     * no longer set up for the simulator (no usable `ftcsim.json`, and not skipped) is left on the Projects screen, which
     * says so, instead of leading to the Robot page: the person did not ask for it this time.
     */
    fun openAtStart(project: Project): Boolean {
        atStart = true
        val accepted = start(project, restarting = false, retried = false)
        if (!accepted) atStart = false
        return accepted
    }

    /**
     * Builds what is on disk now and starts the session again with it, with the field, alliance, seed, start pose and
     * speed of the session that is on screen. False, doing nothing, if no project is open or one is being built.
     */
    override fun restart(): Boolean {
        val current = mutableOpen.value ?: return false
        return start(current.project, restarting = true, retried = false)
    }

    /** Stops the build that is under way, if there is one; the session that runs is not touched. */
    override fun cancel() {
        if (mutableActivity.value is ProjectActivity.Building) prepare.cancel()
    }

    /** The person chose the game of a built project: starts the session. */
    fun continueWithGame() {
        val waiting = mutableActivity.value as? ProjectActivity.NeedsGame ?: return
        val rules = rules() ?: return
        if (!setup.ready(rules)) return
        val mine = synchronized(lock) { token }
        io.execute { resume(waiting, mine) }
    }

    /**
     * Starts the session the form describes, replacing the one that is open, with the open project's program and without
     * building. The seed of the run on screen is kept when the seed is random and the field is the same, so that a change
     * of alliance or start pose is the only difference. False, doing nothing, while a build is under way or the form is not right.
     */
    fun apply(): Boolean {
        val rules = rules() ?: return false
        if (!setup.ready(rules)) return false
        val current = mutableOpen.value ?: return false
        if (mutableActivity.value is ProjectActivity.Building) return false
        val input = setup.input
        io.execute {
            var mapping = RobotMapping.empty(current.project.name)
            var file = current.robotFile
            var kept = current.keptByApp
            try {
                val found = services.mappings.find(current.project.folder).orElse(null)
                mapping = found?.mapping() ?: mapping
                file = found?.takeIf { it.usable() }?.file()
                kept = found?.takeIf { it.usable() }?.keptByApp() ?: false
            } catch (failure: IOException) {
                // The mapping as it was is still the one that runs; the Problems tab says what the file does not.
            }
            val sameSeed = services.session.requested?.takeIf { it.field() == input.field }?.seed()
            val request = setup.request(rules, current.program.toString(), mapping, sameSeed) ?: return@execute
            services.openSession(request)
            mutableOpen.value = current.copy(robotFile = file, keptByApp = kept)
            remember(current.project, input)
            navigator.go(Destination.PRACTICE)
        }
        return true
    }

    /** Writes how the settings form starts a run into the project's `ftcsim.json`, so that teammates start the same way. */
    fun saveDefaults() {
        val current = mutableOpen.value ?: return
        val rules = rules() ?: return
        val input = setup.input
        io.execute {
            try {
                val pose = input.startPose?.takeIf { rules.startPoses(input.field, input.alliance).any { pose -> pose.name() == it } }
                services.mappings.saveRunDefaults(current.project.folder, RunDefaults(input.field, input.alliance, pose))
                announce("Saved to ftcsim.json")
            } catch (failure: IOException) {
                announce("Could not save: ${failure.message}")
            }
        }
    }

    /**
     * The person finished the Robot page: writes [mapping] as the project's `ftcsim.json` (a file this version cannot read
     * is kept as `ftcsim.old.json`), and restarts the code with it, which shows the Simulator.
     */
    fun saveRobot(mapping: RobotMapping) {
        val current = mutableOpen.value ?: return
        io.execute {
            try {
                services.mappings.save(current.project.folder, mapping)
                announce("Saved ftcsim.json")
                navigator.go(Destination.PRACTICE)
                restart()
            } catch (failure: IOException) {
                announce("Could not save: ${failure.message}")
            }
        }
    }

    /** The person leaves the robot page without describing the robot: the robot stays undescribed, and the project is not asked again. */
    fun skipRobot() {
        val current = mutableOpen.value ?: return
        services.settings.update { it.withRobotSkipped(current.project.folder, true) }
        navigator.go(Destination.PRACTICE)
    }

    override fun dismissFailure() {
        mutableActivity.update { if (it is ProjectActivity.Failed) ProjectActivity.Idle else it }
    }

    private fun start(project: Project, restarting: Boolean, retried: Boolean): Boolean {
        val mine: Long
        synchronized(lock) {
            if (mutableActivity.value is ProjectActivity.Building) return false
            mine = ++token
            mutableActivity.value = ProjectActivity.Building(project, restarting)
            if (!restarting && !retried) {
                formReady = CompletableFuture()
                mutableRobot.value = null
                mutablePositions.value = emptyList()
            }
        }
        // A restart is done where the person is.
        if (!restarting) navigator.go(Destination.PROJECTS)
        val ready = formReady
        val accepted = prepare.prepareAndThen(project) { end -> built(project, end, restarting, retried, mine) }
        if (!accepted) {
            synchronized(lock) { if (token == mine) mutableActivity.value = ProjectActivity.Idle }
            ready.complete(null)
        } else if (!restarting && !retried) {
            io.execute {
                try {
                    prepareForm(project, mine)
                } finally {
                    ready.complete(null)
                }
            }
        }
        return accepted
    }

    /**
     * Shows what can be settled before the build is done: whether the project has an `ftcsim.json`, and the settings
     * form, filled with what the project was last run with, else what its robot file says, else the defaults.
     */
    private fun prepareForm(project: Project, mine: Long) {
        val stored = try {
            services.mappings.find(project.folder).orElse(null)
        } catch (unreadable: IOException) {
            null // the end of the build says that the file cannot be read
        }
        val known = rules()
        if (known != null) {
            // The catalogue is already here: fill the form before the page shows the project, so that nothing the person
            // does next can be overwritten by it.
            synchronized(lock) { if (token != mine) return }
            openInput(project, stored?.run(), known)
        }
        synchronized(lock) {
            if (token != mine) return
            showRobot(project, stored)
        }
        if (known != null) return
        val rules = awaitRules() ?: return
        synchronized(lock) { if (token != mine) return }
        openInput(project, stored?.run(), rules)
    }

    private fun showRobot(project: Project, stored: ProjectMappings.Found?) {
        mutableRobot.value = robotState(project, stored)
        mutablePositions.value = if (stored?.usable() == true) stored.startPositions() else emptyList()
    }

    private fun robotState(project: Project, stored: ProjectMappings.Found?): RobotState = when {
        stored == null -> RobotState.Undescribed(project)
        stored.usable() -> RobotState.FromFile(project, stored.file(), stored.keptByApp())
        else -> RobotState.Undescribed(project, stored.problem())
    }

    private fun built(project: Project, end: PrepareState?, restarting: Boolean, retried: Boolean, mine: Long) {
        synchronized(lock) { if (token != mine) return }
        when {
            end == null -> mutableActivity.value = ProjectActivity.Idle
            end is PrepareState.Failed && end.cancelled -> mutableActivity.value = ProjectActivity.Idle
            // The person kept editing while the project was copied; once more, with what is there now.
            end is PrepareState.Failed && end.failure.code() == ORIGINAL_CHANGED && !retried -> {
                mutableActivity.value = ProjectActivity.Idle
                start(project, restarting, retried = true)
            }
            end is PrepareState.Failed -> {
                if (restarting) {
                    val details = end.failure.details().joinToString("\n")
                    setBuildProblems(listOf(Problem(Severity.ERROR, "Build", listOf(end.failure.message(), details).filter { it.isNotBlank() }.joinToString("\n"), end.failure.log())))
                    mutableActivity.value = ProjectActivity.Idle
                    announce("Build failed", showProblems = true)
                } else {
                    mutableActivity.value = ProjectActivity.Failed(project, end.failure.message())
                }
            }
            end is PrepareState.Finished -> {
                setBuildProblems(end.program.warnings().map { Problem(Severity.WARNING, "Build", it) })
                io.execute { found(project, end.program, restarting, mine) }
            }
        }
    }

    private fun found(project: Project, program: Prepared, restarting: Boolean, mine: Long) {
        if (!restarting) {
            try {
                formReady.get(FORM_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            } catch (late: Exception) {
                // The form could not be filled in time; it is used as it is.
            }
        }
        synchronized(lock) { if (token != mine) return }
        val stored = try {
            services.mappings.find(project.folder).orElse(null)
        } catch (failure: IOException) {
            fail(project, "Could not read ftcsim.json: ${failure.message}", restarting, mine)
            return
        }
        if (!restarting && asksGame(project, stored)) {
            val rules = awaitRules()
            if (rules == null) {
                fail(project, UNAVAILABLE, restarting, mine)
                return
            }
            newestGame(rules)?.let { setup.chooseField(rules, it) }
            synchronized(lock) { if (token == mine) mutableActivity.value = ProjectActivity.NeedsGame(project, program, stored) }
            atStart = false
            return
        }
        proceed(project, program, stored, restarting, mine)
    }

    /** A project that never ran here, and whose robot file does not name a game, is asked which game to play. */
    private fun asksGame(project: Project, stored: ProjectMappings.Found?): Boolean =
        services.settings.state.value.project(project.folder)?.run == null && stored?.run()?.field().isNullOrBlank()

    /** The most recent game on offer, which is the one a person is most likely to practise. */
    private fun newestGame(rules: SetupRules): String? = rules.fields.lastOrNull { it.startPoses().isNotEmpty() }?.id()

    /**
     * Goes on with a built project that waited for its game. Does nothing if the project no longer waits or is already
     * being gone on with, so that two actions at once start one session.
     */
    private fun resume(waiting: ProjectActivity.NeedsGame, mine: Long) {
        synchronized(lock) {
            if (mutableActivity.value != waiting || resuming) return
            resuming = true
        }
        try {
            proceed(waiting.project, waiting.program, waiting.stored, restarting = false, mine = mine)
        } finally {
            synchronized(lock) { resuming = false }
        }
    }

    /** Starts the session for a built project whose stored robot is [stored], or the robot with no motors mapped when it is null or unusable. */
    private fun proceed(project: Project, program: Prepared, stored: ProjectMappings.Found?, restarting: Boolean, mine: Long) {
        val rules = awaitRules()
        if (rules == null) {
            fail(project, UNAVAILABLE, restarting, mine)
            return
        }
        val mapping = stored?.mapping() ?: RobotMapping.empty(project.name)
        val usable = stored?.takeIf { it.usable() }
        val folder = program.folder().toString()
        val sameRun = if (restarting) restartRequest(folder, mapping) else null
        val chosen = sameRun ?: setup.request(rules, folder, mapping)
        if (chosen == null) {
            fail(project, "The setup is not valid", restarting, mine)
            return
        }
        val input = if (sameRun == null) setup.input else null
        synchronized(lock) { if (token != mine) return }
        services.openSession(chosen)
        mutableOpen.value = OpenProject(project, program.folder(), usable?.file(), usable?.keptByApp() ?: false)
        mutableRobot.value = robotState(project, stored)
        mutablePositions.value = if (usable != null) usable.startPositions() else emptyList()
        remember(project, input)
        synchronized(lock) {
            robotProblem = when {
                usable != null -> null
                stored != null -> Problem(Severity.WARNING, "Robot", stored.problem())
                else -> Problem(Severity.WARNING, "Robot", "No ftcsim.json: no motors are mapped")
            }
            publishProblems()
        }
        mutableActivity.value = ProjectActivity.Idle
        val skipped = services.settings.state.value.project(project.folder)?.robotSkipped == true
        val atStartNow = atStart
        atStart = false
        if (!restarting && navigator.state.value.current == Destination.PROJECTS) {
            val needsRobot = usable == null && !skipped
            if (needsRobot && atStartNow) {
                announce("${project.name} is not set up for the simulator")
            } else {
                navigator.go(if (needsRobot) Destination.ROBOT else Destination.PRACTICE)
            }
        }
    }

    /** What is on screen with the new program and robot, at the speed it runs at now. */
    private fun restartRequest(program: String, mapping: RobotMapping): SessionRequest? =
        services.session.requested?.copy(program = program, mapping = mapping, speed = services.session.run.value.speed)

    /**
     * Fills the form for a project that is opened: what it was last run with, else what its robot file says, else the
     * defaults; and returns what was loaded. The form is what [SetupModel.request] turns into the request.
     */
    private fun openInput(project: Project, defaults: RunDefaults?, rules: SetupRules): SetupInput {
        val remembered = services.settings.state.value.project(project.folder)?.run
        val fromProfile = SetupInput(
            field = defaults?.field.orEmpty(),
            alliance = defaults?.alliance ?: Alliance.RED,
            startPose = defaults?.startPose,
        )
        setup.load(remembered ?: fromProfile, rules)
        if (!setup.ready(rules)) setup.load(SetupInput(), rules)
        // There is no control for the seed: a run always draws one, and a restart on the same field keeps it.
        setup.edit { it.copy(randomSeed = true) }
        return setup.input
    }

    private fun remember(project: Project, input: SetupInput?) {
        services.settings.update { settings ->
            val known = settings.project(project.folder)
            settings.withProject(
                known?.copy(folder = project.folder.toString(), name = project.name, run = input ?: known.run)
                    ?: RecentProject(project.folder.toString(), project.name, input),
            )
        }
    }

    private fun setBuildProblems(problems: List<Problem>) {
        synchronized(lock) {
            buildProblems = problems
            publishProblems()
        }
    }

    private fun publishProblems() {
        mutableProblems.value = buildProblems + listOfNotNull(robotProblem)
    }

    private fun fail(project: Project, message: String, restarting: Boolean, mine: Long) {
        synchronized(lock) {
            if (token != mine) return
            if (restarting) {
                buildProblems = listOf(Problem(Severity.ERROR, "Build", message))
                publishProblems()
                mutableActivity.value = ProjectActivity.Idle
                announce("Build failed", showProblems = true)
            } else {
                mutableActivity.value = ProjectActivity.Failed(project, message)
            }
        }
    }

    private fun rules(): SetupRules? = services.session.catalogue.value?.let(SetupRules::of)

    /** The rules of the simulator, waiting a little for a link that is still being opened; null if there will be none. */
    private fun awaitRules(): SetupRules? {
        rules()?.let { return it }
        if (services.session.link.value is LinkState.Unavailable) return null
        val catalogue = runBlocking { withTimeoutOrNull(RULES_TIMEOUT_MILLIS) { services.session.catalogue.filterNotNull().first() } }
        return catalogue?.let(SetupRules::of)
    }

    companion object {
        /** The code of the importer's failure when the project changed while it was copied. */
        const val ORIGINAL_CHANGED = "ORIGINAL_CHANGED"

        private const val UNAVAILABLE = "The simulator is not available"

        private const val RULES_TIMEOUT_MILLIS = 60_000L

        /** How long the end of a build waits for the settings form to be filled, which waits for the simulator's catalogue. */
        private const val FORM_TIMEOUT_MILLIS = RULES_TIMEOUT_MILLIS + 5_000L
    }
}
