package dev.ftcsim.ui.practice

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.ftcsim.contracts.field.Score
import dev.ftcsim.contracts.robot.RobotPhase
import dev.ftcsim.contracts.session.MatchState
import dev.ftcsim.ui.common.FtcSimTheme
import dev.ftcsim.ui.input.ViewportInput
import dev.ftcsim.ui.render.OrbitCamera
import dev.ftcsim.ui.scene.ViewportFeed
import dev.ftcsim.ui.viewport.ViewportEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The real Practice screen composed in memory with a stand-in for the 3D viewport, on a link that only records what it
 * is asked. State changes are made by calling the listener by hand and events are sent to the Compose scene, so there is
 * no window, no OpenGL and no operating-system input.
 */
@OptIn(ExperimentalTestApi::class)
class PracticeScreenTest {
    private class Rig {
        val link = StubLink()
        val feed = ViewportFeed()
        val camera = OrbitCamera()
        val input = ViewportInput(camera)
        val engine = ViewportEngine(feed, camera, input)
        val session = PracticeSession(feed, camera).also { it.attach(link) }
        val ui = PracticeUi()
        var setupRequests = 0
        var retries = 0
        var reconnects = 0

        init {
            session.open(Fixtures.request())
        }

        val listener get() = link.listener!!

        fun openSession() {
            listener.opened(Fixtures.info())
            listener.status(Fixtures.status())
        }
    }

    private fun ComposeUiTest.show(rig: Rig, session: PracticeSession = rig.session) {
        setContent {
            FtcSimTheme(darkTheme = true) {
                PracticeScreen(
                    session, rig.engine, rig.ui,
                    onRetry = { rig.retries++ },
                    onReconnect = { rig.reconnects++ },
                    onSetup = { rig.setupRequests++ },
                    viewport = { _, modifier -> Box(modifier) },
                )
            }
        }
    }

    @Test
    fun theControlsAreDisabledAndTheDriverIsToldWhileTheSessionOpens() = runComposeUiTest {
        val rig = Rig()
        show(rig)

        onNodeWithText("Init").assertIsNotEnabled()
        onNodeWithText("Start").assertIsNotEnabled()
        onNodeWithText("Stop").assertIsNotEnabled()
        onNodeWithText("Match").assertIsNotEnabled()
        onNodeWithContentDescription("Reset the session").assertIsNotEnabled()
        onNodeWithContentDescription("Pause the simulation").assertIsNotEnabled()

        rig.openSession()
        waitForIdle()

        onNodeWithText("Init").assertIsEnabled()
        onNodeWithText("Match").assertIsEnabled()
        onNodeWithContentDescription("Reset the session").assertIsEnabled()
    }

    @Test
    fun initStartAndStopAreEnabledByThePhaseAndSendTheirCommands() = runComposeUiTest {
        val rig = Rig()
        show(rig)
        rig.openSession()
        waitForIdle()

        onNodeWithText("Start").assertIsNotEnabled()
        onNodeWithText("Init").performClick()
        assertEquals("init Teleop A", rig.link.calls.last())

        rig.listener.status(Fixtures.status(RobotPhase.INIT))
        waitForIdle()
        onNodeWithText("Init").assertIsNotEnabled()
        onNodeWithText("Start").assertIsEnabled()
        onNodeWithText("Stop").assertIsEnabled()
        onNodeWithText("Start").performClick()
        assertEquals("start", rig.link.calls.last())

        rig.listener.status(Fixtures.status(RobotPhase.RUNNING))
        waitForIdle()
        onNodeWithText("Start").assertIsNotEnabled()
        onNodeWithText("Teleop A").assertIsNotEnabled()
        onNodeWithText("Stop").performClick()
        assertEquals("stop", rig.link.calls.last())
    }

    @Test
    fun theOpModeMenuListsWhatTheProgramDeclaresAndTheChoiceIsKept() = runComposeUiTest {
        val rig = Rig()
        show(rig)
        rig.openSession()
        waitForIdle()

        onNodeWithText("Teleop A").performClick()
        waitForIdle()
        assertTrue(rig.ui.opModeMenuOpen)
        assertTrue(rig.input.modalOpen(), "an open menu is a modal for the keyboard")
        onNodeWithText("Autonomous").assertExists()
        onNodeWithText("Auto B").performClick()
        waitForIdle()

        assertEquals("Auto B", rig.session.session.value.selectedOpMode)
        assertFalse(rig.ui.opModeMenuOpen)
        assertFalse(rig.input.modalOpen())
        onNodeWithText("Auto B").assertIsEnabled()
    }

