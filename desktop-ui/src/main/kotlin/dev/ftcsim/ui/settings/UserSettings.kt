package dev.ftcsim.ui.settings

import dev.ftcsim.contracts.field.Alliance
import dev.ftcsim.contracts.session.RobotMapping
import dev.ftcsim.contracts.session.SessionRequest
import dev.ftcsim.ui.input.GamepadConfig
import dev.ftcsim.ui.setup.SetupInput
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Locale

/**
 * A project the person opened, and how it was last run.
 *
 * @property folder the project's folder, as the person chose it
 * @property name what to call it: the folder's name
 * @property run what the project was last run with, or null if it was never started
 * @property scanOpMode the OpMode the person chose to find the project's hardware with, or null if none was chosen yet
 * @property robotSkipped whether the person left the robot page without describing the robot
 * @property opMode the OpMode the person chose last in the Simulator (or the autonomous of the last match they armed), which the project opens with, or null if none was chosen yet
 */
data class RecentProject(
    val folder: String,
    val name: String,
    val run: SetupInput? = null,
    val scanOpMode: String? = null,
    val robotSkipped: Boolean = false,
    val opMode: String? = null,
) {
    /** What names the folder, in any case and however it was written; see [UserSettings.projectKey]. */
    val key: String get() = UserSettings.projectKey(folder)

    /** Whether this is the project in [other], which Windows spells in any case. */
    fun isIn(other: Path): Boolean = key == UserSettings.projectKey(other)
}

/**
 * Everything the application remembers between runs: how the viewport is drawn, where each gamepad listens and what
 * the keys of the keyboard gamepad are, the projects the person opened most recently with how each was last run, and
 * What a project needs to run, its robot, is in the project, not here.
 */
data class UserSettings(
    val display: DisplaySettings = DisplaySettings(),
    val gamepads: GamepadConfig = GamepadConfig.DEFAULT,
    val projects: List<RecentProject> = emptyList(),
) {
    /** These settings with [project] at the head of the recent projects, once. */
    fun withProject(project: RecentProject): UserSettings =
        copy(projects = (listOf(project) + projects.filterNot { it.isSameAs(project) }).take(MAX_PROJECTS))

    /** These settings with how the project in [folder] was last run, if it is a recent project; it also becomes the first. */
    fun withRun(folder: Path, run: SetupInput): UserSettings {
        val known = projects.firstOrNull { it.isIn(folder) } ?: return this
        return withProject(known.copy(run = run))
    }

    fun withoutProject(project: RecentProject): UserSettings = copy(projects = projects.filterNot { it.isSameAs(project) })

    /** These settings with [opMode] as the OpMode that finds the hardware of the recent project in [folder]; unchanged if it is not one. */
    fun withScanOpMode(folder: Path, opMode: String): UserSettings =
        copy(projects = projects.map { if (it.isIn(folder)) it.copy(scanOpMode = opMode) else it })

    /** These settings with [opMode] as the one the recent project in [folder] opens with; unchanged if it is not a recent project. */
    fun withOpMode(folder: Path, opMode: String): UserSettings =
        copy(projects = projects.map { if (it.isIn(folder)) it.copy(opMode = opMode) else it })

    /** These settings with whether the person skipped describing the robot of the recent project in [folder]. */
    fun withRobotSkipped(folder: Path, skipped: Boolean): UserSettings =
        copy(projects = projects.map { if (it.isIn(folder)) it.copy(robotSkipped = skipped) else it })

    /** The recent project in [folder], if there is one. */
    fun project(folder: Path): RecentProject? = projects.firstOrNull { it.isIn(folder) }

    private fun RecentProject.isSameAs(other: RecentProject): Boolean = key == other.key

    companion object {
        const val MAX_PROJECTS = 10

        /** What names a project folder: its absolute path, cleaned up and lower case, as Windows treats paths alike. */
        fun projectKey(folder: Path): String =
            try {
                folder.toAbsolutePath().normalize().toString().lowercase(Locale.ROOT)
            } catch (invalid: InvalidPathException) {
                folder.toString().lowercase(Locale.ROOT)
            }

        /** [projectKey] of a folder written as text, which may not be a path at all. */
        fun projectKey(folder: String): String =
            try {
                projectKey(Path.of(folder))
            } catch (invalid: InvalidPathException) {
                folder.lowercase(Locale.ROOT)
            }
    }
}

/** A copy of a request with some of its parts replaced; the record itself has no copy method. */
fun SessionRequest.copy(
    mapping: RobotMapping = mapping(),
    program: String = program(),
    field: String = field(),
    alliance: Alliance = alliance(),
    seed: Long = seed(),
    startX: Double = startX(),
    startY: Double = startY(),
    startHeading: Double = startHeading(),
    speed: Double = speed(),
): SessionRequest = SessionRequest(mapping, program, field, alliance, seed, startX, startY, startHeading, speed)
