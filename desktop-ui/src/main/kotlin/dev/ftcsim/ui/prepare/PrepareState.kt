package dev.ftcsim.ui.prepare

import dev.ftcsim.ui.host.ProgramPreparer.Failure
import dev.ftcsim.ui.host.ProgramPreparer.Prepared
import dev.ftcsim.ui.host.ProgramPreparer.Step
import java.nio.file.Path

/** The team project the Prepare screen is about: where it is, what to call it and what is about to happen to it. */
data class Project(val folder: Path, val name: String, val summary: String)

/** How far a step of the preparation has got. */
enum class StepStatus { DONE, ACTIVE, WAITING }

/** One line of the step list; the label of a step that has not begun is not known yet. */
data class StepRow(val number: Int, val of: Int, val label: String?, val status: StepStatus)

/** What the Prepare screen shows: no project, a project waiting, a preparation under way, or how one ended. */
sealed interface PrepareState {
    val project: Project?

    /** No project has been chosen. */
    data object Idle : PrepareState {
        override val project: Project? = null
    }

    /** A project has been chosen and waits for the person to ask for it to be prepared. */
    data class Ready(override val project: Project) : PrepareState

    /**
     * The preparation is under way. [steps] are the steps that have begun, the last of which is the active one;
     * [cancelling] is set from the moment the person pressed Cancel until the preparer says it ended.
     */
    data class Running(override val project: Project, val steps: List<Step> = emptyList(), val cancelling: Boolean = false) : PrepareState {
        /** The step list as it stands: the steps done, the active one and the ones still waiting, by number. */
        fun rows(): List<StepRow> {
            val current = steps.lastOrNull() ?: return emptyList()
            return (1..current.of()).map { number ->
                StepRow(
                    number, current.of(), steps.firstOrNull { it.number() == number }?.label(),
                    when {
                        number < current.number() -> StepStatus.DONE
                        number == current.number() -> StepStatus.ACTIVE
                        else -> StepStatus.WAITING
                    },
                )
            }
        }
    }

    /** The preparation ended without a program; [cancelled] when the person asked for that. */
    data class Failed(override val project: Project, val failure: Failure) : PrepareState {
        val cancelled: Boolean get() = failure.code() == CANCELLED
    }

    /** The program is ready. */
    data class Finished(override val project: Project, val program: Prepared) : PrepareState

    companion object {
        /** The failure code with which a preparer answers a cancellation. */
        const val CANCELLED = "CANCELLED"
    }
}
