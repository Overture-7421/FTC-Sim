package dev.ftcsim.ui.host

import java.util.PriorityQueue

/**
 * A [Scheduler] whose clock is moved by the test: nothing runs until [advance] says time has passed, and then the tasks
 * that fall due run in order on the calling thread, including the ones they schedule themselves. Tests that use it
 * never sleep.
 */
class ManualScheduler : Scheduler {
    private class Entry(val due: Long, val order: Long, val task: Runnable)

    private val queue = PriorityQueue<Entry>(compareBy({ it.due }, { it.order }))
    private var now = 0L
    private var counter = 0L

    @Synchronized
    override fun schedule(delayMillis: Long, task: Runnable) {
        queue += Entry(now + delayMillis, counter++, task)
    }

    /** How many tasks are waiting. */
    @Synchronized
    fun pending(): Int = queue.size

    /** Moves the clock on by [millis] and runs everything that falls due. */
    fun advance(millis: Long) {
        val until = synchronized(this) { now + millis }
        while (true) {
            val next = synchronized(this) {
                queue.peek()?.takeIf { it.due <= until }?.also {
                    queue.poll()
                    now = it.due
                }
            } ?: break
            next.task.run()
        }
        synchronized(this) { now = until }
    }
}
