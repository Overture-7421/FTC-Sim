package dev.ftcsim.ui.practice

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The tabs of the details panel. */
enum class Panel(val label: String) {
    CODE("Code"),
    SCORE("Score"),
    PROBLEMS("Problems"),
    SETUP("Setup"),
}

/**
 * What the Simulator screen remembers about itself between recompositions: which menus and dialogs are open, whether
 * the details panel is shown and which tab is in it. Every menu and dialog is a modal for the keyboard, because a modal
 * takes the keys the driver is holding away from the robot; the details panel is not, it floats over the viewport.
 */
@Stable
class PracticeUi {
    var opModeMenuOpen by mutableStateOf(false)
    var moreMenuOpen by mutableStateOf(false)
    var restartMenuOpen by mutableStateOf(false)
    var matchDialogOpen by mutableStateOf(false)
    var panel by mutableStateOf(Panel.CODE)
    var panelOpen by mutableStateOf(false)

    /** Opens the details panel on [tab]. */
    fun showPanel(tab: Panel) {
        panel = tab
        panelOpen = true
    }

    /** The error the snackbar has already announced, so that coming back to the screen does not announce it again. */
    var announcedError: Problem? = null

    val modalOpen: Boolean get() = opModeMenuOpen || moreMenuOpen || restartMenuOpen || matchDialogOpen

    /** Closes every menu and dialog, for when the screen is left. */
    fun closeModals() {
        opModeMenuOpen = false
        moreMenuOpen = false
        restartMenuOpen = false
        matchDialogOpen = false
    }
}
