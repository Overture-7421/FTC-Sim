package dev.ftcsim.ui.setup

import dev.ftcsim.contracts.field.Alliance
import dev.ftcsim.contracts.field.StartPose
import dev.ftcsim.contracts.session.Catalogue
import dev.ftcsim.contracts.session.FieldChoice
import dev.ftcsim.contracts.session.SessionRequest
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale

/**
 * What the simulator behind the Setup screen accepts, and the one place that says what a valid form is: the fields
 * with their start poses, the bundled robot profiles, and whether a prepared program has to be chosen. It is made from
 * the catalogue the link reports, so the screen offers exactly what the simulator has.
 *
 * @property fields the fields to choose from, with the start poses each offers
 * @property bundledProfiles the robot profiles that ship with the simulator, by name
 * @property programRequired whether a prepared program has to be chosen; a stand-in simulator needs none
 */
data class SetupRules(val fields: List<FieldChoice>, val bundledProfiles: List<String>, val programRequired: Boolean) {
    fun field(id: String): FieldChoice? = fields.firstOrNull { it.id() == id }

    fun displayName(fieldId: String): String = field(fieldId)?.displayName() ?: fieldId

    /** The start poses [alliance] may use on [fieldId], in the field's order; none when the field offers none. */
    fun startPoses(fieldId: String, alliance: Alliance): List<StartPose> =
        StartPoses.forAlliance(field(fieldId)?.startPoses().orEmpty(), alliance)

    /** The field a fresh form starts with: the first that offers start poses, which is a game and not the bare floor. */
    fun defaultField(): FieldChoice? = fields.firstOrNull { it.startPoses().isNotEmpty() } ?: fields.firstOrNull()

    companion object {
        fun of(catalogue: Catalogue, programRequired: Boolean) =
            SetupRules(catalogue.fields(), catalogue.profiles(), programRequired)
    }
}

/**
 * What the Setup form holds, as the person left it: text and choices that have not been checked yet. The start pose is
 * either one the field offers, by name, or null for numbers of the person's own.
 */
data class SetupInput(
    val field: String = "",
    val alliance: Alliance = Alliance.RED,
    val randomSeed: Boolean = true,
    val seedText: String = "",
    val profile: String = "",
    val program: String = "",
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
            profile = request.profile(),
            program = request.program(),
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
    val profile: String? = null,
    val program: String? = null,
    val seed: String? = null,
    val startPose: String? = null,
    val startX: String? = null,
    val startY: String? = null,
    val heading: String? = null,
) {
    val none: Boolean
        get() = this.field == null && profile == null && program == null && seed == null && startPose == null &&
            startX == null && startY == null && heading == null
}

/** What the file system says about a path; behind an interface so that tests need no files. */
interface PathChecks {
    fun isFile(path: String): Boolean

    fun isDirectory(path: String): Boolean
}

/** The checks against the real file system. */
object FileSystemChecks : PathChecks {
    override fun isFile(path: String): Boolean = exists(path) { Files.isRegularFile(it) }

    override fun isDirectory(path: String): Boolean = exists(path) { Files.isDirectory(it) }

    private fun exists(path: String, test: (Path) -> Boolean): Boolean =
        try {
            test(Paths.get(path))
        } catch (invalid: InvalidPathException) {
            false
        }
}

/** The rules of the form, as pure functions so that they can be tested without a screen. */
object SetupValidation {
    const val MAX_START_METRES = 10.0

    fun problems(input: SetupInput, rules: SetupRules, checks: PathChecks): SetupProblems {
        val named = input.startPose != null
        return SetupProblems(
            field = fieldProblem(input.field, rules),
            profile = profileProblem(input.profile.trim(), rules, checks),
            program = programProblem(input.program.trim(), rules, checks),
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
     * The request the form describes, or null when it cannot be read. The paths are not looked at again: [problems] has
     * done that, and a request is only made for a form it found nothing wrong with.
     */
    fun request(input: SetupInput, rules: SetupRules, newSeed: () -> Long): SessionRequest? {
        val seed = if (input.randomSeed) newSeed() else input.seedText.trim().toLongOrNull()
        val pose = poseOf(input, rules)
        if (input.startPose != null && pose == null) return null
        val x = pose?.x() ?: number(input.startX)
        val y = pose?.y() ?: number(input.startY)
        val heading = pose?.headingRadians() ?: number(input.headingDegrees)?.let(Math::toRadians)
        if (seed == null || x == null || y == null || heading == null) return null
        return SessionRequest(input.profile.trim(), input.program.trim(), input.field, input.alliance, seed, x, y, heading, 1.0)
    }

    /** Whether the worker would take [reference] as the path of a profile file rather than as a bundled name. */
    fun isPath(reference: String): Boolean = reference.any { it == '/' || it == '\\' || it == ':' }

    /** A number as people write it, with a point or a comma; null when it is none or not finite. */
    fun number(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

    private fun fieldProblem(field: String, rules: SetupRules): String? = when {
        field.isBlank() -> "Choose a field."
        rules.field(field) == null -> "This simulator has no field called $field."
        else -> null
    }

    private fun profileProblem(profile: String, rules: SetupRules, checks: PathChecks): String? = when {
        profile.isEmpty() -> "Choose a robot profile."
        isPath(profile) -> if (checks.isFile(profile)) null else "There is no profile file at this path."
        profile in rules.bundledProfiles -> null
        else -> "There is no bundled profile called $profile. Bundled: ${rules.bundledProfiles.joinToString()}."
    }

    private fun programProblem(program: String, rules: SetupRules, checks: PathChecks): String? = when {
        program.isEmpty() -> if (rules.programRequired) "Choose the folder of the prepared program." else null
        checks.isDirectory(program) -> null
        checks.isFile(program) -> "A prepared program is a folder, not a file."
        else -> "There is no folder at this path."
    }

    private fun metresProblem(text: String): String? {
        val value = number(text)
        return if (value == null || Math.abs(value) > MAX_START_METRES) "Enter metres between -10 and 10, such as 0.5." else null
    }
}
