package dev.ftcsim.ui.setup

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import dev.ftcsim.contracts.field.Alliance
import dev.ftcsim.ui.common.FtcSimTheme
import dev.ftcsim.ui.common.runTallComposeUiTest as runComposeUiTest
import dev.ftcsim.ui.host.RunDefaults
import dev.ftcsim.ui.host.StartPosition
import dev.ftcsim.ui.project.ProjectActivity
import dev.ftcsim.ui.project.ProjectRig
import dev.ftcsim.ui.project.RobotState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The Start tab of the Simulator screen: the alliance and the start pose, changed while the simulator runs or builds. */
@OptIn(ExperimentalTestApi::class)
class StartPanelTest {
    private fun ComposeUiTest.show(rig: ProjectRig) = setContent {
        FtcSimTheme(darkTheme = true) { StartPanel(rig.ui.setup, rig.services.session, rig.runner) }
    }

    private fun ProjectRig.openProject(name: String = "TeamProject", run: RunDefaults? = RunDefaults("decode", null, null)) {
        runner.open(project(name, run))
        built()
        if (settle() is ProjectActivity.NeedsGame) chooseGame()
    }

    @Test
    fun withoutAProjectTheTabSaysSoAndNothingCanBeApplied() = runComposeUiTest {
        ProjectRig().use { rig ->
            show(rig)

            onNodeWithText("Apply").assertIsNotEnabled()
            onNodeWithText("Save as default").assertIsNotEnabled()
        }
    }

    @Test
    fun theFieldIsChosenFromTheTabAndItsPosesFollow() = runComposeUiTest {
        ProjectRig().use { rig ->
            rig.openProject()
            show(rig)

            onNodeWithContentDescription("Field: DECODE").performClick()
            onNodeWithText("Bare floor").performClick()
            waitForIdle()

            assertEquals("none", rig.ui.setup.input.field)
            onNodeWithText("No presets").assertExists()
        }
    }

    @Test
    fun theFieldsPosesForTheAllianceAreListedAndAnythingElseIsCustom() = runComposeUiTest {
        ProjectRig().use { rig ->
            rig.openProject()
            show(rig)

            onNodeWithContentDescription("Start position: RED goal side").performClick()
            onNodeWithText("Centre").assertExists()
            onNodeWithText("BLUE goal side").assertDoesNotExist()
            onNodeWithText("Centre").performClick()
            waitForIdle()

            assertEquals("Centre", rig.ui.setup.input.startPose)
            onNodeWithText("X (m)").assertIsNotEnabled()

            onNodeWithContentDescription("Start position: Centre").performClick()
            onNodeWithText("Custom position").performClick()
            waitForIdle()

            assertNull(rig.ui.setup.input.startPose)
            onNodeWithText("X (m)").assertIsEnabled()
        }
    }

    @Test
    fun changingTheAllianceOffersTheOtherAlliancesPoses() = runComposeUiTest {
        ProjectRig().use { rig ->
            rig.openProject()
            show(rig)

            onNodeWithText("Blue").performClick()
            waitForIdle()

            onNodeWithContentDescription("Start position: BLUE goal side").assertExists()
            assertEquals("BLUE goal side", rig.ui.setup.input.startPose)
        }
    }

    @Test
    fun aFieldWithoutPosesAsksForNumbersAndGoingBackToAGameOffersPosesAgain() = runComposeUiTest {
        ProjectRig().use { rig ->
            rig.openProject()
            val rules = SetupRules.of(rig.services.session.catalogue.value!!)
            rig.ui.setup.chooseField(rules, "none")
            show(rig)

            onNodeWithText("No presets").assertExists()
            onNodeWithText("X (m)").assertIsEnabled()

            rig.ui.setup.chooseField(rules, "decode")
            waitForIdle()
            onNodeWithContentDescription("Start position: RED goal side").assertExists()
        }
    }

    @Test
    fun applyingRestartsOnAFreshWorkerWithTheNewAllianceAndTheSameProgramFieldAndSeed() = runComposeUiTest {
        ProjectRig().use { rig ->
            rig.openProject()
            val first = rig.link.requests.single()
            show(rig)

            onNodeWithText("Blue").performClick()
            waitForIdle()
            onNodeWithText("Apply").performClick()
            rig.await(rig.settings.state) { it.projects.firstOrNull()?.run?.alliance == Alliance.BLUE }
            rig.awaitLinks(2)

            val after = rig.link.requests.single()
            assertEquals(Alliance.BLUE, after.alliance())
            assertEquals("BLUE goal side", rig.ui.setup.input.startPose)
            assertEquals(first.program(), after.program(), "nothing was built again")
            assertEquals(first.field(), after.field())
            assertEquals(first.seed(), after.seed(), "a random seed is kept, so that only the alliance differs")
            assertEquals(1, rig.seedsDrawn.get(), "no new seed was drawn")
        }
    }

