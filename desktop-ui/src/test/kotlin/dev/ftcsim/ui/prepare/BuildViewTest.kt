package dev.ftcsim.ui.prepare

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.ftcsim.ui.common.FakeLogOpener
import dev.ftcsim.ui.common.FtcSimTheme
import dev.ftcsim.ui.common.ScreenPage
import dev.ftcsim.ui.common.runTallComposeUiTest as runComposeUiTest
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The build view and the failure view on a scripted preparer, with only synthetic events. */
@OptIn(ExperimentalTestApi::class)
class BuildViewTest {
    private class Rig {
        val preparer = ScriptedPreparer()
        val model = PrepareModel(preparer)
        val logs = FakeLogOpener()
        val project = Project(Path.of("C:\\Teams\\TeamProject"), "TeamProject", "")
        var again = 0
        var closed = 0
    }

    private fun ComposeUiTest.showBuild(rig: Rig) = setContent {
        FtcSimTheme(darkTheme = true) { ScreenPage("Projects") { BuildView(rig.project, rig.model, rig.model::cancel) } }
    }

    private fun ComposeUiTest.showFailure(rig: Rig, message: String) = setContent {
        FtcSimTheme(darkTheme = true) {
            ScreenPage("Projects") {
                BuildFailedView(rig.project, message, rig.model, rig.logs, onBuildAgain = { rig.again++ }, onClose = { rig.closed++ })
            }
        }
    }

    @Test
    fun theViewNamesTheProjectAndSaysStartingUntilTheFirstStep() = runComposeUiTest {
        val rig = Rig()
        rig.model.prepareAndThen(rig.project) {}
        showBuild(rig)

        onNodeWithText("TeamProject").assertExists()
        onNodeWithText("Starting").assertExists()

        rig.preparer.step(2, of = 6, label = "Compiling your code")
        waitForIdle()
        onNodeWithText("Step 2 of 6: Compiling your code").assertExists()
    }

    @Test
    fun cancelAsksThePreparerToStopAndIsOffAfterwards() = runComposeUiTest {
        val rig = Rig()
        rig.model.prepareAndThen(rig.project) {}
        showBuild(rig)

        onNodeWithText("Cancel").performClick()
        waitForIdle()

        assertEquals(1, rig.preparer.cancels)
        onNodeWithText("Cancelling").assertIsNotEnabled()
    }

    @Test
    fun theLogIsClosedUntilAskedFor() = runComposeUiTest {
        val rig = Rig()
        rig.model.prepareAndThen(rig.project) {}
        showBuild(rig)
        rig.preparer.listener!!.output("> Task :TeamCode:compileDebugJavaWithJavac")
        waitForIdle()

        onNodeWithText("> Task :TeamCode:compileDebugJavaWithJavac").assertDoesNotExist()
        onNodeWithText("Show log").performClick()
        waitForIdle()

        onNodeWithText("> Task :TeamCode:compileDebugJavaWithJavac").assertExists()
        onNodeWithText("Hide log").performClick()
        waitForIdle()
        onNodeWithText("> Task :TeamCode:compileDebugJavaWithJavac").assertDoesNotExist()
    }

    @Test
    fun aFailureIsOneLineWithItsDetailsBehindAToggleAndTheBuildLogToOpen() = runComposeUiTest {
        val rig = Rig()
        val log = Path.of("C:\\logs\\build.log")
        rig.model.prepareAndThen(rig.project) {}
        rig.preparer.fail(message = "Your code does not compile.", details = listOf("Drive.java:12: error: ';' expected"), log = log)
        showFailure(rig, "Your code does not compile.")

        onNodeWithText("Your code does not compile.").assertExists()
        onNodeWithText("Drive.java:12: error: ';' expected").assertDoesNotExist()
        onNodeWithText("Show details").performClick()
        waitForIdle()
        onNodeWithText("Drive.java:12: error: ';' expected").assertExists()

        onNodeWithText("Open build log").performClick()
        waitForIdle()
        assertEquals(listOf(log), rig.logs.opened)
    }

    @Test
    fun aLogThatCannotBeOpenedSaysWhyAndTheTwoButtonsDoWhatTheyName() = runComposeUiTest {
        val rig = Rig()
        rig.logs.problem = "No program opens .log files"
        rig.model.prepareAndThen(rig.project) {}
        rig.preparer.fail(log = Path.of("C:\\logs\\build.log"))
        showFailure(rig, "It did not compile.")

        onNodeWithText("Open build log").performClick()
        waitForIdle()
        onNodeWithText("No program opens .log files").assertExists()

        onNodeWithText("Build again").performClick()
        onNodeWithText("Close").performClick()
        assertEquals(1, rig.again)
        assertEquals(1, rig.closed)
    }

    @Test
    fun aFailureWithoutDetailsOrALogOffersNeitherToggleNorLog() = runComposeUiTest {
        val rig = Rig()
        showFailure(rig, "The simulator is not available")

        onNodeWithText("Show details").assertDoesNotExist()
        onNodeWithText("Open build log").assertDoesNotExist()
        onNodeWithText("Build again").assertExists()
    }
}