    @Test
    fun theSpeedMenuOffersQuarterSpeedToFourTimesAndSendsTheChoice() = runComposeUiTest {
        val rig = Rig()
        show(rig)
        rig.openSession()
        waitForIdle()

        onNodeWithText("1x").performClick()
        for (label in listOf("0.25x", "0.5x", "2x", "4x")) {
            onNodeWithText(label).assertExists()
        }
        onNodeWithText("2x").performClick()

        assertEquals("speed 2.0", rig.link.calls.last())
    }

    @Test
    fun theLabelOfThePauseButtonFollowsTheStateTheLinkReports() = runComposeUiTest {
        val rig = Rig()
        show(rig)
        rig.openSession()
        waitForIdle()

        onNodeWithContentDescription("Pause the simulation").performClick()
        assertEquals("paused true", rig.link.calls.last())
        rig.listener.status(Fixtures.status(paused = true))
        waitForIdle()

        onNodeWithContentDescription("Resume the simulation").assertExists()
        onNodeWithText("Paused").assertExists()
    }

    @Test
    fun resetNeedsConfirmationAndTheDialogIsAModal() = runComposeUiTest {
        val rig = Rig()
        show(rig)
        rig.openSession()
        waitForIdle()

        onNodeWithContentDescription("Reset the session").performClick()
        waitForIdle()
        onNodeWithText("Reset the session?").assertExists()
        assertTrue(rig.input.modalOpen())
        onNodeWithText("Cancel").performClick()
        waitForIdle()
        assertFalse(rig.link.calls.contains("reset"))
        assertFalse(rig.input.modalOpen())

        onNodeWithContentDescription("Reset the session").performClick()
        waitForIdle()
        onNodeWithText("Reset").performClick()
        waitForIdle()
        assertEquals("reset", rig.link.calls.last())
    }

    @Test
    fun errorsAndWarningsAreNamedInWordsAndAnErrorRaisesASnackbarThatLeadsToTheProblemsTab() = runComposeUiTest {
        val rig = Rig()
        show(rig)
        rig.openSession()
        rig.listener.status(
            Fixtures.status(
                warnings = "Low battery",
                error = "java.lang.IllegalStateException: boom",
                problems = listOf("a port had nothing connected"),
            ),
        )
        waitForIdle()

        onNodeWithText("Show").assertExists()
        onNodeWithText("Show").performClick()
        waitForIdle()

        assertEquals(Panel.PROBLEMS, rig.ui.panel)
        onNodeWithText("Error - Robot program").assertExists()
        onNodeWithText("Warning - Driver Station").assertExists()
        onNodeWithText("Warning - Simulator").assertExists()
        onNodeWithText("a port had nothing connected").assertExists()
    }

    @Test
    fun theStatusStripShowsPhaseSimulatedTimeAndTheSpeedKept() = runComposeUiTest {
        val rig = Rig()
        show(rig)
        rig.openSession()
        rig.listener.status(Fixtures.status(RobotPhase.RUNNING, 1_500_000_000L, 1.0, 0.8))
        waitForIdle()

        onNodeWithText("Running: Teleop A").assertExists()
        onNodeWithText("Sim 00:01.500").assertExists()
        onNodeWithText("Speed 0.80x of 1x").assertExists()
    }

    @Test
    fun aFailedSessionShowsWhyAndCanBeTriedAgainOrSetUpDifferently() = runComposeUiTest {
        val rig = Rig()
        show(rig)
        rig.listener.failed("the worker did not start")
        waitForIdle()

        onNodeWithText("The session could not go on").assertExists()
        onNodeWithText("the worker did not start").assertExists()
        onNodeWithText("Change the setup").performClick()
        assertEquals(1, rig.setupRequests)
        onNodeWithText("Try again").performClick()

        assertEquals(1, rig.retries)
    }

    @Test
    fun withoutASessionTheScreenOffersToSetOneUpAndNothingCanBeStarted() = runComposeUiTest {
        val rig = Rig()
        val fresh = PracticeSession(rig.feed, rig.camera).also { it.attach(StubLink()) }
        show(rig, fresh)
        waitForIdle()

        onNodeWithText("No practice session yet").assertExists()
        onNodeWithText("No session").assertExists()
        onNodeWithText("Init").assertIsNotEnabled()
        onNodeWithText("Set up a run").performClick()

        assertEquals(1, rig.setupRequests)
    }

    @Test
    fun whileTheLinkIsBeingOpenedTheScreenSaysSoAndNothingCanBeStarted() = runComposeUiTest {
        val rig = Rig()
        val waiting = PracticeSession(rig.feed, rig.camera)
        show(rig, waiting)
        waitForIdle()

        onNodeWithText("Connecting to the simulator...").assertExists()
        onNodeWithText("Init").assertIsNotEnabled()
        onNodeWithText("Match").assertIsNotEnabled()
    }

