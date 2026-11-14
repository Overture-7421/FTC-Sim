package dev.ftcsim.ui.prepare

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import dev.ftcsim.ui.common.FakeFileChooser
import dev.ftcsim.ui.common.FakeLogOpener
import dev.ftcsim.ui.common.FtcSimTheme
import dev.ftcsim.ui.common.runTallComposeUiTest as runComposeUiTest
import dev.ftcsim.ui.host.FakeProgramPreparer
import dev.ftcsim.ui.host.ManualScheduler
import dev.ftcsim.ui.host.ProgramPreparer
import dev.ftcsim.ui.host.newWorkFolder
import dev.ftcsim.ui.settings.SettingsStore
import java.nio.file.Path
import java.util.concurrent.Executor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The Prepare screen in every state, on the fake preparer played by a clock the test moves, or on a scripted one where
 * the test needs exact events. Only synthetic events reach the Compose scene; no dialog, file chooser or program is opened.
 */
@OptIn(ExperimentalTestApi::class)
class PrepareScreenTest {
    private class Rig(custom: ProgramPreparer? = null) {
        val scheduler = ManualScheduler()
        val work = newWorkFolder()
        val settings = SettingsStore(null, Executor { it.run() })
        val model = PrepareModel(custom ?: FakeProgramPreparer(scheduler, work), settings)
        val files = FakeFileChooser()
        val logs = FakeLogOpener()
        val chosen = ArrayList<Path>()
        val used = ArrayList<Path>()
        var dismissed = 0
        var notice by mutableStateOf<String?>(null)

        fun project(name: String = "TeamProject"): Project = Project(work.resolve(name), name, "A team project that is about to be built.")

        fun choose(name: String = "TeamProject", trusted: Boolean = false): Project {
            val project = project(name)
            if (trusted) settings.update { it.withTrusted(project.folder) }
            model.choose(project)
            return project
        }
    }

    private fun ComposeUiTest.show(rig: Rig) = setContent {
        FtcSimTheme(darkTheme = true) {
            PrepareScreen(
                rig.model, rig.files, rig.logs, rig.notice,
                onChooseFolder = { rig.chosen.add(it) },
                onUseProgram = { rig.used.add(it) },
                onDismissNotice = { rig.dismissed++ },
            )
        }
    }

    private fun ComposeUiTest.inDialog(text: String) = onNode(hasText(text) and hasAnyAncestor(isDialog()))

    private fun ComposeUiTest.scrolledOutput() = onNodeWithContentDescription("Output of the preparation")

    @Test
    fun withoutAProjectTheScreenExplainsAndOffersToChooseOneThroughTheFileChooser() = runComposeUiTest {
        val rig = Rig()
        val folder = rig.work.resolve("Chosen")
        rig.files.folder = folder
        show(rig)

        onNodeWithText("No project has been chosen.", substring = true).assertExists()
        onNodeWithText("Choose project folder").performClick()
        waitForIdle()

        assertEquals(listOf("Choose your team's project folder"), rig.files.asked)
        assertEquals(listOf(folder), rig.chosen)
    }

    @Test
    fun cancellingTheFileChooserChoosesNothing() = runComposeUiTest {
        val rig = Rig()
        show(rig)

        onNodeWithText("Choose project folder").performClick()
        waitForIdle()

        assertTrue(rig.chosen.isEmpty())
    }

    @Test
    fun aChosenProjectShowsItsNameFolderAndSummaryAndIsReadyToBePrepared() = runComposeUiTest {
        val rig = Rig()
        val project = rig.choose()
        show(rig)

        onNodeWithText("TeamProject").assertExists()
        onNodeWithText(project.folder.toString()).assertExists()
        onNodeWithText("A team project that is about to be built.").assertExists()
        onNodeWithText("Prepare").assertIsEnabled()
        onNodeWithText("Choose another folder").assertExists()
    }

    @Test
    fun anUntrustedProjectIsAskedAboutInPlainWordsAndRefusingStartsNothing() = runComposeUiTest {
        val rig = Rig()
        rig.choose()
        show(rig)

        onNodeWithText("Prepare").performClick()
        waitForIdle()
        onNodeWithText("Prepare this project?").assertExists()
        onNodeWithText(
            "Preparing runs this project's own Gradle build and its code on this computer. Only continue with projects you trust.",
        ).assertExists()
        inDialog("Cancel").performClick()
        waitForIdle()

        onNodeWithText("Prepare this project?").assertDoesNotExist()
        assertTrue(rig.model.state.value is PrepareState.Ready)
        assertFalse(rig.settings.state.value.trusts(rig.project().folder))
        assertEquals(0, rig.scheduler.pending(), "nothing was started")
    }

