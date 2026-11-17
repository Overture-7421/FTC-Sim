package dev.ftcsim.ui.practice

import dev.ftcsim.contracts.field.Alliance
import dev.ftcsim.contracts.field.Score
import dev.ftcsim.contracts.robot.GamepadState
import dev.ftcsim.contracts.robot.RobotPhase
import dev.ftcsim.contracts.robot.TelemetrySnapshot
import dev.ftcsim.contracts.session.MatchState
import dev.ftcsim.contracts.session.Readout
import dev.ftcsim.contracts.snapshot.BodyKinds
import dev.ftcsim.contracts.snapshot.BodyState
import dev.ftcsim.contracts.snapshot.WorldSnapshot
import dev.ftcsim.ui.render.OrbitCamera
import dev.ftcsim.ui.prepare.OutputLog
import dev.ftcsim.ui.scene.ViewportFeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Calls the listener methods by hand from the test thread, the way the link's thread would, one at a time. */
class PracticeSessionTest {
    private val link = StubLink()
    private val feed = ViewportFeed()
    private val camera = OrbitCamera()
    private val session = PracticeSession(feed, camera).also { it.attach(link) }

    private fun open() {
        session.open(Fixtures.request())
        link.listener!!.opened(Fixtures.info())
    }

    @Test
    fun openingASessionDrawsTheRobotInTheColourOfItsAllianceAndTheDriversViewIsFromTheAudienceForACornerStart() {
        session.open(Fixtures.request(alliance = Alliance.BLUE))
        link.listener!!.opened(Fixtures.info())
        val blue = feed.scene()!!.parts(BodyKinds.CHASSIS)[0]
        assertTrue(blue.blue() > blue.red() + 0.4f, "a blue alliance robot is blue")

        session.open(Fixtures.request(alliance = Alliance.RED, startHeading = Math.toRadians(90.0)))
        link.listener!!.opened(Fixtures.info())
        val red = feed.scene()!!.parts(BodyKinds.CHASSIS)[0]
        assertTrue(red.red() > red.blue() + 0.4f, "a red alliance robot is red")
        camera.select(OrbitCamera.Preset.DRIVER)
        assertEquals(-90.0, camera.yawDegrees(), 1e-9, "a robot square to the walls is watched from behind, along its heading")

        session.open(Fixtures.request(startHeading = Math.toRadians(36.0)))
        assertEquals(0.0, camera.yawDegrees(), 1e-9, "a robot in a corner is watched from the audience, which is at +X")
    }

    @Test
    fun beforeAnythingIsOpenedThereIsNoSessionAndTheLinkIsOnlyListenedTo() {
        assertEquals(Connection.NONE, session.session.value.connection)
        assertEquals(listOf("addListener"), link.calls)
        assertFalse(Controls.of(session.session.value, session.run.value).init)
    }

    @Test
    fun openingAsksForTheSessionAtOnceEachTime() {
        session.open(Fixtures.request())
        session.open(Fixtures.request())

        assertEquals(listOf("addListener", "open", "open"), link.calls)
        assertEquals(Connection.OPENING, session.session.value.connection)
    }

    @Test
    fun aSessionAskedForBeforeTheLinkExistsOpensWhenTheLinkArrives() {
        val early = PracticeSession(ViewportFeed(), OrbitCamera())
        val late = StubLink()
        assertEquals(LinkState.Connecting, early.link.value)

        early.open(Fixtures.request(seed = 5L))
        assertEquals(Connection.OPENING, early.session.value.connection)
        early.attach(late)

        assertEquals(listOf("addListener", "open"), late.calls)
        assertEquals(5L, late.requests.single().seed())
        assertEquals(LinkState.Connected, early.link.value)
    }

    @Test
    fun aLinkThatCannotBeOpenedIsReportedWithTheApplicationsWordsAndCanBeAwaitedAgain() {
        val waiting = PracticeSession(ViewportFeed(), OrbitCamera())

        waiting.unavailable("The worker could not be started.")
        assertEquals(LinkState.Unavailable("The worker could not be started."), waiting.link.value)

        waiting.connecting()
        assertEquals(LinkState.Connecting, waiting.link.value)
    }

