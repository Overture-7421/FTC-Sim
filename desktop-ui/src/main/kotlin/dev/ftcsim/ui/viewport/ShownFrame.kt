package dev.ftcsim.ui.viewport

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import kotlin.math.roundToInt
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode

/**
 * The picture on screen: the newest frame the render thread produced, kept until it produces a newer one, so that a
 * viewport that comes back after another screen was shown has something to draw before the first new frame is done.
 * Used from the UI thread only.
 */
internal class ShownFrame {
    private var image: Image? = null

    fun draw(scope: DrawScope, engine: ViewportEngine) {
        // Reading the counter while drawing is what makes Compose draw again when a new frame arrives.
        engine.frameCount.longValue
        engine.takeFrame()?.let { frame ->
            image?.close()
            image = Image.makeRaster(
                ImageInfo(frame.width, frame.height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE),
                frame.data,
                frame.width * 4,
            )
            frame.data.close()
        }
        val current = image ?: return
        val width = scope.size.width
        val height = scope.size.height
        // A picture drawn smaller than the area is stretched with filtering; one of the same size is copied as it is.
        val sampling = if (current.width == width.roundToInt() && current.height == height.roundToInt()) {
            SamplingMode.DEFAULT
        } else {
            SamplingMode.LINEAR
        }
        scope.drawIntoCanvas { canvas ->
            val skia = canvas.skiaCanvas
            // OpenGL rows run bottom-up, so the picture is flipped as it is drawn.
            skia.save()
            skia.translate(0f, height)
            skia.scale(1f, -1f)
            skia.drawImageRect(
                current,
                Rect.makeWH(current.width.toFloat(), current.height.toFloat()),
                Rect.makeWH(width, height),
                sampling,
                null,
                true,
            )
            skia.restore()
        }
    }
}
