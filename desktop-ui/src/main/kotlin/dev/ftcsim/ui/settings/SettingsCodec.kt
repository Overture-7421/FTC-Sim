package dev.ftcsim.ui.settings

import dev.ftcsim.contracts.field.Alliance
import dev.ftcsim.ui.input.GamepadConfig
import dev.ftcsim.ui.input.KeyboardMap
import dev.ftcsim.ui.input.PadAssignment
import dev.ftcsim.ui.input.PadControl
import dev.ftcsim.ui.setup.SetupInput
import java.util.Properties

/**
 * [UserSettings] as the key and value pairs of a properties file. Reading is forgiving: every key stands alone, so
 * a missing, mistyped or hand-edited one gives that setting's default and leaves the others as they were.
 */
internal object SettingsCodec {
    const val FORMAT = 1
    const val FORMAT_KEY = "format"

    private val PROJECT_FOLDER_KEY = Regex("""project\.(\d+)\.folder""")

    fun encode(settings: UserSettings): Properties {
        val out = Properties()
        out.setProperty(FORMAT_KEY, FORMAT.toString())
        with(settings.display) {
            out.setProperty("display.renderScalePercent", renderScalePercent.toString())
            out.setProperty("display.frameCapHz", frameCapHz.toString())
            out.setProperty("display.performanceOverlay", performanceOverlay.toString())
            }
        for (gamepad in 1..2) {
            val pad = settings.gamepads.pad(gamepad)
            val prefix = "gamepad.$gamepad."
            out.setProperty(prefix + "assignment", pad.assignment().kind().name)
            out.setProperty(prefix + "guid", pad.assignment().guid())
            out.setProperty(prefix + "name", pad.assignment().name())
            out.setProperty(prefix + "stickDeadzone", pad.stickDeadzone().toString())
            out.setProperty(prefix + "triggerDeadzone", pad.triggerDeadzone().toString())
        }
        for ((control, key) in settings.gamepads.keyboard().bindings()) {
            out.setProperty("keyboard.${control.name}", key.toString())
        }
        settings.projects.forEachIndexed { index, project ->
            val prefix = "project.$index."
            out.setProperty(prefix + "folder", project.folder)
            out.setProperty(prefix + "name", project.name)
            project.scanOpMode?.let { out.setProperty(prefix + "scanOpMode", it) }
            project.opMode?.let { out.setProperty(prefix + "opMode", it) }
            if (project.robotSkipped) out.setProperty(prefix + "robotSkipped", "true")
            project.run?.let { run ->
                out.setProperty(prefix + "run.field", run.field)
                out.setProperty(prefix + "run.alliance", run.alliance.name)
                run.startPose?.let { out.setProperty(prefix + "run.startPose", it) }
                out.setProperty(prefix + "run.startX", run.startX)
                out.setProperty(prefix + "run.startY", run.startY)
                out.setProperty(prefix + "run.heading", run.headingDegrees)
                out.setProperty(prefix + "run.touched", run.startTouched.toString())
            }
        }
        return out
    }

    fun decode(properties: Properties): UserSettings = UserSettings(
        display = decodeDisplay(properties),
        gamepads = GamepadConfig(decodePad(properties, 1), decodePad(properties, 2), decodeKeyboard(properties)),
        projects = decodeProjects(properties),
    )

    private fun decodeDisplay(p: Properties): DisplaySettings {
        val defaults = DisplaySettings()
        return DisplaySettings(
            renderScalePercent = p.int("display.renderScalePercent") ?: defaults.renderScalePercent,
            frameCapHz = p.int("display.frameCapHz") ?: defaults.frameCapHz,
            performanceOverlay = p.text("display.performanceOverlay")?.toBooleanStrictOrNull() ?: defaults.performanceOverlay,
        ).normalized()
    }

    private fun decodePad(p: Properties, gamepad: Int): GamepadConfig.Pad {
        val prefix = "gamepad.$gamepad."
        val kind = p.text(prefix + "assignment")
            ?.let { text -> PadAssignment.Kind.entries.firstOrNull { it.name.equals(text, ignoreCase = true) } }
            ?: PadAssignment.Kind.AUTOMATIC
        val guid = p.getProperty(prefix + "guid").orEmpty()
        val assignment = when {
            kind == PadAssignment.Kind.CONTROLLER && guid.isNotBlank() -> PadAssignment.controller(guid, p.getProperty(prefix + "name"))
            kind == PadAssignment.Kind.CONTROLLER -> PadAssignment.AUTOMATIC
            else -> PadAssignment(kind, "", "")
        }
        return GamepadConfig.Pad(
            assignment,
            p.float(prefix + "stickDeadzone") ?: GamepadConfig.Pad.DEFAULT_STICK_DEADZONE,
            p.float(prefix + "triggerDeadzone") ?: GamepadConfig.Pad.DEFAULT_TRIGGER_DEADZONE,
        )
    }

    private fun decodeKeyboard(p: Properties): KeyboardMap {
        var map = KeyboardMap.DEFAULT
        for (control in PadControl.entries) {
            val key = p.int("keyboard.${control.name}")
            if (key != null && key >= 0) {
                map = map.with(control, key)
            }
        }
        return map
    }

    private fun decodeProjects(p: Properties): List<RecentProject> =
        p.stringPropertyNames()
            .mapNotNull { PROJECT_FOLDER_KEY.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
            .distinct()
            .sorted()
            .mapNotNull { decodeProject(p, "project.$it.") }
            .take(UserSettings.MAX_PROJECTS)

    private fun decodeProject(p: Properties, prefix: String): RecentProject? {
        val folder = p.text(prefix + "folder") ?: return null
        val name = p.text(prefix + "name") ?: folder
        return RecentProject(
            folder, name, decodeRun(p, prefix + "run."), p.text(prefix + "scanOpMode"), p.text(prefix + "robotSkipped") == "true", p.text(prefix + "opMode"),
        )
    }

    /**
     * What a project was last run with; a run with anything unreadable in it is not remembered, and the project starts
     * from its defaults. Every run draws a new seed, so an older file's `run.seed` and `run.randomSeed` are ignored.
     */
    private fun decodeRun(p: Properties, prefix: String): SetupInput? {
        val field = p.text(prefix + "field") ?: return null
        val alliance = p.text(prefix + "alliance")?.let { text -> Alliance.entries.firstOrNull { it.name.equals(text, ignoreCase = true) } }
            ?: return null
        return SetupInput(
            field = field,
            alliance = alliance,
            startPose = p.text(prefix + "startPose"),
            startX = p.text(prefix + "startX") ?: "0",
            startY = p.text(prefix + "startY") ?: "0",
            headingDegrees = p.text(prefix + "heading") ?: "0",
            startTouched = p.text(prefix + "touched")?.toBooleanStrictOrNull() ?: false,
        )
    }

    private fun Properties.text(key: String): String? = getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }

    private fun Properties.int(key: String): Int? = text(key)?.toIntOrNull()

    private fun Properties.float(key: String): Float? = text(key)?.toFloatOrNull()?.takeIf { it.isFinite() }
}
