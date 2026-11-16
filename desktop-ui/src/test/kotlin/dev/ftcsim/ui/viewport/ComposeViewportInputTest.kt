package dev.ftcsim.ui.viewport

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.ftcsim.ui.input.ViewportInput
import dev.ftcsim.ui.render.OrbitCamera
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The mouse on the viewport: which drag orbits the camera and which one pans it, with synthetic events and no window. */
@OptIn(ExperimentalTestApi::class)
class ComposeViewportInputTest {
    private val camera = OrbitCamera().also { it.select(OrbitCamera.Preset.DRIVER) }
    private val input = ViewportInput(camera)

    private fun ComposeUiTest.show() = setContent {
        Box(Modifier.size(400.dp, 300.dp).testTag("viewport").composeViewportInput(input, FocusRequester()))
    }

    private fun projectedX(): Double {
        val ndc = DoubleArray(2)
        camera.project(0.0, 0.0, 0.15, 16.0 / 9.0, ndc)
        return ndc[0]
    }

    private fun ComposeUiTest.drag(button: MouseButton) {
        onNodeWithTag("viewport").performMouseInput {
            moveTo(Offset(100f, 100f))
            press(button)
            moveBy(Offset(60f, 0f))
            release(button)
        }
    }

    @Test
    fun leftDragOrbitsAndLeavesThePresetWithoutSlidingTheScene() = runComposeUiTest {
        show()
        val yaw = camera.yawDegrees()
        val before = projectedX()

        drag(MouseButton.Primary)

        assertEquals(OrbitCamera.Preset.FREE, camera.preset())
        assertNotEquals(yaw, camera.yawDegrees(), "the view turned")
        assertEquals(before, projectedX(), 1e-9, "the scene did not slide")
    }

    @Test
    fun rightOrMiddleDragPansTheSceneWithThePointerAndDoesNotTurnTheView() = runComposeUiTest {
        show()
        for (button in listOf(MouseButton.Secondary, MouseButton.Tertiary)) {
            camera.select(OrbitCamera.Preset.DRIVER)
            val yaw = camera.yawDegrees()
            val before = projectedX()

            drag(button)

            assertEquals(OrbitCamera.Preset.FREE, camera.preset(), "$button")
            assertEquals(yaw, camera.yawDegrees(), "$button does not orbit")
            assertTrue(projectedX() > before, "$button: dragging right moves the scene right")
        }
    }
}
