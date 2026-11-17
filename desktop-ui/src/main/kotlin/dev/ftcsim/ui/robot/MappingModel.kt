package dev.ftcsim.ui.robot

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ftcsim.contracts.hardware.UnmappedDevice
import dev.ftcsim.contracts.hardware.UnmappedDevice.Kind
import dev.ftcsim.contracts.session.MotorChoice
import dev.ftcsim.contracts.session.RobotMapping
import dev.ftcsim.contracts.session.RobotMapping.CameraMount
import dev.ftcsim.contracts.session.RobotMapping.MotorMapping
import dev.ftcsim.contracts.session.RobotMapping.Slot
import dev.ftcsim.ui.setup.SetupValidation
import java.math.BigDecimal

/** The names of the parts of the fixed robot on the page. */
val Slot.label: String
    get() = when (this) {
        Slot.FRONT_LEFT -> "Front left"
        Slot.FRONT_RIGHT -> "Front right"
        Slot.REAR_LEFT -> "Rear left"
        Slot.REAR_RIGHT -> "Rear right"
        Slot.INTAKE -> "Intake roller"
        Slot.CONVEYOR -> "Conveyor"
        Slot.SHOOTER -> "Shooter flywheel"
        Slot.TURRET -> "Turret"
    }

/**
 * What is typed into the form below the slots: text that is not checked yet. [camera] holds the six numbers of the
 * Limelight's mount in the order of [RobotMapping.CameraMount]. [turretServo] is the name of the continuous-rotation
 * servo that turns the turret, or empty when a motor does ([servoMirrored] turns it the other way); [cameraOnTurret] says
 * that the Limelight turns with the turret.
 */
data class MappingForm(
    val name: String,
    val massKg: String,
    val wheelDiameterMm: String,
    val turretRatio: String,
    val hubLogo: String,
    val hubUsb: String,
    val camera: List<String>,
    val turretServo: String = "",
    val servoMirrored: Boolean = false,
    val cameraOnTurret: Boolean = false,
)

/**
 * What the Robot page holds: the motors the robot's code asked for and the ones the project's mapping already places,
 * where each is, how it is mounted, and the numbers of the robot. Pure state, so a test needs no screen.
 *
 * The page works the same for a project without a mapping and for one with it: [load] puts what the file says on the page,
 * so everything is edited in the same way, and [edited] tells whether the page differs from what was loaded.
 *
 * @param motors the motors the simulator offers, the drive motor first
 * @param turret whether the project's game has a robot with a turret
 */
@Stable
class MappingModel(projectName: String, val motors: List<MotorChoice>, val turret: Boolean) {
    private val mutableFound = mutableStateListOf<String>()
    private val placed = mutableStateListOf<MotorMapping>()
    private var loaded: RobotMapping? = null

    private val defaults = RobotMapping.empty(projectName.ifBlank { "My robot" })

    /** The numbers and choices below the slots, as typed. */
    var form by mutableStateOf(formOf(defaults))
        private set

    /** The motor of all four wheels, by catalogue id. */
    var driveMotor by mutableStateOf(defaults.driveMotor())

    /** The motor picked up by a click, waiting to be put somewhere. */
    var selected by mutableStateOf<String?>(null)

    /** The parts of the robot that take a motor, in the order of the page: wheels, then mechanisms. */
    val slots: List<Slot> = Slot.entries.filter { turret || it != Slot.TURRET }

    /** Every motor found so far, in the order found. */
    val found: List<String> get() = mutableFound

    /** Takes in the motors the code asked for; the other kinds of device are fixed and are not offered, and those already known are not repeated. */
    fun addFound(devices: List<UnmappedDevice>) {
        for (device in devices) {
            if (device.kind == Kind.MOTOR && device.name !in mutableFound) mutableFound += device.name
        }
    }

    fun edit(change: (MappingForm) -> MappingForm) {
        form = change(form)
    }

    /** The motor model [id] by its catalogue name, or null when the catalogue does not have it. */
    fun motorName(id: String): String? = motors.firstOrNull { it.id() == id }?.displayName()

