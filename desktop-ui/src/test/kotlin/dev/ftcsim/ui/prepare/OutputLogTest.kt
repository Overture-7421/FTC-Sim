package dev.ftcsim.ui.prepare

import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OutputLogTest {
    @Test
    fun linesAreKeptInOrderAndNothingIsDroppedBelowTheCapacity() {
        val log = OutputLog(capacity = 5)

        for (number in 1..3) log.add("line $number")
        val view = log.view()

        assertEquals(listOf("line 1", "line 2", "line 3"), view.lines)
        assertEquals(0L, view.first)
        assertEquals(3L, view.total)
    }

    @Test
    fun onlyTheNewestLinesAreKeptAndTheCountOfTheOnesLetGoIsRemembered() {
        val log = OutputLog(capacity = 3)

        for (number in 1..10) log.add("line $number")
        val view = log.view()

        assertEquals(listOf("line 8", "line 9", "line 10"), view.lines)
        assertEquals(7L, view.first, "seven lines were dropped before the first one that is kept")
        assertEquals(10L, view.total)
    }

    @Test
    fun theDefaultCapacityIsTheNewestTwoThousandLines() {
        val log = OutputLog()

        for (number in 1..2_500) log.add("line $number")
        val view = log.view()

        assertEquals(2_000, view.lines.size)
        assertEquals("line 501", view.lines.first())
        assertEquals("line 2500", view.lines.last())
        assertEquals(500L, view.first)
    }

    @Test
    fun theVersionChangesWithEveryChangeSoThatAScreenKnowsToLookAgain() {
        val log = OutputLog(capacity = 2)
        val before = log.version.value

        log.add("a")
        val afterAdd = log.version.value
        log.clear()

        assertNotEquals(before, afterAdd)
        assertNotEquals(afterAdd, log.version.value)
    }

    @Test
    fun clearingStartsTheCountOverSoAnEarlierPreparationLeavesNothingBehind() {
        val log = OutputLog(capacity = 2)
        for (number in 1..5) log.add("line $number")

        log.clear()
        log.add("fresh")

        assertEquals(listOf("fresh"), log.view().lines)
        assertEquals(0L, log.view().first)
    }

    @Test
    fun aViewIsACopyThatLaterLinesDoNotChange() {
        val log = OutputLog(capacity = 4)
        log.add("a")
        val view = log.view()

        log.add("b")

        assertEquals(listOf("a"), view.lines)
    }

    @Test
    fun linesAddedFromSeveralThreadsAreAllCounted() {
        val log = OutputLog(capacity = 100)
        val start = CountDownLatch(1)
        val workers = (1..4).map { id ->
            thread(start = true) {
                start.await()
                repeat(1_000) { log.add("worker $id line $it") }
            }
        }

        start.countDown()
        workers.forEach { it.join() }

        assertEquals(4_000L, log.view().total)
        assertEquals(100, log.view().lines.size)
    }

    @Test
    fun aLogNeedsRoomForAtLeastOneLine() {
        assertThrows(IllegalArgumentException::class.java) { OutputLog(capacity = 0) }
        assertTrue(OutputLog(capacity = 1).view().lines.isEmpty())
    }
}
