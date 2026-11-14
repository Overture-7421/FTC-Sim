package dev.ftcsim.ui.home

import androidx.compose.ui.input.key.Key
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
import dev.ftcsim.contracts.session.SessionRequest
import dev.ftcsim.ui.common.FtcSimTheme
import dev.ftcsim.ui.setup.SetupRules
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalTestApi::class)
class HomeScreenTest {
    private val first = SessionRequest("hornet-2026", "C:\\Teams\\Tina\\prepared", "decode", Alliance.RED, 42L, 0.0, 0.0, 0.0, 1.0)
    private val second = SessionRequest("C:\\robots\\mine.json", "", "biobuzz", Alliance.BLUE, 7L, 0.0, 0.0, 0.0, 1.0)
    private val rules = SetupRules.of(
        Catalogue(
            listOf(FieldChoice("decode", "DECODE", emptyList(), emptyList()), FieldChoice("biobuzz", "BIOBUZZ", emptyList(), emptyList())),
            listOf("hornet-2026"),
        ),
        programRequired = false,
    )

    private class Taps {
        val opened = ArrayList<SessionRequest>()
        val removed = ArrayList<SessionRequest>()
        var newRuns = 0
        var projects = 0
        var packages = 0
        var dismissed = 0
    }

    private fun ComposeUiTest.show(
        recents: List<SessionRequest>,
        taps: Taps = Taps(),
        rules: SetupRules? = this@HomeScreenTest.rules,
        notice: String? = null,
    ) = setContent {
        FtcSimTheme(darkTheme = true) {
            HomeScreen(
                recents, rules, notice,
                onOpen = { taps.opened += it }, onRemove = { taps.removed += it },
                onNewRun = { taps.newRuns++ }, onOpenProject = { taps.projects++ }, onOpenPackage = { taps.packages++ },
                onDismissNotice = { taps.dismissed++ },
            )
        }
    }

    @Test
    fun withoutRecentRunsTheScreenSaysSoAndStillOffersEveryWayToStart() = runComposeUiTest {
        val taps = Taps()
        show(emptyList(), taps)

        onNodeWithText("No runs yet", substring = true).assertExists()
        onNodeWithText("New practice run").performClick()
        onNodeWithText("Open project").performClick()
        onNodeWithText("Open practice package").performClick()

        assertEquals(1, taps.newRuns)
        assertEquals(1, taps.projects)
        assertEquals(1, taps.packages)
    }

    @Test
    fun eachRecentRunShowsFieldAllianceProfileSeedAndProgram() = runComposeUiTest {
        show(listOf(first, second))

        onNodeWithText("DECODE, red alliance").assertExists()
        onNodeWithText("Profile hornet-2026", substring = true).assertExists()
        onNodeWithText("Seed 42", substring = true).assertExists()
        onNodeWithText("Program prepared", substring = true).assertExists()
        onNodeWithText("BIOBUZZ, blue alliance").assertExists()
        onNodeWithText("Profile mine.json", substring = true).assertExists()
        onNodeWithText("Program none", substring = true).assertExists()
    }

    @Test
    fun beforeTheSimulatorHasListedItsFieldsARunIsNamedByTheFieldsId() = runComposeUiTest {
        show(listOf(first), rules = null)

        onNodeWithText("decode, red alliance").assertExists()
    }

    @Test
    fun aRecentRunOpensWithOneClickAndCanBeForgotten() = runComposeUiTest {
        val taps = Taps()
        show(listOf(first, second), taps)

        onNodeWithContentDescription("Open BIOBUZZ, blue alliance").performClick()
        onNodeWithContentDescription("Remove DECODE, red alliance from recent runs").performClick()

        assertEquals(listOf(second), taps.opened)
        assertEquals(listOf(first), taps.removed)
    }

    @Test
    fun aFolderThatCannotBeUsedIsExplainedInPlaceAndCanBeDismissed() = runComposeUiTest {
        val taps = Taps()
        show(emptyList(), taps, notice = "This folder is neither a team project nor a prepared program.")

        onNodeWithText("That folder cannot be used").assertExists()
        onNodeWithText("This folder is neither a team project nor a prepared program.").assertExists()
        onNodeWithText("Dismiss").performClick()

        assertEquals(1, taps.dismissed)
    }

    @Test
    fun noNoticeIsShownWhenThereIsNothingToSay() = runComposeUiTest {
        show(emptyList())

        onNodeWithText("That folder cannot be used").assertDoesNotExist()
    }

    @Test
    fun tabLeadsFromTheStartButtonsToTheRecentRunsInTheOrderTheyAreShown() = runComposeUiTest {
        show(listOf(first, second))
        onNodeWithText("New practice run").requestFocus()
        onNodeWithText("New practice run").assertIsFocused()

        onNodeWithText("New practice run").performKeyInput { pressKey(Key.Tab) }
        onNodeWithText("Open project").assertIsFocused()

        onNodeWithText("Open project").performKeyInput { pressKey(Key.Tab) }
        onNodeWithText("Open practice package").assertIsFocused()

        onNodeWithText("Open practice package").performKeyInput { pressKey(Key.Tab) }
        onNodeWithContentDescription("Open DECODE, red alliance").assertIsFocused()

        onNodeWithContentDescription("Open DECODE, red alliance").performKeyInput { pressKey(Key.Tab) }
        onNodeWithContentDescription("Remove DECODE, red alliance from recent runs").assertIsFocused()

        onNodeWithContentDescription("Remove DECODE, red alliance from recent runs").performKeyInput { pressKey(Key.Tab) }
        onNodeWithContentDescription("Open BIOBUZZ, blue alliance").assertIsFocused()
    }
}
