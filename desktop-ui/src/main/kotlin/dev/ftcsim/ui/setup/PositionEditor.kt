package dev.ftcsim.ui.setup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ftcsim.ui.host.StartPosition
import java.util.Locale

/** One start position as the person is typing it: text that has not been checked yet. */
data class PositionRow(val name: String, val x: String, val y: String, val heading: String) {
    companion object {
        fun of(x: Double, y: Double, headingDegrees: Double, name: String): PositionRow =
            PositionRow(name, SetupInput.formatNumber(x), SetupInput.formatNumber(y), SetupInput.formatNumber(headingDegrees))
    }
}

/** Messages for the parts of a row that cannot be used as they are; a null part is fine. */
data class RowProblems(val name: String? = null, val x: String? = null, val y: String? = null, val heading: String? = null) {
    val none: Boolean get() = name == null && x == null && y == null && heading == null
}

/**
 * The start positions of one field being edited: adding, changing and removing them before they are saved in the
 * project's `ftcsim.json`. The positions of other fields are carried along untouched.
 *
 * @param field the field whose positions are edited
 * @param saved every start position the project has, of any field
 */
class PositionEditor(val field: String, saved: List<StartPosition>) {
    private val others = saved.filter { it.field != field }

    var rows by mutableStateOf(saved.filter { it.field == field }.map { PositionRow.of(it.x, it.y, it.headingDegrees, it.name) })
        private set

    /** Adds a position at [x], [y] and [headingDegrees] under the first name that is free: Position 1, Position 2, ... */
    fun add(x: Double, y: Double, headingDegrees: Double) {
        val names = rows.map { it.name.trim().lowercase(Locale.ROOT) }.toSet()
        val name = generateSequence(1) { it + 1 }.map { "Position $it" }.first { it.lowercase(Locale.ROOT) !in names }
        rows = rows + PositionRow.of(x, y, headingDegrees, name)
    }

    fun change(index: Int, row: PositionRow) {
        rows = rows.toMutableList().also { it[index] = row }
    }

    fun remove(index: Int) {
        rows = rows.filterIndexed { at, _ -> at != index }
    }

    fun problems(index: Int): RowProblems {
        val row = rows[index]
        val name = row.name.trim()
        return RowProblems(
            name = when {
                name.isEmpty() -> "Give it a name."
                rows.withIndex().any { (at, other) -> at != index && other.name.trim().equals(name, ignoreCase = true) } -> "Another position has this name."
                else -> null
            },
            x = SetupValidation.metresProblem(row.x),
            y = SetupValidation.metresProblem(row.y),
            heading = if (SetupValidation.number(row.heading) != null) null else "Enter the heading in degrees, such as 90.",
        )
    }

    /** Whether every row can be saved. */
    val valid: Boolean get() = rows.indices.all { problems(it).none }

    /** What the project should hold now: the positions of the other fields, then these, as typed; null while a row is not [valid]. */
    fun result(): List<StartPosition>? = if (valid) others + rows.indices.map { position(it)!! } else null

    /** The position at [index] as it would be saved; null while its row is not valid. */
    fun position(index: Int): StartPosition? {
        if (!problems(index).none) return null
        val row = rows[index]
        return StartPosition(row.name.trim(), field, SetupValidation.number(row.x)!!, SetupValidation.number(row.y)!!, SetupValidation.number(row.heading)!!)
    }
}
