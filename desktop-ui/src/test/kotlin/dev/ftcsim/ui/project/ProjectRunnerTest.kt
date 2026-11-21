package dev.ftcsim.ui.project

import dev.ftcsim.contracts.field.Alliance
import dev.ftcsim.ui.nav.Destination
import dev.ftcsim.ui.practice.Connection
import dev.ftcsim.ui.practice.Fixtures
import dev.ftcsim.ui.practice.Severity
import dev.ftcsim.ui.prepare.PrepareState
import dev.ftcsim.ui.settings.RecentProject
import dev.ftcsim.ui.setup.SetupInput
import dev.ftcsim.ui.setup.SetupRules
import java.io.IOException
import java.nio.file.Path
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The runner from the project folder to the session and back again for a restart, on a preparer that the test finishes
 * by hand, and a link it speaks for.
 */
class ProjectRunnerTest {
    private val rig = ProjectRig()

    @AfterEach
    fun close() = rig.close()

    /** Opens a project that ran before, so that its game is not asked, so that it is not asked, and brings its session to the open state. */
    private fun openedProject(name: String = "TeamProject", run: SetupInput = SetupInput(field = "decode"), program: String = "program"): Path {
        val project = rig.project(name, run)
        assertTrue(rig.runner.open(project))
        rig.built(program)
        assertEquals(ProjectActivity.Idle, rig.settle())
        rig.link.listener!!.opened(Fixtures.info())
        return project.folder
    }

    @Test
    fun openingAProjectBuildsItStartsTheSessionOnItsProgramAndRemembersTheProject() {
        val project = rig.project("TeamProject", SetupInput(field = "decode", alliance = Alliance.BLUE))

        assertTrue(rig.runner.open(project))
        assertEquals(ProjectActivity.Building(project, restarting = false), rig.runner.activity.value)
        assertEquals(Destination.PROJECTS, rig.ui.navigator.state.value.current, "the build is shown on the Projects screen")
        assertEquals(project.folder, rig.preparer.requests.single().projectFolder())
        assertTrue(rig.preparer.requests.single().trusted(), "choosing the folder is the consent")
        assertTrue(rig.link.requests.isEmpty(), "nothing starts before the build is done")

        rig.built("program-one")
        rig.settle()

        val request = rig.link.requests.single()
        assertEquals(rig.work.resolve("program-one").toString(), request.program())
        assertEquals(Alliance.BLUE, request.alliance(), "the project was last run for the blue alliance")
        assertEquals("decode", request.field())
        assertEquals(ProjectRig.SEED, request.seed())
        assertEquals(Destination.PRACTICE, rig.ui.navigator.state.value.current)
        assertEquals(project.name, rig.runner.open.value?.project?.name)
        assertEquals(listOf("TeamProject"), rig.settings.state.value.projects.map { it.name })
        assertTrue(rig.runner.problems.value.isEmpty())
    }

    @Test
    fun aProjectThatNeverRanHereWaitsForItsGameAndStartsOnlyAfterTheChoice() {
        val project = rig.project("TeamProject", run = null)
        rig.runner.open(project)
        rig.built()

        val waiting = assertInstanceOf(ProjectActivity.NeedsGame::class.java, rig.settle())

        assertEquals(project, waiting.project)
        assertTrue(rig.link.requests.isEmpty(), "the session waits for the choice")
        assertEquals("decode", rig.ui.setup.input.field, "the newest game on offer is chosen already")
        assertEquals(Destination.PROJECTS, rig.ui.navigator.state.value.current)

        assertEquals(ProjectActivity.Idle, rig.chooseGame())
        assertEquals("decode", rig.link.requests.single().field())
        assertEquals(Destination.PRACTICE, rig.ui.navigator.state.value.current)
    }

    @Test
    fun whatTheProjectWasLastRunWithIsUsedExceptTheSeedWhichIsAlwaysDrawn() {
        val project = rig.project("TeamProject", run = null)
        val remembered = SetupInput(field = "decode", alliance = Alliance.RED, randomSeed = false, seedText = "77", startPose = "Centre")
        rig.settings.update { it.withProject(RecentProject(project.folder.toString(), project.name, remembered)) }

        rig.runner.open(project)
        rig.built()
        rig.settle()

        val request = rig.link.requests.single()
        assertEquals(Alliance.RED, request.alliance())
        assertEquals("Centre", rig.settings.state.value.projects.single().run?.startPose)
        assertEquals(0.0, request.startX())
        assertEquals(ProjectRig.SEED, request.seed(), "there is no control for the seed, so a saved fixed one is not used")
        assertTrue(rig.settings.state.value.projects.single().run!!.randomSeed)
    }

