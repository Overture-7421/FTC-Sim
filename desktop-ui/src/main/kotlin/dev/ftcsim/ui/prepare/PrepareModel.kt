package dev.ftcsim.ui.prepare

import dev.ftcsim.ui.host.ProgramPreparer
import dev.ftcsim.ui.host.ProgramPreparer.Failure
import dev.ftcsim.ui.host.ProgramPreparer.Prepared
import dev.ftcsim.ui.host.ProgramPreparer.Request
import dev.ftcsim.ui.host.ProgramPreparer.Step
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The build's state: the project that was chosen, the preparation of it and how it ended. It lives as long as the
 * application, so a preparation goes on, and is shown again as it is, while another screen is shown; it never touches
 * the simulator session.
 *
 * A project's build runs its own scripts on this computer. Choosing the project's folder is the person's consent, so
 * [Request.trusted] is always true. The preparer reports from a thread of its own, one event at a time; every state is a [StateFlow] that is only replaced, and events from a preparation
 * that has ended are ignored.
 */
class PrepareModel(private val preparer: ProgramPreparer) {
    private val mutableState = MutableStateFlow<PrepareState>(PrepareState.Idle)
    private val lock = Any()
    private var job: ProgramPreparer.Job? = null
    private var pending: ((PrepareState?) -> Unit)? = null
    @Volatile private var generation = 0L

    /** The output of the project's build, as far as the preparation has got. */
    val log = OutputLog()

    val state: StateFlow<PrepareState> = mutableState.asStateFlow()

    /**
     * Makes [project] the one the screen is about, with the output of any earlier preparation cleared. Refused, with
     * false, while a preparation is under way: that one has to end or be cancelled first.
     */
    fun choose(project: Project): Boolean {
        val dropped: ((PrepareState?) -> Unit)?
        synchronized(lock) {
            if (mutableState.value is PrepareState.Running) return false
            log.clear()
            mutableState.value = PrepareState.Ready(project)
            dropped = pending
            pending = null
        }
        dropped?.invoke(null)
        return true
    }

    /**
     * Chooses [project] and prepares it at once. [then] is called
     * once, from the preparer's thread, with how the preparation ended, or with null if it never began because
     * another project was chosen. Refused, with false and without calling [then], while a preparation is under way.
     */
    fun prepareAndThen(project: Project, then: (PrepareState?) -> Unit): Boolean {
        if (!choose(project)) return false
        synchronized(lock) { pending = then }
        prepare()
        return true
    }

    /** Asks for the chosen project to be built, again if it already was. */
    fun prepare() {
        val project = mutableState.value.takeIf { it !is PrepareState.Running }?.project ?: return
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
        var notStarted: PrepareState? = null
        synchronized(lock) {
            if (mutableState.value is PrepareState.Running) return
            val mine = ++generation
            log.clear()
            mutableState.value = PrepareState.Running(project)
            job = try {
                preparer.prepare(Request(project.folder, true), Events(project, mine))
            } catch (failure: RuntimeException) {
                notStarted = PrepareState.Failed(
                    project,
                    Failure("NOT_STARTED", "The preparation could not be started: ${failure.message ?: failure}", emptyList(), null),
                )
                mutableState.value = notStarted
                null
            }
        }
        notStarted?.let { takePending()?.invoke(it) }
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
            var ended = false
            mutableState.update {
                ended = mine == generation && it is PrepareState.Running
                if (ended) result else it
            }
            if (ended) takePending()?.invoke(result)
        }
    }

    private fun takePending(): ((PrepareState?) -> Unit)? = synchronized(lock) {
        val taken = pending
        pending = null
        taken
    }
}
