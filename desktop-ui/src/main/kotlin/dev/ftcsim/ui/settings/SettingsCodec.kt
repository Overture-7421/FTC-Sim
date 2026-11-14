package dev.ftcsim.ui.settings

import dev.ftcsim.contracts.field.Alliance
import dev.ftcsim.contracts.session.SessionRequest
import dev.ftcsim.ui.input.GamepadConfig
import dev.ftcsim.ui.input.KeyboardMap
import dev.ftcsim.ui.input.PadAssignment
import dev.ftcsim.ui.input.PadControl
import java.util.Properties

/**
 * [UserSettings] as the key and value pairs of a properties file. Reading is forgiving: every key stands alone, so
 * a missing, mistyped or hand-edited one gives that setting's default and leaves the others as they were.
 */
internal object SettingsCodec {
    const val FORMAT = 1
    const val FORMAT_KEY = "format"

    private val RECENT_FIELD_KEY = Regex("""recent\.(\d+)\.field""")
    private val TRUSTED_KEY = Regex("""trusted\.(\d+)""")

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
        settings.recents.forEachIndexed { index, request ->
            val prefix = "recent.$index."
            out.setProperty(prefix + "profile", request.profile())
            out.setProperty(prefix + "program", request.program())
            out.setProperty(prefix + "field", request.field())
            out.setProperty(prefix + "alliance", request.alliance().name)
            out.setProperty(prefix + "seed", request.seed().toString())
            out.setProperty(prefix + "startX", request.startX().toString())
            out.setProperty(prefix + "startY", request.startY().toString())
            out.setProperty(prefix + "startHeading", request.startHeading().toString())
        }
        settings.trustedProjects.forEachIndexed { index, key -> out.setProperty("trusted.$index", key) }
        return out
    }

    fun decode(properties: Properties): UserSettings = UserSettings(
        display = decodeDisplay(properties),
        gamepads = GamepadConfig(decodePad(properties, 1), decodePad(properties, 2), decodeKeyboard(properties)),
        recents = decodeRecents(properties),
        trustedProjects = decodeTrusted(properties),
    )

    private fun decodeTrusted(p: Properties): List<String> =
        p.stringPropertyNames()
            .mapNotNull { TRUSTED_KEY.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
            .distinct()
            .sorted()
            .mapNotNull { p.text("trusted.$it") }
            .distinct()
            .takeLast(UserSettings.MAX_TRUSTED)

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

    private fun decodeRecents(p: Properties): List<SessionRequest> =
        p.stringPropertyNames()
            .mapNotNull { RECENT_FIELD_KEY.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
            .distinct()
            .sorted()
            .mapNotNull { decodeRecent(p, "recent.$it.") }
            .take(UserSettings.MAX_RECENTS)

    private fun decodeRecent(p: Properties, prefix: String): SessionRequest? {
        val field = p.text(prefix + "field") ?: return null
        val profile = p.text(prefix + "profile") ?: return null
        val alliance = p.text(prefix + "alliance")?.let { text -> Alliance.entries.firstOrNull { it.name.equals(text, ignoreCase = true) } }
        return SessionRequest(
            profile,
            p.getProperty(prefix + "program").orEmpty(),
            field,
            alliance ?: return null,
            p.text(prefix + "seed")?.toLongOrNull() ?: return null,
            p.double(prefix + "startX") ?: return null,
            p.double(prefix + "startY") ?: return null,
            p.double(prefix + "startHeading") ?: return null,
            1.0,
        )
    }

    private fun Properties.text(key: String): String? = getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }

    private fun Properties.int(key: String): Int? = text(key)?.toIntOrNull()

    private fun Properties.float(key: String): Float? = text(key)?.toFloatOrNull()?.takeIf { it.isFinite() }

    private fun Properties.double(key: String): Double? = text(key)?.toDoubleOrNull()?.takeIf { it.isFinite() }
}
