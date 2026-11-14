package dev.ftcsim.ui.controls

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ftcsim.contracts.robot.GamepadState
import dev.ftcsim.ui.common.AppIcons
import dev.ftcsim.ui.common.DropdownSelect
import dev.ftcsim.ui.input.GamepadConfig
import dev.ftcsim.ui.input.PadAssignment
import java.util.Locale
import kotlin.math.roundToInt

/**
 * One gamepad of the Controls screen: where it listens, what it is doing right now and its dead zones. The preview
 * shows what the controller reports, so a person can see at once that it works and how far a dead zone reaches.
 */
@Composable
fun GamepadCard(
    gamepad: Int,
    pad: GamepadConfig.Pad,
    view: PadView,
    controllers: List<ControllerInfo>,
    model: ControlsModel,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Gamepad $gamepad", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            SourceSelect(gamepad, pad.assignment(), controllers, model)
            Listening(view)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                StickPreview("Left stick", view.raw[0], view.raw[1], view.state.leftStickX(), view.state.leftStickY(), pad.stickDeadzone())
                StickPreview("Right stick", view.raw[2], view.raw[3], view.state.rightStickX(), view.state.rightStickY(), pad.stickDeadzone())
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TriggerBar("Left trigger", view.state.leftTrigger())
                    TriggerBar("Right trigger", view.state.rightTrigger())
                }
            }
            ButtonLights(view.state)
            DeadzoneSlider(
                "Stick dead zone", "Gamepad $gamepad stick dead zone", pad.stickDeadzone(), GamepadConfig.Pad.MAX_STICK_DEADZONE,
            ) { model.setStickDeadzone(gamepad, it) }
            DeadzoneSlider(
                "Trigger dead zone", "Gamepad $gamepad trigger dead zone", pad.triggerDeadzone(), GamepadConfig.Pad.MAX_TRIGGER_DEADZONE,
            ) { model.setTriggerDeadzone(gamepad, it) }
        }
    }
}

@Composable
private fun SourceSelect(gamepad: Int, assignment: PadAssignment, controllers: List<ControllerInfo>, model: ControlsModel) {
    val connected = controllers.any { it.guid == assignment.guid() && it.hasGamepadMapping }
    DropdownSelect(sourceName(assignment, connected), "Gamepad $gamepad listens to") { close ->
        SourceItem("Automatic", assignment.kind() == PadAssignment.Kind.AUTOMATIC) { model.assign(gamepad, PadAssignment.AUTOMATIC); close() }
        SourceItem("Keyboard", assignment.kind() == PadAssignment.Kind.KEYBOARD) { model.assign(gamepad, PadAssignment.KEYBOARD); close() }
        SourceItem("None", assignment.kind() == PadAssignment.Kind.NONE) { model.assign(gamepad, PadAssignment.NONE); close() }
        HorizontalDivider()
        if (assignment.kind() == PadAssignment.Kind.CONTROLLER && !connected) {
            SourceItem("${assignment.name().ifBlank { "The controller" }} (not connected)", true) { close() }
        }
        if (controllers.isEmpty()) {
            DropdownMenuItem(text = { Text("No controller is connected") }, onClick = close, enabled = false)
        }
        controllers.forEach { controller ->
            val selected = assignment.kind() == PadAssignment.Kind.CONTROLLER && assignment.guid() == controller.guid
            SourceItem(
                if (controller.hasGamepadMapping) controller.name else "${controller.name} (no gamepad mapping)",
                selected,
                enabled = controller.hasGamepadMapping,
            ) {
                model.assign(gamepad, PadAssignment.controller(controller.guid, controller.name))
                close()
            }
        }
    }
}

@Composable
private fun SourceItem(text: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        leadingIcon = { if (selected) Icon(Icons.Filled.Check, contentDescription = "Chosen") },
        onClick = onClick,
        enabled = enabled,
    )
}

private fun sourceName(assignment: PadAssignment, connected: Boolean): String = when (assignment.kind()) {
    PadAssignment.Kind.AUTOMATIC -> "Automatic"
    PadAssignment.Kind.KEYBOARD -> "Keyboard"
    PadAssignment.Kind.NONE -> "None"
    PadAssignment.Kind.CONTROLLER ->
        assignment.name().ifBlank { "Controller" } + if (connected) "" else " (not connected)"
}

@Composable
private fun Listening(view: PadView) {
    val problem = view.resolved.problem()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Listening to: ${view.resolved.description()}", style = MaterialTheme.typography.bodyMedium)
        if (problem != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Icon(AppIcons.Error, contentDescription = "Problem", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                Text(problem, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** A stick as a circle: the grey disc is the dead zone, the small dot what the controller reports, the large one what the robot gets. */
@Composable
private fun StickPreview(name: String, rawX: Float, rawY: Float, mappedX: Float, mappedY: Float, deadzone: Float) {
    val outline = MaterialTheme.colorScheme.outline
    val zone = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    val rawColor = MaterialTheme.colorScheme.tertiary
    val mappedColor = MaterialTheme.colorScheme.primary
    Canvas(
        Modifier.size(88.dp).semantics {
            contentDescription = String.format(Locale.ROOT, "%s, x %.2f, y %.2f", name, mappedX, mappedY)
        },
    ) {
        val radius = size.minDimension / 2 - 4.dp.toPx()
        drawCircle(zone, radius = radius * deadzone)
        drawCircle(outline, radius = radius, style = Stroke(1.dp.toPx()))
        drawLine(outline.copy(alpha = 0.4f), Offset(center.x - radius, center.y), Offset(center.x + radius, center.y), 1.dp.toPx())
        drawLine(outline.copy(alpha = 0.4f), Offset(center.x, center.y - radius), Offset(center.x, center.y + radius), 1.dp.toPx())
        drawCircle(rawColor, radius = 3.dp.toPx(), center = Offset(center.x + rawX * radius, center.y + rawY * radius))
        drawCircle(mappedColor, radius = 5.dp.toPx(), center = Offset(center.x + mappedX * radius, center.y + mappedY * radius))
    }
}

@Composable
private fun TriggerBar(name: String, value: Float) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(name, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(88.dp))
        LinearProgressIndicator(
            progress = { value.coerceIn(0f, 1f) },
            modifier = Modifier.weight(1f).semantics { contentDescription = "$name ${(value * 100).roundToInt()} percent" },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ButtonLights(state: GamepadState) {
    val lights = listOf(
        "A" to state.a(), "B" to state.b(), "X" to state.x(), "Y" to state.y(),
        "LB" to state.leftBumper(), "RB" to state.rightBumper(),
        "LS" to state.leftStickButton(), "RS" to state.rightStickButton(),
        "Back" to state.back(), "Start" to state.start(), "Guide" to state.guide(),
        "Up" to state.dpadUp(), "Down" to state.dpadDown(), "Left" to state.dpadLeft(), "Right" to state.dpadRight(),
    )
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        lights.forEach { (label, pressed) ->
            Surface(
                shape = MaterialTheme.shapes.small,
                color = if (pressed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (pressed) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { contentDescription = "$label ${if (pressed) "pressed" else "released"}" },
            ) {
                Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            }
        }
    }
}

@Composable
private fun DeadzoneSlider(label: String, description: String, value: Float, max: Float, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Text("${(value * 100).roundToInt()} %", style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = 0f..max,
            modifier = Modifier.semantics { contentDescription = description },
        )
    }
}
