package dev.ftcsim.ui.settings

import dev.ftcsim.contracts.field.Alliance
import dev.ftcsim.ui.input.GamepadConfig
import dev.ftcsim.ui.input.KeyboardMap
import dev.ftcsim.ui.input.PadAssignment
import dev.ftcsim.ui.input.PadControl
import dev.ftcsim.ui.setup.SetupInput
import java.awt.event.KeyEvent
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SettingsStoreTest {
    @TempDir
    lateinit var directory: Path

    private val direct = Executor { it.run() }

    private fun file(): Path = directory.resolve("config").resolve("ui.properties")

    private fun store(path: Path? = file()) = SettingsStore(path, direct)

    private fun project(number: Int, run: SetupInput? = null) = RecentProject("C:\\Teams\\Project $number", "Project $number", run)

    @Test
    fun aMissingFileGivesTheDefaultsAndNoProblem() {
        val store = store()

        assertEquals(UserSettings(), store.state.value)
        assertNull(store.problem.value)
        assertFalse(Files.exists(file()), "nothing is written until something changes")
    }

    @Test
    fun everyKindOfSettingSurvivesBeingSavedAndReadBack() {
        val keyboard = KeyboardMap.DEFAULT.with(PadControl.A, KeyEvent.VK_P).with(PadControl.GUIDE, 0)
        val gamepads = GamepadConfig.DEFAULT
            .withPad(1, GamepadConfig.Pad(PadAssignment.controller("030000005e0400008e02000000007200", "Xbox 360 \u00e9 pad"), 0.2f, 0.1f))
            .withPad(2, GamepadConfig.Pad.DEFAULT.withAssignment(PadAssignment.KEYBOARD))
            .withKeyboard(keyboard)
        val saved = UserSettings(
            display = DisplaySettings(renderScalePercent = 75, frameCapHz = 90, performanceOverlay = true),
            gamepads = gamepads,
            projects = listOf(
                project(1, SetupInput("decode", Alliance.BLUE, true, "", "BLUE goal side", "1.25", "-0.5", "90", true)),
                project(2, SetupInput("none", Alliance.RED, true, "", null, "0", "0", "0", false)),
                project(3).copy(robotSkipped = true),
            ),
        )

        store().update { saved }
        val reread = store()

        assertEquals(saved, reread.state.value)
        assertNull(reread.problem.value)
    }

    @Test
    fun aChangeIsWrittenAtOnceThroughATemporaryFileThatIsGoneAfterwards() {
        val store = store()

        store.update { it.copy(display = it.display.copy(performanceOverlay = true)) }

        assertTrue(Files.isRegularFile(file()))
        assertFalse(Files.exists(file().resolveSibling("ui.properties.tmp")))
        assertTrue(Files.readString(file()).contains("display.performanceOverlay=true"))
    }

    @Test
    fun aFileThatCannotBeReadGivesTheDefaultsIsKeptAsideAndIsReported() {
        Files.createDirectories(file().parent)
        Files.write(file(), byteArrayOf(0x66, 0x6f, 0x72, 0x6d, 0x61, 0x74, 0x3d, 0xC3.toByte(), 0x28))

        val store = store()

        assertEquals(UserSettings(), store.state.value)
        assertNotNull(store.problem.value)
        assertTrue(store.problem.value!!.contains("ui.properties.corrupt"))
        assertFalse(Files.exists(file()))
        assertTrue(Files.exists(file().resolveSibling("ui.properties.corrupt")), "nothing the person had is thrown away")
    }

    @Test
    fun aBrokenEscapeIsCorruptToo() {
        Files.createDirectories(file().parent)
        Files.writeString(file(), "format=1\ndisplay.frameCapHz=\\uZZZZ\n")

        val store = store()

        assertEquals(UserSettings(), store.state.value)
        assertNotNull(store.problem.value)
    }

    @Test
    fun aFileOfAnotherVersionIsNotUsedAndNotLost() {
        Files.createDirectories(file().parent)
        Files.writeString(file(), "format=2\ndisplay.frameCapHz=60\n")

        val store = store()

        assertEquals(UserSettings(), store.state.value)
        assertNotNull(store.problem.value)
        assertTrue(Files.exists(file().resolveSibling("ui.properties.corrupt")))
    }

    @Test
    fun aValueThatMakesNoSenseIsReplacedByItsDefaultAndTheRestIsKept() {
        Files.createDirectories(file().parent)
        Files.writeString(
            file(),
            """
            format=1
            display.renderScalePercent=lots
            display.frameCapHz=100
            display.performanceOverlay=yes
            gamepad.1.assignment=CONTROLLER
            gamepad.1.guid=
            gamepad.1.stickDeadzone=9
            gamepad.2.assignment=KEYBOARD
            gamepad.2.triggerDeadzone=NaN
            keyboard.A=-5
            keyboard.B=${KeyEvent.VK_P}
            project.0.folder=C:\\one
            project.0.name=one
            project.0.run.field=decode
            project.0.run.alliance=GREEN
            project.1.folder=C:\\two
            project.1.name=two
            project.1.run.field=biobuzz
            project.1.run.alliance=red
            project.1.run.randomSeed=false
            project.1.run.seed=42
            project.1.run.startX=0.5
            project.1.run.startY=-0.5
            project.1.run.heading=90
            project.1.run.touched=true
            project.2.name=no folder
            """.trimIndent(),
        )

        val store = store()
        val settings = store.state.value

        assertNull(store.problem.value, "a file that parses is not corrupt, only partly useless")
        assertEquals(100, settings.display.renderScalePercent, "unreadable: the default")
        assertEquals(90, settings.display.frameCapHz, "100 is not offered, 90 is the nearest")
        assertFalse(settings.display.performanceOverlay)
        assertEquals(PadAssignment.AUTOMATIC, settings.gamepads.pad(1).assignment(), "a controller without a model is nothing to find")
        assertEquals(0.5f, settings.gamepads.pad(1).stickDeadzone(), "clamped")
        assertEquals(PadAssignment.KEYBOARD, settings.gamepads.pad(2).assignment())
        assertEquals(GamepadConfig.Pad.DEFAULT_TRIGGER_DEADZONE, settings.gamepads.pad(2).triggerDeadzone())
        assertEquals(KeyboardMap.DEFAULT.keyOf(PadControl.A), settings.gamepads.keyboard().keyOf(PadControl.A))
        assertEquals(KeyEvent.VK_P, settings.gamepads.keyboard().keyOf(PadControl.B))
        assertEquals(listOf("one", "two"), settings.projects.map { it.name }, "a project with no folder is dropped")
        assertNull(settings.projects[0].run, "a run with an unknown alliance is not remembered, the project still is")
        assertEquals(Alliance.RED, settings.projects[1].run?.alliance)
        assertTrue(settings.projects[1].run!!.randomSeed, "a seed an older file kept is ignored: every run draws one")
        assertEquals("", settings.projects[1].run?.seedText)
    }

    @Test
    fun recentProjectsAreNewestFirstWithoutRepeatsAndAtMostTen() {
        val store = store(null)

        for (number in 1..12) {
            store.update { it.withProject(project(number)) }
        }
        assertEquals((12 downTo 3).map { "Project $it" }, store.state.value.projects.map { it.name })

        store.update { it.withProject(project(5)) }
        assertEquals("Project 5", store.state.value.projects.first().name, "opening an old project again brings it to the top")
        assertEquals(10, store.state.value.projects.size)
        assertEquals(1, store.state.value.projects.count { it.name == "Project 5" })

        store.update { it.withoutProject(project(5)) }
        assertEquals(9, store.state.value.projects.size)
    }

    @Test
    fun aProjectIsTheSameProjectInAnyLetterCaseAndKeepsHowItWasLastRun() {
        val run = SetupInput(field = "decode", alliance = Alliance.BLUE, randomSeed = false, seedText = "7", startPose = "BLUE goal side")
        val settings = UserSettings()
            .withProject(RecentProject("C:\\Teams\\Tina\\Code", "Code", run))
            .withProject(RecentProject("c:\\TEAMS\\tina\\code", "Code"))

        assertEquals(1, settings.projects.size)
        assertNull(settings.projects[0].run, "the project that was opened last replaces what was remembered")
        assertEquals(run, UserSettings().withProject(RecentProject("C:\\Teams\\Tina\\Code", "Code", run)).project(Path.of("c:\\teams\\TINA\\code"))?.run)
    }

    @Test
    fun howAProjectWasLastRunIsRememberedWithItAndBringsItToTheTop() {
        val first = RecentProject("C:\\a", "a")
        val second = RecentProject("C:\\b", "b")
        val run = SetupInput(field = "none", alliance = Alliance.RED)
        val settings = UserSettings().withProject(first).withProject(second).withRun(Path.of("C:\\a"), run)

        assertEquals(listOf("a", "b"), settings.projects.map { it.name })
        assertEquals(run, settings.projects[0].run)
        assertEquals(settings, settings.withRun(Path.of("C:\\unknown"), run), "a project that was never opened is not added by a run")
    }

    @Test
    fun theOpModeThatFindsAProjectsHardwareIsRememberedWithTheProjectAndSurvivesBeingSavedAndReadBack() {
        val settings = UserSettings().withProject(RecentProject("C:\\a", "a")).withProject(RecentProject("C:\\b", "b"))
            .withScanOpMode(Path.of("c:\\A"), "Teleop A")
        val store = store()
        store.update { settings }

        assertEquals(listOf(null, "Teleop A"), settings.projects.map { it.scanOpMode })
        assertEquals("Teleop A", store().state.value.project(Path.of("C:\\a"))?.scanOpMode)
        assertNull(store().state.value.project(Path.of("C:\\b"))?.scanOpMode)
        assertEquals(settings, settings.withScanOpMode(Path.of("C:\\unknown"), "x"), "a project that was never opened gets nothing")
    }

    @Test
    fun theLatestChosenOpModeIsRememberedWithTheProjectSurvivesBeingSavedAndReadBackAndSurvivesOpeningAgain() {
        val settings = UserSettings().withProject(RecentProject("C:\\a", "a")).withProject(RecentProject("C:\\b", "b"))
            .withOpMode(Path.of("c:\\A"), "Auto B").withScanOpMode(Path.of("c:\\A"), "Teleop A")
        val store = store()
        store.update { settings }

        assertEquals("Auto B", store().state.value.project(Path.of("C:\\a"))?.opMode)
        assertNull(store().state.value.project(Path.of("C:\\b"))?.opMode)
        assertEquals(settings, settings.withOpMode(Path.of("C:\\unknown"), "x"), "a project that was never opened gets nothing")
        val reopened = settings.withProject(RecentProject("C:\\a", "a", null, "Teleop A", false, "Auto B"))
        assertEquals("Auto B", reopened.project(Path.of("C:\\a"))?.opMode)
    }

    @Test
    fun theSettingsOfAnEarlierVersionThatRememberedWholeRunsStartWithNoProjects() {
        Files.createDirectories(file().parent)
        Files.writeString(
            file(),
            "format=1\nrecent.0.field=decode\nrecent.0.profile=hornet-2026\nrecent.0.program=C\\:\\\\p\nrecent.0.alliance=RED\n" +
                "recent.0.seed=1\nrecent.0.startX=0\nrecent.0.startY=0\nrecent.0.startHeading=0\ntrusted.0=c\\:\\\\teams\\\\code\n",
        )

        val settings = store().state.value

        assertEquals(emptyList<RecentProject>(), settings.projects)
    }



    @Test
    fun listenersAreToldNowAndAfterEveryChangeButNotForNothing() {
        val store = store(null)
        val heard = ArrayList<UserSettings>()

        store.onChange { heard += it }
        store.update { it.copy(display = it.display.copy(frameCapHz = 60)) }
        store.update { it }

        assertEquals(2, heard.size)
        assertEquals(60, heard.last().display.frameCapHz)
    }

    @Test
    fun aSaveThatFailsIsReportedAndTheSettingsStillWorkInMemory() {
        Files.createDirectories(directory)
        Files.writeString(directory.resolve("config"), "this is a file where the folder should be")
        val store = store()

        store.update { it.copy(display = it.display.copy(renderScalePercent = 50)) }

        assertNotNull(store.problem.value)
        assertTrue(store.problem.value!!.startsWith("The settings could not be saved"))
        assertEquals(50, store.state.value.display.renderScalePercent)
        store.dismissProblem()
        assertNull(store.problem.value)
    }

    @Test
    fun withoutAFileNothingIsWrittenAndNothingGoesWrong() {
        val store = store(null)

        store.update { it.copy(display = it.display.copy(frameCapHz = 60)) }

        assertEquals(60, store.state.value.display.frameCapHz)
        assertNull(store.problem.value)
    }

    @Test
    fun manyChangesBeforeTheWriterRunsAreOneWriteOfTheLatestState() {
        val queued = ArrayList<Runnable>()
        val store = SettingsStore(file(), Executor { queued += it })

        store.update { it.copy(display = it.display.copy(frameCapHz = 60)) }
        store.update { it.copy(display = it.display.copy(renderScalePercent = 50)) }
        store.update { it.copy(display = it.display.copy(performanceOverlay = true)) }

        assertEquals(1, queued.size)
        queued.single().run()
        assertEquals(DisplaySettings(50, 60, true), store(file()).state.value.display)
    }
}
