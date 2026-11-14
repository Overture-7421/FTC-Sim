package dev.ftcsim.ui.settings

import dev.ftcsim.contracts.field.Alliance
import dev.ftcsim.contracts.session.SessionRequest
import dev.ftcsim.ui.input.GamepadConfig
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Locale

/**
 * Everything the application remembers between runs: how the viewport is drawn, where each gamepad listens and what
 * the keys of the keyboard gamepad are, the runs the person started most recently, and the project folders the person
 * agreed to have prepared, which run the project's own build scripts and so are asked about once each.
 */
data class UserSettings(
    val display: DisplaySettings = DisplaySettings(),
    val gamepads: GamepadConfig = GamepadConfig.DEFAULT,
    val recents: List<SessionRequest> = emptyList(),
    val trustedProjects: List<String> = emptyList(),
) {
    /** These settings with [request] at the head of the recent runs, once; a run is the same run at any speed. */
    fun withRecent(request: SessionRequest): UserSettings {
        val remembered = request.copy(speed = 1.0)
        return copy(recents = (listOf(remembered) + recents.filter { it != remembered }).take(MAX_RECENTS))
    }

    fun withoutRecent(request: SessionRequest): UserSettings = copy(recents = recents.filter { it != request })

    /** Whether the person has agreed to the build scripts of the project in [folder] running on this computer. */
    fun trusts(folder: Path): Boolean = projectKey(folder) in trustedProjects

    /** These settings once the person has agreed to the build scripts of the project in [folder] running. */
    fun withTrusted(folder: Path): UserSettings =
        copy(trustedProjects = (trustedProjects.filter { it != projectKey(folder) } + projectKey(folder)).takeLast(MAX_TRUSTED))

    companion object {
        const val MAX_RECENTS = 10
        const val MAX_TRUSTED = 100

        /** What names a project folder: its absolute path, cleaned up and lower case, as Windows treats paths alike. */
        fun projectKey(folder: Path): String =
            try {
                folder.toAbsolutePath().normalize().toString().lowercase(Locale.ROOT)
            } catch (invalid: InvalidPathException) {
                folder.toString().lowercase(Locale.ROOT)
            }
    }
}

/** A copy of a request with some of its parts replaced; the record itself has no copy method. */
fun SessionRequest.copy(
    profile: String = profile(),
    program: String = program(),
    field: String = field(),
    alliance: Alliance = alliance(),
    seed: Long = seed(),
    startX: Double = startX(),
    startY: Double = startY(),
    startHeading: Double = startHeading(),
    speed: Double = speed(),
): SessionRequest = SessionRequest(profile, program, field, alliance, seed, startX, startY, startHeading, speed)
