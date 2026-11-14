package dev.ftcsim.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import dev.ftcsim.ui.common.AppIcons
import dev.ftcsim.ui.common.Tip

private val DIGIT_KEYS = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six)

/**
 * The frame around every screen: a Material 3 navigation rail with the destinations and a Back button, and the shown
 * destination beside it. Ctrl+1 to Ctrl+6 go to the destinations in the order of the rail and Alt+Left goes back, from
 * wherever the keyboard focus is.
 */
@Composable
fun AppShell(navigator: Navigator, modifier: Modifier = Modifier, content: @Composable (Destination) -> Unit) {
    val state by navigator.state.collectAsState()
    Row(modifier.fillMaxSize().onPreviewKeyEvent { navigator.handleShortcut(it) }) {
        NavigationRail(
            header = {
                Tip("Back (Alt+Left)") {
                    IconButton(onClick = { navigator.back() }, enabled = state.canGoBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            },
        ) {
            Destination.entries.forEach { destination ->
                NavigationRailItem(
                    selected = destination == state.current,
                    onClick = { navigator.go(destination) },
                    icon = { Icon(iconOf(destination), contentDescription = null) },
                    label = { Text(destination.label) },
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight()) { content(state.current) }
    }
}

private fun iconOf(destination: Destination): ImageVector = when (destination) {
    Destination.HOME -> Icons.Filled.Home
    Destination.PREPARE -> AppIcons.Package
    Destination.SETUP -> Icons.Filled.Build
    Destination.CONTROLS -> AppIcons.Gamepad
    Destination.PRACTICE -> Icons.Filled.PlayArrow
    Destination.SETTINGS -> Icons.Filled.Settings
}

/** Handles the shortcuts of the shell; true when [event] was one of them. */
internal fun Navigator.handleShortcut(event: KeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    if (event.isCtrlPressed) {
        val index = DIGIT_KEYS.indexOf(event.key)
        if (index in Destination.entries.indices) {
            go(Destination.entries[index])
            return true
        }
    } else if (event.isAltPressed && event.key == Key.DirectionLeft) {
        return back()
    }
    return false
}
