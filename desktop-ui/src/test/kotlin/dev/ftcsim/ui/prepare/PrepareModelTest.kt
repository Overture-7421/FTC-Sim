package dev.ftcsim.ui.prepare

import dev.ftcsim.ui.host.FakeProgramPreparer
import dev.ftcsim.ui.host.ManualScheduler
import dev.ftcsim.ui.host.ProgramPreparer
import dev.ftcsim.ui.host.newWorkFolder
import dev.ftcsim.ui.settings.SettingsStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PrepareModelTest {
    private val settings = SettingsStore(null, Executor { it.run() })
    private val folder: Path = Path.of("C:\\Teams\\Tina\\RobotProject")
    private val project = Project(folder, "RobotProject", "A project.")
    private val preparer = ScriptedPreparer()
    private val model = PrepareModel(preparer, settings)

    private fun trusted() = settings.update { it.withTrusted(folder) }

    private fun running() = model.state.value as PrepareState.Running

    @Test
    fun nothingIsChosenAtFirstAndNothingIsAskedOrRunning() {
        assertSame(PrepareState.Idle, model.state.value)
        assertNull(model.consent.value)
        model.cancel()
        model.prepare()
        assertSame(PrepareState.Idle, model.state.value)
        assertTrue(preparer.requests.isEmpty())
    }

    @Test
    fun aChosenProjectWaitsForThePersonToAskForItToBePrepared() {
        assertTrue(model.choose(project))

        assertEquals(PrepareState.Ready(project), model.state.value)
        assertTrue(preparer.requests.isEmpty())
    }

    @Test
    fun anUntrustedProjectIsAskedAboutFirstAndNothingRunsUntilTheAnswerIsYes() {
        model.choose(project)

        model.prepare()
        assertEquals(project, model.consent.value)
        assertTrue(preparer.requests.isEmpty(), "nothing runs before the person agrees")
        assertEquals(PrepareState.Ready(project), model.state.value)

        model.answerConsent(false)
        assertNull(model.consent.value)
        assertTrue(preparer.requests.isEmpty())
        assertFalse(settings.state.value.trusts(folder), "a refusal is not remembered as trust")

        model.prepare()
        model.answerConsent(true)
        assertTrue(model.state.value is PrepareState.Running)
        assertEquals(1, preparer.requests.size)
        assertEquals(folder, preparer.requests.single().projectFolder())
        assertTrue(preparer.requests.single().trusted(), "trusted only because the person agreed")
        assertTrue(settings.state.value.trusts(folder), "and the agreement is remembered for the folder")
    }

    @Test
    fun aProjectTheyAlreadyAgreedToStartsWithoutAQuestion() {
        trusted()
        model.choose(project)

        model.prepare()

        assertNull(model.consent.value)
        assertTrue(model.state.value is PrepareState.Running)
        assertTrue(preparer.requests.single().trusted())
    }

    @Test
    fun agreeingToOneProjectSaysNothingAboutAnother() {
        trusted()
        val other = Project(Path.of("C:\\Teams\\Other\\Project"), "Project", "Another.")

        model.choose(other)
        model.prepare()

        assertEquals(other, model.consent.value)
        assertTrue(preparer.requests.isEmpty())
    }

    @Test
    fun answeringWhenNoQuestionIsAskedDoesNothing() {
        model.choose(project)

        model.answerConsent(true)

        assertTrue(preparer.requests.isEmpty())
        assertFalse(settings.state.value.trusts(folder))
    }

    @Test
    fun theStepsBuildTheListWithDoneActiveAndWaitingRows() {
        trusted()
        model.choose(project)
        model.prepare()
        assertTrue(running().rows().isEmpty(), "no step has begun")

        preparer.step(1, label = "Copying")
        assertEquals(
            listOf(
                StepRow(1, 3, "Copying", StepStatus.ACTIVE),
                StepRow(2, 3, null, StepStatus.WAITING),
                StepRow(3, 3, null, StepStatus.WAITING),
            ),
            running().rows(),
        )

        preparer.step(2, label = "Building")
        assertEquals(
            listOf(
                StepRow(1, 3, "Copying", StepStatus.DONE),
                StepRow(2, 3, "Building", StepStatus.ACTIVE),
                StepRow(3, 3, null, StepStatus.WAITING),
            ),
            running().rows(),
        )
    }

    @Test
    fun outputGoesToTheLogInOrderAndAStartedPreparationBeginsWithAnEmptyOne() {
        trusted()
        model.choose(project)
        model.log.add("left over from before")
        model.prepare()

        preparer.listener!!.output("first")
        preparer.listener!!.output("second")

        assertEquals(listOf("first", "second"), model.log.view().lines)
    }

    @Test
    fun aFinishedPreparationKeepsTheProgramItMade() {
        trusted()
        model.choose(project)
        model.prepare()

        preparer.finish(Path.of("C:\\prepared\\RobotProject"), warnings = listOf("Check Vision.java."))

        val finished = model.state.value as PrepareState.Finished
        assertEquals(project, finished.project)
        assertEquals(Path.of("C:\\prepared\\RobotProject"), finished.program.folder())
        assertEquals(listOf("Check Vision.java."), finished.program.warnings())
    }

    @Test
    fun aFailureKeepsItsMessageDetailsAndLogAndIsNotACancellation() {
        trusted()
        model.choose(project)
        model.prepare()
        val log = Path.of("C:\\work\\build.log")

        preparer.fail("COMPILATION_FAILED", "It did not compile.", listOf("Auto.java:42: error"), log)

        val failed = model.state.value as PrepareState.Failed
        assertEquals("It did not compile.", failed.failure.message())
        assertEquals(listOf("Auto.java:42: error"), failed.failure.details())
        assertEquals(log, failed.failure.log())
        assertFalse(failed.cancelled)
    }

    @Test
    fun cancellingAsksThePreparerOnceAndShowsCancellingUntilItEnds() {
        trusted()
        model.choose(project)
        model.prepare()

        model.cancel()
        model.cancel()

        assertEquals(1, preparer.cancels, "asked once however often the button is pressed")
        assertTrue(running().cancelling)

        preparer.fail("CANCELLED", "Preparation was cancelled.")
        val failed = model.state.value as PrepareState.Failed
        assertTrue(failed.cancelled)
    }

    @Test
    fun whatAnEndedPreparationSaysLaterChangesNothing() {
        trusted()
        model.choose(project)
        model.prepare()
        preparer.listener!!.output("one")
        preparer.finish()
        val result = model.state.value

        preparer.step(2)
        preparer.listener!!.output("two")
        preparer.fail()

        assertSame(result, model.state.value)
        assertEquals(listOf("one"), model.log.view().lines)
    }

    @Test
    fun aNewPreparationIgnoresWhatTheOldOneStillSays() {
        trusted()
        model.choose(project)
        model.prepare()
        val old = preparer.listener!!
        preparer.fail()

        model.prepare()
        assertTrue(running().rows().isEmpty())
        old.step(ProgramPreparer.Step(2, 3, "Step 2"))
        old.output("stale")
        assertTrue(running().rows().isEmpty(), "the old preparation is over")
        assertTrue(model.log.view().lines.isEmpty())

        preparer.step(1)
        assertEquals(StepStatus.ACTIVE, running().rows().first().status)
    }

    @Test
    fun preparingAgainAfterAFailureStartsOverWithACleanLog() {
        trusted()
        model.choose(project)
        model.prepare()
        preparer.listener!!.output("old line")
        preparer.fail()

        model.prepare()

        assertTrue(model.state.value is PrepareState.Running)
        assertTrue(model.log.view().lines.isEmpty())
        assertEquals(2, preparer.requests.size)
    }

    @Test
    fun aProjectCannotBeChosenWhileAnotherIsBeingPreparedButCanBeAfterwards() {
        trusted()
        model.choose(project)
        model.prepare()

        assertFalse(model.choose(Project(Path.of("C:\\Other"), "Other", "")))
        assertEquals(project, model.state.value.project)

        preparer.finish()
        assertTrue(model.choose(Project(Path.of("C:\\Other"), "Other", "")))
        assertEquals("Other", model.state.value.project!!.name)
    }

    @Test
    fun choosingAnotherProjectDropsAPendingQuestionAndTheOldOutput() {
        model.choose(project)
        model.prepare()
        model.log.add("old")

        model.choose(Project(Path.of("C:\\Other"), "Other", ""))

        assertNull(model.consent.value)
        assertTrue(model.log.view().lines.isEmpty())
    }

    @Test
    fun aPreparerThatCannotEvenStartIsAFailureInWordsNotACrash() {
        trusted()
        preparer.failToStart = IllegalStateException("No JDK was found.")
        model.choose(project)

        model.prepare()

        val failed = model.state.value as PrepareState.Failed
        assertEquals("NOT_STARTED", failed.failure.code())
        assertTrue(failed.failure.message().contains("No JDK was found."))
        assertNull(failed.failure.log())
    }

    // On the fake preparer, played on a clock the test moves -----------------------------------------------------

    private class Fakes {
        val scheduler = ManualScheduler()
        val work = newWorkFolder()
        val settings = SettingsStore(null, Executor { it.run() })
        val model = PrepareModel(FakeProgramPreparer(scheduler, work), settings)

        fun prepare(name: String): PrepareState {
            val folder = work.resolve(name)
            settings.update { it.withTrusted(folder) }
            model.choose(Project(folder, name, ""))
            model.prepare()
            scheduler.advance(120_000)
            return model.state.value
        }
    }

    @Test
    fun theFakePreparerPlaysAllSixStepsAndEndsWithAProgramAndItsBuildLog() {
        val fakes = Fakes()

        val finished = fakes.prepare("TeamProject") as PrepareState.Finished

        assertEquals("TeamProject", finished.program.name())
        assertEquals("11.1.0", finished.program.sdkVersion())
        assertEquals(3, finished.program.opModes().size)
        assertEquals(2, finished.program.warnings().size)
        assertTrue(Files.isDirectory(finished.program.folder()))
        assertTrue(Files.readAllLines(finished.program.log()).contains("BUILD SUCCESSFUL in 41s"))
        assertTrue(fakes.model.log.view().lines.contains("BUILD SUCCESSFUL in 41s"))
    }

    @Test
    fun aProjectThatDoesNotCompileEndsWithTheLinesThatShowWhy() {
        val failed = Fakes().prepare("broken-project") as PrepareState.Failed

        assertEquals("COMPILATION_FAILED", failed.failure.code())
        assertTrue(failed.failure.details().any { it.contains("cannot find symbol") })
        assertNotNull(failed.failure.log())
        assertTrue(Files.readAllLines(failed.failure.log()).any { it.contains("FAILED") })
    }

    @Test
    fun aComputerWithoutTheAndroidSdkIsToldSoInWords() {
        val failed = Fakes().prepare("no-sdk-project") as PrepareState.Failed

        assertEquals("NO_ANDROID_SDK", failed.failure.code())
        assertTrue(failed.failure.message().contains("Android Studio"))
    }

    @Test
    fun cancellingAFakePreparationEndsItAsCancelled() {
        val fakes = Fakes()
        val folder = fakes.work.resolve("TeamProject")
        fakes.settings.update { it.withTrusted(folder) }
        fakes.model.choose(Project(folder, "TeamProject", ""))
        fakes.model.prepare()
        fakes.scheduler.advance(1_000)
        assertTrue(fakes.model.state.value is PrepareState.Running)

        fakes.model.cancel()
        assertTrue((fakes.model.state.value as PrepareState.Running).cancelling)
        fakes.scheduler.advance(120_000)

        val failed = fakes.model.state.value as PrepareState.Failed
        assertTrue(failed.cancelled)
    }
}