    @Test
    fun aLinkThatCannotBeOpenedShowsTheApplicationsMessageAndAWayToTryAgain() = runComposeUiTest {
        val rig = Rig()
        val broken = PracticeSession(rig.feed, rig.camera).also { it.unavailable("The worker could not be started: no Java found.") }
        show(rig, broken)
        waitForIdle()

        onNodeWithText("The simulator could not be started").assertExists()
        onNodeWithText("The worker could not be started: no Java found.").assertExists()
        onNodeWithText("Try again").performClick()

        assertEquals(1, rig.reconnects)
    }

    @Test
    fun aSessionAskedForBeforeTheLinkIsThereShowsConnectingNotOpening() = runComposeUiTest {
        val rig = Rig()
        val early = PracticeSession(rig.feed, rig.camera).also { it.open(Fixtures.request()) }
        show(rig, early)
        waitForIdle()
        onNodeWithText("Connecting to the simulator...").assertExists()

        early.attach(StubLink())
        waitForIdle()

        onNodeWithText("Connecting to the simulator...").assertDoesNotExist()
        onAllNodesWithText("Opening the session").onFirst().assertExists()
    }

    @Test
    fun anErrorThatWasAnnouncedIsNotAnnouncedAgainWhenTheScreenComesBack() = runComposeUiTest {
        val rig = Rig()
        var shown by mutableStateOf(true)
        setContent {
            FtcSimTheme(darkTheme = true) {
                if (shown) {
                    PracticeScreen(rig.session, rig.engine, rig.ui, {}, {}, viewport = { _, modifier -> Box(modifier) })
                }
            }
        }
        rig.openSession()
        rig.listener.status(Fixtures.status(error = "boom"))
        waitForIdle()
        onNodeWithText("Show").assertExists()

        shown = false
        waitForIdle()
        shown = true
        waitForIdle()

        onNodeWithText("Show").assertDoesNotExist()
    }

    @Test
    fun leavingTheScreenClosesItsMenusAndDialogsAndTheModalGate() = runComposeUiTest {
        val rig = Rig()
        var shown by mutableStateOf(true)
        setContent {
            FtcSimTheme(darkTheme = true) {
                if (shown) {
                    PracticeScreen(rig.session, rig.engine, rig.ui, {}, {}, viewport = { _, modifier -> Box(modifier) })
                }
            }
        }
        rig.openSession()
        waitForIdle()
        onNodeWithContentDescription("Reset the session").performClick()
        waitForIdle()
        assertTrue(rig.input.modalOpen())

        shown = false
        waitForIdle()

        assertFalse(rig.ui.resetDialogOpen)
        assertFalse(rig.input.modalOpen())
    }

    @Test
    fun theButtonsCanBeReachedAndPressedFromTheKeyboardAlone() = runComposeUiTest {
        val rig = Rig()
        show(rig)
        rig.openSession()
        waitForIdle()

        onNodeWithText("Init").requestFocus()
        onNodeWithText("Init").assertIsFocused()
        onNodeWithText("Init").performKeyInput { pressKey(Key.Enter) }

        assertEquals("init Teleop A", rig.link.calls.last())
    }

    // The timed match --------------------------------------------------------------------------------------------

    private fun radio(text: String) = isSelectable() and hasText(text)

    @Test
    fun theMatchDialogStartsFromTheFirstOpModeOfEachKindAndArmsWithThem() = runComposeUiTest {
        val rig = Rig()
        show(rig)
        rig.openSession()
        waitForIdle()

        onNodeWithText("Match").performClick()
        waitForIdle()
        assertTrue(rig.input.modalOpen(), "the dialog takes the keys away from the robot")
        onNodeWithText("Play a match").assertExists()
        onNode(radio("Auto B")).assertIsSelected()
        onNode(radio("Teleop A")).assertIsSelected()
        onNodeWithText("Arm").performClick()
        waitForIdle()

        assertEquals("armMatch 'Auto B' 'Teleop A'", rig.link.calls.last())
        assertFalse(rig.ui.matchDialogOpen)
        assertFalse(rig.input.modalOpen())
    }

    @Test
    fun theAutonomousPeriodIsOptionalAndNoneArmsAMatchStraightIntoTeleOp() = runComposeUiTest {
        val rig = Rig()
        show(rig)
        rig.openSession()
        waitForIdle()

        onNodeWithText("Match").performClick()
        waitForIdle()
        onNode(radio("None: the match starts with the driver-controlled period")).performClick()
        onNode(radio("Auto B")).assertIsNotSelected()
        onNodeWithText("Arm").performClick()
        waitForIdle()

        assertEquals("armMatch '' 'Teleop A'", rig.link.calls.last())
    }

    @Test
    fun cancellingTheMatchDialogArmsNothing() = runComposeUiTest {
        val rig = Rig()
        show(rig)
        rig.openSession()
        waitForIdle()
        val before = rig.link.calls.size

        onNodeWithText("Match").performClick()
        waitForIdle()
        onNodeWithText("Cancel").performClick()
        waitForIdle()

        assertEquals(before, rig.link.calls.size)
        assertFalse(rig.input.modalOpen())
    }

