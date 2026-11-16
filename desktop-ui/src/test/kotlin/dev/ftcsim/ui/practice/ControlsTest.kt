package dev.ftcsim.ui.practice

import dev.ftcsim.contracts.robot.OpModeDescriptor
import dev.ftcsim.contracts.robot.OpModeKind
import dev.ftcsim.contracts.robot.RobotPhase
import dev.ftcsim.contracts.session.MatchState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ControlsTest {
    private fun controls(
        connection: Connection = Connection.OPEN,
        phase: RobotPhase = RobotPhase.IDLE,
        selected: String? = "Teleop A",
        match: MatchState = MatchState.NONE,
        opModes: List<OpModeDescriptor> = Fixtures.opModes,
    ) = Controls.of(
        SessionState(connection, if (connection == Connection.OPEN) Fixtures.info(opModes) else null, selected),
        RunState(phase),
        MatchDisplay(match),
    )

    @Test
    fun nothingWorksBeforeASessionOrWhileItOpens() {
        for (connection in listOf(Connection.NONE, Connection.OPENING)) {
            assertEquals(
                Controls(false, false, false, false, false, false, false, "No session", false, false),
                controls(connection),
            )
        }
    }

    @Test
    fun aSessionThatFailedOffersOnlyARestartBecauseTheCodeMayBeWhatWasWrong() {
        val failed = controls(Connection.FAILED)

        assertTrue(failed.restart)
        assertEquals(Controls(false, false, false, false, false, false, true, "No session", false, false), failed)
    }

    @Test
    fun restartingTheCodeIsPossibleInEveryPhaseOfAnOpenSessionButNotWhileTheCodeIsBeingBuilt() {
        for (phase in RobotPhase.entries) {
            assertTrue(controls(phase = phase).restart, "phase $phase")
        }
        val building = Controls.of(SessionState(Connection.OPEN, Fixtures.info(), "Teleop A"), RunState(), building = true)

        assertFalse(building.restart, "a second restart waits for the first")
        assertTrue(building.pause && building.reset, "the session itself is untouched while the code builds")
    }

    @Test
    fun fromIdleOnlyInitIsPossibleAndOnlyWithAnOpModePicked() {
        val idle = controls()
        assertTrue(idle.selectOpMode)
        assertTrue(idle.init)
        assertFalse(idle.start)
        assertFalse(idle.stop)

        assertFalse(controls(selected = null).init)
        assertFalse(controls(selected = "Gone").init, "an OpMode the program no longer declares cannot be initialised")
    }

    @Test
    fun afterInitStartAndStopAreEnabledButInitAndTheChoiceOfOpModeAreNot() {
        val initialised = controls(phase = RobotPhase.INIT)

        assertTrue(initialised.start)
        assertTrue(initialised.stop)
        assertFalse(initialised.init)
        assertFalse(initialised.selectOpMode)
    }

    @Test
    fun whileRunningOnlyStopCanBePressed() {
        val running = controls(phase = RobotPhase.RUNNING)

        assertTrue(running.stop)
        assertFalse(running.start)
        assertFalse(running.init)
        assertFalse(running.selectOpMode)
    }

    @Test
    fun pauseAndResetWorkInEveryPhaseOfAnOpenSession() {
        for (phase in RobotPhase.entries) {
            val state = controls(phase = phase)
            assertTrue(state.pause && state.reset, "phase $phase")
        }
    }

    @Test
    fun anOpenSessionWithoutOpModesCannotInit() {
        val empty = controls(opModes = emptyList(), selected = null)

        assertFalse(empty.selectOpMode)
        assertFalse(empty.init)
    }

    @Test
    fun aMatchCanBeArmedFromIdleWhenTheProgramHasATeleOp() {
        val idle = controls()

        assertTrue(idle.arm)
        assertNull(idle.matchReason)
        assertFalse(idle.startMatch)
        assertFalse(idle.stopMatch)
    }

    @Test
    fun aMatchCannotBeArmedWhileAnOpModeIsInitialisedOrRunningAndTheReasonIsGiven() {
        for (phase in listOf(RobotPhase.INIT, RobotPhase.RUNNING)) {
            val busy = controls(phase = phase)
            assertFalse(busy.arm, "phase $phase")
            assertEquals("Stop the OpMode first", busy.matchReason)
        }
    }

    @Test
    fun aProgramWithoutATeleOpCannotPlayAMatch() {
        val autoOnly = controls(opModes = listOf(Fixtures.opModes[1]), selected = "Auto B")

        assertFalse(autoOnly.arm)
        assertEquals("No TeleOp OpMode", autoOnly.matchReason)
    }

    @Test
    fun anArmedMatchTakesTheControlsOverUntilItIsStartedOrGivenUp() {
        val armed = controls(phase = RobotPhase.INIT, match = MatchState.ARMED)

        assertTrue(armed.startMatch)
        assertTrue(armed.stopMatch)
        assertFalse(armed.init || armed.start || armed.stop || armed.selectOpMode, "free practice waits")
        assertFalse(armed.arm)
    }

    @Test
    fun aMatchUnderWayCanOnlyBeStopped() {
        for (state in listOf(MatchState.AUTONOMOUS, MatchState.TRANSITION, MatchState.TELEOP)) {
            val underWay = controls(phase = RobotPhase.RUNNING, match = state)

            assertTrue(underWay.stopMatch, "$state")
            assertFalse(underWay.startMatch, "$state")
            assertFalse(underWay.init || underWay.start || underWay.stop, "$state")
            assertFalse(underWay.arm, "$state")
        }
    }

    @Test
    fun aMatchThatHasBeenPlayedGivesFreePracticeBackButWantsAResetForTheNextMatch() {
        val ended = controls(match = MatchState.ENDED)

        assertTrue(ended.init)
        assertFalse(ended.stopMatch)
        assertFalse(ended.arm)
        assertEquals("Reset the world to play another match", ended.matchReason)
    }

    @Test
    fun theSuggestedOpModesAreThePickedOnesOrTheFirstOfEachKind() {
        val modes = listOf(
            OpModeDescriptor("Auto 1", "", OpModeKind.AUTONOMOUS, "a.One"),
            OpModeDescriptor("Auto 2", "", OpModeKind.AUTONOMOUS, "a.Two"),
            OpModeDescriptor("Drive 1", "", OpModeKind.TELEOP, "d.One"),
            OpModeDescriptor("Drive 2", "", OpModeKind.TELEOP, "d.Two"),
        )

        assertEquals(MatchPlan("Auto 1", "Drive 1"), MatchPlan.suggest(modes, null))
        assertEquals(MatchPlan("Auto 2", "Drive 1"), MatchPlan.suggest(modes, "Auto 2"))
        assertEquals(MatchPlan("Auto 1", "Drive 2"), MatchPlan.suggest(modes, "Drive 2"))
        assertEquals(MatchPlan(null, "Drive 1"), MatchPlan.suggest(modes.filter { it.kind() == OpModeKind.TELEOP }, null), "no autonomous OpMode, no autonomous period")
        assertNull(MatchPlan.suggest(modes.filter { it.kind() == OpModeKind.AUTONOMOUS }, null), "a match needs a teleop OpMode")
    }
}
