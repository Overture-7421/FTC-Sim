package dev.ftcsim.ui.viewport

import dev.ftcsim.ui.glfw.GlfwThread
import dev.ftcsim.ui.glfw.HiddenGlContext
import dev.ftcsim.ui.render.FieldRenderer
import dev.ftcsim.ui.render.GlInfo
import dev.ftcsim.ui.render.GpuTimer
import dev.ftcsim.ui.render.OffscreenTarget
import dev.ftcsim.ui.render.OrbitCamera
import dev.ftcsim.ui.scene.SceneModel
import dev.ftcsim.ui.scene.ViewportFeed
import dev.ftcsim.ui.stats.StatSet
import org.jetbrains.skia.Data
import org.lwjgl.system.MemoryUtil

/** Pixels of one finished frame in Skia-owned native memory: BGRA, rows bottom-up, `width` by `height`. */
internal class ProducedFrame(val data: Data, val width: Int, val height: Int, val index: Long)

/**
 * The render side of the viewport: an offscreen multisampled framebuffer on a hidden GLFW context, read back through
 * two pixel-pack buffers (so the read of frame N completes while frame N+1 renders) and copied once into Skia memory
 * for the UI thread to draw. Everything but [resize] runs on the render thread.
 */
internal class OffscreenSink(
    private val glfw: GlfwThread,
    private val feed: ViewportFeed,
    private val camera: OrbitCamera,
    private val msaa: Int,
    stats: StatSet,
    private val started: (glInfo: String) -> Unit,
    private val publish: (ProducedFrame) -> Unit,
) : RenderLoop.Sink {
    @Volatile private var wantedWidth = 0
    @Volatile private var wantedHeight = 0
    @Volatile private var scalePercent = FULL_SCALE
    private val recording = stats::recording
    private val copyCost = stats.recorder(COPY_COST, CAPACITY)
    private val gpuTime = stats.recorder(GPU_TIME, CAPACITY)
    private var gl: HiddenGlContext? = null
    private var renderer: FieldRenderer? = null
    private var timer: GpuTimer? = null
    private var target: OffscreenTarget? = null
    private var appliedScene: SceneModel? = null

    /** The size of the surface the UI shows the frames on, in device pixels; any thread. */
    fun resize(width: Int, height: Int) {
        wantedWidth = width
        wantedHeight = height
    }

    /** Draws at this percentage of the surface's size, and lets the UI stretch the picture to fit; any thread. */
    fun setScale(percent: Int) {
        scalePercent = percent.coerceIn(MIN_SCALE, FULL_SCALE)
    }

    private fun scaled(size: Int): Int = if (size <= 0) 0 else maxOf(1, size * scalePercent / FULL_SCALE)

    override fun attach() {
        val hidden = HiddenGlContext.create(glfw)
        gl = hidden
        hidden.makeCurrent()
        renderer = FieldRenderer().also { it.init() }
        timer = GpuTimer()
        started(GlInfo.describe())
    }

    override fun wantsFrame(): Boolean {
        val current = target
        val width = scaled(wantedWidth)
        val height = scaled(wantedHeight)
        return width > 0 && height > 0 && (current == null || current.width() != width || current.height() != height)
    }

    override fun draw(frame: RenderLoop.Frame): Boolean {
        val width = scaled(wantedWidth)
        val height = scaled(wantedHeight)
        if (width <= 0 || height <= 0) return false
        val drawing = renderer!!
        val scene = feed.scene()
        if (scene !== appliedScene) {
            if (scene != null) drawing.setScene(scene)
            appliedScene = scene
        }
        var current = target
        if (current == null || current.width() != width || current.height() != height) {
            current?.close()
            current = OffscreenTarget(width, height, msaa)
            target = current
        }
        val gpu = timer!!
        gpu.begin()
        drawing.render(frame.poses, camera, current.framebuffer(), width, height)
        gpu.end()
        current.requestReadback()
        val source = current.mapPrevious()
        if (source != 0L) {
            val copyStart = System.nanoTime()
            val data = Data.makeUninitialized(current.frameBytes().toInt())
            MemoryUtil.memCopy(source, data.writableData(), current.frameBytes())
            current.unmapPrevious()
            if (recording()) copyCost.record(System.nanoTime() - copyStart)
            publish(ProducedFrame(data, width, height, frame.index))
        }
        gpu.collect { if (recording()) gpuTime.record(it) }
        return true
    }

    override fun detach() {
        target?.close()
        timer?.close()
        renderer?.close()
        gl?.release()
        gl?.close()
    }

    companion object {
        const val COPY_COST = "viewport.copyToSkia"
        const val GPU_TIME = "viewport.gpuRender"
        const val FULL_SCALE = 100
        private const val MIN_SCALE = 10
        private const val CAPACITY = 4096
    }
}
