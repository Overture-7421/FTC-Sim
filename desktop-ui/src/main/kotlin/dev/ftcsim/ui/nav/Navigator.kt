package dev.ftcsim.ui.nav

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The places the navigation rail leads to, in the order it lists them. */
enum class Destination(val label: String) {
    PROJECTS("Projects"),
    ROBOT("Robot"),
    CONTROLS("Controls"),
    PRACTICE("Simulator"),
    SETTINGS("Settings"),
}

/** Where the person is and where Back leads: the places visited before, most recent last, each at most once. */
data class NavState(val current: Destination, val back: List<Destination> = emptyList()) {
    val canGoBack: Boolean get() = back.isNotEmpty()
}

/**
 * Which destination is shown and the history behind it. Going to a place that is already in the history moves it to
 * the front instead of repeating it, so Back always leads to somewhere different from where the person is, and the
 * history can never hold more places than there are destinations. Holds no screen state: every screen keeps its own.
 */
class Navigator(start: Destination = Destination.PROJECTS) {
    private val mutable = MutableStateFlow(NavState(start))

    val state: StateFlow<NavState> = mutable.asStateFlow()

    fun go(destination: Destination) {
        mutable.update { now ->
            if (now.current == destination) now else NavState(destination, now.back.filter { it != destination } + now.current)
        }
    }

    /** Returns to the place visited before; false when there is none. */
    fun back(): Boolean {
        var moved = false
        mutable.update { now ->
            if (now.back.isEmpty()) {
                moved = false
                now
            } else {
                moved = true
                NavState(now.back.last(), now.back.dropLast(1))
            }
        }
        return moved
    }
}
