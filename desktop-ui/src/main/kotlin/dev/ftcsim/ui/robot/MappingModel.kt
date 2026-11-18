package dev.ftcsim.ui.robot

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
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
    /** The names of the positional servos of the hood, the stopper and the colibri arms, empty for none; by [RobotMapping.ServoRole]. */
    val mechanismServos: Map<RobotMapping.ServoRole, String> = emptyMap(),
    /** Motor turns to one turn of a drive wheel, as typed: a number or a fraction such as 43/30; empty is 1. */
    val driveRatio: String = "",
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

    /** The ratio of each motor that is not a wheel, as typed; a wheel's is [MappingForm.driveRatio]. */
    private val ratioTexts = mutableStateMapOf<String, String>()
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
        placed += mapping.motors().filter { turret || it.slot() != Slot.TURRET }.map { it.withRatio(0.0) }
        ratioTexts.clear()
        mapping.motors().filter { !it.slot().wheel() && it.ratio() != 0.0 }.forEach { ratioTexts[it.name()] = text(it.ratio()) }
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
        ratioTexts.remove(name)
    }

    /** The ratio typed for [name], motor turns to one turn of what it drives; on a wheel it is the one of all four. */
    fun ratio(name: String): String {
        val motor = placed.firstOrNull { it.name() == name } ?: return ""
        return if (motor.slot().wheel()) form.driveRatio else ratioTexts[name].orEmpty()
    }

    fun setRatio(name: String, text: String) {
        val motor = placed.firstOrNull { it.name() == name } ?: return
        if (motor.slot().wheel()) edit { it.copy(driveRatio = text) } else ratioTexts[name] = text
    }

    /** What is wrong with the ratio typed for [name], or null when it is a ratio or empty. */
    fun ratioError(name: String): String? {
        val value = parseRatio(ratio(name)) ?: return "A number or a fraction, like 43/30"
        return if (value == 0.0 || value in RobotMapping.MIN_RATIO..RobotMapping.MAX_RATIO) null
        else "${RobotMapping.MIN_RATIO} to ${RobotMapping.MAX_RATIO}"
    }

    /** The id of the catalogue motor on [name]; on a wheel it is the one that turns all four. */
    fun model(name: String): String {
        val motor = placed.firstOrNull { it.name() == name } ?: return driveMotor
        return if (motor.slot().wheel()) driveMotor else motor.model().ifEmpty { RobotMapping.USUAL_MOTOR }
    }

    /** Chooses the model of [name]; on a wheel it changes all four, and the part's usual model is stored as none. */
    fun setModel(name: String, id: String) {
        val motor = placed.firstOrNull { it.name() == name } ?: return
        if (motor.slot().wheel()) {
            driveMotor = id
        } else {
            replace(motor, id.takeUnless { it == RobotMapping.USUAL_MOTOR }.orEmpty(), motor.mirrored())
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

    /** The motors as the page has them, each with the ratio typed for it. */
    private fun motorsWithRatios(): List<MotorMapping> =
        placed.map { if (it.slot().wheel()) it else it.withRatio(parseRatio(ratioTexts[it.name()].orEmpty()) ?: Double.NaN) }

    /** What is wrong with the page, by the key of the field: [NAME], [MASS], [WHEEL_DIAMETER], [TURRET_RATIO], [HUB] and [camera], and [MOTORS] for the rest. */
    fun problems(): Map<String, String> {
        val errors = linkedMapOf<String, String>()
        for (problem in draft().problems(turret)) {
            errors.putIfAbsent(keyOf(problem), problem)
        }
        if (parseRatio(form.driveRatio) == null) errors.putIfAbsent(MOTORS, "Drive ratio is a number or a fraction")
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
            motorsWithRatios(),
            number(form.massKg),
            number(form.wheelDiameterMm),
            driveMotor,
            if (turret) number(form.turretRatio) else number(form.turretRatio).takeIf { it > 0 } ?: 1.0,
            form.hubLogo,
            form.hubUsb,
            CameraMount(camera[0], camera[1], camera[2], camera[3], camera[4], camera[5]),
            turretParts(),
            parseRatio(form.driveRatio) ?: Double.NaN,
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
        val mechanisms = RobotMapping.ServoRole.entries.mapNotNull { role ->
            form.mechanismServos[role]?.trim()?.takeIf { it.isNotEmpty() }?.let { name ->
                val kept = before.servoFor(role)
                RobotMapping.MechanismServo(role, RobotMapping.ServoMapping(name, kept?.model().orEmpty(), kept?.mirrored() ?: false))
            }
        }
        return RobotMapping.TurretParts(servo, before.encoders(), before.axisX(), before.axisY(), form.cameraOnTurret, mechanisms)
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

        /**
         * A ratio as a person writes it: a number, or a fraction such as 43/30. Empty is 0, which the simulator takes as 1 to 1; null when
         * it is neither.
         */
        fun parseRatio(text: String): Double? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return 0.0
            val parts = trimmed.split('/')
            if (parts.size > 2) return null
            val numbers = parts.map { SetupValidation.number(it.trim()) ?: return null }
            if (numbers.size == 1) return numbers[0]
            return if (numbers[1] == 0.0) null else numbers[0] / numbers[1]
        }

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
                RobotMapping.ServoRole.entries.mapNotNull { role -> mapping.turretParts().servoFor(role)?.let { role to it.name() } }.toMap(),
                if (mapping.driveRatio() != 0.0) text(mapping.driveRatio()) else "",
            )
        }

        /** [value] as the shortest text that reads back as the same number, without an exponent. */
        private fun text(value: Double): String =
            if (value.isFinite()) BigDecimal(value.toString()).stripTrailingZeros().toPlainString() else ""
    }
}
