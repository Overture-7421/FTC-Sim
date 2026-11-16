package dev.ftcsim.ui.controls

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.focusable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ftcsim.ui.common.AppIcons
import dev.ftcsim.ui.common.Tip
import dev.ftcsim.ui.input.KeyboardMap
import dev.ftcsim.ui.input.PadControl
import dev.ftcsim.ui.input.ViewportInput
import dev.ftcsim.ui.viewport.heldKeyCapture

/**
 * The keyboard as a gamepad: a place to try the keys, and the table of which key stands for which control. A key is
 * changed by pressing its button and then the new key, inside the application window.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeyboardCard(
    model: ControlsModel,
    map: KeyboardMap,
    input: ViewportInput,
    heldKeys: Set<Int>,
    modifier: Modifier = Modifier,
) {
    val shared = map.conflicts()
    OutlinedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Keyboard", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            KeyTestArea(input, heldKeys)
            if (shared.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    Icon(AppIcons.Error, contentDescription = "Problem", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                    Column {
                        shared.forEach { (key, controls) ->
                            Text(
                                "${KeyboardMap.keyName(key)} is on ${controls.joinToString(" and ") { it.label() }}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PadControl.entries.groupBy { it.group() }.forEach { (group, controls) ->
                    OutlinedCard(Modifier.width(250.dp)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(group, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                            controls.forEach { control ->
                                BindingRow(control, map.keyOf(control), map.keyOf(control) in shared, model.rebinding == control, model)
                            }
                        }
                    }
                }
            }
            val message = model.message
            if (message != null) {
                Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            TextButton(onClick = model::restoreDefaultKeys) { Text("Restore the default keys") }
        }
    }
}

/** Takes the keys while it has focus, like the viewport does, so that a person can see what the mapping does. */
@Composable
private fun KeyTestArea(input: ViewportInput, held: Set<Int>) {
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    DisposableEffect(input) { onDispose { input.focusChanged(false) } }
    val ring = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heldKeyCapture(input, focus) { focused = it }
            .pointerInput(focus) { detectTapGestures(onPress = { focus.requestFocus() }) }
            .border(2.dp, ring, MaterialTheme.shapes.small)
            .semantics { contentDescription = "Key test area" },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            when {
                !focused -> "Click to test keys"
                held.isEmpty() -> "Press keys"
                else -> "Held: " + held.joinToString(", ") { KeyboardMap.keyName(it) }
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun BindingRow(control: PadControl, key: Int, conflicting: Boolean, rebinding: Boolean, model: ControlsModel) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            control.shortName().replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (rebinding) {
            KeyCapture(control, model)
        } else {
            OutlinedButton(
                onClick = { model.beginRebind(control) },
                colors = if (conflicting) {
                    ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.outlinedButtonColors()
                },
                modifier = Modifier.semantics { contentDescription = "${control.label()}: ${KeyboardMap.keyName(key)}. Press to change." },
            ) { Text(KeyboardMap.keyName(key), maxLines = 1) }
        }
        Tip("Clear this key") {
            IconButton(onClick = { model.clearBinding(control) }, enabled = key != 0) {
                Icon(Icons.Filled.Clear, contentDescription = "Clear the key of ${control.label()}")
            }
        }
    }
}

/** Waits for the next key: takes keyboard focus, hands every key to the model and gives up if focus goes elsewhere. */
@Composable
private fun KeyCapture(control: PadControl, model: ControlsModel) {
    val focus = remember { FocusRequester() }
    var hadFocus by remember { mutableStateOf(false) }
    LaunchedEffect(control) { focus.requestFocus() }
    Surface(
        modifier = Modifier
            .focusRequester(focus)
            .onFocusChanged {
                if (it.isFocused) hadFocus = true else if (hadFocus) model.cancelRebind()
            }
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) model.offerKey(event.key.nativeKeyCode)
                true
            }
            .semantics { contentDescription = "Press the new key for ${control.label()}" },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Text("Press a key, Esc cancels", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    }
}