    @Test
    fun attachingAnotherLinkGivesBackTheOneItReplacedAndNothingTheFirstSaysIsHeardAgain() {
        val second = StubLink()

        val replaced = session.attach(second)

        assertSame(link, replaced)
        assertNull(PracticeSession(ViewportFeed(), OrbitCamera()).attach(StubLink()), "a first link replaces nothing")
        session.open(Fixtures.request())
        link.listener!!.opened(Fixtures.info())
        assertEquals(Connection.OPENING, session.session.value.connection, "the replaced link is not heard")
        second.listener!!.opened(Fixtures.info())
        assertEquals(Connection.OPEN, session.session.value.connection)
    }

    @Test
    fun whatAReplacedLinkSaysIsNotHeard() {
        open()
        val old = link.listener!!
        val replaced = session.detach()
        assertSame(link, replaced)
        val next = StubLink()
        session.attach(next)

        old.failed("the old worker died")
        old.status(Fixtures.status(warnings = "stale"))

        assertEquals(Connection.OPEN, session.session.value.connection, "the old events changed nothing")
        assertTrue(session.problems.value.isEmpty())
        next.listener!!.failed("the new worker died")
        assertEquals(Connection.FAILED, session.session.value.connection)
    }

    @Test
    fun restartingGivesBackTheOldLinkAndAsksForTheSameSessionOnTheNextOne() {
        session.open(Fixtures.request(seed = 9L))
        link.listener!!.failed("the worker died")

        val old = session.restart()
        assertSame(link, old)
        assertEquals(Connection.OPENING, session.session.value.connection)
        assertTrue(session.problems.value.isEmpty(), "the failure belongs to the link that is gone")
        assertEquals(LinkState.Connecting, session.link.value)

        val fresh = StubLink()
        session.attach(fresh)
        assertEquals(listOf("addListener", "open"), fresh.calls)
        assertEquals(9L, fresh.requests.single().seed())
    }

    @Test
    fun theCatalogueIsKeptAndSurvivesTheLinkBeingReplaced() {
        assertNull(session.catalogue.value)

        link.listener!!.catalogue(Fixtures.catalogue())
        assertEquals(listOf("goBILDA-bare-6000", "rev-hd-hex-6000"), session.catalogue.value!!.motors().map { it.id() })

        session.detach()
        session.attach(StubLink())
        assertNotNull(session.catalogue.value, "until the next link says otherwise")
    }

    @Test
    fun theLogOfTheRobotProgramFillsTheConsoleInOrderAndAnotherSessionStartsItAgain() {
        open()

        link.listener!!.log(listOf("I/System.out: first", "W/Robot: second"))
        link.listener!!.log(listOf("E/Robot: third"))

        assertEquals(listOf("I/System.out: first", "W/Robot: second", "E/Robot: third"), session.console.view().lines)

        link.listener!!.opened(Fixtures.info())

        assertTrue(session.console.view().lines.isEmpty(), "a new session has a new log")
    }

    @Test
    fun theConsoleKeepsOnlyTheNewestLinesWhateverIsLogged() {
        open()

        link.listener!!.log((1..OutputLog.DEFAULT_CAPACITY + 50).map { "D/Loop: $it" })

        val view = session.console.view()
        assertEquals(OutputLog.DEFAULT_CAPACITY, view.lines.size)
        assertEquals("D/Loop: ${OutputLog.DEFAULT_CAPACITY + 50}", view.lines.last())
        assertEquals(50L, view.first, "the lines that were dropped are counted")
    }

    @Test
    fun aRefusedCommandIsAnnouncedAndTheSessionGoesOnAsItWas() = runBlocking {
        open()
        val seen = ArrayList<String>()
        val collecting = launch(Dispatchers.Unconfined) { session.refusals.collect { seen += it } }

        link.listener!!.refused("An OpMode is running. Press Stop before arming a match.")
        collecting.cancel()

        assertEquals(listOf("An OpMode is running. Press Stop before arming a match."), seen)
        assertEquals(Connection.OPEN, session.session.value.connection)
        assertTrue(session.problems.value.isEmpty(), "a refusal is not a problem of the run")
    }

    @Test
    fun aFailureEndsTheSessionButARefusalDoesNot() {
        open()

        link.listener!!.refused("No match is armed.")
        assertEquals(Connection.OPEN, session.session.value.connection)

        link.listener!!.failed("the worker died")
        assertEquals(Connection.FAILED, session.session.value.connection)
    }

