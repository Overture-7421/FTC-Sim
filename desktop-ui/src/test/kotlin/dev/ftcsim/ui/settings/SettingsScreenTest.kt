package dev.ftcsim.ui.settings

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.ftcsim.ui.common.runTallComposeUiTest as runComposeUiTest
import dev.ftcsim.ui.common.FtcSimTheme
import dev.ftcsim.ui.input.ViewportInput
import dev.ftcsim.ui.render.OrbitCamera
import dev.ftcsim.ui.scene.ViewportFeed
import dev.ftcsim.ui.viewport.ViewportEngine
import java.util.concurrent.Executor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalTestApi::class)
class SettingsScreenTest {
    private val settings = SettingsStore(null, Executor { it.run() })
    private val camera = OrbitCamera()
    private val engine = ViewportEngine(ViewportFeed(), camera, ViewportInput(camera))

    private fun ComposeUiTest.show(path: String? = "C:\\Users\\Tina\\AppData\\Local\\FTC-Sim\\config\\ui.properties") = setContent {
        FtcSimTheme(darkTheme = true) { SettingsScreen(settings, engine, path) }
    }

    @Test
    fun theRenderResolutionIsOneOfThreeChoicesAndIsSavedAtOnce() = runComposeUiTest {
        show()

        onNodeWithText("The picture is drawn at the size of the viewport.").assertExists()
        onNodeWithText("75 %").performClick()
        waitForIdle()

        assertEquals(75, settings.state.value.display.renderScalePercent)
        onNodeWithText("The picture is drawn at 75 % of the viewport's size and stretched to fit.").assertExists()
        onNodeWithText("50 %").performClick()
        assertEquals(50, settings.state.value.display.renderScalePercent)
    }

    @Test
    fun theFrameRateCapIsOfferedAt60And90And144() = runComposeUiTest {
        show()

        onNodeWithText("60 Hz").performClick()
        assertEquals(60, settings.state.value.display.frameCapHz)
        onNodeWithText("90 Hz").performClick()
        assertEquals(90, settings.state.value.display.frameCapHz)
        onNodeWithText("144 Hz").performClick()
        assertEquals(144, settings.state.value.display.frameCapHz)
    }

    @Test
    fun thePerformanceOverlayIsOffUntilTheSwitchIsTurnedOn() = runComposeUiTest {
        show()
        assertFalse(settings.state.value.display.performanceOverlay)

        onNodeWithText("Show the performance overlay").performClick()
        assertTrue(settings.state.value.display.performanceOverlay)
        onNodeWithText("Show the performance overlay").performClick()
        assertFalse(settings.state.value.display.performanceOverlay)
    }

    @Test
    fun theScreenSaysWhereTheSettingsAreKeptOrThatTheyAreNot() = runComposeUiTest {
        show()
        onNodeWithText("C:\\Users\\Tina\\AppData\\Local\\FTC-Sim\\config\\ui.properties").assertExists()
    }

    @Test
    fun theScreenSaysNothingIsSavedWhenThereIsNoFile() = runComposeUiTest {
        show(path = null)
        onNodeWithText("Nowhere: this run does not save settings.").assertExists()
    }

    @Test
    fun theFrameRateTextFollowsTheDisplayAndTheCap() {
        assertEquals(
            "Your display refreshes at 60 Hz, so the viewport draws at most 60 frames per second.",
            frameRateText(60.0, 144),
        )
        assertEquals(
            "Your display refreshes at 144 Hz, so the viewport draws at most 144 frames per second.",
            frameRateText(144.0, 144),
        )
        assertEquals(
            "Your display refreshes at 280 Hz, so the viewport draws at most 140 frames per second: " +
                "an even share of the display's rate, which looks smoother than 144.",
            frameRateText(280.0, 144),
        )
        assertEquals(
            "Your display refreshes at 144 Hz, so the viewport draws at most 72 frames per second: " +
                "an even share of the display's rate, which looks smoother than 90.",
            frameRateText(144.0, 90),
        )
        assertTrue(frameRateText(0.0, 144).contains("not known yet"))
    }
}
