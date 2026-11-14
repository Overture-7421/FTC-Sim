package dev.ftcsim.ui.prepare

import dev.ftcsim.ui.host.ProgramPreparer
import dev.ftcsim.ui.host.ProgramPreparer.Failure
import dev.ftcsim.ui.host.ProgramPreparer.Prepared
import dev.ftcsim.ui.host.ProgramPreparer.Request
import dev.ftcsim.ui.host.ProgramPreparer.Step
import dev.ftcsim.ui.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update

/**
 * The Prepare screen's state: the project that was chosen, the preparation of it and how it ended, and the question
 * whether the person trusts the project. It lives as long as the application, so a preparation goes on, and is
 * shown again as it is, while another screen is shown; it never touches the practice session.
 *
 * A project's build runs its own scripts on this computer, so no preparation starts before the person has agreed
 * to that for the project's folder, once; [Request.trusted] is true only after that. The preparer reports from a thread
 * of its own, one event at a time; every state is a [StateFlow] that is only replaced, and events from a preparation
 * that has ended are ignored.
 */
class PrepareModel(private val preparer: ProgramPreparer, private val settings: SettingsStore) {
    private val mutableState = MutableStateFlow<PrepareState>(PrepareState.Idle)
    private val mutableConsent = MutableStateFlow<Project?>(null)
    private val lock = Any()
    private var job: ProgramPreparer.Job? = null
    @Volatile private var generation = 0L

    /** The output of the project's build, as far as the preparation has got. */
    val log = OutputLog()

    val state: StateFlow<PrepareState> = mutableState.asStateFlow()

    /** The project whose person is being asked for agreement before its build runs, or null. */
    val consent: StateFlow<Project?> = mutableConsent.asStateFlow()

    /**
     * Makes [project] the one the screen is about, with the output of any earlier preparation cleared. Refused, with
     * false, while a preparation is under way: that one has to end or be cancelled first.
     */
    fun choose(project: Project): Boolean = synchronized(lock) {
        if (mutableState.value is PrepareState.Running) return false
        mutableConsent.value = null
        log.clear()
        mutableState.value = PrepareState.Ready(project)
        true
    }

    /** Asks for the chosen project to be prepared, again if it already was; with a question first if it is not trusted. */
    fun prepare() {
        val project = mutableState.value.takeIf { it !is PrepareState.Running }?.project ?: return
        if (settings.state.value.trusts(project.folder)) start(project) else mutableConsent.value = project
    }

    /** The person's answer to the question: agreeing starts the preparation and is remembered for the folder. */
    fun answerConsent(agreed: Boolean) {
        val project = mutableConsent.getAndUpdate { null } ?: return
        if (!agreed) return
        settings.update { it.withTrusted(project.folder) }
        start(project)
    }

    /** Asks the preparation to stop; it ends when the preparer says so. Does nothing when none is under way. */
    fun cancel() {
        var asked = false
        mutableState.update { now ->
            if (now is PrepareState.Running && !now.cancelling) {
                asked = true
                now.copy(cancelling = true)
            } else {
                asked = false
                now
            }
        }
        if (asked) synchronized(lock) { job }?.cancel()
    }

    private fun start(project: Project) {
        synchronized(lock) {
            if (mutableState.value is PrepareState.Running) return
            val mine = ++generation
            log.clear()
            mutableState.value = PrepareState.Running(project)
            job = try {
                preparer.prepare(Request(project.folder, true), Events(project, mine))
            } catch (failure: RuntimeException) {
                mutableState.value = PrepareState.Failed(
                    project,
                    Failure("NOT_STARTED", "The preparation could not be started: ${failure.message ?: failure}", emptyList(), null),
                )
                null
            }
        }
    }

    /** Hears one preparation, and only while it is the one under way. */
    private inner class Events(private val project: Project, private val mine: Long) : ProgramPreparer.Listener {
        override fun step(step: Step) {
            mutableState.update { if (mine == generation && it is PrepareState.Running) it.copy(steps = it.steps + step) else it }
        }

        override fun output(line: String) {
            if (mine == generation && mutableState.value is PrepareState.Running) log.add(line)
        }

        override fun finished(program: Prepared) = end(PrepareState.Finished(project, program))

        override fun failed(failure: Failure) = end(PrepareState.Failed(project, failure))

        private fun end(result: PrepareState) {
            mutableState.update { if (mine == generation && it is PrepareState.Running) result else it }
        }
    }
}