    @Test
    fun openingAnotherRunWhileOneIsOpenStartsOverFromTheOpeningState() {
        open()
        link.listener!!.status(Fixtures.status(warnings = "Low battery"))
        val other = Fixtures.request(seed = 7L)

        session.open(other)

        assertEquals(Connection.OPENING, session.session.value.connection)
        assertNull(session.session.value.info)
        assertTrue(session.problems.value.isEmpty(), "what the old session reported does not belong to the new one")
        assertEquals(other, link.requests.last())
    }

    @Test
    fun theRunOfAnEndedSessionDoesNotShowOnTheNewOneAfterAReopenOrAReset() {
        open()
        link.listener!!.status(Fixtures.status(phase = RobotPhase.RUNNING, paused = true, speed = 2.0))
        assertEquals(RobotPhase.RUNNING, session.run.value.phase)

        session.open(Fixtures.request(seed = 7L))
        assertEquals(RunState(), session.run.value, "asking for a session at once clears the old run")

        link.listener!!.opened(Fixtures.info())
        link.listener!!.status(Fixtures.status(phase = RobotPhase.INIT))
        link.listener!!.opened(Fixtures.info())
        assertEquals(RobotPhase.IDLE, session.run.value.phase, "a reset opens the session again before its first status")
        assertFalse(Controls.of(session.session.value, session.run.value).start)
    }

    @Test
    fun resetWorldHoldsTheSessionUntilTheNewOneIsIdleFailsOrTheResetIsRefused() {
        open()
        assertEquals(emptySet<Hold>(), session.holds.value)

        session.reset()
        assertEquals(setOf(Hold.RESETTING), session.holds.value)
        link.listener!!.status(Fixtures.status(phase = RobotPhase.IDLE))
        assertEquals(setOf(Hold.RESETTING), session.holds.value, "an Idle status of the old session is not the end of the reset")
        link.listener!!.opened(Fixtures.info())
        assertEquals(setOf(Hold.RESETTING), session.holds.value, "the program may still be stopping when the session opens")
        link.listener!!.status(Fixtures.status(phase = RobotPhase.INIT))
        assertEquals(setOf(Hold.RESETTING), session.holds.value)
        link.listener!!.status(Fixtures.status(phase = RobotPhase.IDLE))
        assertEquals(emptySet<Hold>(), session.holds.value, "the new session is Idle")

        session.reset()
        link.listener!!.refused("Not now.")
        assertEquals(emptySet<Hold>(), session.holds.value, "a refused reset leaves the old session running")

        session.reset()
        link.listener!!.failed("the worker died")
        assertEquals(emptySet<Hold>(), session.holds.value)
    }

    @Test
    fun aHoldClaimedWhenTheSessionOpensIsThereBeforeTheSessionShowsAsOpenAndOnlyItsOwnerEndsIt() {
        session.whenOpened { session.hold(Hold.SCANNING) }
        session.open(Fixtures.request())

        link.listener!!.opened(Fixtures.info())
        assertEquals(Connection.OPEN to setOf(Hold.SCANNING), session.session.value.connection to session.holds.value)
        val opening = session.openings.value

        session.release(Hold.SCANNING, opening - 1)
        assertEquals(setOf(Hold.SCANNING), session.holds.value, "the owner of an earlier session cannot end it")
        session.release(Hold.SCANNING, session.hold(Hold.SCANNING))
        assertEquals(emptySet<Hold>(), session.holds.value)

        session.hold(Hold.SCANNING)
        session.open(Fixtures.request(seed = 7L))
        assertEquals(emptySet<Hold>(), session.holds.value, "asking for another session drops what held the old one")
    }

    @Test
    fun anOpenedSessionOffersItsOpModesPicksTheFirstAndGivesTheViewportItsScene() {
        open()

        val state = session.session.value
        assertEquals(Connection.OPEN, state.connection)
        assertEquals(listOf("Teleop A", "Auto B"), state.opModes.map { it.name() })
        assertEquals("Teleop A", state.selectedOpMode)
        assertNotNull(feed.scene())
    }

    @Test
    fun theDriversChoiceOfOpModeSurvivesTheSessionOpeningAgain() {
        open()
        session.selectOpMode("Auto B")

        link.listener!!.opened(Fixtures.info())

        assertEquals("Auto B", session.session.value.selectedOpMode)
    }

