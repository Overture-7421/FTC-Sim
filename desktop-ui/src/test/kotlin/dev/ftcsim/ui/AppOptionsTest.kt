package dev.ftcsim.ui

import java.nio.file.Paths
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AppOptionsTest {
    @Test
    fun noArgumentsMeansANormalLaunch() {
        assertEquals(AppOptions(), AppOptions.parse(emptyArray()))
    }

    @Test
    fun scriptedLaunchesCanExitByThemselvesAndStayOutOfTheWay() {
        assertEquals(AppOptions(exitAfterSeconds = 8L, takesFocus = false), AppOptions.parse(arrayOf("--exit-after=8", "--no-focus")))
    }

    @Test
    fun aScriptedLaunchCanKeepItsSettingsInAnotherFile() {
        val options = AppOptions.parse(arrayOf("--settings=build\\tmp\\ui.properties"))

        assertEquals(Paths.get("build\\tmp\\ui.properties"), options.settingsFile)
    }

    @Test
    fun theLastProjectIsOpenedAtStartUnlessTheLaunchSaysNot() {
        assertEquals(true, AppOptions.parse(emptyArray()).opensLastProject)
        assertEquals(false, AppOptions.parse(arrayOf("--no-last-project")).opensLastProject)
    }

    @Test
    fun aBadOptionIsRefusedRatherThanIgnored() {
        assertThrows(IllegalArgumentException::class.java) { AppOptions.parse(arrayOf("--exit-after=soon")) }
        assertThrows(IllegalArgumentException::class.java) { AppOptions.parse(arrayOf("--exit-after=0")) }
        assertThrows(IllegalArgumentException::class.java) { AppOptions.parse(arrayOf("--settings=")) }
        assertThrows(IllegalArgumentException::class.java) { AppOptions.parse(arrayOf("--fullscreen")) }
    }
}
