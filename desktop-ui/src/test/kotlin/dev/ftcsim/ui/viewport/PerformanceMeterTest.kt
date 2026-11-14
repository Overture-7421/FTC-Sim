package dev.ftcsim.ui.viewport

import dev.ftcsim.contracts.snapshot.BodyState
import dev.ftcsim.contracts.snapshot.WorldSnapshot
import dev.ftcsim.ui.input.ViewportInput
import dev.ftcsim.ui.practice.performanceLines
import dev.ftcsim.ui.render.OrbitCamera
import dev.ftcsim.ui.scene.ViewportFeed
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PerformanceMeterTest {
    private val feed = ViewportFeed()
    private val camera = OrbitCamera()
    private val engine = ViewportEngine(feed, camera, ViewportInput(camera))
    private var now = 0L
    private val meter = PerformanceMeter(engine, feed) { now }

    private fun publish(count: Int) {
        val bodies = listOf(BodyState(1, 1, 0f, 0f, 0f, 0f, 0f, 0f, 1f))
        repeat(count) { feed.publish(WorldSnapshot(it.toLong(), it * 4_000_000L, bodies)) }
    }

    @Test
    fun snapshotsPerSecondIsTheCountSinceTheLastSampleOverTheTimeSinceThen() {
        now = 1_000_000_000L
        publish(120)

        val sample = meter.sample()

        assertEquals(120.0, sample.snapshotsPerSecond, 1e-9)
        now += 500_000_000L
        publish(120)
        assertEquals(240.0, meter.sample().snapshotsPerSecond, 1e-9, "120 snapshots in half a second")
    }

    @Test
    fun nothingIsReportedForRecordersThatSawNothing() {
        now = 1L

        val sample = meter.sample()

        assertNull(sample.frameInterval)
        assertNull(sample.renderCpu)
        assertNull(sample.renderGpu)
        assertEquals(0.0, sample.framesPerSecond)
    }

    @Test
    fun theDistributionsComeFromWhatTheRenderThreadRecorded() {
        engine.stats.recorder(RenderLoop.INTERVAL, 64).apply { repeat(10) { record(7_000_000L) } }
        engine.stats.recorder(RenderLoop.COST, 64).apply { repeat(10) { record(1_500_000L) } }
        engine.stats.recorder(OffscreenSink.GPU_TIME, 64).apply { repeat(10) { record(800_000L) } }
        now = 1L

        val sample = meter.sample()

        assertEquals(7.0, sample.frameInterval!!.p50(), 1e-9)
        assertEquals(1.5, sample.renderCpu!!.p50(), 1e-9)
        assertEquals(0.8, sample.renderGpu!!.p50(), 1e-9)
    }

    @Test
    fun theOverlayWritesTheNumbersInMillisecondsAndSaysWhenTheViewportIsAtRest() {
        engine.stats.recorder(RenderLoop.INTERVAL, 64).apply { repeat(10) { record(6_944_000L) } }
        engine.stats.recorder(RenderLoop.COST, 64).apply { repeat(10) { record(1_200_000L) } }
        engine.stats.recorder(OffscreenSink.GPU_TIME, 64).apply { repeat(10) { record(900_000L) } }
        val moving = PerformanceSample(144.0, 240.0, engine.stats.summaries()[RenderLoop.INTERVAL], engine.stats.summaries()[RenderLoop.COST], engine.stats.summaries()[OffscreenSink.GPU_TIME])

        assertEquals(
            listOf("Frame   6.9 ms p50  6.9 ms p99", "Render  1.20 ms CPU  0.90 ms GPU", "Frames  144/s  Snapshots 240/s"),
            performanceLines(moving),
        )
        assertEquals("Frame   at rest", performanceLines(moving.copy(framesPerSecond = 0.0))[0])
        assertEquals(listOf("Measuring..."), performanceLines(null))
        assertNotNull(moving.frameInterval)
        assertTrue(performanceLines(PerformanceSample(0.0, 0.0, null, null, null))[1].startsWith("Render  - CPU  - GPU"))
    }
}