    @Test
    fun theMatchButtonIsOffWhileAnOpModeRunsAndSaysWhyToScreenReaders() = runComposeUiTest {
        val rig = Rig()
        show(rig)
        rig.openSession()
        rig.listener.status(Fixtures.status(RobotPhase.RUNNING))
        waitForIdle()

        onNodeWithText("Match").assertIsNotEnabled()
        onNodeWithText("Match").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Stop the OpMode first: a match starts from idle."),
        )
    }

    @Test
    fun anArmedMatchReplacesFreePracticeWithStartMatchAndStopAndShowsTheBanner() = runComposeUiTest {
        val rig = Rig()
        show(rig)
        rig.openSession()
        rig.session.armMatch("Auto B", "Teleop A")
        rig.listener.status(Fixtures.status(RobotPhase.INIT, match = Fixtures.match(MatchState.ARMED)))
        waitForIdle()

        onNodeWithText("Init").assertDoesNotExist()
        onNodeWithText("Match").assertDoesNotExist()
        onNodeWithText("Armed").assertExists()
        onNodeWithText("Autonomous: Auto B, TeleOp: Teleop A").assertExists()
        onNodeWithText("Press Start match when you are ready.").assertExists()
        onNodeWithText("Start match").assertIsEnabled()
        onNodeWithText("Start match").performClick()
        assertEquals("startMatch", rig.link.calls.last())
        onNodeWithText("Stop").performClick()
        assertEquals("stop", rig.link.calls.last())
    }

    @Test
    fun aMatchUnderWayShowsThePeriodAndTheCountdownTheStatusReportsAndOnlyOffersStop() = runComposeUiTest {
        val rig = Rig()
        show(rig)
        rig.openSession()
        rig.listener.status(Fixtures.status(RobotPhase.RUNNING, match = Fixtures.match(MatchState.AUTONOMOUS, 5, 30)))
        waitForIdle()

        onNodeWithText("Autonomous").assertExists()
        onNodeWithText("0:25").assertExists()
        onNodeWithText("Match: Autonomous 0:25 left").assertExists()
        onNodeWithText("Start match").assertDoesNotExist()
        onNodeWithText("Stop").assertIsEnabled()

        rig.listener.status(Fixtures.status(RobotPhase.RUNNING, match = Fixtures.match(MatchState.TRANSITION, 2, 8)))
        waitForIdle()
        onNodeWithText("Transition").assertExists()
        onNodeWithText("0:06").assertExists()

        rig.listener.status(Fixtures.status(RobotPhase.RUNNING, match = Fixtures.match(MatchState.TELEOP, 1, 120)))
        waitForIdle()
        onNodeWithText("TeleOp").assertExists()
        onNodeWithText("1:59").assertExists()
    }

    @Test
    fun aMatchThatEndedShowsItsFinalScoreGivesFreePracticeBackAndWantsAResetForAnother() = runComposeUiTest {
        val rig = Rig()
        show(rig)
        rig.openSession()
        rig.listener.status(
            Fixtures.status(match = Fixtures.match(MatchState.ENDED), score = Score(listOf(Score.Line("Goals", 3, 12)))),
        )
        waitForIdle()

        onNodeWithText("Ended").assertExists()
        onNodeWithText("Final score 12").assertExists()
        onNodeWithText("Reset the session to play another match.").assertExists()
        onNodeWithText("Init").assertIsEnabled()
        onNodeWithText("Match").assertIsNotEnabled()
        onNodeWithText("Match").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "A match has been played. Reset the session to play another."),
        )
    }

    @Test
    fun aRefusedCommandIsASnackbarAndTheSessionGoesOnAsItWas() = runComposeUiTest {
        val rig = Rig()
        show(rig)
        rig.openSession()
        waitForIdle()

        rig.listener.refused("An OpMode is running. Press Stop before arming a match.")
        waitForIdle()

        onNodeWithText("An OpMode is running. Press Stop before arming a match.").assertExists()
        onNodeWithText("The session could not go on").assertDoesNotExist()
        onNodeWithText("Init").assertIsEnabled()
        assertEquals(Connection.OPEN, rig.session.session.value.connection)
    }

    @Test
    fun aLaterRefusalReplacesTheEarlierOneInsteadOfQueueingBehindIt() = runComposeUiTest {
        val rig = Rig()
        show(rig)
        rig.openSession()
        waitForIdle()

        rig.listener.refused("first refusal")
        waitForIdle()
        rig.listener.refused("second refusal")
        waitForIdle()

        onNodeWithText("second refusal").assertExists()
        onNodeWithText("first refusal").assertDoesNotExist()
    }
}
