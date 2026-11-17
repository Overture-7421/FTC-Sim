package dev.ftcsim.ui.robot

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.ftcsim.contracts.hardware.UnmappedDevice
import dev.ftcsim.contracts.robot.OpModeDescriptor
import dev.ftcsim.contracts.session.RobotMapping
import dev.ftcsim.contracts.session.RobotMapping.Slot
import dev.ftcsim.ui.common.DropdownSelect
import dev.ftcsim.ui.common.FormTextField
import dev.ftcsim.ui.common.ProblemText
import dev.ftcsim.ui.project.ScanState
import kotlin.math.roundToInt

/** Where a motor is being dragged: which one, where the pointer is, and where each place to drop it is, all in the window's coordinates. */
@Stable
class DragState {
    var motor by mutableStateOf<String?>(null)
    var position by mutableStateOf(Offset.Zero)
    val bounds = mutableStateMapOf<Slot, Rect>()

    /** The place the pointer is over, if any. */
    fun over(): Slot? = bounds.entries.firstOrNull { position in it.value }?.key

    /** Puts the dragged motor where the pointer was let go, if it fits there. */
    fun drop(model: MappingModel) {
        val dragged = motor
        val slot = over()
        motor = null
        if (dragged != null && slot != null) model.place(dragged, slot)
    }
}

