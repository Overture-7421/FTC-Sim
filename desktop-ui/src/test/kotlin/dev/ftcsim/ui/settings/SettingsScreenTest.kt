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

        onNodeWithText("75 %").performClick()
        waitForIdle()

        assertEquals(75, settings.state.value.display.renderScalePercent)
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

        onNodeWithText("Show overlay").performClick()
        assertTrue(settings.state.value.display.performanceOverlay)
        onNodeWithText("Show overlay").performClick()
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
        onNodeWithText("Not saved").assertExists()
    }

    @Test
    fun theFrameRateTextFollowsTheDisplayAndTheCap() {
        assertEquals("Display 60 Hz, drawing at 60 fps", frameRateText(60.0, 144))
        assertEquals("Display 144 Hz, drawing at 144 fps", frameRateText(144.0, 144))
        assertEquals("Display 280 Hz, drawing at 140 fps", frameRateText(280.0, 144), "an even share of the display's rate")
        assertEquals("Display 144 Hz, drawing at 72 fps", frameRateText(144.0, 90))
        assertEquals("Display rate unknown", frameRateText(0.0, 144))
    }
}
