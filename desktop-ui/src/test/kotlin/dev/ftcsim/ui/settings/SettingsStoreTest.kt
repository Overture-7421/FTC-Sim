package dev.ftcsim.ui.settings

import dev.ftcsim.contracts.field.Alliance
import dev.ftcsim.contracts.session.SessionRequest
import dev.ftcsim.ui.input.GamepadConfig
import dev.ftcsim.ui.input.KeyboardMap
import dev.ftcsim.ui.input.PadAssignment
import dev.ftcsim.ui.input.PadControl
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

    private fun request(seed: Long = 1L, program: String = "C:\\Users\\Tina\\Robot Code\\prepared") =
        SessionRequest("hornet-2026", program, "decode", Alliance.BLUE, seed, 1.25, -0.5, Math.PI / 2, 1.0)

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
            recents = listOf(request(1L), request(2L, program = "")),
            trustedProjects = listOf(UserSettings.projectKey(Path.of("C:\\Teams\\Tina\\Project")), "c:\\other\\project"),
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
            recent.0.field=decode
            recent.0.profile=hornet-2026
            recent.0.alliance=GREEN
            recent.0.seed=1
            recent.0.startX=0
            recent.0.startY=0
            recent.0.startHeading=0
            recent.1.field=biobuzz
            recent.1.profile=hornet-2026
            recent.1.alliance=red
            recent.1.seed=42
            recent.1.startX=0.5
            recent.1.startY=-0.5
            recent.1.startHeading=1.5
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
        assertEquals(listOf("biobuzz"), settings.recents.map { it.field() }, "the run with an unknown alliance is dropped")
        assertEquals(Alliance.RED, settings.recents[0].alliance())
    }

    @Test
    fun recentRunsAreNewestFirstWithoutRepeatsAndAtMostTen() {
        val store = store(null)

        for (seed in 1L..12L) {
            store.update { it.withRecent(request(seed)) }
        }
        assertEquals((12L downTo 3L).toList(), store.state.value.recents.map { it.seed() })

        store.update { it.withRecent(request(5L)) }
        assertEquals(5L, store.state.value.recents.first().seed(), "opening an old run again brings it to the top")
        assertEquals(10, store.state.value.recents.size)
        assertEquals(1, store.state.value.recents.count { it.seed() == 5L })

        store.update { it.withoutRecent(request(5L)) }
        assertEquals(9, store.state.value.recents.size)
    }

    @Test
    fun aRunIsTheSameRunAtAnySpeed() {
        val settings = UserSettings().withRecent(request(1L).copy(speed = 4.0)).withRecent(request(1L))

        assertEquals(1, settings.recents.size)
        assertEquals(1.0, settings.recents[0].speed())
    }

    @Test
    fun agreeingToAProjectIsRememberedByFolderAcrossRestartsWhateverTheLetterCase() {
        val folder = Path.of("C:\\Teams\\Tina\\RobotProject")
        val store = store()
        assertFalse(store.state.value.trusts(folder))

        store.update { it.withTrusted(folder) }
        store.update { it.withTrusted(Path.of("C:\\TEAMS\\tina\\robotproject")) }
        val reread = store()

        assertTrue(reread.state.value.trusts(folder))
        assertTrue(reread.state.value.trusts(Path.of("c:\\teams\\TINA\\robotproject\\")), "Windows paths differ in case and a trailing separator only")
        assertEquals(1, reread.state.value.trustedProjects.size, "the same folder is remembered once")
        assertFalse(reread.state.value.trusts(Path.of("C:\\Teams\\Tina\\OtherProject")), "the agreement is for this folder only")
    }

    @Test
    fun onlyTheMostRecentHundredAgreementsAreKept() {
        var settings = UserSettings()

        for (number in 1..120) settings = settings.withTrusted(Path.of("C:\\projects\\p$number"))

        assertEquals(UserSettings.MAX_TRUSTED, settings.trustedProjects.size)
        assertFalse(settings.trusts(Path.of("C:\\projects\\p1")))
        assertTrue(settings.trusts(Path.of("C:\\projects\\p120")))
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