    @Test
    fun agreeingStartsThePreparationAndIsRememberedSoThatTheQuestionIsNotAskedAgain() = runComposeUiTest {
        val rig = Rig()
        val project = rig.choose()
        show(rig)

        onNodeWithText("Prepare").performClick()
        waitForIdle()
        inDialog("Prepare").performClick()
        waitForIdle()

        assertTrue(rig.model.state.value is PrepareState.Running)
        assertTrue(rig.settings.state.value.trusts(project.folder))
        onNodeWithText("Preparing TeamProject").assertExists()

        rig.scheduler.advance(120_000)
        waitForIdle()
        onNodeWithText("Prepare again").performClick()
        waitForIdle()

        onNodeWithText("Prepare this project?").assertDoesNotExist()
        assertTrue(rig.model.state.value is PrepareState.Running, "a project that was agreed to is not asked about again")
    }

    @Test
    fun theButtonCanBePressedFromTheKeyboardAndOpensTheQuestion() = runComposeUiTest {
        val rig = Rig()
        rig.choose()
        show(rig)

        onNodeWithText("Prepare").requestFocus()
        onNodeWithText("Prepare").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        onNodeWithText("Prepare this project?").assertExists()
    }

    @Test
    fun whileItRunsTheStepsShowWhatIsDoneWhatIsActiveAndWhatWaitsWithThePreparersLabels() = runComposeUiTest {
        val rig = Rig()
        rig.choose(trusted = true)
        show(rig)
        rig.model.prepare()
        waitForIdle()
        onNodeWithText("Starting...").assertExists()

        rig.scheduler.advance(1_100)
        waitForIdle()

        onNodeWithContentDescription("Step 1 of 6, done: Copying the project, leaving the original alone").assertExists()
        onNodeWithContentDescription("Step 2 of 6, in progress: Building the project with its own Gradle build").assertExists()
        onNodeWithContentDescription("Step 3 of 6, waiting: Step 3 of 6").assertExists()
        onNodeWithText("Cancel").assertIsEnabled()
    }

    @Test
    fun cancelIsDisabledOnceItWasPressedAndTheCancellationIsReportedNeutrally() = runComposeUiTest {
        val rig = Rig()
        rig.choose(trusted = true)
        show(rig)
        rig.model.prepare()
        rig.scheduler.advance(1_100)
        waitForIdle()

        onNodeWithText("Cancel").performClick()
        waitForIdle()

        onNodeWithText("Cancel").assertIsNotEnabled()
        onNodeWithText("Cancelling: waiting for the preparation to stop.").assertExists()

        rig.scheduler.advance(120_000)
        waitForIdle()

        onNodeWithText("Preparation cancelled").assertExists()
        onNodeWithText("Preparation was cancelled. Nothing was kept.").assertExists()
        onNodeWithText("Cancel").assertDoesNotExist()
        onNodeWithText("Open build log").assertDoesNotExist()
        onNodeWithText("Prepare again").assertExists()
    }

    @Test
    fun aFailureIsSaidInWordsWithTheLinesThatShowWhyAndTheBuildLogCanBeOpened() = runComposeUiTest {
        val rig = Rig()
        rig.choose("broken-project", trusted = true)
        show(rig)
        rig.model.prepare()

        rig.scheduler.advance(120_000)
        waitForIdle()

        val failed = rig.model.state.value as PrepareState.Failed
        onNodeWithText("Preparing broken-project did not finish").assertExists()
        onNodeWithText("The project does not compile", substring = true).assertExists()
        onNodeWithText("error: cannot find symbol", substring = true).assertExists()
        onNodeWithText("Open build log").performClick()
        waitForIdle()

        assertEquals(listOf(failed.failure.log()), rig.logs.opened)
    }

