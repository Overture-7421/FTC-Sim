package dev.ftcsim.ui.common

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.ftcsim.ui.common.runTallComposeUiTest as runComposeUiTest
import dev.ftcsim.ui.host.UpdateService.Status
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The bar that offers a newer version: what it says and which choices it gives, with synthetic clicks. */
@OptIn(ExperimentalTestApi::class)
class UpdateBannerTest {
    private class Choices {
        var installs = 0
        var dismissals = 0
    }

    private fun ComposeUiTest.show(status: Status, choices: Choices) {
        setContent {
            FtcSimTheme(darkTheme = true) { UpdateBanner(status, { choices.installs++ }, { choices.dismissals++ }) }
        }
    }

    @Test
    fun aNewerVersionIsOfferedAndNothingHappensUntilThePersonChooses() = runComposeUiTest {
        val choices = Choices()
        show(Status.available("1.2.0"), choices)

        onNodeWithText("FTC-Sim 1.2.0 is available.").assertExists()
        assertEquals(0, choices.installs)

        onNodeWithText("Update and restart").performClick()
        assertEquals(1, choices.installs)
        onNodeWithText("Later").performClick()
        assertEquals(1, choices.dismissals)
    }

    @Test
    fun aFailedUpdateSaysWhyAndCanBeTriedAgainOrDismissed() = runComposeUiTest {
        val choices = Choices()
        show(Status.failed("1.2.0", "the download did not match its checksum"), choices)

        onNodeWithText("The update to FTC-Sim 1.2.0 did not work: the download did not match its checksum").assertExists()
        onNodeWithText("Try again").performClick()
        onNodeWithText("Dismiss").performClick()
        assertEquals(1, choices.installs)
        assertEquals(1, choices.dismissals)
    }

    @Test
    fun whileTheInstallerIsFetchedAndWhenItStartsThereAreNoChoicesToMake() = runComposeUiTest {
        val choices = Choices()
        show(Status.downloading("1.2.0", 42), choices)
        onNodeWithText("Downloading FTC-Sim 1.2.0: 42%").assertExists()
        onAllNodesWithText("Update and restart").assertCountEquals(0)
        onAllNodesWithText("Later").assertCountEquals(0)
    }

    @Test
    fun withNothingToSayThereIsNoBarAtAll() = runComposeUiTest {
        val choices = Choices()
        show(Status.NONE, choices)
        onAllNodesWithText("Later").assertCountEquals(0)
        assertEquals("", updateText(Status.NONE))
        assertEquals("Installing FTC-Sim 2.0.0. The window closes and FTC-Sim starts again.", updateText(Status.restarting("2.0.0")))
    }
}
