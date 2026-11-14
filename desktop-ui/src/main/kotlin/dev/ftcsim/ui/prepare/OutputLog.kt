package dev.ftcsim.ui.prepare

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The lines a preparation printed, newest last. A project's Gradle build can print thousands of lines, far more than
 * anyone reads, so only the newest [capacity] are kept; the complete output is in the build log file. Lines can be
 * added from any thread; the screen reads copies with [view], and [version] tells it when there is a new one.
 */
class OutputLog(private val capacity: Int = DEFAULT_CAPACITY) {
    /** A copy of the log: [first] is the position of its first line among all the lines ever added, which only grows. */
    class View(val first: Long, val lines: List<String>) {
        /** How many lines there have been in all, kept or not. */
        val total: Long get() = first + lines.size
    }

    private val lines = ArrayDeque<String>()
    private var first = 0L
    private val mutableVersion = MutableStateFlow(0L)

    /** Changes whenever the log does. */
    val version: StateFlow<Long> = mutableVersion.asStateFlow()

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    fun add(line: String) {
        synchronized(this) {
            lines.addLast(line)
            if (lines.size > capacity) {
                lines.removeFirst()
                first++
            }
        }
        mutableVersion.update { it + 1 }
    }

    fun clear() {
        synchronized(this) {
            lines.clear()
            first = 0L
        }
        mutableVersion.update { it + 1 }
    }

    fun view(): View = synchronized(this) { View(first, lines.toList()) }

    companion object {
        const val DEFAULT_CAPACITY = 2000
    }
}
