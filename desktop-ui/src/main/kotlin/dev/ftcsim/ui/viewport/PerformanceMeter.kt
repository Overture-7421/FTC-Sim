package dev.ftcsim.ui.viewport

import dev.ftcsim.ui.scene.ViewportFeed
import dev.ftcsim.ui.stats.Summary

/**
 * What the performance overlay shows: how many frames and snapshots arrived per second since the last sample, and the
 * distribution of the recent frame intervals and render costs, each null until something was recorded.
 */
data class PerformanceSample(
    val framesPerSecond: Double,
    val snapshotsPerSecond: Double,
    val frameInterval: Summary?,
    val renderCpu: Summary?,
    val renderGpu: Summary?,
)

/** Turns the engine's counters and recorders into per-second rates; call [sample] at a steady pace, about twice a second. */
class PerformanceMeter(
    private val engine: ViewportEngine,
    private val feed: ViewportFeed,
    private val clock: () -> Long = System::nanoTime,
) {
    private var lastNanos = clock()
    private var lastFrames = engine.framesDrawn()
    private var lastSnapshots = feed.snapshotCount()

    fun sample(): PerformanceSample {
        val now = clock()
        val frames = engine.framesDrawn()
        val snapshots = feed.snapshotCount()
        val seconds = ((now - lastNanos) / NANOS_PER_SECOND).coerceAtLeast(MIN_SECONDS)
        val summaries = engine.stats.summaries()
        val sample = PerformanceSample(
            framesPerSecond = (frames - lastFrames) / seconds,
            snapshotsPerSecond = (snapshots - lastSnapshots) / seconds,
            frameInterval = summaries[RenderLoop.INTERVAL],
            renderCpu = summaries[RenderLoop.COST],
            renderGpu = summaries[OffscreenSink.GPU_TIME],
        )
        lastNanos = now
        lastFrames = frames
        lastSnapshots = snapshots
        return sample
    }

    private companion object {
        const val NANOS_PER_SECOND = 1e9
        const val MIN_SECONDS = 1e-3
    }
}
