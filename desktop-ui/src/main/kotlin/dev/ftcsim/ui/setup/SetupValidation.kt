package dev.ftcsim.ui.setup

import dev.ftcsim.contracts.field.Alliance
import dev.ftcsim.contracts.field.StartPose
import dev.ftcsim.contracts.session.Catalogue
import dev.ftcsim.contracts.session.FieldChoice
import dev.ftcsim.contracts.session.SessionRequest
import java.util.Locale

/**
 * What the simulator behind the settings panel accepts, and the one place that says what a valid form is: the fields
 * with their start poses. It is made from the catalogue the link reports, so the screen offers exactly what the
 * simulator has.
 *
 * @property fields the fields to choose from, with the start poses each offers
 */
data class SetupRules(val fields: List<FieldChoice>) {
    fun field(id: String): FieldChoice? = fields.firstOrNull { it.id() == id }

    fun displayName(fieldId: String): String = field(fieldId)?.displayName() ?: fieldId

    /** The start poses [alliance] may use on [fieldId], in the field's order; none when the field offers none. */
    fun startPoses(fieldId: String, alliance: Alliance): List<StartPose> =
        StartPoses.forAlliance(field(fieldId)?.startPoses().orEmpty(), alliance)

    /** The field a fresh form starts with: the first that offers start poses, which is a game and not the bare floor. */
    fun defaultField(): FieldChoice? = fields.firstOrNull { it.startPoses().isNotEmpty() } ?: fields.firstOrNull()

    companion object {
        fun of(catalogue: Catalogue) = SetupRules(catalogue.fields())
    }
}

/**
 * What the settings form holds, as the person left it: text and choices that have not been checked yet. The start pose is
 * either one the field offers, by name, or null for numbers of the person's own. It is also what the application
 * remembers for each project, so that opening the project again starts the way it was last run.
 */
data class SetupInput(
    val field: String = "",
    val alliance: Alliance = Alliance.RED,
    val randomSeed: Boolean = true,
    val seedText: String = "",
    val startPose: String? = null,
    val startX: String = "0",
    val startY: String = "0",
    val headingDegrees: String = "0",
    val startTouched: Boolean = false,
) {
    /**
     * Whether nobody has chosen where the robot starts: no pose of the field is named and the numbers are the form's own
     * to begin with, not typed and not taken from a run. Such a form takes the first start pose of the field it is given.
     */
    val untouchedPose: Boolean get() = startPose == null && !startTouched

    companion object {
        /** The form filled in the way [request] was asked for, with its seed fixed so that the run repeats. */
        fun from(request: SessionRequest): SetupInput = SetupInput(
            field = request.field(),
            alliance = request.alliance(),
            randomSeed = false,
            seedText = request.seed().toString(),
            startX = formatNumber(request.startX()),
            startY = formatNumber(request.startY()),
            headingDegrees = formatNumber(Math.toDegrees(request.startHeading())),
            startTouched = true,
        )

        fun formatNumber(value: Double): String =
            if (value == Math.rint(value)) value.toLong().toString()
            else String.format(Locale.ROOT, "%.3f", value).trimEnd('0').trimEnd('.')
    }
}

/** Messages for the parts of the form that cannot be used as they are; a null part is fine. */
data class SetupProblems(
    val field: String? = null,
    val seed: String? = null,
    val startPose: String? = null,
    val startX: String? = null,
    val startY: String? = null,
    val heading: String? = null,
) {
    val none: Boolean
        get() = this.field == null && seed == null && startPose == null && startX == null && startY == null && heading == null
}

/** The rules of the form, as pure functions so that they can be tested without a screen. */
object SetupValidation {
    const val MAX_START_METRES = 10.0

    fun problems(input: SetupInput, rules: SetupRules): SetupProblems {
        val named = input.startPose != null
        return SetupProblems(
            field = fieldProblem(input.field, rules),
            seed = if (input.randomSeed || input.seedText.trim().toLongOrNull() != null) null else "The seed is a whole number, such as 12345.",
            startPose = if (named && poseOf(input, rules) == null) "This field has no start pose called ${input.startPose}." else null,
            startX = if (named) null else metresProblem(input.startX),
            startY = if (named) null else metresProblem(input.startY),
            heading = if (named || number(input.headingDegrees) != null) null else "Enter the heading in degrees, such as 90.",
        )
    }

    /** The start pose of the field that the form names, or null when it names none or the field has no such pose. */
    fun poseOf(input: SetupInput, rules: SetupRules): StartPose? =
        input.startPose?.let { name -> rules.field(input.field)?.startPoses()?.firstOrNull { it.name() == name } }

    /**
     * The run the form describes for the program of the open project, or null when it cannot be read.
     *
     * @param program the folder of the program that was built from the project
     */
    fun request(input: SetupInput, rules: SetupRules, newSeed: () -> Long, program: String): SessionRequest? {
        val seed = if (input.randomSeed) newSeed() else input.seedText.trim().toLongOrNull()
        val pose = poseOf(input, rules)
        if (input.startPose != null && pose == null) return null
        val x = pose?.x() ?: number(input.startX)
        val y = pose?.y() ?: number(input.startY)
        val heading = pose?.headingRadians() ?: number(input.headingDegrees)?.let(Math::toRadians)
        if (seed == null || x == null || y == null || heading == null) return null
        return SessionRequest(program, input.field, input.alliance, seed, x, y, heading, 1.0)
    }

    /** A number as people write it, with a point or a comma; null when it is none or not finite. */
    fun number(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

    private fun fieldProblem(field: String, rules: SetupRules): String? = when {
        field.isBlank() -> "Choose a field."
        rules.field(field) == null -> "This simulator has no field called $field."
        else -> null
    }

    /** What is wrong with a distance typed in metres, or null: it is a number between -10 and 10. */
    fun metresProblem(text: String): String? {
        val value = number(text)
        return if (value == null || Math.abs(value) > MAX_START_METRES) "Enter metres between -10 and 10, such as 0.5." else null
    }
}
