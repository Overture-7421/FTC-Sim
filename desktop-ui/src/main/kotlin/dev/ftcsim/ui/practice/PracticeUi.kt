package dev.ftcsim.ui.practice

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The tabs of the side panel. */
enum class Panel(val label: String) {
    TELEMETRY("Telemetry"),
    HARDWARE("Hardware"),
    SCORE("Score"),
    PROBLEMS("Problems"),
}

/**
 * What the Practice screen remembers about itself between recompositions: which menus and dialogs are open and which
 * tab is shown. Every menu and dialog is a modal for the keyboard, because a modal takes the keys the driver is
 * holding away from the robot.
 */
@Stable
class PracticeUi {
    var opModeMenuOpen by mutableStateOf(false)
    var speedMenuOpen by mutableStateOf(false)
    var resetDialogOpen by mutableStateOf(false)
    var matchDialogOpen by mutableStateOf(false)
    var panel by mutableStateOf(Panel.TELEMETRY)
    var viewportFocused by mutableStateOf(false)

    /** The error the snackbar has already announced, so that coming back to the screen does not announce it again. */
    var announcedError: Problem? = null

    val modalOpen: Boolean get() = opModeMenuOpen || speedMenuOpen || resetDialogOpen || matchDialogOpen

    /** Closes every menu and dialog, for when the screen is left. */
    fun closeModals() {
        opModeMenuOpen = false
        speedMenuOpen = false
        resetDialogOpen = false
        matchDialogOpen = false
    }
}
