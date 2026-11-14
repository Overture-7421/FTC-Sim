package dev.ftcsim.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.ftcsim.contracts.robot.RobotPhase
import dev.ftcsim.ui.common.FakeFileChooser
import dev.ftcsim.ui.common.FakeLogOpener
import dev.ftcsim.ui.common.FtcSimTheme
import dev.ftcsim.ui.common.runTallComposeUiTest as runComposeUiTest
import dev.ftcsim.ui.host.FakeAppHost
import dev.ftcsim.ui.host.ManualScheduler
import dev.ftcsim.ui.host.newWorkFolder
import dev.ftcsim.ui.host.testHost
import dev.ftcsim.ui.input.PadControl
import dev.ftcsim.ui.link.FakeSimulatorLink
import dev.ftcsim.ui.nav.Destination
import dev.ftcsim.ui.practice.Connection
import dev.ftcsim.ui.practice.LinkState
import dev.ftcsim.ui.prepare.PrepareState
import dev.ftcsim.ui.prepare.Project
import dev.ftcsim.ui.settings.SettingsStore
import dev.ftcsim.ui.setup.SetupRules
import java.awt.event.KeyEvent
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The whole interface on the fake host, driven by synthetic clicks: no window, no OpenGL (the viewport is never
 * started, so the Practice screen shows its placeholder) and no operating-system input.
 */
@OptIn(ExperimentalTestApi::class)
class AppFlowTest {
    private class Rig(links: FakeAppHost.LinkOpener = FakeAppHost.LinkOpener { FakeSimulatorLink() }) : AutoCloseable {
        val scheduler = ManualScheduler()
        val work = newWorkFolder()
        val settings = SettingsStore(null, Executor { it.run() })
        val services = AppServices(testHost(scheduler, work, links), settings)
        val ui = AppUi(services)
        val files = FakeFileChooser()
        val logs = FakeLogOpener()

        val rules: SetupRules? get() = services.session.catalogue.value?.let { SetupRules.of(it, services.programRequired) }

        fun folder(name: String, marker: String): Path =
            Files.createDirectories(work.resolve(name)).also { Files.writeString(it.resolve(marker), "x") }

        fun project(name: String = "TeamProject") = folder(name, "settings.gradle")

        override fun close() = services.close()
    }

    private fun ComposeUiTest.show(rig: Rig) = setContent {
        FtcSimTheme(darkTheme = true) { AppContent(rig.services, rig.ui, rig.files, rig.logs, null) }
    }

    @Test
    fun aRunStartedFromSetupOpensInPracticeAndKeepsItsStateWhileAnotherScreenIsShown() = runComposeUiTest {
        Rig().use { rig ->
            show(rig)
            onNodeWithText("Home").assertIsSelected()

            onNodeWithText("New practice run").performClick()
            waitUntil(timeoutMillis = 10_000) { rig.rules?.let { rig.ui.setup.ready(it) } == true }
            onNodeWithText("Start practice").performClick()
            waitUntil(timeoutMillis = 10_000) { rig.services.session.session.value.connection == Connection.OPEN }
            onNodeWithText("Practice").assertIsSelected()
            onNodeWithText("Init").performClick()
            waitUntil(timeoutMillis = 10_000) { rig.services.session.run.value.phase == RobotPhase.INIT }

            onNodeWithText("Controls").performClick()
            waitForIdle()
            onNodeWithText("Gamepad 1").assertExists()
            assertEquals(RobotPhase.INIT, rig.services.session.run.value.phase, "the run goes on while another screen is shown")

            onNodeWithText("Practice").performClick()
            waitForIdle()
            onNodeWithText("Start").assertIsEnabled()
            assertEquals(1, rig.settings.state.value.recents.size)
        }
    }

    @Test
    fun aRecentRunOnHomeOpensAgainWithOneClick() = runComposeUiTest {
        Rig().use { rig ->
            rig.settings.update { it.withRecent(FakeSimulatorLink.defaultRequest()) }
            show(rig)

            onNodeWithText("Open").performClick()
            waitUntil(timeoutMillis = 10_000) { rig.services.session.session.value.connection == Connection.OPEN }

            onNodeWithText("Practice").assertIsSelected()
            assertEquals("decode", rig.services.session.session.value.info!!.field().id())
        }
    }

    @Test
    fun openingAPracticePackageFromHomeFillsInTheSetupFormAndShowsIt() = runComposeUiTest {
        Rig().use { rig ->
            val prepared = rig.folder("prepared", "manifest.json")
            rig.files.folder = prepared
            show(rig)

            onNodeWithText("Open practice package").performClick()
            waitUntil(timeoutMillis = 10_000) { rig.ui.navigator.state.value.current == Destination.SETUP }

            assertEquals(prepared.toString(), rig.ui.setup.input.program)
            assertEquals(listOf("Open a practice package"), rig.files.asked)
        }
    }