    @Test
    fun aBuildLogThatCannotBeOpenedSaysWhyRightThere() = runComposeUiTest {
        val rig = Rig()
        rig.choose("broken-project", trusted = true)
        rig.logs.problem = "No program is set up to open .log files."
        show(rig)
        rig.model.prepare()
        rig.scheduler.advance(120_000)
        waitForIdle()

        onNodeWithText("Open build log").performClick()
        waitForIdle()

        onNodeWithText("The build log could not be opened: No program is set up to open .log files.").assertExists()
    }

    @Test
    fun aFailureWithoutABuildLogOffersNoButtonForIt() = runComposeUiTest {
        val scripted = ScriptedPreparer()
        val rig = Rig(scripted)
        rig.choose(trusted = true)
        show(rig)
        rig.model.prepare()

        scripted.fail("NO_ANDROID_SDK", "The Android SDK was not found.", listOf("SDK location not found."), log = null)
        waitForIdle()

        onNodeWithText("The Android SDK was not found.").assertExists()
        onNodeWithText("SDK location not found.").assertExists()
        onNodeWithText("Open build log").assertDoesNotExist()
        onNodeWithText("Prepare again").assertExists()
    }

    @Test
    fun aFinishedPreparationShowsWhatWasMadeWithOpModesAndWarningsThatOpenOnRequest() = runComposeUiTest {
        val rig = Rig()
        rig.choose(trusted = true)
        show(rig)
        rig.model.prepare()
        rig.scheduler.advance(120_000)
        waitForIdle()
        val program = (rig.model.state.value as PrepareState.Finished).program

        onNodeWithText("TeamProject is ready").assertExists()
        onNodeWithText("FTC SDK 11.1.0").assertExists()
        onNodeWithText(program.folder().toString()).assertExists()
        onNodeWithText("Mecanum TeleOp").assertDoesNotExist()
        onNodeWithText("OpModes (3)").performClick()
        waitForIdle()
        onNodeWithText("Mecanum TeleOp").assertExists()
        onNodeWithText("Competition Auto").assertExists()

        onNodeWithText("These point to code that may not work in the simulator. They are not claims that it fails.").assertDoesNotExist()
        onNodeWithText("Warnings (2)").performClick()
        waitForIdle()
        onNodeWithText("These point to code that may not work in the simulator. They are not claims that it fails.").assertExists()
        onNodeWithText("Vision.java reads the camera", substring = true).assertExists()

        onNodeWithText("OpModes (3)").performClick()
        waitForIdle()
        onNodeWithText("Mecanum TeleOp").assertDoesNotExist()
    }

    @Test
    fun usingThePreparedProgramHandsItsFolderOn() = runComposeUiTest {
        val rig = Rig()
        rig.choose(trusted = true)
        show(rig)
        rig.model.prepare()
        rig.scheduler.advance(120_000)
        waitForIdle()
        val program = (rig.model.state.value as PrepareState.Finished).program

        onNodeWithText("Use this program").performClick()

        assertEquals(listOf(program.folder()), rig.used)
        onNodeWithText("Open build log").performClick()
        waitForIdle()
        assertEquals(listOf(program.log()), rig.logs.opened)
    }

    @Test
    fun aPreparationWithNoWarningsSaysSoInsteadOfShowingAnEmptyList() = runComposeUiTest {
        val scripted = ScriptedPreparer()
        val rig = Rig(scripted)
        rig.choose(trusted = true)
        show(rig)
        rig.model.prepare()

        scripted.finish(warnings = emptyList(), log = null)
        waitForIdle()

        onNodeWithText("No warnings.").assertExists()
        onNodeWithText("Open build log").assertDoesNotExist()
    }

    @Test
    fun theOutputArrivesAsLinesAreAddedAndShowsOnlyTheNewestOnes() = runComposeUiTest {
        val scripted = ScriptedPreparer()
        val rig = Rig(scripted)
        rig.choose(trusted = true)
        show(rig)
        rig.model.prepare()
        scripted.step(1)

        scripted.listener!!.output("> Task :TeamCode:preBuild UP-TO-DATE")
        waitForIdle()
        onNodeWithText("> Task :TeamCode:preBuild UP-TO-DATE").assertExists()
        onNodeWithText("Nothing has been printed yet.").assertDoesNotExist()

        repeat(2_500) { scripted.listener!!.output("line $it") }
        waitForIdle()

        onNodeWithText("Showing the newest 2000 lines of output; the 501 before them are not shown.").assertExists()
        onNodeWithText("line 2499").assertExists()
    }

