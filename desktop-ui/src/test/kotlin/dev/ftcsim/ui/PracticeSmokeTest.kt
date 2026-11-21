package dev.ftcsim.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import dev.ftcsim.contracts.robot.RobotPhase
import dev.ftcsim.contracts.session.MatchState
import dev.ftcsim.ui.host.ManualScheduler
import dev.ftcsim.ui.host.newWorkFolder
import dev.ftcsim.ui.host.testHost
import dev.ftcsim.ui.nav.Destination
import dev.ftcsim.ui.practice.Connection
import dev.ftcsim.ui.practice.LinkState
import dev.ftcsim.ui.practice.MatchPlan
import dev.ftcsim.ui.prepare.PrepareState
import dev.ftcsim.ui.project.ProjectActivity
import dev.ftcsim.ui.link.FakeSimulatorLink
import java.awt.event.KeyEvent.VK_W
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Runs the real application stack without a window or any operating-system input and looks at what it draws. These
 * tests need an OpenGL 3.3 context; where there is none they are skipped, not failed.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class PracticeSmokeTest {
    private fun HeadlessApp.awaitViewport() {
        pump { viewportReady() || viewportFailure() != null }
        assumeTrue(viewportFailure() == null, "no OpenGL 3.3 context here: ${viewportFailure()}")
        assertTrue(viewportReady(), "the viewport never produced frames")
    }

    /** Counts pixels of the viewport region that satisfy [test]; the region is what is left of the rail, the side panel and the bars. */
    private fun Bitmap.count(app: HeadlessApp, test: (Int, Int, Int) -> Boolean): Int {
        var found = 0
        for (y in TOP_BAR until app.height - STATUS_STRIP) {
            for (x in RAIL until app.width - SIDE_PANEL) {
                val color = getColor(x, y)
                if (test((color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF)) found++
            }
        }
        return found
    }

    /** The robot is blue or red, by the alliance it plays for; a green piece is on screen with it. */
    private fun Bitmap.showsTheRobotAndAPiece(app: HeadlessApp): Boolean =
        (count(app) { r, g, b -> b > r + 80 && b > g + 30 } > MIN_PIXELS || count(app) { r, g, b -> r > g + 80 && r > b + 80 } > MIN_PIXELS) &&
            count(app) { r, g, b -> g > r + 40 && g > b + 40 } > MIN_PIXELS

    @Test
    fun theApplicationOpensOnProjectsAndAnOpenedSessionIsDrawnByTheViewport() {
        HeadlessApp().use { app ->
            assertEquals(Destination.PROJECTS, app.ui.navigator.state.value.current)
            app.savePng(app.settle(), "projects.png")

            val request = FakeSimulatorLink.defaultRequest()
            app.services.openSession(request)
            app.ui.navigator.go(Destination.PRACTICE)
            app.awaitViewport()

            var picture = app.pixels(app.frame())
            val shown = app.pump(10) {
                picture = app.pixels(app.frame())
                picture.showsTheRobotAndAPiece(app)
            }
            app.savePng(app.frame(), "practice-idle.png")

            assertTrue(shown, "the robot (in its alliance's colour) and a green piece should both be on screen")
            assertTrue(picture.count(app) { r, g, b -> b > g + 70 && r > g + 60 } > MIN_PIXELS, "purple pieces")
            val distinct = HashSet<Int>()
            for (y in TOP_BAR until app.height - STATUS_STRIP step 7) {
                for (x in RAIL until app.width - SIDE_PANEL step 7) distinct += picture.getColor(x, y)
            }
            assertTrue(distinct.size > 50, "a lit scene has many shades, found ${distinct.size}")
            assertEquals(request, app.services.session.requested)
        }
    }

    @Test
    fun aProjectIsBuiltAndItsMatchPlayedOnTheFakeLink() {
        val scheduler = ManualScheduler()
        val work = newWorkFolder()
        val project = Files.createDirectories(work.resolve("TeamProject"))
        Files.writeString(project.resolve("settings.gradle"), "include ':TeamCode'")
        HeadlessApp(host = testHost(scheduler, work)).use { app ->
            app.savePng(app.settle(), "flow-1-projects.png")

            runBlocking { app.ui.openFolder(project, Destination.PROJECTS) }
            assertEquals(Destination.PROJECTS, app.ui.navigator.state.value.current, "the build is shown on the Projects screen")
            assertTrue(app.ui.prepare.state.value is PrepareState.Running)
            scheduler.advance(2_500)
            app.savePng(app.settle(), "flow-2-build-running.png")
            scheduler.advance(60_000)
            assertTrue(app.pump { app.ui.runner.activity.value is ProjectActivity.NeedsGame }, "a project that never ran asks for its game")
            app.savePng(app.settle(), "flow-3-game.png")
            app.ui.runner.continueWithGame()
            assertTrue(app.pump { app.ui.runner.open.value != null && app.ui.runner.activity.value == ProjectActivity.Idle }, "the simulator starts after the choice")
            assertEquals(Destination.PRACTICE, app.ui.navigator.state.value.current, "a built project goes straight to the Simulator")
            app.awaitViewport()
            app.savePng(app.settle(), "flow-5-simulator.png")

            val session = app.services.session
            val plan = MatchPlan.suggest(session.session.value.opModes, session.session.value.selectedOpMode)!!
            session.armMatch(plan.autonomous, plan.teleOp)
            assertTrue(app.pump { session.match.value.state == MatchState.ARMED }, "the fake link arms the match")
            app.savePng(app.settle(), "flow-6-match-armed.png")
            session.startMatch()
            assertTrue(app.pump { session.match.value.timed }, "the match starts its first period")
            app.pump(2) { false }
            app.savePng(app.settle(), "flow-7-match-running.png")
            session.stop()
            assertTrue(app.pump { session.match.value.state == MatchState.ENDED }, "Stop ends a match that is under way")
            app.savePng(app.settle(), "flow-8-match-ended.png")

            app.ui.runner.restart()
            assertTrue(app.pump { app.ui.runner.activity.value is ProjectActivity.Building })
            app.savePng(app.settle(), "flow-9-restart-building.png")
            scheduler.advance(60_000)
            assertTrue(app.pump { app.ui.runner.activity.value == ProjectActivity.Idle }, "the code is restarted")
            assertTrue(app.pump { app.services.session.session.value.connection == Connection.OPEN })
        }
    }

    @Test
    fun theKeyboardDrivesTheRobotOnlyWhileTheViewportHasFocusAndStopsWhenADialogOpens() {
        HeadlessApp().use { app ->
            app.openPractice()
            app.awaitViewport()
            val session = app.services.session
            val startY = app.services.feed.mailbox().latest()!!.current().bodies()[0].y()
            session.init()
            app.pump { session.run.value.phase == RobotPhase.INIT }
            session.start()
            app.pump { session.run.value.phase == RobotPhase.RUNNING }

            // Without focus a held key does nothing.
            app.key(KeyEvent(Key.W, KeyEventType.KeyDown))
            assertTrue(app.services.input.keys().held().isEmpty())

            app.click(500f, 400f)
            assertTrue(app.pump { app.services.input.focused() }, "clicking the viewport focuses it")
            assertTrue(app.key(KeyEvent(Key.W, KeyEventType.KeyDown)), "W belongs to the keyboard gamepad, so the viewport consumes it")
            assertEquals(setOf(VK_W), app.services.input.keys().held())
            val moved = app.pump {
                app.services.feed.mailbox().latest()!!.current().bodies()[0].y() > startY + 0.3f
            }
            app.savePng(app.frame(), "practice-driving.png")
            assertTrue(moved, "the chassis should have driven forward")

            app.ui.practice.moreMenuOpen = true
            assertTrue(app.pump { app.services.input.keys().held().isEmpty() }, "opening a dialog releases the held key")
            assertFalse(app.key(KeyEvent(Key.W, KeyEventType.KeyDown)).and(app.services.input.keys().held().isNotEmpty()))
            app.savePng(app.frame(), "practice-dialog.png")
        }
    }

    @Test
    fun leavingPracticeReleasesTheKeysStopsDrawingAndKeepsTheRunGoingUntilItComesBack() {
        HeadlessApp().use { app ->
            app.openPractice()
            app.awaitViewport()
            val session = app.services.session
            session.init()
            app.pump { session.run.value.phase == RobotPhase.INIT }
            app.click(500f, 400f)
            app.pump { app.services.input.focused() }
            app.key(KeyEvent(Key.W, KeyEventType.KeyDown))
            assertEquals(setOf(VK_W), app.services.input.keys().held())

            app.ui.navigator.go(Destination.CONTROLS)
            app.settle()

            assertTrue(app.services.input.keys().held().isEmpty(), "the viewport left the screen, so its keys are released")
            assertFalse(app.services.input.focused())
            assertEquals(RobotPhase.INIT, session.run.value.phase, "the run is still there")
            val framesWhileAway = app.services.engine.framesDrawn()
            app.pump(1) { false }
            assertTrue(app.services.engine.framesDrawn() - framesWhileAway <= SETTLE_DRAWS, "nothing is drawn while another screen is shown")

            app.ui.navigator.go(Destination.PRACTICE)
            app.settle()
            assertTrue(app.pump(10) { app.pixels(app.frame()).showsTheRobotAndAPiece(app) }, "the field is there again")
            assertEquals(RobotPhase.INIT, session.run.value.phase)
        }
    }

    @Test
    fun thePerformanceOverlayMeasuresTheRealRenderThreadAndAHalfSizePictureStillShowsTheField() {
        HeadlessApp().use { app ->
            app.openPractice()
            app.awaitViewport()

            app.settings.update { it.copy(display = it.display.copy(performanceOverlay = true, renderScalePercent = 50, frameCapHz = 60)) }
            // Turning the camera keeps the render thread drawing, so that it has frames to measure.
            val measured = app.pump(10) {
                app.services.camera.orbit(1.0, 0.0)
                app.services.performance.sample().frameInterval != null
            }
            app.pump(2) { false }
            app.savePng(app.settle(), "practice-overlay.png")

            assertTrue(measured, "frame intervals were recorded once the overlay was turned on")
            assertTrue(app.services.engine.stats.recording())
            assertTrue(app.pump(10) { app.pixels(app.frame()).showsTheRobotAndAPiece(app) }, "a picture drawn at 50 % and stretched still shows the field")
        }
    }

    @Test
    fun everyScreenDrawsSomethingAtTheSizeOfALaptopWindow() {
        HeadlessApp(startViewport = false).use { app ->
            for ((destination, name) in listOf(
                Destination.PROJECTS to "projects.png",
                Destination.CONTROLS to "controls.png",
                Destination.SETTINGS to "settings.png",
                Destination.PRACTICE to "practice-empty.png",
            )) {
                app.ui.navigator.go(destination)
                val image = app.settle()
                app.savePng(image, name)
                val distinct = HashSet<Int>()
                val pixels = app.pixels(image)
                for (y in 0 until app.height step 11) {
                    for (x in 0 until app.width step 11) distinct += pixels.getColor(x, y)
                }
                assertTrue(distinct.size > 4, "$destination drew only ${distinct.size} colours")
            }
        }
    }

    @Test
    fun theMatchDialogAndALinkThatCannotBeOpenedAreDrawnOverThePracticeScreen() {
        HeadlessApp(startViewport = false).use { app ->
            app.openPractice()
            assertTrue(app.pump { app.services.session.session.value.connection == Connection.OPEN })
            app.ui.practice.matchDialogOpen = true
            val dialog = app.settle()
            app.savePng(dialog, "practice-match-dialog.png")
            assertTrue(app.services.input.modalOpen(), "the dialog takes the keys away from the robot")
            assertTrue(distinctColours(app, dialog) > 4)
        }
        val unreachable = testHost(links = { throw IOException("The worker could not be started: the simulator's Java is missing.") })
        HeadlessApp(host = unreachable, startViewport = false).use { app ->
            assertTrue(app.pump { app.services.session.link.value is LinkState.Unavailable })
            app.ui.navigator.go(Destination.PRACTICE)
            val image = app.settle()
            app.savePng(image, "practice-link-unavailable.png")
            assertTrue(distinctColours(app, image) > 4)
        }
    }

    private fun distinctColours(app: HeadlessApp, image: org.jetbrains.skia.Image): Int {
        val colours = HashSet<Int>()
        val pixels = app.pixels(image)
        for (y in 0 until app.height step 11) {
            for (x in 0 until app.width step 11) colours += pixels.getColor(x, y)
        }
        return colours.size
    }

    @Test
    fun thePagesThatScrollAreCompleteAtFullLength() {
        HeadlessApp(height = 1900, startViewport = false).use { app ->
            for ((destination, name) in listOf(Destination.SETTINGS to "settings-full.png", Destination.CONTROLS to "controls-full.png")) {
                app.ui.navigator.go(destination)
                app.savePng(app.settle(), name)
            }
        }
    }

    private companion object {
        const val TOP_BAR = 64
        const val STATUS_STRIP = 32
        const val SIDE_PANEL = 400
        const val RAIL = 80
        const val MIN_PIXELS = 150
        const val SETTLE_DRAWS = 4L
    }
}