    @Test
    fun aRunThatCannotBeReadBackFromWhatWasRememberedStartsFromTheDefaultsInstead() {
        val project = rig.project()
        val rubbish = SetupInput(field = "chess", alliance = Alliance.BLUE)
        rig.settings.update { it.withProject(RecentProject(project.folder.toString(), project.name, rubbish)) }

        rig.runner.open(project)
        rig.built()
        rig.settle()

        assertEquals("decode", rig.link.requests.single().field())
    }

    @Test
    fun aBuildThatFailsWhileOpeningIsSaidAndNoSessionStarts() {
        val project = rig.project()
        rig.runner.open(project)

        rig.preparer.fail(code = "COMPILATION_FAILED", message = "The project does not compile.")
        val failed = assertInstanceOf(ProjectActivity.Failed::class.java, rig.settle())

        assertEquals("The project does not compile.", failed.message)
        assertTrue(rig.link.requests.isEmpty())
        assertEquals(Destination.PROJECTS, rig.ui.navigator.state.value.current, "the Projects screen has the details")
        rig.runner.dismissFailure()
        assertEquals(ProjectActivity.Idle, rig.runner.activity.value)
    }

    @Test
    fun theWarningsOfABuildAreListedAsProblems() {
        val project = rig.project("TeamProject")
        rig.runner.open(project)

        rig.preparer.finish(rig.work.resolve("program"), warnings = listOf("Drive.run() uses reflection"))
        rig.settle()

        val problem = rig.runner.problems.value.single()
        assertEquals("Build", problem.source)
        assertEquals("Drive.run() uses reflection", problem.text)
    }

    @Test
    fun aSecondProjectCannotBeOpenedWhileOneIsBeingBuilt() {
        val first = rig.project("First")
        val second = rig.project("Second")
        assertTrue(rig.runner.open(first))

        assertFalse(rig.runner.open(second))

        assertEquals(ProjectActivity.Building(first, restarting = false), rig.runner.activity.value)
        assertEquals(1, rig.preparer.requests.size)
    }

    @Test
    fun restartingBuildsTheProjectAgainAndStartsTheSameRunOnTheNewProgramAtTheSpeedItHasNow() {
        val folder = openedProject(program = "program-one", run = SetupInput(field = "decode", alliance = Alliance.BLUE, startPose = "BLUE goal side"))
        rig.link.listener!!.status(Fixtures.status(speed = 2.0))
        val before = rig.link.requests.single()

        assertTrue(rig.runner.restart())
        assertEquals(ProjectActivity.Building(rig.runner.open.value!!.project, restarting = true), rig.runner.activity.value)
        assertEquals(Destination.PRACTICE, rig.ui.navigator.state.value.current, "a restart is done where the person is")
        assertEquals(1, rig.link.requests.size, "the session goes on and is not replaced until the new code is ready")
        rig.built("program-two")
        rig.settle()
        rig.awaitLinks(2)

        val after = rig.link.requests.single()
        assertEquals(2, rig.links.size, "the new code runs on a fresh worker")
        assertEquals(rig.work.resolve("program-two").toString(), after.program())
        assertTrue(before.program() != after.program())
        assertEquals(before.field(), after.field())
        assertEquals(before.alliance(), after.alliance())
        assertEquals(before.seed(), after.seed(), "the seed of the run on screen is kept")
        assertEquals(listOf(before.startX(), before.startY(), before.startHeading()), listOf(after.startX(), after.startY(), after.startHeading()))
        assertEquals(2.0, after.speed())
        assertEquals(1, rig.seedsDrawn.get(), "no new seed was drawn for a restart")
        assertEquals(folder, rig.preparer.requests.last().projectFolder())
        assertEquals(rig.work.resolve("program-two"), rig.runner.open.value!!.program)
    }

