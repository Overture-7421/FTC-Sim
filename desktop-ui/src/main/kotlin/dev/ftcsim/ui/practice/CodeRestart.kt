package dev.ftcsim.ui.practice

import dev.ftcsim.ui.prepare.PrepareState
import dev.ftcsim.ui.project.ProjectActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the Simulator screen needs of the application to restart the robot code: whether a build is under way and how
 * far it has got, what the build and the robot file say, and the buttons. The [ProjectRunner][dev.ftcsim.ui.project.ProjectRunner] is the
 * real one; [None] stands in where there is no project, such as a test of the screen on its own.
 */
interface CodeRestart {
    /** What the application is doing about the project: nothing, building it, or what went wrong. */
    val activity: StateFlow<ProjectActivity>

    /** The build's steps as far as it has got, for the words of the banner. */
    val buildState: StateFlow<PrepareState>

    /** What the build and the project's robot file say, to be listed with the problems of the run. */
    val problems: StateFlow<List<Problem>>

    /** Builds what is on disk now and starts the session again with it; false, doing nothing, when that cannot be done now. */
    fun restart(): Boolean

    /** Stops the build that is under way. */
    fun cancel()

    fun dismissFailure()

    /** A restart that does nothing, for a screen that is shown without a project. */
    data object None : CodeRestart {
        override val activity: StateFlow<ProjectActivity> = MutableStateFlow(ProjectActivity.Idle)
        override val buildState: StateFlow<PrepareState> = MutableStateFlow(PrepareState.Idle)
        override val problems: StateFlow<List<Problem>> = MutableStateFlow(emptyList())

        override fun restart(): Boolean = false

        override fun cancel() {}

        override fun dismissFailure() {}
    }
}
