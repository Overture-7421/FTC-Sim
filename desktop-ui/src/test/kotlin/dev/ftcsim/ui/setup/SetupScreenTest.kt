package dev.ftcsim.ui.setup

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import dev.ftcsim.contracts.field.Alliance
import dev.ftcsim.contracts.session.SessionRequest
import dev.ftcsim.ui.common.FakeFileChooser
import dev.ftcsim.ui.common.FtcSimTheme
import dev.ftcsim.ui.common.runTallComposeUiTest as runComposeUiTest
import dev.ftcsim.ui.practice.Fixtures
import dev.ftcsim.ui.practice.PracticeSession
import dev.ftcsim.ui.practice.StubLink
import dev.ftcsim.ui.render.OrbitCamera
import dev.ftcsim.ui.scene.ViewportFeed
import java.nio.file.Paths
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalTestApi::class)
class SetupScreenTest {
    private class Disk(val files: Set<String> = emptySet(), val folders: Set<String> = emptySet()) : PathChecks {
        override fun isFile(path: String) = path in files

        override fun isDirectory(path: String) = path in folders
    }

    private class Rig(val programRequired: Boolean = false, disk: PathChecks = Disk(), initial: SetupInput = SetupInput()) {
        val link = StubLink()
        val session = PracticeSession(ViewportFeed(), OrbitCamera()).also { it.attach(link) }
        val files = FakeFileChooser()
        val started = ArrayList<SessionRequest>()
        var reconnects = 0
        val model = SetupModel(disk, { 123L }, initial)
        val rules get() = SetupRules.of(Fixtures.catalogue(), programRequired)

        fun deliverCatalogue() = link.listener!!.catalogue(Fixtures.catalogue())
    }

    private fun ComposeUiTest.show(rig: Rig, session: PracticeSession = rig.session) {
        setContent {
            FtcSimTheme(darkTheme = true) {
                SetupScreen(
                    rig.model, session, rig.files, rig.programRequired,
                    onStart = { rig.started += it },
                    onReconnect = { rig.reconnects++ },
                )
            }
        }
    }

    private fun ComposeUiTest.waitUntilReady(rig: Rig) = waitUntil(timeoutMillis = 10_000) { rig.model.ready(rig.rules) }

    @Test
    fun beforeTheCatalogueArrivesTheScreenSaysItIsConnectingAndNothingCanBeStarted() = runComposeUiTest {
        val rig = Rig()
        show(rig)

        onNodeWithText("Connecting to the simulator...").assertExists()
        onNodeWithText("The fields the simulator offers appear here once it answers.").assertExists()
        onNodeWithText("Start practice").assertIsNotEnabled()
        onNodeWithText("Waiting for the simulator.").assertExists()
        onNodeWithText("Fixed").performClick()
        waitForIdle()
        onNodeWithText("Fixed seed").assertExists()
    }

    @Test
    fun whenTheCatalogueArrivesTheFieldsAndTheFirstBundledProfileAreOfferedAndAFreshFormCanStart() = runComposeUiTest {
        val rig = Rig()
        show(rig)

        rig.deliverCatalogue()
        waitForIdle()
        onNodeWithText("DECODE").assertExists()
        onNodeWithText("Bare floor").assertExists()
        onNodeWithContentDescription("Robot profile: hornet-2026").assertExists()
        waitUntilReady(rig)
        onNodeWithText("Connecting to the simulator...").assertDoesNotExist()
        onNodeWithText("Start practice").assertIsEnabled()
        onNodeWithText("Start practice").performClick()

        val request = rig.started.single()
        assertEquals("decode", request.field())
        assertEquals("hornet-2026", request.profile())
        assertEquals(Alliance.RED, request.alliance())
        assertEquals(123L, request.seed(), "the random seed is drawn when the run starts")
        assertEquals(1.2, request.startX(), 1e-9)
        assertEquals(-1.4, request.startY(), 1e-9)
    }

    @Test
    fun theStartPoseChooserListsThePosesOfTheChosenFieldAndAllianceAndAnythingElseIsCustom() = runComposeUiTest {
        val rig = Rig()
        show(rig)
        rig.deliverCatalogue()
        waitUntilReady(rig)

        onNodeWithContentDescription("Start pose: RED goal side").performClick()
        onNodeWithText("Centre").assertExists()
        onNodeWithText("BLUE goal side").assertDoesNotExist()
        onNodeWithText("Custom position").assertExists()
        onNodeWithText("Centre").performClick()
        waitForIdle()

        assertEquals("Centre", rig.model.input.startPose)
        onNodeWithText("X (m)").assertIsNotEnabled()

        onNodeWithContentDescription("Start pose: Centre").performClick()
        onNodeWithText("Custom position").performClick()
        waitForIdle()

        assertNull(rig.model.input.startPose)
        onNodeWithText("X (m)").assertIsEnabled()
    }

    @Test
    fun changingTheAllianceOffersTheOtherAlliancesPoses() = runComposeUiTest {
        val rig = Rig()
        show(rig)
        rig.deliverCatalogue()
        waitUntilReady(rig)

        onNodeWithText("Blue").performClick()
        waitForIdle()

        onNodeWithContentDescription("Start pose: BLUE goal side").assertExists()
        assertEquals("BLUE goal side", rig.model.input.startPose)
    }

