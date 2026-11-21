package dev.ftcsim.ui.project

import dev.ftcsim.contracts.field.Alliance
import dev.ftcsim.contracts.session.SessionRequest
import dev.ftcsim.ui.AppServices
import dev.ftcsim.ui.host.ProgramPreparer.Prepared
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

/** The project the simulator is running: where it is and the program built from it. */
data class OpenProject(val project: Project, val program: Path)

/** What the runner is doing besides running the project that is open. */
sealed interface ProjectActivity {
    /** Nothing: the project that is open runs, or none is. */
    data object Idle : ProjectActivity

    /** The project is being built; the steps are the [PrepareModel]'s. [restarting] when it is a restart of the code that runs. */
    data class Building(val project: Project, val restarting: Boolean) : ProjectActivity

    /** The project is built and waits for the person to choose its game, which is asked once for each project. */
    data class NeedsGame(val project: Project, val program: Prepared) : ProjectActivity

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

/** Something short for the person to see once, as a snackbar; [showProblems] adds a way to the Problems tab. */
data class RunnerMessage(val text: String, val showProblems: Boolean = false)

/**
 * Opens projects and restarts their code: the one place that knows the way from a project folder to a session. Opening
 * builds the project (or reuses the program of the same source). When the build is done a project that has never run
 * here waits for the person to choose its game; otherwise the session starts by itself with what the settings form
 * holds, and the Simulator is shown. The robot is the simulator's own, chosen by the game. Restarting does the same from the session that is on screen, keeping what it was started with, and never asks the
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
    private val mutableProblems = MutableStateFlow<List<Problem>>(emptyList())
    private val mutableMessages = MutableSharedFlow<RunnerMessage>(extraBufferCapacity = 16)
    private var token = 0L
    private var resuming = false

    /** Ends when the form has been filled for the project that is being opened; the end of its build waits for it. */
    @Volatile private var formReady: CompletableFuture<Void> = CompletableFuture.completedFuture(null)

    /** The project the simulator was last started for, or null before the first. */
    val open: StateFlow<OpenProject?> = mutableOpen.asStateFlow()

    override val activity: StateFlow<ProjectActivity> = mutableActivity.asStateFlow()

    override val buildState: StateFlow<PrepareState> get() = prepare.state

    /** What the build says that the person should know, next to what the robot program reports. */
    override val problems: StateFlow<List<Problem>> = mutableProblems.asStateFlow()

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
    fun open(project: Project): Boolean = start(project, restarting = false, retried = false)

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
            val sameSeed = services.session.requested?.takeIf { it.field() == input.field }?.seed()
            val request = setup.request(rules, current.program.toString(), sameSeed) ?: return@execute
            services.openSession(request)
            remember(current.project, input)
            navigator.go(Destination.PRACTICE)
        }
        return true
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
            if (!restarting && !retried) formReady = CompletableFuture()
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
                    fillForm(project, mine)
                } finally {
                    ready.complete(null)
                }
            }
        }
        return accepted
    }

    /**
     * Fills the settings form before the build is done, with what the project was last run with, else the defaults, so that
     * nothing the person does to it meanwhile can be overwritten by it later. Waits for the simulator's catalogue.
     */
    private fun fillForm(project: Project, mine: Long) {
        val rules = awaitRules() ?: return
        synchronized(lock) { if (token != mine) return }
        openInput(project, rules)
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
        if (!restarting) {
            val rules = awaitRules()
            if (rules == null) {
                fail(project, UNAVAILABLE, restarting, mine)
                return
            }
            if (asksGame(project)) {
                newestGame(rules)?.let { setup.chooseField(rules, it) }
                synchronized(lock) { if (token == mine) mutableActivity.value = ProjectActivity.NeedsGame(project, program) }
                return
            }
        }
        proceed(project, program, restarting, mine)
    }

    /** A project that never ran here is asked which game to play. */
    private fun asksGame(project: Project): Boolean = services.settings.state.value.project(project.folder)?.run == null

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
            proceed(waiting.project, waiting.program, restarting = false, mine = mine)
        } finally {
            synchronized(lock) { resuming = false }
        }
    }

    /** Starts the session for a built project, with the robot of its game. */
    private fun proceed(project: Project, program: Prepared, restarting: Boolean, mine: Long) {
        val rules = awaitRules()
        if (rules == null) {
            fail(project, UNAVAILABLE, restarting, mine)
            return
        }
        val folder = program.folder().toString()
        val sameRun = if (restarting) restartRequest(folder) else null
        val chosen = sameRun ?: setup.request(rules, folder)
        if (chosen == null) {
            fail(project, "The setup is not valid", restarting, mine)
            return
        }
        val input = if (sameRun == null) setup.input else null
        synchronized(lock) { if (token != mine) return }
        services.openSession(chosen)
        mutableOpen.value = OpenProject(project, program.folder())
        remember(project, input)
        mutableActivity.value = ProjectActivity.Idle
        if (!restarting && navigator.state.value.current == Destination.PROJECTS) navigator.go(Destination.PRACTICE)
    }

    /** What is on screen with the new program, at the speed it runs at now. */
    private fun restartRequest(program: String): SessionRequest? =
        services.session.requested?.copy(program = program, speed = services.session.run.value.speed)

    /**
     * Fills the form for a project that is opened: what it was last run with, else the defaults. The form is what
     * [SetupModel.request] turns into the request.
     */
    private fun openInput(project: Project, rules: SetupRules) {
        setup.load(services.settings.state.value.project(project.folder)?.run ?: SetupInput(), rules)
        if (!setup.ready(rules)) setup.load(SetupInput(), rules)
        // There is no control for the seed: a run always draws one, and a restart on the same field keeps it.
        setup.edit { it.copy(randomSeed = true) }
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
        mutableProblems.value = problems
    }

    private fun fail(project: Project, message: String, restarting: Boolean, mine: Long) {
        synchronized(lock) {
            if (token != mine) return
            if (restarting) {
                mutableProblems.value = listOf(Problem(Severity.ERROR, "Build", message))
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