    @Test
    fun theStartPositionInTheTopBarOffersEveryPoseOfTheFieldAndChoosingOneStartsAgainFromIt() = runComposeUiTest {
        ProjectRig().use { rig ->
            rig.openProject()
            val first = rig.link.requests.single()
            setContent { FtcSimTheme(darkTheme = true) { StartPositionMenu(rig.ui.setup, rig.services.session, rig.runner) } }

            onNodeWithContentDescription("Start position: RED goal side").performClick()
            onNodeWithText("Centre").assertExists()
            onNodeWithText("BLUE goal side").assertExists()
            onNodeWithText("BLUE goal side").performClick()
            rig.await(rig.settings.state) { it.projects.firstOrNull()?.run?.alliance == Alliance.BLUE }
            rig.awaitLinks(2)

            val after = rig.link.requests.single()
            assertEquals(Alliance.BLUE, after.alliance(), "a pose of the other alliance brings the alliance with it")
            assertEquals("BLUE goal side", rig.ui.setup.input.startPose)
            assertEquals(first.program(), after.program(), "nothing was built again")
        }
    }

    private fun ProjectRig.openWithPositions(vararg positions: StartPosition) {
        val project = project()
        mappings.withStartPositions(project.folder, positions.toList())
        runner.open(project)
        built()
        if (settle() is ProjectActivity.NeedsGame) chooseGame()
    }

    @Test
    fun theTeamsNamedPositionsAreInTheTopBarMenuAndChoosingOneStartsAgainFromIt() = runComposeUiTest {
        ProjectRig().use { rig ->
            rig.openWithPositions(StartPosition("Near goal", "decode", -1.2, 0.5, 135.0), StartPosition("Other game", "biobuzz", 0.0, 0.0, 0.0))
            val first = rig.link.requests.single()
            setContent { FtcSimTheme(darkTheme = true) { StartPositionMenu(rig.ui.setup, rig.services.session, rig.runner) } }

            onNodeWithContentDescription("Start position: RED goal side").performClick()
            onNodeWithText("Other game").assertDoesNotExist()
            onNodeWithText("Near goal").performClick()
            rig.awaitLinks(2)

            val after = rig.link.requests.single()
            assertEquals(-1.2, after.startX(), 1e-9)
            assertEquals(0.5, after.startY(), 1e-9)
            assertEquals(Math.toRadians(135.0), after.startHeading(), 1e-9)
            assertEquals(first.program(), after.program(), "nothing was built again")
            waitForIdle()
            onNodeWithContentDescription("Start position: Near goal").assertExists()
        }
    }

    @Test
    fun positionsAreAddedChangedAndRemovedInTheDialogAndSavedInTheProject() = runComposeUiTest {
        ProjectRig().use { rig ->
            rig.openWithPositions(StartPosition("Near goal", "decode", -1.2, 0.5, 135.0), StartPosition("Other game", "biobuzz", 2.0, 2.0, 0.0))
            setContent { FtcSimTheme(darkTheme = true) { StartPositionMenu(rig.ui.setup, rig.services.session, rig.runner) } }

            onNodeWithContentDescription("Start position: RED goal side").performClick()
            onNodeWithText("Edit positions...").performClick()
            waitForIdle()
            onNodeWithText("Start positions").assertExists()
            onNodeWithText("Add position").performClick()
            waitForIdle()
            onAllNodesWithText("X (m)")[1].performTextReplacement("1")
            onAllNodesWithText("Y (m)")[1].performTextReplacement("-3")
            onAllNodesWithText("Heading (deg)")[1].performTextReplacement("90")
            onAllNodesWithText("Name")[1].performTextReplacement("Far")
            onAllNodesWithText("X (m)")[0].performTextReplacement("-1.5")
            onNodeWithContentDescription("Remove Near goal").assertExists()
            onNodeWithText("Save").performClick()
            rig.await(rig.runner.startPositions) { it.size == 3 }
            waitForIdle()

            assertEquals(
                listOf(
                    StartPosition("Other game", "biobuzz", 2.0, 2.0, 0.0),
                    StartPosition("Near goal", "decode", -1.5, 0.5, 135.0),
                    StartPosition("Far", "decode", 1.0, -3.0, 90.0),
                ),
                rig.mappings.savedStartPositions().single(), "the other game's position is carried along",
            )
            onNodeWithText("Start positions").assertDoesNotExist()
        }
    }