    /**
     * Puts what the mapping says on the page, replacing what was there. A motor on the turret of a game that has none goes
     * back to the list, which makes the page differ from the file.
     */
    fun load(mapping: RobotMapping) {
        selected = null
        placed.clear()
        placed += mapping.motors().filter { turret || it.slot() != Slot.TURRET }
        // The motors the file maps come first, in the file's order; the others stay in the order found.
        val inFile = mapping.motors().map { it.name() }
        mutableFound.removeAll(inFile)
        mutableFound.addAll(0, inFile)
        driveMotor = mapping.driveMotor()
        form = formOf(mapping)
        loaded = mapping
    }

    /** Whether anything on the page differs from what was loaded; always true for a robot that has no file yet. */
    val edited: Boolean get() = loaded?.let { inNameOrder(it) != inNameOrder(draft()) } ?: true

    /** The motors are found by name, so which comes first is no difference to the person. */
    private fun inNameOrder(mapping: RobotMapping): RobotMapping = mapping.withMotors(mapping.motors().sortedBy { it.name() })

    /** The motors that are not on the robot, in the order found. */
    val unplaced: List<String> get() = mutableFound.filter { name -> placed.none { it.name() == name } }

    fun motorsIn(slot: Slot): List<String> = placed.filter { it.slot() == slot }.map { it.name() }

    fun canPlace(name: String, slot: Slot): Boolean = slot in slots && name.isNotBlank()

    /**
     * Puts the motor [name] in [slot]. A wheel holds one motor, so the one that was there goes back to the list; a mechanism
     * holds any number, and a motor is on one part only. False if it does not fit.
     */
    fun place(name: String, slot: Slot): Boolean {
        if (!canPlace(name, slot)) return false
        val before = placed.firstOrNull { it.name() == name }
        if (slot.wheel()) placed.removeAll { it.slot() == slot && it.name() != name }
        val motor = MotorMapping(name, slot, if (slot.wheel()) "" else before?.model().orEmpty(), before?.mirrored() ?: false)
        val at = placed.indexOfFirst { it.name() == name }
        if (at >= 0) placed[at] = motor else placed += motor
        if (name !in mutableFound) mutableFound += name
        selected = null
        return true
    }

    /** Takes the motor [name] off the robot and back to the list. */
    fun remove(name: String) {
        placed.removeAll { it.name() == name }
    }

    /** The id of the catalogue motor on [name]; on a wheel it is the one that turns all four. */
    fun model(name: String): String {
        val motor = placed.firstOrNull { it.name() == name } ?: return driveMotor
        return if (motor.slot().wheel()) driveMotor else motor.model().ifEmpty { RobotMapping.usualModel(motor.slot()) }
    }

    /** Chooses the model of [name]; on a wheel it changes all four, and the part's usual model is stored as none. */
    fun setModel(name: String, id: String) {
        val motor = placed.firstOrNull { it.name() == name } ?: return
        if (motor.slot().wheel()) {
            driveMotor = id
        } else {
            replace(motor, id.takeUnless { it == RobotMapping.usualModel(motor.slot()) }.orEmpty(), motor.mirrored())
        }
    }

    /** Whether the motor is mounted the other way round, so that positive power turns what it drives backwards. */
    fun isMirrored(name: String): Boolean = placed.firstOrNull { it.name() == name }?.mirrored() ?: false

    fun setMirrored(name: String, mirrored: Boolean) {
        val motor = placed.firstOrNull { it.name() == name } ?: return
        replace(motor, motor.model(), mirrored)
    }

    private fun replace(motor: MotorMapping, model: String, mirrored: Boolean) {
        val at = placed.indexOf(motor)
        if (at >= 0) placed[at] = MotorMapping(motor.name(), motor.slot(), model, mirrored)
    }

    /** What is wrong with the page, by the key of the field: [NAME], [MASS], [WHEEL_DIAMETER], [TURRET_RATIO], [HUB] and [camera], and [MOTORS] for the rest. */
    fun problems(): Map<String, String> {
        val errors = linkedMapOf<String, String>()
        for (problem in draft().problems(turret)) {
            errors.putIfAbsent(keyOf(problem), problem)
        }
        form.camera.forEachIndexed { index, text ->
            if (SetupValidation.number(text) == null) errors[camera(index)] = "A number"
        }
        return errors
    }

