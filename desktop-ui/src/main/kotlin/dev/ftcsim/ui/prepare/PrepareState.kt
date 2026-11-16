package dev.ftcsim.ui.prepare

import dev.ftcsim.ui.host.ProgramPreparer.Failure
import dev.ftcsim.ui.host.ProgramPreparer.Prepared
import dev.ftcsim.ui.host.ProgramPreparer.Step
import java.nio.file.Path

/** The team project the Project screen is about: where it is, what to call it and what is about to happen to it. */
data class Project(val folder: Path, val name: String, val summary: String)

/** What the Build panel shows: no project, a project waiting, a build under way, or how one ended. */
sealed interface PrepareState {
    val project: Project?

    /** No project has been chosen. */
    data object Idle : PrepareState {
        override val project: Project? = null
    }

    /** A project has been chosen and waits for the person to ask for it to be built. */
    data class Ready(override val project: Project) : PrepareState

    /**
     * The build is under way. [steps] are the steps that have begun, the last of which is the active one;
     * [cancelling] is set from the moment the person pressed Cancel until the preparer says it ended.
     */
    data class Running(override val project: Project, val steps: List<Step> = emptyList(), val cancelling: Boolean = false) : PrepareState {
        /** What the build is doing now, in the preparer's words, or null before the first step has begun. */
        val doing: String? get() = steps.lastOrNull()?.label()
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