/**
 * The Robot page: pick an OpMode, see the motors its code asks for that are not mapped yet, and put each on the robot's
 * wheel or mechanism. A motor is dragged, or clicked and then the place is clicked. Below are the robot's numbers, the
 * Control Hub's mounting and the Limelight's mount. The same page maps a project that has no file and edits one that has.
 *
 * @param scan what finding the OpMode's motors is doing
 * @param unmapped what the code asked for that the mapping does not place, as the simulator reports it
 * @param onScan runs the OpMode picked, to find its motors
 * @param primary the label of the button that writes the mapping
 * @param onPrimary writes the mapping; enabled when [MappingModel.blockedReason] is null and something changed
 * @param onSkip leaves the page without a mapping; no button when null
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MappingScreen(
    model: MappingModel,
    opModes: List<OpModeDescriptor>,
    selectedOpMode: String?,
    scan: ScanState,
    unmapped: List<UnmappedDevice>,
    onScan: (String) -> Unit,
    onCancelScan: () -> Unit,
    primary: String,
    onPrimary: () -> Unit,
    onSkip: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val drag = remember { DragState() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(unmapped) { model.addFound(unmapped) }
    Box(modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Robot", style = MaterialTheme.typography.headlineMedium)
                DropdownSelect(selectedOpMode ?: "Pick an OpMode", "OpMode") { close ->
                    opModes.forEach { mode ->
                        DropdownMenuItem(text = { Text(mode.name()) }, onClick = { onScan(mode.name()); close() })
                    }
                }
                ScanLine(scan, onCancelScan)
            }
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                FoundList(model, drag, Modifier.width(LIST_WIDTH).fillMaxHeight().verticalScroll(rememberScrollState()))
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Group("Wheels", model.slots.filter { it.wheel() }, model, drag)
                    Group("Mechanisms", model.slots.filter { !it.wheel() }, model, drag)
                    RobotForm(model)
                    HubForm(model)
                    LimelightRow(model)
                }
            }
            val reason = model.blockedReason()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onPrimary, enabled = reason == null && model.edited) { Text(primary) }
                if (onSkip != null) OutlinedButton(onClick = onSkip) { Text("Skip") }
                if (reason != null) Text(reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        drag.motor?.let { name ->
            Surface(
                Modifier.offset { IntOffset((drag.position.x - origin.x).roundToInt() - GHOST_OFFSET, (drag.position.y - origin.y).roundToInt() - GHOST_OFFSET) },
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer,
                shadowElevation = 6.dp,
            ) { Text(name, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge) }
        }
    }
}

private const val GHOST_OFFSET = 20

private val LIST_WIDTH = 260.dp

private val SLOT_LABEL_WIDTH = 140.dp

private val FORM_FIELD_WIDTH = 170.dp

@Composable
private fun ScanLine(scan: ScanState, onCancel: () -> Unit) {
    when (scan) {
        ScanState.Quiet -> Unit
        is ScanState.Scanning -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text("Reading ${scan.opMode}", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
        is ScanState.Done -> when {
            scan.failure != null -> ProblemText(scan.failure)
            scan.found == 0 -> Text("No motors missing", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> Unit
        }
    }
}

/** The left column: every motor the code asked for that is not on the robot yet, one to a line. */
@Composable
private fun FoundList(model: MappingModel, drag: DragState, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Detected", style = MaterialTheme.typography.titleMedium)
        if (model.found.isEmpty()) {
            Text("Pick an OpMode to list its motors", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (model.unplaced.isEmpty()) {
            Text("All mapped", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        model.unplaced.forEach { MotorChip(it, model, drag, onRemove = null, modifier = Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun MotorChip(name: String, model: MappingModel, drag: DragState, onRemove: (() -> Unit)?, modifier: Modifier = Modifier) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val selected = model.selected == name
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
            .onGloballyPositioned { origin = it.positionInRoot() }
            .pointerInput(name) {
                detectDragGestures(
                    onDragStart = { offset ->
                        model.selected = null
                        drag.motor = name
                        drag.position = origin + offset
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        drag.position = origin + change.position
                    },
                    onDragEnd = { drag.drop(model) },
                    onDragCancel = { drag.motor = null },
                )
            }
            .clickable { model.selected = if (selected) null else name }
            .semantics {
                contentDescription = "$name, motor"
                if (selected) stateDescription = "Selected"
            },
    ) {
        Row(Modifier.padding(start = 12.dp, end = if (onRemove == null) 12.dp else 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.labelLarge, modifier = if (onRemove == null) Modifier.weight(1f) else Modifier)
            if (onRemove != null) {
                IconButton(onClick = onRemove, Modifier.size(24.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove $name", modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/** A heading and one row for each of [slots], on the right of the page. */
@Composable
private fun Group(title: String, slots: List<Slot>, model: MappingModel, drag: DragState) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    slots.forEach { SlotRow(it, model, drag) }
}

/** A place on the robot, as one row: its name and the motors on it, each with its model and which way it is mounted. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SlotRow(slot: Slot, model: MappingModel, drag: DragState) {
    val carried = drag.motor ?: model.selected
    val fits = carried != null && model.canPlace(carried, slot)
    val hot = fits && drag.motor != null && drag.over() == slot
    OutlinedCard(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { drag.bounds[slot] = it.boundsInRoot() }
            .clickable(enabled = fits && drag.motor == null) { model.selected?.let { model.place(it, slot) } }
            .semantics { contentDescription = "Place here: ${slot.label}" },
        border = BorderStroke(if (hot) 3.dp else if (fits) 2.dp else 1.dp, if (fits) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(slot.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(SLOT_LABEL_WIDTH))
            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                model.motorsIn(slot).forEach { name ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MotorChip(name, model, drag, onRemove = { model.remove(name) })
                        MotorControls(name, model)
                    }
                }
            }
        }
    }
}

/** What a motor is and which way it is mounted. The four wheels share the drive motor, so changing it on one changes it on all. */
@Composable
private fun MotorControls(name: String, model: MappingModel) {
    val current = model.model(name)
    DropdownSelect(model.motorName(current) ?: "Motor", "Motor of $name") { close ->
        model.motors.forEach { choice ->
            DropdownMenuItem(text = { Text(choice.displayName()) }, onClick = { model.setModel(name, choice.id()); close() })
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Switch(
            checked = model.isMirrored(name),
            onCheckedChange = { checked -> model.setMirrored(name, checked) },
            modifier = Modifier.semantics { contentDescription = "$name motor is mounted mirrored" },
        )
        Text("Mirrored", style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RobotForm(model: MappingModel) {
    val problems = model.problems()
    val form = model.form
    Text("Robot", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val width = Modifier.width(FORM_FIELD_WIDTH)
        FormTextField(form.name, { text -> model.edit { it.copy(name = text) } }, "Name", problems[MappingModel.NAME], null, Modifier.width(240.dp))
        FormTextField(form.massKg, { text -> model.edit { it.copy(massKg = text) } }, "Mass (kg)", problems[MappingModel.MASS], null, width)
        FormTextField(form.wheelDiameterMm, { text -> model.edit { it.copy(wheelDiameterMm = text) } }, "Wheel diameter (mm)", problems[MappingModel.WHEEL_DIAMETER], null, width)
        if (model.turret) {
            FormTextField(form.turretRatio, { text -> model.edit { it.copy(turretRatio = text) } }, "Turret ratio", problems[MappingModel.TURRET_RATIO], null, width)
            FormTextField(
                form.turretServo, { text -> model.edit { it.copy(turretServo = text) } }, "Turret servo (name in code)",
                problems[MappingModel.SERVO], "Empty when a motor turns the turret", Modifier.width(240.dp),
            )
        }
    }
    if (model.turret) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(
                checked = form.servoMirrored,
                onCheckedChange = { checked -> model.edit { it.copy(servoMirrored = checked) } },
                modifier = Modifier.semantics { contentDescription = "Turret servo is mounted mirrored" },
            )
            Text("Servo mirrored", style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = form.cameraOnTurret,
                onCheckedChange = { checked -> model.edit { it.copy(cameraOnTurret = checked) } },
                modifier = Modifier.semantics { contentDescription = "Limelight turns with the turret" },
            )
            Text("Limelight turns with the turret", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Which way the Control Hub's logo and USB ports face on the robot. */
@Composable
private fun HubForm(model: MappingModel) {
    val form = model.form
    Text("Hub", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Logo faces", style = MaterialTheme.typography.bodyMedium)
        DropdownSelect(form.hubLogo.lowercase(), "Hub logo faces") { close ->
            RobotMapping.DIRECTIONS.forEach { direction ->
                DropdownMenuItem(text = { Text(direction.lowercase()) }, onClick = { model.edit { it.copy(hubLogo = direction) }; close() })
            }
        }
        Text("USB faces", style = MaterialTheme.typography.bodyMedium)
        DropdownSelect(form.hubUsb.lowercase(), "Hub USB faces") { close ->
            RobotMapping.DIRECTIONS.forEach { direction ->
                DropdownMenuItem(text = { Text(direction.lowercase()) }, onClick = { model.edit { it.copy(hubUsb = direction) }; close() })
            }
        }
    }
}

/** The Limelight, with a button for the window that holds the numbers of its mount; the button turns red while one of them is wrong. */
@Composable
private fun LimelightRow(model: MappingModel) {
    var open by remember { mutableStateOf(false) }
    val wrong = model.problems().keys.any { it.startsWith("camera.") }
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Limelight", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { open = true }, Modifier.semantics { contentDescription = "Settings of Limelight" }) {
            Text("Settings", color = if (wrong) MaterialTheme.colorScheme.error else Color.Unspecified)
        }
    }
    if (open) LimelightWindow(model) { open = false }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LimelightWindow(model: MappingModel, onClose: () -> Unit) {
    val problems = model.problems()
    SettingsWindow("Limelight", null, onClose) {
        SettingsGroup("Mount") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MappingModel.CAMERA_LABELS.forEachIndexed { index, label ->
                    FormTextField(
                        model.form.camera[index],
                        { text -> model.edit { form -> form.copy(camera = form.camera.mapIndexed { at, old -> if (at == index) text else old }) } },
                        label,
                        problems[MappingModel.camera(index)],
                        null,
                        Modifier.width(FIELD_WIDTH).semantics { contentDescription = "Limelight $label" },
                    )
                }
            }
        }
    }
}