    @Test
    fun aRestartThatFailsLeavesTheSessionAsItWasAndTheFailureIsAProblemWithItsDetails() {
        openedProject()
        rig.runner.restart()

        rig.preparer.fail(code = "COMPILATION_FAILED", message = "Your code does not compile.", details = listOf("Drive.java:12: error: ';' expected"))
        assertEquals(ProjectActivity.Idle, rig.settle())
        rig.awaitMessages(1)

        assertEquals(1, rig.link.requests.size, "the session that runs was not touched")
        assertEquals(Connection.OPEN, rig.services.session.session.value.connection)
        val problem = rig.runner.problems.value.single()
        assertEquals(Severity.ERROR, problem.severity)
        assertTrue(problem.text.contains("Your code does not compile.") && problem.text.contains("Drive.java:12"), problem.text)
        assertEquals(listOf(RunnerMessage("Build failed", showProblems = true)), rig.messages)
        assertTrue(rig.runner.restart(), "and it can be tried again")
    }

    @Test
    fun aProjectThatChangedWhileItWasCopiedIsBuiltOnceMoreAndAfterThatTheFailureIsSaid() {
        openedProject()
        rig.runner.restart()

        rig.preparer.fail(code = ProjectRunner.ORIGINAL_CHANGED, message = "The original project changed.")
        assertEquals(3, rig.preparer.requests.size, "the project was built once more, with what is there now")
        assertTrue(rig.runner.activity.value is ProjectActivity.Building, "still building")

        rig.preparer.fail(code = ProjectRunner.ORIGINAL_CHANGED, message = "The original project changed.")
        rig.settle()
        assertEquals(3, rig.preparer.requests.size, "no third try")
        assertEquals(1, rig.link.requests.size)
        assertEquals(Severity.ERROR, rig.runner.problems.value.single().severity)
    }

    @Test
    fun cancellingTheBuildEndsItQuietlyAndTheSessionGoesOn() {
        openedProject()
        rig.runner.restart()

        rig.runner.cancel()
        assertEquals(1, rig.preparer.cancels)
        rig.preparer.fail(code = PrepareState.CANCELLED, message = "Cancelled.")

        assertEquals(ProjectActivity.Idle, rig.settle())
        assertEquals(1, rig.link.requests.size)
        assertTrue(rig.messages.isEmpty())
    }

    @Test
    fun aSecondRestartWhileTheCodeIsBuildingAndARestartWithoutAProjectDoNothing() {
        assertFalse(rig.runner.restart(), "no project is open")
        openedProject()
        assertTrue(rig.runner.restart())

        assertFalse(rig.runner.restart())

        assertEquals(2, rig.preparer.requests.size, "the first build and the restart, and no third")
    }

    @Test
    fun aSessionThatFailedIsRestartedOnAFreshLinkWithTheNewProgram() {
        openedProject(program = "program-one")
        rig.link.listener!!.failed("the robot code threw")
        val first = rig.link
        assertEquals(Connection.FAILED, rig.services.session.session.value.connection)

        assertTrue(rig.runner.restart())
        rig.built("program-two")
        rig.settle()
        rig.await(rig.services.session.link) { rig.links.size == 2 }

        assertEquals(2, rig.links.size, "a link that failed is not asked again")
        assertTrue(first.calls.contains("close"))
        assertEquals(rig.work.resolve("program-two").toString(), rig.link.requests.single().program())
    }

    @Test
    fun applyingTheSetupStartsTheSessionAgainOnTheSameProgramWithoutBuildingAndRemembersTheRun() {
        openedProject(program = "program-one")
        rig.ui.setup.edit { it.copy(alliance = Alliance.BLUE) }

        assertTrue(rig.runner.apply())
        rig.await(rig.settings.state) { it.projects.firstOrNull()?.run?.alliance == Alliance.BLUE }

        assertEquals(1, rig.preparer.requests.size, "applying builds nothing")
        val request = rig.link.requests.last()
        assertEquals(Alliance.BLUE, request.alliance())
        assertEquals(rig.work.resolve("program-one").toString(), request.program())
        assertEquals(Destination.PRACTICE, rig.ui.navigator.state.value.current)
    }

    @Test
    fun applyingAFormWithSomethingWrongOrWithoutAProjectDoesNothing() {
        assertFalse(rig.runner.apply(), "no project is open")
        openedProject()
        rig.breakForm()

        assertFalse(rig.runner.apply())

        assertEquals(1, rig.link.requests.size)
    }

    @Test
    fun withoutASimulatorTheOpenFailsWithAnExplanationInsteadOfWaitingForever() {
        ProjectRig(IOException("no worker")).use { broken ->
            val project = broken.project()
            broken.runner.open(project)
            broken.built()

            val failed = assertInstanceOf(ProjectActivity.Failed::class.java, broken.settle())

            assertTrue(failed.message.contains("The simulator is not available"), failed.message)
        }
    }
}
