package dev.ftcsim.ui.setup

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ftcsim.contracts.field.Alliance
import dev.ftcsim.contracts.session.SessionRequest

/**
 * The Setup form between visits to the screen. What the person typed stays when they look at another screen, and the
 * file system is only asked about the paths off the UI thread: [check] does the asking and [publish] hands the answer
 * back. A run can be started only for the exact input and the exact [SetupRules] the last answer was about, and the
 * rules are the simulator's catalogue, which can arrive after the form is shown, so every question takes them as an
 * argument and the model keeps none.
 */
@Stable
class SetupModel(
    private val checks: PathChecks,
    private val newSeed: () -> Long,
    initial: SetupInput,
) {
    /** What the problems were found for. */
    data class Checked(val input: SetupInput, val rules: SetupRules, val problems: SetupProblems)

    var input by mutableStateOf(initial)
        private set

    private var checked by mutableStateOf<Checked?>(null)

    /** The problems of the input as it was last checked against [rules], or none before the first check. */
    fun problems(rules: SetupRules?): SetupProblems = checked?.takeIf { it.rules == rules }?.problems ?: SetupProblems()

    /** Whether the form as it is now has been checked against [rules] and has nothing wrong with it. */
    fun ready(rules: SetupRules?): Boolean =
        rules != null && checked?.let { it.rules == rules && it.input == input && it.problems.none } == true

    fun edit(change: (SetupInput) -> SetupInput) {
        input = change(input)
    }

    /** Fills the form in the way [request] was asked for; with [rules] the numbers of a start pose become that pose. */
    fun use(request: SessionRequest, rules: SetupRules?) {
        val filled = SetupInput.from(request)
        input = if (rules == null) filled else withPose(rules, filled)
    }

    /**
     * Takes in the rules when they arrive or change: a blank field becomes the default one, a blank profile the first
     * bundled one, and the start pose is matched to the numbers or, in a form nobody touched, to the first pose.
     */
    fun adopt(rules: SetupRules) {
        var next = input
        if (next.field.isBlank()) next = next.copy(field = rules.defaultField()?.id().orEmpty())
        if (next.profile.isBlank() && rules.bundledProfiles.isNotEmpty()) next = next.copy(profile = rules.bundledProfiles.first())
        next = withPose(rules, next)
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

    /** Looks at [input] against [rules], including the file system; call from a background thread. */
    fun check(input: SetupInput, rules: SetupRules): Checked = Checked(input, rules, SetupValidation.problems(input, rules, checks))

    fun publish(result: Checked) {
        checked = result
    }

    /** The run the form describes, with a new seed if the seed is left random, or null while the form is not [ready]. */
    fun request(rules: SetupRules?): SessionRequest? = if (rules != null && ready(rules)) SetupValidation.request(input, rules, newSeed) else null

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
