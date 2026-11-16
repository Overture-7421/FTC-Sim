package dev.ftcsim.ui.project

import dev.ftcsim.contracts.field.Alliance
import dev.ftcsim.contracts.session.RobotMapping
import dev.ftcsim.contracts.session.RobotMapping.MotorMapping
import dev.ftcsim.contracts.session.RobotMapping.Slot
import dev.ftcsim.ui.host.RunDefaults
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
 * by hand, a link it speaks for and robot mappings that live in memory.
 */
class ProjectRunnerTest {
    private val rig = ProjectRig()

    @AfterEach
    fun close() = rig.close()

    private fun mapped(name: String = "Team robot") = RobotMapping.empty(name).withMotors(
        listOf(
            MotorMapping("frontLeft", Slot.FRONT_LEFT, "", true),
            MotorMapping("frontRight", Slot.FRONT_RIGHT, "", false),
            MotorMapping("backLeft", Slot.REAR_LEFT, "", true),
            MotorMapping("backRight", Slot.REAR_RIGHT, "", false),
            MotorMapping("shooter", Slot.SHOOTER, "", false),
        ),
    )

    /** Opens a project whose file names its game, so that it is not asked, and brings its session to the open state. */
    private fun openedProject(name: String = "TeamProject", run: RunDefaults = RunDefaults("decode", null, null), program: String = "program"): Path {
        val project = rig.project(name, run)
        assertTrue(rig.runner.open(project))
        rig.built(program)
        assertEquals(ProjectActivity.Idle, rig.settle())
        rig.link.listener!!.opened(Fixtures.info())
        return project.folder
    }

    @Test
    fun openingAProjectBuildsItStartsTheSessionOnItsProgramAndMappingAndRemembersTheProject() {
        val project = rig.project("TeamProject", RunDefaults("decode", Alliance.BLUE, null), mapped())

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
        assertEquals(mapped(), request.mapping(), "the mapping the file holds travels in the request")
        assertEquals(Alliance.BLUE, request.alliance(), "the file says how a run starts")
        assertEquals("decode", request.field())
        assertEquals(ProjectRig.SEED, request.seed())
        assertEquals(Destination.PRACTICE, rig.ui.navigator.state.value.current)
        assertEquals(project.name, rig.runner.open.value?.project?.name)
        assertEquals(listOf("TeamProject"), rig.settings.state.value.projects.map { it.name })
        assertTrue(rig.runner.problems.value.isEmpty())
    }

