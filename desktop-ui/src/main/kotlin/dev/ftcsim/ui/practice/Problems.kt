package dev.ftcsim.ui.practice

import dev.ftcsim.contracts.session.SessionStatus

/** How much a problem matters; the panel orders by it and styles by it, and never by colour alone. */
enum class Severity(val label: String) {
    ERROR("Error"),
    WARNING("Warning"),
}

/** One thing a person should know about, and where it came from; [log] is a file with more, such as a build log. */
data class Problem(val severity: Severity, val source: String, val text: String, val log: java.nio.file.Path? = null)

/**
 * Collects what the port reports into one list. The port gives the simulator's problems as plain text, so they are all
 * treated as warnings: each weakens the run without stopping it. A failed session and an error of the robot program
 * are errors.
 */
object Problems {
    fun of(status: SessionStatus?, failure: String?): List<Problem> {
        val problems = ArrayList<Problem>()
        if (failure != null) {
            problems += Problem(Severity.ERROR, "Session", failure)
        }
        val telemetry = status?.telemetry()
        if (telemetry != null && telemetry.error().isNotEmpty()) {
            problems += Problem(Severity.ERROR, "Robot program", telemetry.error())
        }
        if (telemetry != null && telemetry.warnings().isNotEmpty()) {
            problems += Problem(Severity.WARNING, "Driver Station", telemetry.warnings())
        }
        status?.problems()?.forEach { problems += Problem(Severity.WARNING, "Simulator", it) }
        return problems
    }
}
