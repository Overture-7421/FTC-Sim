package dev.ftcsim.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.ftcsim.contracts.field.Alliance
import dev.ftcsim.contracts.hardware.UnmappedDevice
import dev.ftcsim.contracts.robot.RobotPhase
import dev.ftcsim.contracts.session.RobotMapping
import dev.ftcsim.contracts.session.RobotMapping.MotorMapping
import dev.ftcsim.contracts.session.RobotMapping.Slot
import dev.ftcsim.ui.common.FakeFileChooser
import dev.ftcsim.ui.common.FakeLogOpener
import dev.ftcsim.ui.common.FtcSimTheme
import dev.ftcsim.ui.common.runTallComposeUiTest as runComposeUiTest
import dev.ftcsim.ui.host.FakeAppHost
import dev.ftcsim.ui.host.FakeProjectMappings
import dev.ftcsim.ui.host.ManualScheduler
import dev.ftcsim.ui.host.RunDefaults
import dev.ftcsim.ui.host.newWorkFolder
import dev.ftcsim.ui.host.testHost
import dev.ftcsim.ui.input.PadControl
import dev.ftcsim.ui.link.FakeSimulatorLink
import dev.ftcsim.ui.nav.Destination
import dev.ftcsim.ui.practice.Connection
import dev.ftcsim.ui.practice.LinkState
import dev.ftcsim.ui.prepare.PrepareState
import dev.ftcsim.ui.project.ProjectActivity
import dev.ftcsim.ui.project.RobotState
import dev.ftcsim.ui.robot.MappingModel
import dev.ftcsim.ui.settings.RecentProject
import dev.ftcsim.ui.setup.SetupInput
import dev.ftcsim.ui.settings.SettingsStore
import java.awt.event.KeyEvent
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The whole interface on the fake host, driven by synthetic clicks: no window, no OpenGL (the viewport is never
 * started, so the Simulator screen shows its placeholder) and no operating-system input. What the application does with
 * a built project happens on a thread of its own, so the tests wait for it on a flow with a bounded wait, never a sleep.
 */
@OptIn(ExperimentalTestApi::class)
class AppFlowTest {
    private class Rig(links: FakeAppHost.LinkOpener = FakeAppHost.LinkOpener { FakeSimulatorLink() }) : AutoCloseable {
        val scheduler = ManualScheduler()
        val work = newWorkFolder()
        val mappings = FakeProjectMappings(work)
        val settings = SettingsStore(null, Executor { it.run() })
        val services = AppServices(testHost(scheduler, work, links, mappings), settings)
        val ui = AppUi(services)
        val files = FakeFileChooser()
        val logs = FakeLogOpener()

        fun folder(name: String, marker: String): Path =
            Files.createDirectories(work.resolve(name)).also { Files.writeString(it.resolve(marker), "x") }

        /** A project folder; [run] is what its `ftcsim.json` says, which is nothing about a game unless a field is named, and [mapping] is what it maps. */
        fun project(name: String = "TeamProject", run: RunDefaults? = RunDefaults("decode", null, null), mapping: RobotMapping = RobotMapping.empty(name)): Path {
            val folder = folder(name, "settings.gradle")
            if (run != null) mappings.withMapping(folder, mapping, run)
            return folder
        }

        fun <T> await(flow: StateFlow<T>, accept: (T) -> Boolean): T = runBlocking {
            withTimeout(TimeUnit.SECONDS.toMillis(WAIT_SECONDS)) { flow.first(accept) }
        }

        override fun close() = services.close()
    }

    private fun ComposeUiTest.show(rig: Rig) = setContent {
        FtcSimTheme(darkTheme = true) { AppContent(rig.services, rig.ui, rig.files, rig.logs, null) }
    }

    private fun ComposeUiTest.waitForSession(rig: Rig) =
        waitUntil(timeoutMillis = 10_000) { rig.services.session.session.value.connection == Connection.OPEN }