    @Test
    fun changingTheFieldChangesTheStartPosesAndABareFloorAsksForNumbers() = runComposeUiTest {
        val rig = Rig()
        show(rig)
        rig.deliverCatalogue()
        waitUntilReady(rig)

        onNodeWithText("Bare floor").performClick()
        waitForIdle()
        onNodeWithText("This field offers no start poses: say where the robot starts.").assertExists()
        onNodeWithText("X (m)").assertIsEnabled()
        assertNull(rig.model.input.startPose)

        onNodeWithText("DECODE").performClick()
        waitForIdle()
        onNodeWithContentDescription("Start pose: RED goal side").assertExists()
        onNodeWithText("X (m)").assertIsNotEnabled()
    }

    @Test
    fun aProfileThatIsNoLongerBundledIsSaidNextToItAndStartStaysOff() = runComposeUiTest {
        val rig = Rig(initial = SetupInput(field = "decode", profile = "falcon"))
        show(rig)
        rig.deliverCatalogue()
        waitUntil(timeoutMillis = 10_000) { rig.model.problems(rig.rules).profile != null }

        onNodeWithText("There is no bundled profile called falcon", substring = true).assertExists()
        onNodeWithText("Start practice").assertIsNotEnabled()
        onNodeWithText("Fix the marked fields to start.").assertExists()
    }

    @Test
    fun theProfileMenuListsTheBundledProfilesAndAFileChoiceThatTheFileChooserAnswers() = runComposeUiTest {
        val file = Paths.get("C:\\robots\\mine.json")
        val rig = Rig(disk = Disk(files = setOf(file.toString())))
        rig.files.file = file
        show(rig)
        rig.deliverCatalogue()
        waitUntilReady(rig)

        onNodeWithContentDescription("Robot profile: hornet-2026").performClick()
        onNodeWithText("practice-bot").assertExists()
        onNodeWithText("practice-bot").performClick()
        waitForIdle()
        assertEquals("practice-bot", rig.model.input.profile)

        onNodeWithContentDescription("Robot profile: practice-bot").performClick()
        onNodeWithText("Choose a file...").performClick()
        waitUntil(timeoutMillis = 10_000) { rig.model.input.profile == file.toString() }

        assertEquals(listOf("Choose a robot profile"), rig.files.asked)
        onNodeWithContentDescription("Robot profile: File: mine.json").assertExists()
        waitUntilReady(rig)
    }

    @Test
    fun cancellingTheProfileFileChooserChangesNothing() = runComposeUiTest {
        val rig = Rig()
        show(rig)
        rig.deliverCatalogue()
        waitUntilReady(rig)

        onNodeWithContentDescription("Robot profile: hornet-2026").performClick()
        onNodeWithText("Choose a file...").performClick()
        waitForIdle()

        assertEquals(listOf("Choose a robot profile"), rig.files.asked)
        assertEquals("hornet-2026", rig.model.input.profile)
    }

    @Test
    fun aFixedSeedIsOfferedAsAFieldWhoseMistakesAreSaid() = runComposeUiTest {
        val rig = Rig()
        show(rig)
        rig.deliverCatalogue()
        waitUntilReady(rig)

        onNodeWithText("Fixed").performClick()
        waitForIdle()
        onNodeWithText("Fixed seed").performTextReplacement("abc")
        waitUntil(timeoutMillis = 10_000) { rig.model.problems(rig.rules).seed != null }

        onNodeWithText("The seed is a whole number, such as 12345.").assertExists()
        onNodeWithText("Fixed seed").performTextReplacement("2026")
        waitUntilReady(rig)
        onNodeWithText("Start practice").performClick()

        assertEquals(2026L, rig.started.single().seed())
    }

    @Test
    fun theChosenFolderFillsTheProgramWithoutAnyDialogOpeningInTheTest() = runComposeUiTest {
        val folder = Paths.get("C:\\Teams\\Tina\\prepared")
        val rig = Rig(programRequired = true, disk = Disk(folders = setOf(folder.toString())))
        rig.files.folder = folder
        show(rig)
        rig.deliverCatalogue()
        waitUntil(timeoutMillis = 10_000) { rig.model.problems(rig.rules).program != null }
        onNodeWithText("Choose the folder of the prepared program.").assertExists()

        onNodeWithContentDescription("Choose the program folder").performClick()
        waitUntilReady(rig)

        assertEquals(listOf("Choose the prepared program"), rig.files.asked)
        assertEquals(folder.toString(), rig.model.input.program)
        onNodeWithText("Start practice").assertIsEnabled()
    }

    @Test
    fun aLinkThatCannotBeOpenedSaysSoWithAWayToTryAgainAndNothingCanBeStarted() = runComposeUiTest {
        val rig = Rig()
        val broken = PracticeSession(ViewportFeed(), OrbitCamera()).also { it.unavailable("The worker could not be started.") }
        show(rig, broken)

        onNodeWithText("The simulator could not be started").assertExists()
        onNodeWithText("The worker could not be started.").assertExists()
        onNodeWithText("The simulator is not available.").assertExists()
        onNodeWithText("Start practice").assertIsNotEnabled()
        onNodeWithText("Try again").performClick()

        assertEquals(1, rig.reconnects)
    }

    @Test
    fun startingARunWhileASessionIsOpenSaysItReplacesIt() = runComposeUiTest {
        val rig = Rig()
        show(rig)
        rig.deliverCatalogue()
        rig.session.open(Fixtures.request())
        rig.link.listener!!.opened(Fixtures.info())
        waitUntilReady(rig)

        onNodeWithText("Starting a run replaces the session that is open now.").assertExists()
        assertTrue(rig.started.isEmpty())
    }
}
