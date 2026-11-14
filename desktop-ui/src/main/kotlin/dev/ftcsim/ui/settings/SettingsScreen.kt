package dev.ftcsim.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import dev.ftcsim.ui.common.ChoiceRow
import dev.ftcsim.ui.common.ScreenPage
import dev.ftcsim.ui.common.SectionCard
import dev.ftcsim.ui.common.SwitchRow
import dev.ftcsim.ui.pacing.Pacing
import dev.ftcsim.ui.viewport.ViewportEngine
import java.util.Locale

/**
 * The Settings screen: how the viewport is drawn (render resolution and frame rate cap), the performance overlay, and
 * where the settings are kept. Every change is applied at once and saved.
 *
 * @param settingsPath the file the settings are saved in, or null when they are not saved
 */
@Composable
fun SettingsScreen(settings: SettingsStore, engine: ViewportEngine, settingsPath: String?, modifier: Modifier = Modifier) {
    val state by settings.state.collectAsState()
    val refreshHz by engine.displayRefreshHz.collectAsState()
    val display = state.display
    fun change(update: (DisplaySettings) -> DisplaySettings) = settings.update { it.copy(display = update(it.display)) }

    ScreenPage("Settings", modifier) {
        SectionCard(
            "Viewport",
            description = "A smaller picture and a lower frame rate leave more of the computer to the simulation.",
        ) {
            Text("Render resolution", style = MaterialTheme.typography.titleSmall)
            ChoiceRow(
                DisplaySettings.RENDER_SCALES, display.renderScalePercent, { "$it %" },
                { value -> change { it.copy(renderScalePercent = value) } }, "Render resolution",
            )
            Text(
                if (display.renderScalePercent == 100) "The picture is drawn at the size of the viewport."
                else "The picture is drawn at ${display.renderScalePercent} % of the viewport's size and stretched to fit.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Frame rate cap", style = MaterialTheme.typography.titleSmall)
            ChoiceRow(
                DisplaySettings.FRAME_CAPS, display.frameCapHz, { "$it Hz" },
                { value -> change { it.copy(frameCapHz = value) } }, "Frame rate cap",
            )
            Text(
                frameRateText(refreshHz, display.frameCapHz),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SectionCard("Performance overlay") {
            SwitchRow(
                "Show the performance overlay",
                "Frame times, render cost and the rates of frames and snapshots, in a small card over the viewport.",
                display.performanceOverlay,
            ) { value -> change { it.copy(performanceOverlay = value) } }
        }
        SectionCard("Where your settings are kept") {
            Text(
                settingsPath ?: "Nowhere: this run does not save settings.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** What the frame rate will be, from the display's refresh rate (0 when not known) and the cap that was chosen. */
internal fun frameRateText(refreshHz: Double, capHz: Int): String {
    if (refreshHz <= 0) {
        return "The display's refresh rate is not known yet. The viewport never draws faster than the cap or the display."
    }
    val target = Pacing.targetHz(refreshHz, capHz)
    val base = String.format(
        Locale.ROOT, "Your display refreshes at %.0f Hz, so the viewport draws at most %.0f frames per second", refreshHz, target,
    )
    // Below the cap and below the display: an even share of the refresh rate, which keeps frames evenly spaced.
    return if (target < capHz - TOLERANCE_HZ && target < refreshHz - TOLERANCE_HZ) {
        "$base: an even share of the display's rate, which looks smoother than $capHz."
    } else {
        "$base."
    }
}

private const val TOLERANCE_HZ = 0.5