    /** Chooses the project's folder and lets the fake build run to its end. */
    private fun ComposeUiTest.openAndBuild(rig: Rig, folder: Path) {
        rig.files.folder = folder
        onNodeWithText("Open project").performClick()
        waitUntil(timeoutMillis = 10_000) { rig.ui.prepare.state.value is PrepareState.Running }
        rig.scheduler.advance(60_000)
    }

    @Test
    fun aProjectOpenedFromProjectsIsBuiltAndOpensInTheSimulatorWithItsStateKeptWhileAnotherScreenIsShown() = runComposeUiTest {
        Rig().use { rig ->
            show(rig)
            onNodeWithText("Projects").assertIsSelected()
            rig.files.folder = rig.project()

            onNodeWithText("Open project").performClick()
            waitUntil(timeoutMillis = 10_000) { rig.ui.prepare.state.value is PrepareState.Running }
            assertEquals(Destination.PROJECTS, rig.ui.navigator.state.value.current, "the build is shown where the project was chosen")
            onNodeWithText("Starting").assertExists()
            rig.scheduler.advance(500)
            waitForIdle()
            onNodeWithText("Step 1 of 6", substring = true).assertExists()
            rig.scheduler.advance(60_000)
            waitUntil(timeoutMillis = 10_000) { rig.ui.navigator.state.value.current == Destination.PRACTICE }
            waitForSession(rig)

            onNodeWithText("Simulator").assertIsSelected()
            onNodeWithText("Init").performClick()
            waitUntil(timeoutMillis = 10_000) { rig.services.session.run.value.phase == RobotPhase.INIT }

            onNodeWithText("Controls").performClick()
            waitForIdle()
            onNodeWithText("Gamepad 1").assertExists()
            assertEquals(RobotPhase.INIT, rig.services.session.run.value.phase, "the run goes on while another screen is shown")

            onNodeWithText("Simulator").performClick()
            waitForIdle()
            onNodeWithText("Start").assertIsEnabled()
            assertEquals(listOf("TeamProject"), rig.settings.state.value.projects.map { it.name })
            assertEquals("TeamProject", rig.ui.runner.open.value?.project?.name)
        }
    }

    private fun mapped() = RobotMapping.empty("Team robot").withMotors(
        listOf(
            MotorMapping("frontLeft", Slot.FRONT_LEFT, "", true),
            MotorMapping("frontRight", Slot.FRONT_RIGHT, "", false),
            MotorMapping("backLeft", Slot.REAR_LEFT, "", true),
            MotorMapping("backRight", Slot.REAR_RIGHT, "", false),
            MotorMapping("shooterLeft", Slot.SHOOTER, "", false),
            MotorMapping("shooterRight", Slot.SHOOTER, "", false),
        ),
    )

    @Test
    fun theRobotScreenOfAProjectWithAFileHasItsMotorsInPlaceNothingToSaveUntilOneChangesAndSavingWritesTheWholeMapping() = runComposeUiTest {
        Rig().use { rig ->
            show(rig)
            val folder = rig.project(mapping = mapped())
            openAndBuild(rig, folder)
            waitUntil(timeoutMillis = 10_000) { rig.ui.navigator.state.value.current == Destination.PRACTICE }
            waitForSession(rig)

            onNodeWithText("Robot").performClick()
            waitUntil(timeoutMillis = 10_000) { runCatching { onNodeWithContentDescription("frontLeft, motor").assertExists() }.isSuccess }

            onNodeWithText("Mass (kg)").assertExists()
            onNodeWithContentDescription("shooterLeft, motor").assertExists()
            onNodeWithText("Skip").assertDoesNotExist()
            onNodeWithText("Save").assertIsNotEnabled()

            onNodeWithContentDescription("Remove shooterRight").performClick()
            waitForIdle()
            onNodeWithText("Save").assertIsEnabled()
            onNodeWithText("Save").performClick()
            waitUntil(timeoutMillis = 10_000) { rig.mappings.saved().isNotEmpty() }

            assertEquals(mapped().withMotors(mapped().motors().dropLast(1)), rig.mappings.saved().single())
            waitUntil(timeoutMillis = 10_000) { rig.ui.navigator.state.value.current == Destination.PRACTICE }
        }
    }

