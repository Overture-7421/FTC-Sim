package dev.ftcsim.ui.practice

import dev.ftcsim.contracts.session.MatchState
import java.util.Locale

/** The speeds the speed menu offers, simulated seconds per wall-clock second. */
val SPEEDS: List<Double> = listOf(0.25, 0.5, 1.0, 2.0, 4.0)

/** A countdown in whole seconds as minutes and seconds: `2:00`, `0:07`. */
fun formatCountdown(seconds: Long): String = String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)

/** Where the match is, in words: the period's name while one is under way. */
fun matchStateLabel(state: MatchState): String = when (state) {
    MatchState.NONE -> "No match"
    MatchState.ARMED -> "Armed"
    MatchState.AUTONOMOUS -> "Autonomous"
    MatchState.TRANSITION -> "Transition"
    MatchState.TELEOP -> "TeleOp"
    MatchState.ENDED -> "Ended"
}

/** The status strip's words for the match, or null when there is none: `Match: Autonomous 0:24 left`. */
fun matchSummary(match: MatchDisplay): String? = when {
    match.state == MatchState.NONE -> null
    match.timed -> "Match: ${matchStateLabel(match.state)} ${formatCountdown(match.secondsLeft)} left"
    else -> "Match ${matchStateLabel(match.state).lowercase(Locale.ROOT)}"
}

/** The OpModes of a match in a line: `Autonomous: Red auto, TeleOp: Drive`. */
fun matchPlanText(plan: MatchPlan?): String = when {
    plan == null -> ""
    plan.autonomous == null -> "No autonomous period, TeleOp: ${plan.teleOp}"
    else -> "Autonomous: ${plan.autonomous}, TeleOp: ${plan.teleOp}"
}

/** Simulated time as minutes, seconds and milliseconds: `02:07.345`. */
fun formatSimTime(nanos: Long): String {
    val millis = nanos / 1_000_000L
    return String.format(Locale.ROOT, "%02d:%02d.%03d", millis / 60_000, millis / 1_000 % 60, millis % 1_000)
}

/** A speed as people write it: `1x`, `0.5x`, `0.25x`. */
fun formatSpeed(speed: Double): String =
    if (speed == Math.rint(speed)) String.format(Locale.ROOT, "%dx", speed.toLong())
    else String.format(Locale.ROOT, "%sx", String.format(Locale.ROOT, "%.2f", speed).trimEnd('0'))

/** The speed actually kept, to two places: `0.98x`. */
fun formatAchieved(speed: Double): String = String.format(Locale.ROOT, "%.2fx", speed)
