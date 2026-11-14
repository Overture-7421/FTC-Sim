package dev.ftcsim.ui.viewport

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import dev.ftcsim.ui.input.ViewportInput

/**
 * Keyboard focus and held keys for a surface that stands in for a gamepad: the viewport, and the key test area of the
 * Controls screen. Keys the keyboard gamepad uses are consumed while the surface has focus, otherwise Compose would
 * turn the arrow keys into focus movement and the surface would lose focus under the driver's fingers.
 */
fun Modifier.heldKeyCapture(
    input: ViewportInput,
    focusRequester: FocusRequester,
    onFocusChange: (Boolean) -> Unit = {},
): Modifier = this
    .focusRequester(focusRequester)
    .onFocusChanged {
        input.focusChanged(it.isFocused)
        onFocusChange(it.isFocused)
    }
    .focusable()
    .onKeyEvent { event ->
        val code = event.key.nativeKeyCode
        when (event.type) {
            KeyEventType.KeyDown -> input.keyPressed(code)
            KeyEventType.KeyUp -> input.keyReleased(code)
            else -> Unit
        }
        input.focused() && input.mapsKey(code)
    }

/** [heldKeyCapture] plus drag-to-orbit and wheel zoom for a viewport that Compose draws itself. */
@Composable
fun Modifier.composeViewportInput(
    input: ViewportInput,
    focusRequester: FocusRequester,
    onFocusChange: (Boolean) -> Unit = {},
): Modifier {
    val density = LocalDensity.current.density
    return this
        .heldKeyCapture(input, focusRequester, onFocusChange)
        .pointerInput(input) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.first()
                    when (event.type) {
                        PointerEventType.Press -> focusRequester.requestFocus()
                        PointerEventType.Move -> if (change.pressed) {
                            val delta = change.positionChange() / density
                            input.orbit(delta.x.toDouble(), delta.y.toDouble())
                        }
                        PointerEventType.Scroll -> input.zoom(-change.scrollDelta.y.toDouble())
                        else -> Unit
                    }
                }
            }
        }
}