    @Test
    fun aProjectThatNeverRanIsAskedForItsGameAfterTheBuildAndStartsWhenContinueIsPressed() = runComposeUiTest {
        Rig().use { rig ->
            show(rig)
            openAndBuild(rig, rig.project(run = RunDefaults.NONE))
            waitUntil(timeoutMillis = 10_000) { rig.ui.runner.activity.value is ProjectActivity.NeedsGame }
            waitForIdle()

            assertEquals(Destination.PROJECTS, rig.ui.navigator.state.value.current)
            onNodeWithText("Game").assertExists()
            assertEquals(Connection.NONE, rig.services.session.session.value.connection, "nothing starts before the choice")

            onNodeWithText("Continue").performClick()
            waitUntil(timeoutMillis = 10_000) { rig.ui.navigator.state.value.current == Destination.PRACTICE }
            waitForSession(rig)
            assertEquals("biobuzz", rig.services.session.requested?.field(), "the newest game was chosen already")
        }
    }

    @Test
    fun aProjectWithoutARobotFileShowsTheRobotPageAndSkippingLeadsToTheSimulator() = runComposeUiTest {
        Rig().use { rig ->
            show(rig)
            openAndBuild(rig, rig.project(run = null))
            waitUntil(timeoutMillis = 10_000) { rig.ui.runner.activity.value is ProjectActivity.NeedsGame }
            waitForIdle()
            onNodeWithText("Continue").performClick()
            waitUntil(timeoutMillis = 10_000) { rig.ui.navigator.state.value.current == Destination.ROBOT }
            waitForSession(rig)
            waitForIdle()

            onNodeWithText("Pick an OpMode to list its motors").assertExists()
            onNodeWithText("Create").assertIsNotEnabled()
            onNodeWithText("Map a motor").assertExists()
            onNodeWithText("Skip").performClick()
            waitUntil(timeoutMillis = 10_000) { rig.ui.navigator.state.value.current == Destination.PRACTICE }

            assertTrue(rig.mappings.saved().isEmpty(), "nothing was written")
            assertTrue(rig.ui.runner.robot.value is RobotState.Undescribed)
            onNodeWithText("Details").performClick()
            onNodeWithText("Problems").performClick()
            waitForIdle()
            onNodeWithText("No ftcsim.json: no motors are mapped").assertExists()
        }
    }

    @Test
    fun theMotorsTheCodeAskedForAreMappedByClickAndCreateWritesThemAndShowsTheSimulator() = runComposeUiTest {
        Rig().use { rig ->
            show(rig)
            openAndBuild(rig, rig.project(run = null))
            waitUntil(timeoutMillis = 10_000) { rig.ui.runner.activity.value is ProjectActivity.NeedsGame }
            waitForIdle()
            onNodeWithText("Continue").performClick()
            waitUntil(timeoutMillis = 10_000) { rig.ui.navigator.state.value.current == Destination.ROBOT }
            waitForSession(rig)
            waitForIdle()
            val model = rig.ui.mapping(
                rig.ui.runner.open.value!!.project, rig.services.session.catalogue.value!!.motors(), MappingModel.hasTurret(rig.ui.setup.input.field))
            model.addFound(listOf("frontLeft", "frontRight", "backLeft", "backRight", "flywheel").map { UnmappedDevice(UnmappedDevice.Kind.MOTOR, it) })
            waitForIdle()

            for ((motor, slot) in listOf("frontLeft" to "Front left", "frontRight" to "Front right", "backLeft" to "Rear left", "backRight" to "Rear right", "flywheel" to "Shooter flywheel")) {
                onNodeWithContentDescription("$motor, motor").performClick()
                waitForIdle()
                onNodeWithContentDescription("Place here: $slot").performClick()
                waitForIdle()
            }
            onNodeWithText("Create").assertIsEnabled()
            onNodeWithText("Create").performClick()
            waitUntil(timeoutMillis = 10_000) { rig.mappings.saved().isNotEmpty() }

            assertEquals(
                listOf("frontLeft" to Slot.FRONT_LEFT, "frontRight" to Slot.FRONT_RIGHT, "backLeft" to Slot.REAR_LEFT, "backRight" to Slot.REAR_RIGHT, "flywheel" to Slot.SHOOTER),
                rig.mappings.saved().single().motors().map { it.name() to it.slot() })
            waitUntil(timeoutMillis = 10_000) { rig.ui.navigator.state.value.current == Destination.PRACTICE }
        }
    }

