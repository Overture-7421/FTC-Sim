package dev.ftcsim.ui.practice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.ftcsim.ui.viewport.PerformanceMeter
import dev.ftcsim.ui.viewport.PerformanceSample
import java.util.Locale
import kotlinx.coroutines.delay

private const val SAMPLE_PERIOD_MILLIS = 500L

/** A small card with how the viewport is doing: frame times, render cost and the rates of frames and snapshots. */
@Composable
fun PerformanceOverlay(meter: PerformanceMeter, modifier: Modifier = Modifier) {
    val sample by produceState<PerformanceSample?>(null, meter) {
        while (true) {
            delay(SAMPLE_PERIOD_MILLIS)
            value = meter.sample()
        }
    }
    Surface(
        modifier.semantics { contentDescription = "Performance" },
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        shape = MaterialTheme.shapes.small,
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Performance", style = MaterialTheme.typography.labelLarge)
            performanceLines(sample).forEach {
                Text(it, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

/** The lines the overlay shows for [sample], which is null before the first measurement. */
internal fun performanceLines(sample: PerformanceSample?): List<String> {
    if (sample == null) return listOf("Measuring...")
    val interval = sample.frameInterval
    val frame = if (sample.framesPerSecond < 0.5 || interval == null) {
        "at rest"
    } else {
        "${millis(interval.p50())} p50  ${millis(interval.p99())} p99"
    }
    return listOf(
        "Frame   $frame",
        "Render  ${sample.renderCpu?.let { cost(it.p50()) } ?: "-"} CPU  ${sample.renderGpu?.let { cost(it.p50()) } ?: "-"} GPU",
        String.format(Locale.ROOT, "Frames  %.0f/s  Snapshots %.0f/s", sample.framesPerSecond, sample.snapshotsPerSecond),
    )
}

private fun millis(value: Double): String = String.format(Locale.ROOT, "%.1f ms", value)

/** Render costs are often a fraction of a millisecond, so they get a second decimal. */
private fun cost(value: Double): String = String.format(Locale.ROOT, "%.2f ms", value)
