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
        SectionCard("Viewport") {
            Text("Render resolution", style = MaterialTheme.typography.titleSmall)
            ChoiceRow(
                DisplaySettings.RENDER_SCALES, display.renderScalePercent, { "$it %" },
                { value -> change { it.copy(renderScalePercent = value) } }, "Render resolution",
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
        SectionCard("Performance") {
            SwitchRow(
                "Show overlay",
                null,
                display.performanceOverlay,
            ) { value -> change { it.copy(performanceOverlay = value) } }
        }
        SectionCard("Settings file") {
            Text(
                settingsPath ?: "Not saved",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** The frame rate the viewport will draw at, from the display's refresh rate (0 when not known) and the cap that was chosen. */
internal fun frameRateText(refreshHz: Double, capHz: Int): String {
    if (refreshHz <= 0) return "Display rate unknown"
    val target = Pacing.targetHz(refreshHz, capHz)
    return String.format(Locale.ROOT, "Display %.0f Hz, drawing at %.0f fps", refreshHz, target)
}