    @Test
    fun theOutputFollowsTheNewestLineUntilTheUserScrollsUpAndFollowsAgainAtTheEnd() = runComposeUiTest {
        val scripted = ScriptedPreparer()
        val rig = Rig(scripted)
        rig.choose(trusted = true)
        show(rig)
        rig.model.prepare()
        repeat(300) { scripted.listener!!.output("line $it") }
        waitForIdle()

        onNodeWithText("line 299").assertExists()
        onNodeWithText("line 0").assertDoesNotExist()

        scrolledOutput().performMouseInput { moveTo(center); scroll(-100f) }
        waitForIdle()
        onNodeWithText("line 299").assertDoesNotExist()
        repeat(50) { scripted.listener!!.output("more $it") }
        waitForIdle()
        onNodeWithText("more 49").assertDoesNotExist()

        scrolledOutput().performMouseInput { moveTo(center); scroll(1_000f) }
        waitForIdle()
        onNodeWithText("more 49").assertExists()
        repeat(10) { scripted.listener!!.output("again $it") }
        waitForIdle()
        onNodeWithText("again 9").assertExists()
    }

    @Test
    fun theOutputCanBeScrolledFromTheKeyboardWhenItHasFocus() = runComposeUiTest {
        val scripted = ScriptedPreparer()
        val rig = Rig(scripted)
        rig.choose(trusted = true)
        show(rig)
        rig.model.prepare()
        repeat(300) { scripted.listener!!.output("line $it") }
        waitForIdle()
        onNodeWithText("line 299").assertExists()

        scrolledOutput().requestFocus()
        scrolledOutput().performKeyInput { pressKey(Key.MoveHome) }
        waitForIdle()
        onNodeWithText("line 0").assertExists()
        onNodeWithText("line 299").assertDoesNotExist()

        scrolledOutput().performKeyInput { pressKey(Key.PageDown) }
        waitForIdle()
        onNodeWithText("line 0").assertIsNotDisplayed()
        onNodeWithText("line 20").assertIsDisplayed()

        scrolledOutput().performKeyInput { pressKey(Key.MoveEnd) }
        waitForIdle()
        onNodeWithText("line 299").assertExists()
    }

    @Test
    fun theNoticeIsShownInPlaceAndCanBeDismissed() = runComposeUiTest {
        val rig = Rig()
        rig.notice = "This folder is neither a team project nor a prepared program."
        show(rig)

        onNodeWithText("That folder cannot be used").assertExists()
        onNodeWithText("This folder is neither a team project nor a prepared program.").assertExists()
        onNodeWithText("Dismiss").performClick()

        assertEquals(1, rig.dismissed)
    }

    @Test
    fun leavingTheScreenWithTheQuestionOpenWithdrawsItAndAPreparationGoesOnAndIsShownAsItIsOnReturn() = runComposeUiTest {
        val rig = Rig()
        rig.choose(trusted = true)
        var shown by mutableStateOf(true)
        setContent {
            FtcSimTheme(darkTheme = true) {
                if (shown) PrepareScreen(rig.model, rig.files, rig.logs, null, {}, {}, {})
            }
        }
        rig.model.prepare()
        rig.scheduler.advance(1_100)
        waitForIdle()

        shown = false
        waitForIdle()
        rig.scheduler.advance(500)
        assertTrue(rig.model.state.value is PrepareState.Running, "leaving the screen did not cancel it")

        shown = true
        waitForIdle()
        onNodeWithText("Preparing TeamProject").assertExists()
        onNodeWithText("Cancel").assertIsEnabled()
        assertNotNull(rig.model.log.view().lines.firstOrNull(), "the output kept coming while the screen was away")
    }

    @Test
    fun aQuestionLeftOpenIsWithdrawnWhenTheScreenIsLeft() = runComposeUiTest {
        val rig = Rig()
        rig.choose()
        var shown by mutableStateOf(true)
        setContent {
            FtcSimTheme(darkTheme = true) {
                if (shown) PrepareScreen(rig.model, rig.files, rig.logs, null, {}, {}, {})
            }
        }
        onNodeWithText("Prepare").performClick()
        waitForIdle()
        assertNotNull(rig.model.consent.value)

        shown = false
        waitForIdle()

        assertNull(rig.model.consent.value)
        assertTrue(rig.model.state.value is PrepareState.Ready)
    }
}