    @Test
    fun openingAProjectFromHomeLeadsToThePrepareScreenWithTheProjectChosen() = runComposeUiTest {
        Rig().use { rig ->
            val project = rig.project("RoboticsTeam")
            rig.files.folder = project
            show(rig)

            onNodeWithText("Open project").performClick()
            waitUntil(timeoutMillis = 10_000) { rig.ui.navigator.state.value.current == Destination.PREPARE }
            waitForIdle()

            assertEquals(listOf("Open a project"), rig.files.asked)
            val chosen = rig.ui.prepare.state.value as PrepareState.Ready
            assertEquals(project, chosen.project.folder)
            assertEquals("RoboticsTeam", chosen.project.name)
            onNodeWithText("RoboticsTeam").assertExists()
            onNode(hasText("Prepare") and hasClickAction() and !isSelectable()).assertIsEnabled()
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

            assertEquals(Destination.HOME, rig.ui.navigator.state.value.current, "the person stays where they were")
            onNodeWithText("That folder cannot be used").assertExists()
            onNodeWithText("neither a team project", substring = true).assertExists()

            onNodeWithText("Setup").performClick()
            waitForIdle()
            onNodeWithText("Home").performClick()
            waitForIdle()
            onNodeWithText("That folder cannot be used").assertDoesNotExist()
        }
    }

    @Test
    fun theNoticeCanBeDismissed() = runComposeUiTest {
        Rig().use { rig ->
            rig.files.folder = rig.folder("empty", "notes.txt")
            show(rig)
            onNodeWithText("Open practice package").performClick()
            waitUntil(timeoutMillis = 10_000) { rig.ui.folderNotice != null }
            waitForIdle()

            onNodeWithText("Dismiss").performClick()
            waitForIdle()

            onNodeWithText("That folder cannot be used").assertDoesNotExist()
        }
    }

    @Test
    fun aProjectCannotBeOpenedWhileAnotherIsBeingPreparedAndTheScreenSaysSo() = runComposeUiTest {
        Rig().use { rig ->
            val first = rig.project("First")
            val second = rig.project("Second")
            rig.settings.update { it.withTrusted(first) }
            assertTrue(rig.ui.prepare.choose(Project(first, "First", "")))
            rig.ui.prepare.prepare()
            rig.files.folder = second
            show(rig)

            onNodeWithText("Open project").performClick()
            waitUntil(timeoutMillis = 10_000) { rig.ui.navigator.state.value.current == Destination.PREPARE }
            waitForIdle()

            onNodeWithText("A project is being prepared", substring = true).assertExists()
            assertEquals(first, rig.ui.prepare.state.value.project!!.folder, "the preparation under way is untouched")
        }
    }

    @Test
    fun aPreparationGoesOnWhileAnotherScreenIsShownAndNextToARunningPracticeSession() = runComposeUiTest {
        Rig().use { rig ->
            val project = rig.project()
            rig.settings.update { it.withTrusted(project) }
            show(rig)
            rig.services.startRun(FakeSimulatorLink.defaultRequest())
            waitUntil(timeoutMillis = 10_000) { rig.services.session.session.value.connection == Connection.OPEN }
            rig.ui.prepare.choose(Project(project, "TeamProject", ""))
            rig.ui.prepare.prepare()
            rig.scheduler.advance(1_000)
            assertTrue(rig.ui.prepare.state.value is PrepareState.Running)

            onNodeWithText("Practice").performClick()
            waitForIdle()
            rig.scheduler.advance(1_500)
            assertTrue(rig.ui.prepare.state.value is PrepareState.Running, "leaving the screen did not cancel it")
            assertEquals(Connection.OPEN, rig.services.session.session.value.connection, "preparing does not touch the session")

            rig.scheduler.advance(60_000)
            onNodeWithText("Prepare").performClick()
            waitForIdle()

            assertTrue(rig.ui.prepare.state.value is PrepareState.Finished)
            onNodeWithText("TeamProject is ready").assertExists()
        }
    }

    @Test
    fun aLinkThatCannotBeOpenedIsSaidOnPracticeAndSetupAndTryAgainOpensItWithoutRestartingTheWindow() = runComposeUiTest {
        val failing = AtomicBoolean(true)
        val opener = FakeAppHost.LinkOpener {
            if (failing.get()) throw IOException("The worker could not be started: the simulator's Java is missing.")
            FakeSimulatorLink()
        }
        Rig(opener).use { rig ->
            show(rig)

            onNodeWithText("Practice").performClick()
            waitUntil(timeoutMillis = 10_000) { rig.services.session.link.value is LinkState.Unavailable }
            onNodeWithText("The simulator could not be started").assertExists()
            onNodeWithText("The worker could not be started: the simulator's Java is missing.").assertExists()

            onNodeWithText("Setup").performClick()
            waitForIdle()
            onNodeWithText("The simulator could not be started").assertExists()
            onNodeWithText("Start practice").assertIsNotEnabled()

            failing.set(false)
            onNodeWithText("Try again").performClick()
            waitUntil(timeoutMillis = 10_000) { rig.services.session.link.value == LinkState.Connected && rig.rules != null }
            waitUntil(timeoutMillis = 10_000) { rig.rules?.let { rig.ui.setup.ready(it) } == true }

            onNodeWithText("The simulator could not be started").assertDoesNotExist()
            onNodeWithText("Start practice").assertIsEnabled()
            onNodeWithText("Start practice").performClick()
            waitUntil(timeoutMillis = 10_000) { rig.services.session.session.value.connection == Connection.OPEN }
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
}
