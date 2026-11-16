package dev.ftcsim.ui.setup

import dev.ftcsim.ui.host.StartPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PositionEditorTest {
    private val saved = listOf(
        StartPosition("Near", "decode", -1.2, 0.5, 135.0),
        StartPosition("Elsewhere", "biobuzz", 2.0, 2.0, 0.0),
        StartPosition("Far", "decode", 1.0, -3.0, 90.0),
    )

    @Test
    fun itEditsTheFieldsPositionsAndCarriesTheOtherFieldsAlong() {
        val editor = PositionEditor("decode", saved)

        assertEquals(listOf("Near", "Far"), editor.rows.map { it.name })
        editor.change(0, editor.rows[0].copy(x = "-1.5"))
        editor.remove(1)

        assertEquals(
            listOf(StartPosition("Elsewhere", "biobuzz", 2.0, 2.0, 0.0), StartPosition("Near", "decode", -1.5, 0.5, 135.0)),
            editor.result(),
        )
    }

    @Test
    fun anAddedPositionTakesTheFirstFreeNameAndStartsWhereItWasAskedFor() {
        val editor = PositionEditor("decode", listOf(StartPosition("position 1", "decode", 0.0, 0.0, 0.0)))

        editor.add(0.25, -1.0, 45.0)

        assertEquals(PositionRow("Position 2", "0.25", "-1", "45"), editor.rows[1])
    }

    @Test
    fun aRowNeedsAUniqueNameAndNumbersInRange() {
        val editor = PositionEditor("decode", saved)

        editor.change(1, editor.rows[1].copy(name = " near ", x = "11", heading = "up"))
        val problems = editor.problems(1)

        assertEquals("Another position has this name.", problems.name)
        assertEquals("Enter metres between -10 and 10, such as 0.5.", problems.x)
        assertNull(problems.y)
        assertEquals("Enter the heading in degrees, such as 90.", problems.heading)
        assertFalse(editor.valid)
        assertNull(editor.result())
        assertNull(editor.position(1))
        assertTrue(editor.problems(0).name != null, "the other row with the name has the problem too")

        editor.change(1, editor.rows[1].copy(name = "", x = "1", heading = "90"))
        assertEquals("Give it a name.", editor.problems(1).name)
    }
}
