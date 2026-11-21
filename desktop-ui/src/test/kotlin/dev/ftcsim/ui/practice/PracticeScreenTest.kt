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
import dev.ftcsim.ui.common.FakeUrlOpener
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
        val urls = FakeUrlOpener()
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
                    urlOpener = rig.urls,
                    viewport = { _, modifier -> Box(modifier) },
                )
            }
        }
    }

    @Test
    fun theDashboardButtonIsThereOnlyWhileTheStatusNamesADashboardAndOpensItInTheBrowser() = runComposeUiTest {
        val rig = Rig()
        show(rig)
        rig.openSession()
        waitForIdle()
        onNodeWithText("Open dashboard").assertDoesNotExist()

        rig.listener.status(Fixtures.status(dashboardUrl = "http://127.0.0.1:8001/"))
        waitForIdle()
        onNodeWithText("Open dashboard").performClick()
        waitForIdle()
        assertEquals(listOf("http://127.0.0.1:8001/"), rig.urls.opened)

        rig.listener.status(Fixtures.status())
        waitForIdle()
        onNodeWithText("Open dashboard").assertDoesNotExist()
    }

    @Test
    fun aBrowserThatCannotBeOpenedIsSaidInASnackbar() = runComposeUiTest {
        val rig = Rig()
        rig.urls.problem = "No browser is set up on this computer."
        show(rig)
        rig.openSession()
        rig.listener.status(Fixtures.status(dashboardUrl = "http://127.0.0.1:8001/"))
        waitForIdle()

        onNodeWithText("Open dashboard").performClick()
        waitForIdle()

        onNodeWithText("No browser is set up on this computer.").assertExists()
    }

    @Test
    fun theDetailsPanelIsClosedUntilTheButtonOpensItAndClosesItAgain() = runComposeUiTest {
        val rig = Rig()
        show(rig)
        rig.openSession()
        waitForIdle()

        onNodeWithText("Telemetry").assertDoesNotExist()
        onNodeWithText("Details").performClick()
        onNodeWithText("Telemetry").assertExists()
        onNodeWithText("Details").performClick()
        onNodeWithText("Telemetry").assertDoesNotExist()
    }

    @Test
    fun theControlsAreDisabledWhileTheSessionOpens() = runComposeUiTest {
        val rig = Rig()
        show(rig)

        onNodeWithText("Starting").assertIsNotEnabled()
        onNodeWithText("Init").assertDoesNotExist()
        openMore()
        onNodeWithText("Match").assertIsNotEnabled()
        onNodeWithText("Pause").assertIsNotEnabled()
        onNodeWithText("Stop").assertIsNotEnabled()
        onNodeWithText("Speed 2x").assertIsNotEnabled()

        rig.openSession()
        waitForIdle()

        onNodeWithText("Match").assertIsEnabled()
    }

    @Test
    fun resetWorldDisablesTheMainButtonUntilTheNewSessionIsThere() = runComposeUiTest {
        val rig = Rig()
        show(rig)
        rig.openSession()
        waitForIdle()
        onNodeWithText("Init").performClick()
        rig.listener.status(Fixtures.status(phase = RobotPhase.INIT))
        waitForIdle()
        onNodeWithText("Start").assertIsEnabled()

        onNodeWithText("Reset world").performClick()
        waitForIdle()

        onNodeWithText("Resetting").assertIsNotEnabled()
        onNodeWithText("Start").assertDoesNotExist()
        rig.listener.status(Fixtures.status(phase = RobotPhase.INIT))
        waitForIdle()
        onNodeWithText("Start").assertDoesNotExist()

        rig.listener.opened(Fixtures.info())
        rig.listener.status(Fixtures.status(phase = RobotPhase.INIT))
        waitForIdle()
        onNodeWithText("Resetting").assertIsNotEnabled()
        onNodeWithText("Start").assertDoesNotExist()

        rig.openSession()
        waitForIdle()

        onNodeWithText("Init").assertIsEnabled()
    }

    @Test
    fun resetWorldIsTheButtonAndNeedsASessionAndRestartCodeIsInTheMenuBesideIt() = runComposeUiTest {
        val rig = Rig()
        show(rig)

        onNodeWithText("Reset world").assertIsNotEnabled()
        openRestart()
        onNodeWithText("Restart code").assertIsNotEnabled()
        rig.ui.restartMenuOpen = false
        waitForIdle()

        rig.openSession()
        waitForIdle()
        onNodeWithText("Reset world").assertIsEnabled()
        openRestart()
        onNodeWithText("Restart code").assertIsEnabled()
    }

    @Test
    fun theOneButtonIsInitThenStartThenStopAndSendsTheirCommands() = runComposeUiTest {
        val rig = Rig()
        show(rig)
        rig.openSession()
        waitForIdle()

        onNodeWithText("Start").assertDoesNotExist()
        onNodeWithText("Init").performClick()
        assertEquals("init Teleop A", rig.link.calls.last())

        rig.listener.status(Fixtures.status(RobotPhase.INIT))
        waitForIdle()
        onNodeWithText("Init").assertDoesNotExist()
        onNodeWithText("Start").assertIsEnabled()
        onNodeWithText("Start").performClick()
        assertEquals("start", rig.link.calls.last())

        rig.listener.status(Fixtures.status(RobotPhase.RUNNING))
        waitForIdle()
        onNodeWithText("Start").assertDoesNotExist()
        onNodeWithText("Teleop A").assertIsNotEnabled()
        onNodeWithText("Stop").performClick()
        assertEquals("stop", rig.link.calls.last())
    }

    @Test
    fun anInitialisedOpModeCanBeStoppedFromTheMenu() = runComposeUiTest {
        val rig = Rig()
        show(rig)
        rig.openSession()
        rig.listener.status(Fixtures.status(RobotPhase.INIT))
        waitForIdle()

        openMore()
        onNodeWithText("Stop").assertIsEnabled()
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
    fun theMenuOffersQuarterSpeedToFourTimesAndSendsTheChoice() = runComposeUiTest {
        val rig = Rig()
        show(rig)
        rig.openSession()
        waitForIdle()

        openMore()
        for (label in listOf("Speed 0.25x", "Speed 0.5x", "Speed 1x", "Speed 4x")) {
            onNodeWithText(label).assertExists()
        }
        onNodeWithText("Speed 2x").performClick()

        assertEquals("speed 2.0", rig.link.calls.last())
    }

    @Test
    fun theLabelOfThePauseItemFollowsTheStateTheLinkReports() = runComposeUiTest {
        val rig = Rig()
        show(rig)
        rig.openSession()
        waitForIdle()

        openMore()
        onNodeWithText("Pause").performClick()
        assertEquals("paused true", rig.link.calls.last())
        rig.listener.status(Fixtures.status(paused = true))
        waitForIdle()

        openMore()
        onNodeWithText("Resume").assertExists()
        onNodeWithText("Paused").assertExists()
    }

    @Test
    fun resetWorldAsksTheLinkAtOnceWithoutAQuestionAndTheSessionStaysOpen() = runComposeUiTest {
        val rig = Rig()
        show(rig)
        rig.openSession()
        waitForIdle()

        onNodeWithText("Reset world").performClick()
        waitForIdle()

        assertEquals("reset", rig.link.calls.last())
        assertFalse(rig.input.modalOpen(), "there is no dialog to answer")
        assertEquals(Connection.OPEN, rig.session.session.value.connection, "the window does not go back to opening")
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

        onNodeWithText("Session stopped").assertExists()
        onNodeWithText("the worker did not start").assertExists()
        onNodeWithText("Projects").performClick()
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

        onNodeWithText("No project open").assertExists()
        onNodeWithText("No session").assertExists()
        onNodeWithText("Init").assertIsNotEnabled()
        onNodeWithText("Open project").performClick()

        assertEquals(1, rig.setupRequests)
    }

    @Test
    fun whileTheLinkIsBeingOpenedTheScreenSaysSoAndNothingCanBeStarted() = runComposeUiTest {
        val rig = Rig()
        val waiting = PracticeSession(rig.feed, rig.camera)
        show(rig, waiting)
        waitForIdle()

        onNodeWithText("Connecting").assertExists()
        onNodeWithText("Init").assertIsNotEnabled()
        openMore()
        onNodeWithText("Match").assertIsNotEnabled()
    }

    @Test
    fun aLinkThatCannotBeOpenedShowsTheApplicationsMessageAndAWayToTryAgain() = runComposeUiTest {
        val rig = Rig()
        val broken = PracticeSession(rig.feed, rig.camera).also { it.unavailable("The worker could not be started: no Java found.") }
        show(rig, broken)
        waitForIdle()

        onNodeWithText("Simulator did not start").assertExists()
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
        onNodeWithText("Connecting").assertExists()

        early.attach(StubLink())
        waitForIdle()

        onNodeWithText("Connecting").assertDoesNotExist()
        onAllNodesWithText("Opening").onFirst().assertExists()
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
        openRestart()
        assertTrue(rig.input.modalOpen())

        shown = false
        waitForIdle()

        assertFalse(rig.ui.restartMenuOpen)
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

    private fun ComposeUiTest.openRestart() {
        onNodeWithContentDescription("Restart options").performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.openMore() {
        onNodeWithContentDescription("More").performClick()
        waitForIdle()
    }

    private fun radio(text: String) = isSelectable() and hasText(text)

    @Test
    fun theMatchDialogStartsFromTheFirstOpModeOfEachKindAndArmsWithThem() = runComposeUiTest {
        val rig = Rig()
        show(rig)
        rig.openSession()
        waitForIdle()

        openMore()
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

        openMore()
        onNodeWithText("Match").performClick()
        waitForIdle()
        onNode(radio("None")).performClick()
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

        openMore()
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

        openMore()
        onNodeWithText("Match").assertIsNotEnabled()
        onNodeWithText("Stop the OpMode first").assertExists()
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
        onNodeWithText("Reset the world to play another match.").assertExists()
        onNodeWithText("Init").assertIsEnabled()
        openMore()
        onNodeWithText("Match").assertIsNotEnabled()
        onNodeWithText("Reset the world to play another match").assertExists()
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
        onNodeWithText("Session stopped").assertDoesNotExist()
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