    @Test
    fun theAllianceAndStartPositionAreChangedInTheSetupTabAndTheSessionRestartsOnAFreshWorkerWithTheNewOnes() = runComposeUiTest {
        Rig().use { rig ->
            show(rig)
            openAndBuild(rig, rig.project())
            waitUntil(timeoutMillis = 10_000) { rig.ui.navigator.state.value.current == Destination.PRACTICE }
            waitForSession(rig)
            val first = rig.services.session.requested!!

            onNodeWithText("Details").performClick()
            onNodeWithText("Setup").performClick()
            waitForIdle()
            onNodeWithText("Blue").performClick()
            waitForIdle()
            onNodeWithText("Apply").performClick()
            waitUntil(timeoutMillis = 10_000) { rig.services.session.requested?.alliance() == Alliance.BLUE }
            waitForSession(rig)

            val after = rig.services.session.requested!!
            assertEquals(first.program(), after.program())
            assertEquals(first.seed(), after.seed())
            assertEquals(Destination.PRACTICE, rig.ui.navigator.state.value.current)
        }
    }

    @Test
    fun aRecentProjectOnProjectsOpensAgainWithOneClickAndReusesWhatItWasLastRunWith() = runComposeUiTest {
        Rig().use { rig ->
            val project = rig.project()
            rig.settings.update { it.withProject(RecentProject(project.toString(), "TeamProject")) }
            show(rig)

            onNodeWithText("Open").performClick()
            waitUntil(timeoutMillis = 10_000) { rig.ui.prepare.state.value is PrepareState.Running }
            rig.scheduler.advance(60_000)
            waitUntil(timeoutMillis = 10_000) { rig.ui.navigator.state.value.current == Destination.PRACTICE }
            waitForSession(rig)

            onNodeWithText("Simulator").assertIsSelected()
            assertEquals("decode", rig.services.session.session.value.info!!.field().id())
        }
    }

    @Test
    fun theRestartButtonBuildsAgainShowsItsProgressInAStripAndStartsTheSessionAgainWithoutAskingAnything() = runComposeUiTest {
        Rig().use { rig ->
            show(rig)
            openAndBuild(rig, rig.project())
            waitUntil(timeoutMillis = 10_000) { rig.ui.navigator.state.value.current == Destination.PRACTICE }
            waitForSession(rig)
            waitForIdle()
            onNodeWithContentDescription("Restart options").performClick()
            waitForIdle()
            onNodeWithText("Restart code").assertIsEnabled()

            onNodeWithText("Restart code").performClick()
            waitForIdle()

            assertEquals(Destination.PRACTICE, rig.ui.navigator.state.value.current, "a restart is done where the person is")
            onNodeWithText("Cancel").assertExists()
            onNodeWithContentDescription("Restart options").performClick()
            waitForIdle()
            onNodeWithText("Restart code").assertIsNotEnabled()
            rig.ui.practice.restartMenuOpen = false
            waitForIdle()
            rig.scheduler.advance(60_000)
            rig.await(rig.ui.runner.activity) { it == ProjectActivity.Idle }
            waitForSession(rig)
            waitForIdle()

            onNodeWithText("Cancel").assertDoesNotExist()
            onNodeWithContentDescription("Restart options").performClick()
            waitForIdle()
            onNodeWithText("Restart code").assertIsEnabled()
        }
    }

