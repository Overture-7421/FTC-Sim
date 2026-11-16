package dev.ftcsim.ui.project

import dev.ftcsim.ui.practice.Fixtures
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The OpMode a project opens with: what is kept in the user's settings and what a session picks again. */
class OpModeMemoryTest {
    private val rig = ProjectRig()
    private val scope = CoroutineScope(Dispatchers.Default)

    init {
        scope.launch { rig.ui.opModes.run() }
    }

    @AfterEach
    fun close() {
        scope.cancel()
        rig.close()
    }

    private fun opened(): Path {
        val project = rig.project("TeamProject")
        assertTrue(rig.runner.open(project))
        rig.built("program")
        rig.settle()
        rig.link.listener!!.opened(Fixtures.info())
        return project.folder
    }

    private fun remembered(folder: Path) = rig.settings.state.value.project(folder)?.opMode

    @Test
    fun theOpModeAPersonPicksIsKeptWithTheProjectAndTheOneASessionPicksItselfIsNot() {
        val folder = opened()
        assertEquals("Teleop A", rig.services.session.session.value.selectedOpMode, "a session picks the first by itself")
        assertNull(remembered(folder))

        rig.services.session.selectOpMode("Auto B")

        rig.await(rig.settings.state) { it.project(folder)?.opMode == "Auto B" }
    }

    @Test
    fun theAutonomousOfAnArmedMatchIsTheLatestChoiceToo() {
        val folder = opened()

        rig.services.session.armMatch("Auto B", "Teleop A")

        rig.await(rig.settings.state) { it.project(folder)?.opMode == "Auto B" }
    }

    @Test
    fun aSessionThatOpensLaterPicksTheRememberedOpModeAgainAndDoesNotKeepItselfAsAChoice() {
        val folder = opened()
        rig.services.session.selectOpMode("Auto B")
        rig.await(rig.settings.state) { it.project(folder)?.opMode == "Auto B" }
        rig.services.session.selectOpMode("Teleop A", picked = false)

        rig.link.listener!!.opened(Fixtures.info())

        rig.await(rig.services.session.session) { it.selectedOpMode == "Auto B" }
        assertEquals("Auto B", remembered(folder))
    }

    @Test
    fun anOpModeThatTheProgramNoLongerHasIsLeftAlone() {
        val folder = opened()
        rig.settings.update { it.withOpMode(folder, "Gone") }

        rig.link.listener!!.opened(Fixtures.info())

        assertEquals("Teleop A", rig.services.session.session.value.selectedOpMode)
    }
}
