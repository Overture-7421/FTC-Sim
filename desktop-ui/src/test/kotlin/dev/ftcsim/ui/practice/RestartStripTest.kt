package dev.ftcsim.ui.practice

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.ftcsim.ui.common.FtcSimTheme
import dev.ftcsim.ui.common.runTallComposeUiTest as runComposeUiTest
import dev.ftcsim.ui.host.ProgramPreparer.Step
import dev.ftcsim.ui.prepare.PrepareState
import dev.ftcsim.ui.prepare.Project
import dev.ftcsim.ui.project.ProjectActivity
import java.nio.file.Path
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalTestApi::class)
class RestartStripTest {
    private val project = Project(Path.of("C:\\Teams\\Code"), "TeamCode", "")

    private class Rig : CodeRestart {
        override val activity = MutableStateFlow<ProjectActivity>(ProjectActivity.Idle)
        override val buildState = MutableStateFlow<PrepareState>(PrepareState.Idle)
        override val problems = MutableStateFlow<List<Problem>>(emptyList())
        var cancels = 0

        override fun restart() = true

        override fun cancel() {
            cancels++
        }

        override fun dismissFailure() {}
    }

    private fun ComposeUiTest.show(rig: Rig) = setContent { FtcSimTheme(darkTheme = true) { RestartStrip(rig) } }

    @Test
    fun nothingIsShownWhileNothingIsGoingOn() = runComposeUiTest {
        show(Rig())

        onNodeWithText("Cancel").assertDoesNotExist()
    }

    @Test
    fun aRestartInProgressShowsTheStepAndCancelWorks() = runComposeUiTest {
        val rig = Rig()
        rig.activity.value = ProjectActivity.Building(project, restarting = true)
        rig.buildState.value = PrepareState.Running(project, listOf(Step(2, 6, "Compiling your code")))
        show(rig)

        onNodeWithText("Step 2 of 6: Compiling your code").assertExists()
        onNodeWithText("Cancel").performClick()

        assertEquals(1, rig.cancels)
    }

    @Test
    fun beforeTheFirstStepItSaysStartingAndCancelIsOffOnceItWasPressed() = runComposeUiTest {
        val rig = Rig()
        rig.activity.value = ProjectActivity.Building(project, restarting = true)
        rig.buildState.value = PrepareState.Running(project, emptyList(), cancelling = true)
        show(rig)

        onNodeWithText("Starting").assertExists()
        onNodeWithText("Cancelling").assertIsNotEnabled()
    }

    @Test
    fun theFirstBuildOfAProjectIsTheProjectsScreensAndNotTheStrips() = runComposeUiTest {
        val rig = Rig()
        rig.activity.value = ProjectActivity.Building(project, restarting = false)
        show(rig)

        onNodeWithText("Starting").assertDoesNotExist()
    }
}
