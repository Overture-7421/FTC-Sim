package dev.ftcsim.ui.viewport

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private val BACKGROUND = Color(0xFF12141A)

/**
 * Shows what [engine] renders: its newest frame as a bitmap, a focus ring while the viewport has the keyboard, and a
 * short message while the render thread is starting or after it failed. Pointer and key input go to the engine. While
 * this is in the composition the engine knows that the viewport is on screen and draws; when it leaves, the engine
 * stops drawing and releases the keys that were held.
 */
@Composable
fun ViewportSurface(
    engine: ViewportEngine,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    onFocusChange: (Boolean) -> Unit = {},
) {
    val status by engine.status.collectAsState()
    var focused by remember { mutableStateOf(false) }
    DisposableEffect(engine) {
        engine.setShown(true)
        onDispose { engine.setShown(false) }
    }
    val ring = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent
    Box(
        modifier
            .background(BACKGROUND)
            .onSizeChanged { engine.resize(it.width, it.height) }
            .composeViewportInput(engine.input, focusRequester) {
                focused = it
                onFocusChange(it)
            }
            .drawBehind { engine.shown.draw(this, engine) }
            .border(2.dp, ring, RoundedCornerShape(4.dp))
            .semantics { contentDescription = "3D viewport. Focus it to drive with the keyboard." },
        contentAlignment = Alignment.Center,
    ) {
        ViewportStatusMessage(status)
    }
}

/** What stands in place of the picture while the render thread is starting or after it failed; nothing once it runs. */
@Composable
internal fun ViewportStatusMessage(status: ViewportStatus) {
    when (status) {
        ViewportStatus.Starting -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(32.dp))
            Text("Starting the 3D viewport", color = Color.White.copy(alpha = 0.8f))
        }
        is ViewportStatus.Failed -> Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Text("The 3D viewport is not available", color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text(status.message, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium)
        }
        is ViewportStatus.Running -> Unit
    }
}