    @Test
    fun aRowThatIsNotRightStopsSavingAndRemovingARowRemovesIt() = runComposeUiTest {
        ProjectRig().use { rig ->
            rig.openWithPositions(StartPosition("Near goal", "decode", -1.2, 0.5, 135.0))
            setContent { FtcSimTheme(darkTheme = true) { StartPositionMenu(rig.ui.setup, rig.services.session, rig.runner) } }

            onNodeWithContentDescription("Start position: RED goal side").performClick()
            onNodeWithText("Edit positions...").performClick()
            waitForIdle()
            onNodeWithText("X (m)").performTextReplacement("far")
            waitForIdle()
            onNodeWithText("Save").assertIsNotEnabled()
            onNodeWithContentDescription("Remove Near goal").performClick()
            waitForIdle()
            onNodeWithText("Save").assertIsEnabled()
            onNodeWithText("Save").performClick()
            rig.await(rig.runner.startPositions) { it.isEmpty() }

            assertEquals(listOf(emptyList<StartPosition>()), rig.mappings.savedStartPositions())
        }
    }

    @Test
    fun cancellingTheDialogSavesNothing() = runComposeUiTest {
        ProjectRig().use { rig ->
            rig.openWithPositions(StartPosition("Near goal", "decode", -1.2, 0.5, 135.0))
            setContent { FtcSimTheme(darkTheme = true) { StartPositionMenu(rig.ui.setup, rig.services.session, rig.runner) } }

            onNodeWithContentDescription("Start position: RED goal side").performClick()
            onNodeWithText("Edit positions...").performClick()
            waitForIdle()
            onNodeWithContentDescription("Remove Near goal").performClick()
            onNodeWithText("Cancel").performClick()
            waitForIdle()

            onNodeWithText("Start positions").assertDoesNotExist()
            assertEquals(emptyList<List<StartPosition>>(), rig.mappings.savedStartPositions())
            assertEquals(1, rig.link.requests.size)
        }
    }

    @Test
    fun withoutASessionThereIsNoStartPositionToChoose() = runComposeUiTest {
        ProjectRig().use { rig ->
            setContent { FtcSimTheme(darkTheme = true) { StartPositionMenu(rig.ui.setup, rig.services.session, rig.runner) } }

            onNodeWithContentDescription("Start position: RED goal side").assertDoesNotExist()
        }
    }

    @Test
    fun aChangedFieldIsAnotherRunSoANewSeedIsDrawnForIt() = runComposeUiTest {
        ProjectRig().use { rig ->
            rig.openProject()
            val rules = SetupRules.of(rig.services.session.catalogue.value!!)
            rig.ui.setup.chooseField(rules, "none")
            show(rig)
            waitForIdle()

            onNodeWithText("Apply").performClick()
            rig.awaitLinks(2)

            assertEquals("none", rig.link.requests.single().field())
            assertEquals(2, rig.seedsDrawn.get(), "another field is another run: a new seed is drawn")
        }
    }

    @Test
    fun whileTheCodeBuildsTheTabOffersTheChoiceButNotTheApplyAndWhatIsChosenIsWhatStarts() = runComposeUiTest {
        ProjectRig().use { rig ->
            rig.runner.open(rig.project("TeamProject", RunDefaults("decode", Alliance.RED, null)))
            rig.await(rig.runner.robot) { it is RobotState.FromFile }
            show(rig)

            onNodeWithText("Apply").assertIsNotEnabled()

            onNodeWithText("Blue").performClick()
            waitForIdle()
            rig.built()
            rig.settle()

            assertEquals(Alliance.BLUE, rig.link.requests.single().alliance())
        }
    }


    @Test
    fun aMistakeInTheStartNumbersIsSaidAndApplyStaysOff() = runComposeUiTest {
        ProjectRig().use { rig ->
            rig.openProject()
            val rules = SetupRules.of(rig.services.session.catalogue.value!!)
            show(rig)
            waitForIdle()
            rig.ui.setup.choosePose(rules, null)
            waitForIdle()

            onNodeWithText("X (m)").performTextReplacement("far")
            waitForIdle()

            onNodeWithText("Apply").assertIsNotEnabled()
        }
    }

    @Test
    fun savingTheDefaultsWritesFieldAllianceAndPoseIntoTheRobotFileAndSaysSo() = runComposeUiTest {
        ProjectRig().use { rig ->
            rig.openProject()
            show(rig)

            onNodeWithText("Blue").performClick()
            waitForIdle()
            onNodeWithText("Save as default").performClick()
            rig.awaitMessages(1)

            val saved = rig.mappings.savedRunDefaults().single()
            assertEquals(Alliance.BLUE, saved.alliance())
            assertEquals("BLUE goal side", saved.startPose())
            assertEquals("Saved to ftcsim.json", rig.messages.single().text)
        }
    }

    @Test
    fun aProjectWithoutARobotFileCannotSaveDefaultsIntoAFileThatIsNotThere() = runComposeUiTest {
        ProjectRig().use { rig ->
            rig.openProject(run = null)
            show(rig)

            onNodeWithText("Save as default").assertIsNotEnabled()
        }
    }
}
