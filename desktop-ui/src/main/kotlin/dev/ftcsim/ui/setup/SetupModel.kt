package dev.ftcsim.ui.setup

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ftcsim.contracts.field.Alliance
import dev.ftcsim.contracts.session.RobotMapping
import dev.ftcsim.contracts.session.SessionRequest

/**
 * The settings form between visits to the screen. What the person typed stays when they look at another screen. The rules
 * are the simulator's catalogue, which can arrive after the form is shown, so every question takes them as an argument
 * and the model keeps none. The form holds what a run needs besides the project's program and robot mapping, which
 * belong to the open project.
 */
@Stable
class SetupModel(
    private val newSeed: () -> Long,
    initial: SetupInput = SetupInput(),
) {
    var input by mutableStateOf(initial)
        private set

    /** The problems of the input as it is now against [rules], or none before the simulator has said what it has. */
    fun problems(rules: SetupRules?): SetupProblems = if (rules == null) SetupProblems() else SetupValidation.problems(input, rules)

    /** Whether the form as it is now has been checked against [rules] and has nothing wrong with it. */
    fun ready(rules: SetupRules?): Boolean = rules != null && problems(rules).none

    fun edit(change: (SetupInput) -> SetupInput) {
        input = change(input)
    }

    /** Fills the form with [filled], which is what a project was last run with or what its robot file says. */
    fun load(filled: SetupInput, rules: SetupRules?) {
        input = if (rules == null) filled else withPose(rules, withDefaults(rules, filled))
    }

    /**
     * Takes in the rules when they arrive or change: a blank field becomes the default one, and the start pose is
     * matched to the numbers or, in a form nobody touched, to the first pose.
     */
    fun adopt(rules: SetupRules) {
        val next = withPose(rules, withDefaults(rules, input))
        if (next != input) input = next
    }

    fun chooseField(rules: SetupRules, id: String) {
        input = withPose(rules, input.copy(field = id))
    }

    fun chooseAlliance(rules: SetupRules, alliance: Alliance) {
        input = withPose(rules, input.copy(alliance = alliance))
    }

    /** Chooses a start pose of the field by name, or numbers of the person's own when [name] is null. */
    fun choosePose(rules: SetupRules, name: String?) {
        val current = SetupValidation.poseOf(input, rules)
        input = when {
            name != null -> input.copy(startPose = name)
            current == null -> input.copy(startPose = null, startTouched = true)
            // Custom numbers begin where the pose was, so that the person can move it a little.
            else -> input.copy(
                startPose = null,
                startX = SetupInput.formatNumber(current.x()),
                startY = SetupInput.formatNumber(current.y()),
                headingDegrees = SetupInput.formatNumber(Math.toDegrees(current.headingRadians())),
                startTouched = true,
            )
        }
    }

    /** Starts from numbers of the person's own: the field's poses are left, and the form holds these. */
    fun useNumbers(x: Double, y: Double, headingDegrees: Double) {
        input = input.copy(
            startPose = null,
            startX = SetupInput.formatNumber(x),
            startY = SetupInput.formatNumber(y),
            headingDegrees = SetupInput.formatNumber(headingDegrees),
            startTouched = true,
        )
    }

    /**
     * The run the form describes for the open project's [program] and [mapping], or null while the form is not [ready].
     * A random seed is [keepSeed] when that is given (a restart of the run on screen keeps its seed, so that only what
     * the person changed differs) and a new one otherwise.
     */
    fun request(rules: SetupRules?, program: String, mapping: RobotMapping, keepSeed: Long? = null): SessionRequest? =
        if (rules != null && ready(rules)) SetupValidation.request(input, rules, { keepSeed ?: newSeed() }, program, mapping) else null

    private fun withDefaults(rules: SetupRules, form: SetupInput): SetupInput =
        if (form.field.isBlank()) form.copy(field = rules.defaultField()?.id().orEmpty()) else form

    /**
     * Settles the start pose of [form] against what [rules] offer for its field and alliance. A pose that is on offer
     * stays; one that is not (it belonged to the other alliance or field) gives way to the first on offer; a form
     * nobody has touched takes the first on offer; numbers that are a pose's own become that pose and any others stay
     * as the person's own. A field with no poses leaves only numbers.
     */
    private fun withPose(rules: SetupRules, form: SetupInput): SetupInput {
        val poses = rules.startPoses(form.field, form.alliance)
        if (poses.isEmpty()) return form.copy(startPose = null)
        if (form.startPose != null) {
            return if (poses.any { it.name() == form.startPose }) form else form.copy(startPose = poses.first().name())
        }
        if (form.untouchedPose) return form.copy(startPose = poses.first().name())
        val x = SetupValidation.number(form.startX)
        val y = SetupValidation.number(form.startY)
        val heading = SetupValidation.number(form.headingDegrees)
        val matched = if (x == null || y == null || heading == null) null else StartPoses.matching(poses, x, y, Math.toRadians(heading))
        return form.copy(startPose = matched?.name())
    }
}
