package dev.ftcsim.ui.settings

import kotlin.math.abs

/**
 * How the viewport is drawn and what is shown over it.
 *
 * @property renderScalePercent how large the 3D picture is drawn compared with the area it is shown on; a smaller
 *     picture is cheaper for the GPU and is stretched to fit
 * @property frameCapHz the most frames the viewport draws per second; the display's own rate still limits it
 * @property performanceOverlay whether frame times and rates are shown over a corner of the viewport
 */
data class DisplaySettings(
    val renderScalePercent: Int = 100,
    val frameCapHz: Int = 144,
    val performanceOverlay: Boolean = false,
) {
    /** The same settings with each number moved to the nearest value the screen offers. */
    fun normalized(): DisplaySettings = copy(
        renderScalePercent = nearest(renderScalePercent, RENDER_SCALES),
        frameCapHz = nearest(frameCapHz, FRAME_CAPS),
    )

    companion object {
        val RENDER_SCALES = listOf(50, 75, 100)
        val FRAME_CAPS = listOf(60, 90, 144)

        private fun nearest(value: Int, allowed: List<Int>): Int = allowed.minBy { abs(it - value) }
    }
}
