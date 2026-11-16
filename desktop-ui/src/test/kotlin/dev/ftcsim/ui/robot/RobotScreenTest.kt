package dev.ftcsim.ui.robot

import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.ftcsim.ui.common.FtcSimTheme
import dev.ftcsim.ui.common.runTallComposeUiTest as runComposeUiTest
import dev.ftcsim.ui.prepare.Project
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The three states of the Robot screen, with only synthetic events. */
@OptIn(ExperimentalTestApi::class)
class RobotScreenTest {
    private val project = Project(Path.of("C:\\Teams\\TeamProject"), "TeamProject", "")

    @Test
    fun withoutAProjectTheScreenOffersToOpenOne() = runComposeUiTest {
        var opened = 0
        setContent { FtcSimTheme(darkTheme = true) { RobotScreen(null, false, { opened++ }) { Text("mapping") } } }

        onNodeWithText("No project open").assertExists()
        onNodeWithText("mapping").assertDoesNotExist()
        onNodeWithText("Open a project").performClick()

        assertEquals(1, opened)
    }

    @Test
    fun whileABuildRunsNeitherThePageNorTheOfferIsShown() = runComposeUiTest {
        setContent { FtcSimTheme(darkTheme = true) { RobotScreen(null, true, {}) { Text("mapping") } } }

        onNodeWithText("No project open").assertDoesNotExist()
        onNodeWithText("mapping").assertDoesNotExist()
    }

    @Test
    fun anOpenProjectShowsTheMappingPage() = runComposeUiTest {
        setContent { FtcSimTheme(darkTheme = true) { RobotScreen(project, false, {}) { Text("mapping of ${it.name}") } } }

        onNodeWithText("mapping of TeamProject").assertExists()
    }
}
