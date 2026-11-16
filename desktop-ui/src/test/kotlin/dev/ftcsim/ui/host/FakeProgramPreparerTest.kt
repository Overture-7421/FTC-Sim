package dev.ftcsim.ui.host

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FakeProgramPreparerTest {
    private val scheduler = ManualScheduler()
    private val work = newWorkFolder()
    private val preparer = FakeProgramPreparer(scheduler, work)

    private class Recording : ProgramPreparer.Listener {
        val events = ArrayList<String>()
        val steps = ArrayList<ProgramPreparer.Step>()
        var finished: ProgramPreparer.Prepared? = null
        var failed: ProgramPreparer.Failure? = null

        override fun step(step: ProgramPreparer.Step) {
            steps += step
            events += "step ${step.number()}"
        }

        override fun output(line: String) {
            events += "output"
        }

        override fun finished(program: ProgramPreparer.Prepared) {
            finished = program
            events += "finished"
        }

        override fun failed(failure: ProgramPreparer.Failure) {
            failed = failure
            events += "failed ${failure.code()}"
        }
    }

    private fun folderWith(name: String, vararg files: String): Path {
        val folder = Files.createDirectories(work.resolve("in").resolve(name))
        files.forEach { Files.writeString(folder.resolve(it), "x") }
        return folder
    }

    private fun prepare(name: String, trusted: Boolean = true): Pair<Recording, ProgramPreparer.Job> {
        val recording = Recording()
        return recording to preparer.prepare(ProgramPreparer.Request(work.resolve("projects").resolve(name), trusted), recording)
    }

    @Test
    fun aFolderWithAManifestIsAPreparedProgramThatCanBeUsedAsItIs() {
        val found = preparer.inspect(folderWith("package", "manifest.json"))

        assertEquals(ProgramPreparer.Kind.PREPARED_PROGRAM, found.kind())
        assertEquals("package", found.name())
        assertTrue(found.summary().contains("11.1.0"))
    }

    @Test
    fun aFolderWithASettingsGradleFileOfEitherKindIsAProject() {
        assertEquals(ProgramPreparer.Kind.PROJECT, preparer.inspect(folderWith("groovy", "settings.gradle")).kind())
        assertEquals(ProgramPreparer.Kind.PROJECT, preparer.inspect(folderWith("kotlin", "settings.gradle.kts")).kind())
    }

    @Test
    fun anyOtherFolderIsNotRecognisedAndSaysWhy() {
        val found = preparer.inspect(folderWith("random", "notes.txt"))

        assertEquals(ProgramPreparer.Kind.NOT_RECOGNISED, found.kind())
        assertTrue(found.summary().contains("settings.gradle"), "the reason names what is missing")
    }

    @Test
    fun aMissingFolderOrAFileIsNotAFolder() {
        val file = folderWith("holder", "a-file.txt").resolve("a-file.txt")

        assertEquals("This is not a folder.", preparer.inspect(work.resolve("missing")).summary())
        assertEquals("This is not a folder.", preparer.inspect(file).summary())
    }

    @Test
    fun nothingHappensUntilTimePassesAndEveryEventThenComesFromTheScheduler() {
        val (recording, _) = prepare("TeamProject")

        assertTrue(recording.events.isEmpty())
        scheduler.advance(10)
        assertTrue(recording.events.isEmpty(), "the first step is a moment away")
        scheduler.advance(1_000)
        assertEquals("step 1", recording.events.first())
    }

    @Test
    fun aSuccessfulPreparationGoesThroughEveryStepInOrderAndEndsOnce() {
        val (recording, _) = prepare("TeamProject")

        scheduler.advance(60_000)

        assertEquals((1..6).toList(), recording.steps.map { it.number() })
        assertTrue(recording.steps.all { it.of() == 6 })
        assertEquals("Copying the project, leaving the original alone", recording.steps.first().label())
        assertEquals("finished", recording.events.last())
        assertEquals(1, recording.events.count { it == "finished" || it.startsWith("failed") }, "exactly one end")
        val program = recording.finished!!
        assertEquals("TeamProject", program.name())
        assertEquals(listOf("Mecanum TeleOp", "Drive Square", "Competition Auto"), program.opModes())
        assertEquals(2, program.warnings().size)
        assertTrue(Files.isDirectory(program.folder()))
        assertTrue(Files.isRegularFile(program.log()))
    }

    @Test
    fun aProjectWhoseNameSaysBrokenFailsToCompileAtTheSecondStepWithTheLinesThatShowWhy() {
        val (recording, _) = prepare("broken-arm")

        scheduler.advance(60_000)

        assertEquals(listOf(1, 2), recording.steps.map { it.number() })
        assertEquals("failed COMPILATION_FAILED", recording.events.last())
        val failure = recording.failed!!
        assertTrue(failure.details().any { it.contains("cannot find symbol") })
        assertTrue(Files.readAllLines(failure.log()).any { it.contains("compileDebugJavaWithJavac FAILED") })
    }

    @Test
    fun aProjectWhoseNameSaysNoSdkFindsNoAndroidSdkAtTheFirstStep() {
        val (recording, _) = prepare("no-sdk-demo")

        scheduler.advance(60_000)

        assertEquals(listOf(1), recording.steps.map { it.number() })
        assertEquals("NO_ANDROID_SDK", recording.failed!!.code())
        assertTrue(recording.failed!!.message().contains("Android Studio"))
    }

    @Test
    fun aRequestThatIsNotTrustedIsRefusedAndNothingIsPlayed() {
        val (recording, _) = prepare("TeamProject", trusted = false)

        scheduler.advance(60_000)

        assertEquals(listOf("failed NOT_TRUSTED"), recording.events)
    }

    @Test
    fun cancellingEndsWithCancelledAfterAMomentAndNeverWithAProgram() {
        val (recording, job) = prepare("TeamProject")
        scheduler.advance(1_000)
        val stepsBefore = recording.steps.size

        job.cancel()
        scheduler.advance(100)
        assertTrue(recording.failed == null, "stopping takes a moment")
        scheduler.advance(60_000)

        assertEquals("CANCELLED", recording.failed!!.code())
        assertEquals("failed CANCELLED", recording.events.last())
        assertTrue(recording.finished == null)
        assertTrue(recording.steps.size <= stepsBefore + 1, "nothing much goes on after the request")
    }

    @Test
    fun cancellingBeforeAnythingHappenedStillEndsWithCancelled() {
        val (recording, job) = prepare("TeamProject")

        job.cancel()
        scheduler.advance(60_000)

        assertEquals("CANCELLED", recording.failed!!.code())
        assertTrue(recording.steps.size <= 1, "at most the step that was already under way")
    }
}