    @Test
    fun aProjectWhoseBuildFailsIsSaidOnTheProjectsScreenAndNoSessionStarts() = runComposeUiTest {
        Rig().use { rig ->
            show(rig)
            openAndBuild(rig, rig.project("broken-project"))
            waitUntil(timeoutMillis = 10_000) { rig.ui.runner.activity.value is ProjectActivity.Failed }
            waitForIdle()

            onNodeWithText("Your code does not compile. Fix the errors and build again.").assertExists()
            onNodeWithText("Show details").assertExists()
            assertEquals(Destination.PROJECTS, rig.ui.navigator.state.value.current)
            assertEquals(Connection.NONE, rig.services.session.session.value.connection)

            onNodeWithText("Close").performClick()
            waitForIdle()
            onNodeWithText("Open project").assertExists()
        }
    }

    @Test
    fun aFolderThatIsNeitherIsExplainedWhereItWasChosenAndAnythingChosenElsewhereLeavesNoTrace() = runComposeUiTest {
        Rig().use { rig ->
            rig.files.folder = rig.folder("something", "notes.txt")
            show(rig)

            onNodeWithText("Open project").performClick()
            waitUntil(timeoutMillis = 10_000) { rig.ui.folderNotice != null }
            waitForIdle()

            assertEquals(Destination.PROJECTS, rig.ui.navigator.state.value.current, "the person stays where they were")
            onNodeWithText("Cannot open that folder").assertExists()
            onNodeWithText("No settings.gradle here", substring = true).assertExists()

            onNodeWithText("Robot").performClick()
            waitForIdle()
            onNodeWithText("Projects").performClick()
            waitForIdle()
            onNodeWithText("Cannot open that folder").assertDoesNotExist()
        }
    }

    @Test
    fun aBuiltProgramIsNotAProjectAndTheNoticeSaysWhichFolderToOpenInstead() = runComposeUiTest {
        Rig().use { rig ->
            rig.files.folder = rig.folder("prepared", "manifest.json")
            show(rig)

            onNodeWithText("Open project").performClick()
            waitUntil(timeoutMillis = 10_000) { rig.ui.folderNotice != null }
            waitForIdle()

            onNodeWithText("This is a built program", substring = true).assertExists()
            assertEquals(Destination.PROJECTS, rig.ui.navigator.state.value.current)
            assertTrue(rig.ui.runner.activity.value == ProjectActivity.Idle)
            onNodeWithText("Dismiss").performClick()
            waitForIdle()
            onNodeWithText("Cannot open that folder").assertDoesNotExist()
        }
    }

    @Test
    fun aProjectCannotBeOpenedWhileAnotherIsBeingBuiltAndTheScreenSaysSo() = runComposeUiTest {
        Rig().use { rig ->
            val first = rig.project("First")
            val second = rig.project("Second")
            show(rig)
            runBlocking { rig.ui.openFolder(first, Destination.PROJECTS) }
            assertTrue(rig.ui.runner.activity.value is ProjectActivity.Building)
            runBlocking { rig.ui.openFolder(second, Destination.PROJECTS) }
            assertEquals("Another project is building", rig.ui.folderNotice?.text)
            assertEquals("First", (rig.ui.runner.activity.value as ProjectActivity.Building).project.name)
        }
    }

    @Test
    fun theLastProjectIsOpenedAtStartTheWayOpenProjectDoesAndAMissingOneIsSaidOnProjects() = runComposeUiTest {
        Rig().use { rig ->
            val project = rig.project()
            rig.settings.update { it.withProject(RecentProject(project.toString(), "TeamProject")) }
            show(rig)

            runBlocking { rig.ui.openLast() }
            assertTrue(rig.ui.runner.activity.value is ProjectActivity.Building)
            assertEquals(Destination.PROJECTS, rig.ui.navigator.state.value.current)
            rig.scheduler.advance(60_000)
            waitUntil(timeoutMillis = 10_000) { rig.ui.navigator.state.value.current == Destination.PRACTICE }
            waitForSession(rig)
        }
        Rig().use { rig ->
            rig.settings.update { it.withProject(RecentProject(rig.work.resolve("gone").toString(), "Gone")) }
            show(rig)

            runBlocking { rig.ui.openLast() }
            waitForIdle()

            assertEquals(Destination.PROJECTS, rig.ui.navigator.state.value.current)
            onNodeWithText("Gone is no longer at", substring = true).assertExists()
        }
    }

