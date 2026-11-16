package dev.ftcsim.ui.project

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.ftcsim.contracts.field.Alliance
import dev.ftcsim.contracts.session.Catalogue
import dev.ftcsim.contracts.session.FieldChoice
import dev.ftcsim.ui.common.FtcSimTheme
import dev.ftcsim.ui.settings.RecentProject
import dev.ftcsim.ui.setup.SetupInput
import dev.ftcsim.ui.setup.SetupRules
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalTestApi::class)
class ProjectListTest {
    private val first = RecentProject("C:\\Teams\\Tina\\FTC-Hornet", "FTC-Hornet", SetupInput(field = "decode", alliance = Alliance.RED))
    private val second = RecentProject("C:\\Teams\\Tina\\Backup", "Backup")
    private val rules = SetupRules.of(
        Catalogue(listOf(FieldChoice("decode", "DECODE", emptyList(), emptyList()), FieldChoice("biobuzz", "BIOBUZZ", emptyList(), emptyList()))),
    )

    private class Taps {
        val opened = ArrayList<RecentProject>()
        val removed = ArrayList<RecentProject>()
        var projects = 0
        var dismissed = 0
    }

    private fun ComposeUiTest.show(
        recents: List<RecentProject>,
        taps: Taps = Taps(),
        rules: SetupRules? = this@ProjectListTest.rules,
        notice: String? = null,
    ) = setContent {
        FtcSimTheme(darkTheme = true) {
            ProjectList(
                recents, rules, notice,
                onOpen = { taps.opened += it }, onRemove = { taps.removed += it },
                onOpenProject = { taps.projects++ }, onDismissNotice = { taps.dismissed++ },
                modifier = Modifier,
            )
        }
    }

    @Test
    fun withoutRecentProjectsTheScreenOffersOnlyToOpenOne() = runComposeUiTest {
        val taps = Taps()
        show(emptyList(), taps)

        onNodeWithText("No projects yet", substring = true).assertDoesNotExist()
        onNodeWithText("Open project").performClick()

        assertEquals(1, taps.projects)
    }

    @Test
    fun noWordOfPracticeRunsOrPracticePackagesIsLeft() = runComposeUiTest {
        show(listOf(first))

        onNodeWithText("practice run", substring = true, ignoreCase = true).assertDoesNotExist()
        onNodeWithText("practice package", substring = true, ignoreCase = true).assertDoesNotExist()
        onNodeWithText("New practice run").assertDoesNotExist()
    }

    @Test
    fun eachRecentProjectShowsItsNameFolderAndHowItWasLastRun() = runComposeUiTest {
        show(listOf(first, second))

        onNodeWithText("FTC-Hornet").assertExists()
        onNodeWithText("C:\\Teams\\Tina\\FTC-Hornet  |  DECODE, red").assertExists()
        onNodeWithText("Backup").assertExists()
        onNodeWithText("C:\\Teams\\Tina\\Backup").assertExists()
    }

    @Test
    fun beforeTheSimulatorHasListedItsFieldsARunIsNamedByTheFieldsId() = runComposeUiTest {
        show(listOf(first), rules = null)

        onNodeWithText("C:\\Teams\\Tina\\FTC-Hornet  |  decode, red").assertExists()
    }

    @Test
    fun aRecentProjectOpensWithOneClickAndCanBeForgotten() = runComposeUiTest {
        val taps = Taps()
        show(listOf(first, second), taps)

        onNodeWithContentDescription("Open Backup").performClick()
        onNodeWithContentDescription("Remove FTC-Hornet from the list").performClick()

        assertEquals(listOf(second), taps.opened)
        assertEquals(listOf(first), taps.removed)
    }

    @Test
    fun aFolderThatCannotBeUsedIsExplainedInPlaceAndCanBeDismissed() = runComposeUiTest {
        val taps = Taps()
        show(emptyList(), taps, notice = "No settings.gradle here")

        onNodeWithText("Cannot open that folder").assertExists()
        onNodeWithText("No settings.gradle here").assertExists()
        onNodeWithText("Dismiss").performClick()

        assertEquals(1, taps.dismissed)
    }

    @Test
    fun noNoticeIsShownWhenThereIsNothingToSay() = runComposeUiTest {
        show(emptyList())

        onNodeWithText("Cannot open that folder").assertDoesNotExist()
    }

    @Test
    fun tabLeadsFromTheOpenButtonToTheRecentProjectsInTheOrderTheyAreShown() = runComposeUiTest {
        show(listOf(first, second))
        onNodeWithText("Open project").requestFocus()
        onNodeWithText("Open project").assertIsFocused()

        onNodeWithText("Open project").performKeyInput { pressKey(Key.Tab) }
        onNodeWithContentDescription("Open FTC-Hornet").assertIsFocused()

        onNodeWithContentDescription("Open FTC-Hornet").performKeyInput { pressKey(Key.Tab) }
        onNodeWithContentDescription("Remove FTC-Hornet from the list").assertIsFocused()

        onNodeWithContentDescription("Remove FTC-Hornet from the list").performKeyInput { pressKey(Key.Tab) }
        onNodeWithContentDescription("Open Backup").assertIsFocused()
    }
}