    @Test
    fun anUnknownOpModeCannotBeSelected() {
        open()

        session.selectOpMode("Nothing")

        assertEquals("Teleop A", session.session.value.selectedOpMode)
    }

    @Test
    fun driverStationCommandsGoToTheLink() {
        open()
        link.calls.clear()

        session.selectOpMode("Auto B")
        session.init()
        session.start()
        session.stop()
        session.setSpeed(0.5)
        session.reset()
        session.humanAction("feed")

        assertEquals(listOf("init Auto B", "start", "stop", "speed 0.5", "reset", "humanAction feed"), link.calls)
    }

    @Test
    fun armingAMatchSendsTheNamesOfBothOpModesAndRemembersThemAndAnEmptyNameMeansNoAutonomous() {
        open()
        link.calls.clear()

        session.armMatch("Auto B", "Teleop A")
        assertEquals(MatchPlan("Auto B", "Teleop A"), session.matchPlan.value)
        session.armMatch(null, "Teleop A")
        assertEquals(MatchPlan(null, "Teleop A"), session.matchPlan.value)
        session.startMatch()

        assertEquals(listOf("armMatch 'Auto B' 'Teleop A'", "armMatch '' 'Teleop A'", "startMatch"), link.calls)
    }

    @Test
    fun theMatchStateComesFromTheStatusAndOnlyChangesWhenTheSecondsDo() = runBlocking {
        open()
        val seen = ArrayList<MatchDisplay>()
        val collecting = launch(Dispatchers.Unconfined) { session.match.collect { seen += it } }

        link.listener!!.status(Fixtures.status(match = Fixtures.match(MatchState.ARMED)))
        link.listener!!.status(Fixtures.status(match = Fixtures.match(MatchState.AUTONOMOUS, 5, 30)))
        link.listener!!.status(Fixtures.status(simTimeNanos = 1_000_000L, match = Fixtures.match(MatchState.AUTONOMOUS, 5, 30)))
        link.listener!!.status(Fixtures.status(match = Fixtures.match(MatchState.AUTONOMOUS, 6, 30)))
        collecting.cancel()

        assertEquals(
            listOf(
                MatchDisplay.NONE,
                MatchDisplay(MatchState.ARMED),
                MatchDisplay(MatchState.AUTONOMOUS, 25L, 30L),
                MatchDisplay(MatchState.AUTONOMOUS, 24L, 30L),
            ),
            seen,
        )
    }

    @Test
    fun aNewSessionHasNoMatchAndForgetsTheOpModesThatWereArmed() {
        open()
        session.armMatch("Auto B", "Teleop A")
        link.listener!!.status(Fixtures.status(match = Fixtures.match(MatchState.ARMED)))
        assertEquals(MatchState.ARMED, session.match.value.state)

        session.reset()
        link.listener!!.opened(Fixtures.info())

        assertEquals(MatchDisplay.NONE, session.match.value)
        assertNull(session.matchPlan.value)
    }

    @Test
    fun pausingAsksForTheOppositeOfWhatTheLatestStatusReports() {
        open()
        link.calls.clear()

        session.togglePause()
        link.listener!!.status(Fixtures.status(paused = true))
        session.togglePause()

        assertEquals(listOf("paused true", "paused false"), link.calls)
    }

    @Test
    fun theStatusIsSplitIntoPartsThatEachChangeAsRarelyAsTheirContent() {
        open()
        val telemetry = listOf("Pose: x=1")
        val hardware = listOf(Readout("frontLeft", "10 ticks/s"))

        link.listener!!.status(
            Fixtures.status(RobotPhase.RUNNING, 1_500_000_000L, 2.0, 1.9, false, telemetry, hardware = hardware, score = Score(listOf(Score.Line("Goals", 2, 6)))),
        )

        assertEquals(RunState(RobotPhase.RUNNING, false, 2.0), session.run.value)
        assertEquals(Timing(1_500_000_000L, 1.9), session.timing.value)
        assertEquals(telemetry, session.telemetry.value.lines())
        assertEquals(hardware, session.hardware.value)
        assertEquals(6, session.score.value.total())
    }