    /** Why the robot cannot be written yet, in a few words, or null when it can. */
    fun blockedReason(): String? {
        problems().values.firstOrNull()?.let { return it }
        return if (loaded == null && placed.isEmpty()) "Map a motor" else null
    }

    /** The mapping the page describes, or null while anything on it is wrong. */
    fun mapping(): RobotMapping? = if (blockedReason() == null) draft() else null

    private fun draft(): RobotMapping {
        val camera = form.camera.map { SetupValidation.number(it) ?: Double.NaN }
        return RobotMapping(
            form.name.trim(),
            placed.toList(),
            number(form.massKg),
            number(form.wheelDiameterMm),
            driveMotor,
            if (turret) number(form.turretRatio) else number(form.turretRatio).takeIf { it > 0 } ?: 1.0,
            form.hubLogo,
            form.hubUsb,
            CameraMount(camera[0], camera[1], camera[2], camera[3], camera[4], camera[5]),
            turretParts(),
        )
    }

    /**
     * The servo and the camera as the page says them. The encoders and the axis are not on the page: they are the file's,
     * and saving the page keeps them as they were.
     */
    private fun turretParts(): RobotMapping.TurretParts {
        val before = loaded?.turretParts() ?: RobotMapping.TurretParts.NONE
        if (!turret) return before
        val servo = form.turretServo.trim().takeIf { it.isNotEmpty() }?.let {
            RobotMapping.ServoMapping(it, before.servo()?.model().orEmpty(), form.servoMirrored)
        }
        return RobotMapping.TurretParts(servo, before.encoders(), before.axisX(), before.axisY(), form.cameraOnTurret)
    }

    private fun number(text: String): Double = SetupValidation.number(text) ?: Double.NaN

    private fun keyOf(problem: String): String = when {
        problem.startsWith("The robot has no name") -> NAME
        problem.startsWith("Mass") -> MASS
        problem.startsWith("Wheel diameter") -> WHEEL_DIAMETER
        problem.startsWith("Turret ratio") -> TURRET_RATIO
        problem.startsWith("Hub") -> HUB
        problem.contains("turret servo") || problem.contains("Turret servo") || problem.startsWith("The turret has a motor and a servo") -> SERVO
        else -> MOTORS
    }

    companion object {
        const val NAME = "name"
        const val MASS = "mass"
        const val WHEEL_DIAMETER = "wheelDiameter"
        const val TURRET_RATIO = "turretRatio"
        const val HUB = "hub"
        const val SERVO = "servo"
        const val MOTORS = "motors"

        /** The field whose robot has a turret. */
        private const val TURRET_FIELD = "biobuzz"

        /** Whether the robot of the game on [fieldId] has a turret. */
        fun hasTurret(fieldId: String): Boolean = fieldId == TURRET_FIELD

        /** The key of the Limelight mount's [index]th number ([CAMERA_LABELS] names them). */
        fun camera(index: Int): String = "camera.$index"

        val CAMERA_LABELS = listOf("X (m)", "Y (m)", "Z (m)", "Yaw (deg)", "Pitch (deg)", "Roll (deg)")

        private fun formOf(mapping: RobotMapping): MappingForm {
            val camera = mapping.camera()
            return MappingForm(
                mapping.name(),
                text(mapping.massKg()),
                text(mapping.wheelDiameterMm()),
                text(mapping.turretRatio()),
                mapping.hubLogo(),
                mapping.hubUsb(),
                listOf(camera.x(), camera.y(), camera.z(), camera.yawDegrees(), camera.pitchDegrees(), camera.rollDegrees()).map(::text),
                mapping.turretParts().servo()?.name().orEmpty(),
                mapping.turretParts().servo()?.mirrored() ?: false,
                mapping.turretParts().cameraOnTurret(),
            )
        }

        /** [value] as the shortest text that reads back as the same number, without an exponent. */
        private fun text(value: Double): String =
            if (value.isFinite()) BigDecimal(value.toString()).stripTrailingZeros().toPlainString() else ""
    }
}
