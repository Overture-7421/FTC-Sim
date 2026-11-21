package dev.ftcsim.ui

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Compose's `application` ends the process after the window closes unless told not to, and the application's `main` runs the
 * installer of an update after `App.run` returns: if the process ends first, "Update and restart" closes the program and
 * installs nothing. The window cannot be opened in a test, so the call is read from the source.
 */
class AppExitTest {
    @Test
    fun theWindowReturnsToItsCallerInsteadOfEndingTheProcess() {
        val source = Files.readString(Path.of("src/main/kotlin/dev/ftcsim/ui/App.kt"))
        assertTrue("application(exitProcessOnExit = false)" in source, "App.run must return so that Main can start the installer")
    }
}