    @Test
    fun aProjectThatNeverRanHereWaitsForItsGameAndStartsOnlyAfterTheChoice() {
        val project = rig.project("TeamProject", RunDefaults.NONE)
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
    fun aProjectThatRanBeforeIsNotAskedForItsGameAgain() {
        val project = rig.project("TeamProject", RunDefaults.NONE)
        rig.settings.update { it.withProject(RecentProject(project.folder.toString(), project.name, SetupInput(field = "decode", alliance = Alliance.RED))) }

        rig.runner.open(project)
        rig.built()

        assertEquals(ProjectActivity.Idle, rig.settle())
        assertEquals(1, rig.link.requests.size)
    }

    @Test
    fun whatTheProjectWasLastRunWithWinsOverWhatItsFileSaysExceptTheSeedWhichIsAlwaysDrawn() {
        val project = rig.project("TeamProject", RunDefaults("decode", Alliance.BLUE, null))
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
    fun aProjectWithoutARobotFileRunsARobotWithNoMotorsMappedShowsTheRobotScreenAndListsItAsAProblem() {
        val project = rig.project("TeamProject", run = null)
        rig.runner.open(project)
        rig.built()

        rig.chooseGame()

        assertEquals(RobotMapping.empty("TeamProject"), rig.link.requests.single().mapping())
        assertEquals(RobotState.Undescribed(project), rig.runner.robot.value)
        assertEquals(Destination.ROBOT, rig.ui.navigator.state.value.current, "the robot is described next")
        val problem = rig.runner.problems.value.single()
        assertEquals(Severity.WARNING, problem.severity)
        assertEquals("Robot", problem.source)
    }

    @Test
    fun aRobotThatWasSkippedIsNotAskedForAgainWhenTheProjectIsOpenedAnotherTime() {
        val project = rig.project("TeamProject", run = null)
        rig.runner.open(project)
        rig.built()
        rig.chooseGame()
        rig.link.listener!!.opened(Fixtures.info())

        rig.runner.skipRobot()

        assertEquals(Destination.PRACTICE, rig.ui.navigator.state.value.current)
        assertTrue(rig.settings.state.value.project(project.folder)!!.robotSkipped)
        rig.ui.navigator.go(Destination.PROJECTS)
        assertTrue(rig.runner.open(project))
        rig.built()
        rig.settle()
        assertEquals(Destination.PRACTICE, rig.ui.navigator.state.value.current, "the Robot screen is not shown again")
    }

    @Test
    fun theRobotThatWasMappedIsWrittenWholeAndTheCodeIsRestartedWithIt() {
        val project = rig.project("TeamProject", run = null)
        rig.runner.open(project)
        rig.built()
        rig.chooseGame()
        rig.link.listener!!.opened(Fixtures.info())

        rig.runner.saveRobot(mapped())
        rig.await(rig.runner.activity) { it is ProjectActivity.Building }

        assertEquals(listOf(mapped()), rig.mappings.saved(), "the whole mapping was written")
        assertEquals(listOf("Saved ftcsim.json"), rig.messages.map { it.text })
        assertEquals(Destination.PRACTICE, rig.ui.navigator.state.value.current)
        assertEquals(2, rig.preparer.requests.size, "the code is built again so that the session uses the file")
        rig.built("program-two")
        rig.settle()
        rig.awaitLinks(2)
        assertEquals(mapped(), rig.link.requests.single().mapping(), "the new session runs with what was saved")
        assertEquals(RobotState.FromFile(project, rig.mappings.find(project.folder).get().file(), false), rig.runner.robot.value)
        assertTrue(rig.runner.problems.value.isEmpty(), "the robot is mapped now")
    }

    @Test
    fun savingAMappingOverAnExistingOneRestartsTheCodeWithTheChangedMapping() {
        val project = rig.project("TeamProject", mapping = mapped())
        rig.runner.open(project)
        rig.built()
        rig.settle()
        val changed = mapped().withMotors(mapped().motors().dropLast(1))

        rig.runner.saveRobot(changed)
        rig.await(rig.runner.activity) { it is ProjectActivity.Building }

        assertEquals(listOf(changed), rig.mappings.saved())
        assertEquals(2, rig.preparer.requests.size)
        rig.built("program-two")
        rig.settle()
        rig.awaitLinks(2)
        assertEquals(changed, rig.link.requests.single().mapping())
        assertEquals(RunDefaults("decode", null, null), rig.mappings.find(project.folder).get().run(), "the run defaults stay")
    }

    @Test
    fun aFileThatIsNotReadIsAProblemAndTheProjectRunsWithNoMotorsMappedUntilTheRobotIsSaved() {
        val project = rig.project("TeamProject", run = null)
        rig.mappings.withUnreadableFile(project.folder, "ftcsim.json is from an older version")
        rig.runner.open(project)
        rig.built()

        rig.chooseGame()

        assertEquals(RobotMapping.empty("TeamProject"), rig.link.requests.single().mapping())
        assertEquals(RobotState.Undescribed(project, "ftcsim.json is from an older version"), rig.runner.robot.value)
        assertTrue(rig.runner.open.value!!.generic)
        val problem = rig.runner.problems.value.single()
        assertEquals("Robot", problem.source)
        assertEquals("ftcsim.json is from an older version", problem.text)
        assertEquals(Destination.ROBOT, rig.ui.navigator.state.value.current, "the robot is mapped next")
    }

    @Test
    fun aMappingThatCannotBeSavedSaysWhyAndTheCodeIsNotRestarted() {
        val project = rig.project("TeamProject", mapping = mapped())
        rig.runner.open(project)
        rig.built()
        rig.settle()
        rig.mappings.failingWith(IOException("read-only"))

        rig.runner.saveRobot(mapped("Other"))
        rig.awaitMessages(1)

        val message = rig.messages.single().text
        assertTrue(message.startsWith("Could not save") && message.contains("read-only"), message)
        assertEquals(1, rig.preparer.requests.size, "the code is not restarted")
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
        val project = rig.project("TeamProject", RunDefaults("decode", null, null))
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
        val folder = openedProject(program = "program-one", run = RunDefaults("decode", Alliance.BLUE, "BLUE goal side"))
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
    fun aRobotFileThatCannotBeReadFailsTheOpenWithWhatTheDiskSaid() {
        val project = rig.project()
        rig.mappings.failingWith(IOException("the disk is gone"))

        rig.runner.open(project)
        rig.built()
        val failed = assertInstanceOf(ProjectActivity.Failed::class.java, rig.settle())

        assertTrue(failed.message.contains("the disk is gone"), failed.message)
        assertTrue(rig.link.requests.isEmpty())
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
    fun savingTheRunAsTheProjectsDefaultWritesTheFieldAllianceAndPoseIntoItsFileAndSaysSo() {
        openedProject()
        rig.ui.setup.chooseAlliance(SetupRules.of(Fixtures.catalogue()), Alliance.BLUE)

        rig.runner.saveDefaults()
        rig.awaitMessages(1)

        assertEquals(listOf(RunDefaults("decode", Alliance.BLUE, "BLUE goal side")), rig.mappings.savedRunDefaults())
        assertEquals(listOf("Saved to ftcsim.json"), rig.messages.map { it.text })
    }

    @Test
    fun aSaveThatFailsSaysWhy() {
        openedProject()
        rig.mappings.failingWith(IOException("read-only"))

        rig.runner.saveDefaults()
        rig.awaitMessages(1)

        val message = rig.messages.single().text
        assertTrue(message.startsWith("Could not save") && message.contains("read-only"), message)
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