    @Test
    fun aPartThatDidNotChangeIsNotEmittedAgain() = runBlocking {
        open()
        val seen = ArrayList<TelemetrySnapshot>()
        val collecting = launch(Dispatchers.Unconfined) { session.telemetry.collect { seen += it } }

        link.listener!!.status(Fixtures.status(lines = listOf("a")))
        link.listener!!.status(Fixtures.status(simTimeNanos = 5_000_000L, lines = listOf("a")))
        link.listener!!.status(Fixtures.status(simTimeNanos = 300_000_000L, lines = listOf("a")))
        collecting.cancel()

        assertEquals(2, seen.size, "the empty telemetry it started with and the first change; not the repeats")
        assertEquals(300_000_000L, session.timing.value.simTimeNanos, "the clock is its own part and did change")
    }

    @Test
    fun theClockOnlyUpdatesTenTimesASecondOfSimulatedTimeAndFollowsARestart() {
        open()

        link.listener!!.status(Fixtures.status(simTimeNanos = 1_000_000_000L))
        link.listener!!.status(Fixtures.status(simTimeNanos = 1_050_000_000L))
        assertEquals(1_000_000_000L, session.timing.value.simTimeNanos)
        link.listener!!.status(Fixtures.status(simTimeNanos = 1_100_000_000L))
        assertEquals(1_100_000_000L, session.timing.value.simTimeNanos)

        link.listener!!.status(Fixtures.status(simTimeNanos = 20_000_000L))
        assertEquals(20_000_000L, session.timing.value.simTimeNanos, "a reset starts the clock over at once")
    }

    @Test
    fun problemsCollectWhatTheStatusAndTheFailureReport() {
        open()

        link.listener!!.status(Fixtures.status(warnings = "Low battery", problems = listOf("a port had nothing connected")))
        assertEquals(listOf(Severity.WARNING, Severity.WARNING), session.problems.value.map { it.severity })

        link.listener!!.failed("the worker died")
        assertEquals(Severity.ERROR, session.problems.value.first().severity)
        assertEquals(Connection.FAILED, session.session.value.connection)
    }

    @Test
    fun resettingKeepsTheSessionOpenAndTheOpModeWhenTheLinkAnnouncesTheWorldAgain() {
        open()
        session.selectOpMode("Auto B")

        session.reset()

        assertEquals(Connection.OPEN, session.session.value.connection, "a reset is quick, so the window does not go back to opening")
        assertEquals("reset", link.calls.last())
        link.listener!!.opened(Fixtures.info())
        assertEquals(Connection.OPEN, session.session.value.connection)
        assertEquals("Auto B", session.session.value.selectedOpMode)
    }

    @Test
    fun resettingWithoutASessionAsksTheLinkAndChangesNothing() {
        session.reset()

        assertEquals(Connection.NONE, session.session.value.connection)
    }

    @Test
    fun snapshotsGoToTheViewportAndOnlyTheNewestMatters() {
        open()
        val bodies = listOf(BodyState(1, 1, 0f, 0f, 0f, 0f, 0f, 0f, 1f))

        link.listener!!.snapshot(WorldSnapshot(1, 4_000_000L, bodies))
        link.listener!!.snapshot(WorldSnapshot(2, 8_000_000L, bodies))

        assertEquals(2L, feed.mailbox().latest()!!.current().stepIndex())
        assertEquals(1L, feed.mailbox().latest()!!.previous().stepIndex())
    }

    @Test
    fun aGamepadIsRememberedUntilTheSessionIsOpenAndThenReportedAtOnce() {
        session.open(Fixtures.request())
        val driving = GamepadState.sticks(0f, -1f, 0f, 0f)

        session.setGamepad(1, driving)
        assertNull(link.gamepads[1], "nothing is sent before the session is open")

        link.listener!!.opened(Fixtures.info())
        assertEquals(driving, link.gamepads[1])
        assertEquals(GamepadState.NEUTRAL, link.gamepads[2], "a gamepad nobody touches is still reported, as neutral")

        link.calls.clear()
        session.setGamepad(2, driving)
        assertEquals(listOf("gamepad 2"), link.calls)
    }

    @Test
    fun aSecondOpenedEventReportsTheGamepadsAgainForTheFreshSession() {
        open()
        session.setGamepad(1, GamepadState.sticks(1f, 0f, 0f, 0f))
        link.calls.clear()

        link.listener!!.opened(Fixtures.info())

        assertEquals(listOf("gamepad 1", "gamepad 2"), link.calls)
        assertFalse(link.gamepads[1] == GamepadState.NEUTRAL)
    }
}