    @Test
    fun aLastProjectThatIsNoLongerSetUpIsLeftOnProjectsAndSaidSo() = runComposeUiTest {
        Rig().use { rig ->
            val project = rig.project(run = null)
            rig.settings.update { it.withProject(RecentProject(project.toString(), "TeamProject", SetupInput(field = "decode"))) }
            show(rig)

            runBlocking { rig.ui.openLast() }
            rig.scheduler.advance(60_000)
            waitUntil(timeoutMillis = 10_000) { rig.ui.runner.activity.value == ProjectActivity.Idle && rig.ui.runner.open.value != null }
            waitForIdle()

            assertEquals(Destination.PROJECTS, rig.ui.navigator.state.value.current)
            onNodeWithText("TeamProject is not set up for the simulator").assertExists()
        }
    }

    @Test
    fun withoutALastProjectNothingIsOpenedAtStart() = runComposeUiTest {
        Rig().use { rig ->
            show(rig)

            runBlocking { rig.ui.openLast() }

            assertEquals(ProjectActivity.Idle, rig.ui.runner.activity.value)
            assertEquals(Destination.PROJECTS, rig.ui.navigator.state.value.current)
            assertEquals(null, rig.ui.folderNotice)
        }
    }

    @Test
    fun theFakeHostSaysSoOnEveryScreen() = runComposeUiTest {
        Rig().use { rig ->
            show(rig)

            onNodeWithText("FAKE HOST (no robot code runs)", substring = true).assertExists()
            onNodeWithText("Controls").performClick()
            waitForIdle()
            onNodeWithText("FAKE HOST (no robot code runs)", substring = true).assertExists()
        }
    }

    @Test
    fun aLinkThatCannotBeOpenedIsSaidOnTheSimulatorAndTryAgainOpensItWithoutRestartingTheWindow() = runComposeUiTest {
        val failing = AtomicBoolean(true)
        val opener = FakeAppHost.LinkOpener {
            if (failing.get()) throw IOException("The worker could not be started: the simulator's Java is missing.")
            FakeSimulatorLink()
        }
        Rig(opener).use { rig ->
            show(rig)

            onNodeWithText("Simulator").performClick()
            waitUntil(timeoutMillis = 10_000) { rig.services.session.link.value is LinkState.Unavailable }
            onNodeWithText("Simulator did not start").assertExists()
            onNodeWithText("The worker could not be started: the simulator's Java is missing.").assertExists()

            failing.set(false)
            onNodeWithText("Try again").performClick()
            waitUntil(timeoutMillis = 10_000) { rig.services.session.link.value == LinkState.Connected }

            onNodeWithText("Simulator did not start").assertDoesNotExist()
        }
    }

    @Test
    fun changesInTheSettingsReachTheEngineAndTheKeyboardMap() = runComposeUiTest {
        Rig().use { rig ->
            show(rig)
            assertFalse(rig.services.engine.stats.recording())
            assertFalse(rig.services.input.mapsKey(KeyEvent.VK_P))

            rig.settings.update { it.copy(display = it.display.copy(performanceOverlay = true)) }
            rig.settings.update { it.copy(gamepads = it.gamepads.withKeyboard(it.gamepads.keyboard().with(PadControl.A, KeyEvent.VK_P))) }

            assertTrue(rig.services.engine.stats.recording(), "the overlay needs the recorders on")
            assertTrue(rig.services.input.mapsKey(KeyEvent.VK_P))
            assertFalse(rig.services.input.mapsKey(KeyEvent.VK_K), "the key it replaced is free")
        }
    }

    private companion object {
        const val WAIT_SECONDS = 10L
    }
}
