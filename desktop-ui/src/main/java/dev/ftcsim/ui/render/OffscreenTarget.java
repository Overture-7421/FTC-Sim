package dev.ftcsim.ui.render;

import static org.lwjgl.opengl.GL33C.GL_BGRA;
import static org.lwjgl.opengl.GL33C.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL33C.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL33C.GL_DEPTH24_STENCIL8;
import static org.lwjgl.opengl.GL33C.GL_DEPTH_STENCIL_ATTACHMENT;
import static org.lwjgl.opengl.GL33C.GL_DRAW_FRAMEBUFFER;
import static org.lwjgl.opengl.GL33C.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL33C.GL_FRAMEBUFFER_COMPLETE;
import static org.lwjgl.opengl.GL33C.GL_MAP_READ_BIT;
import static org.lwjgl.opengl.GL33C.GL_MAX_SAMPLES;
import static org.lwjgl.opengl.GL33C.GL_NEAREST;
import static org.lwjgl.opengl.GL33C.GL_PIXEL_PACK_BUFFER;
import static org.lwjgl.opengl.GL33C.GL_READ_FRAMEBUFFER;
import static org.lwjgl.opengl.GL33C.GL_RENDERBUFFER;
import static org.lwjgl.opengl.GL33C.GL_RGBA8;
import static org.lwjgl.opengl.GL33C.GL_STREAM_READ;
import static org.lwjgl.opengl.GL33C.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL33C.GL_UNSIGNED_INT_8_8_8_8_REV;
import static org.lwjgl.opengl.GL33C.glBindBuffer;
import static org.lwjgl.opengl.GL33C.glBindFramebuffer;
import static org.lwjgl.opengl.GL33C.glBindRenderbuffer;
import static org.lwjgl.opengl.GL33C.glBlitFramebuffer;
import static org.lwjgl.opengl.GL33C.glBufferData;
import static org.lwjgl.opengl.GL33C.glCheckFramebufferStatus;
import static org.lwjgl.opengl.GL33C.glDeleteBuffers;
import static org.lwjgl.opengl.GL33C.glDeleteFramebuffers;
import static org.lwjgl.opengl.GL33C.glDeleteRenderbuffers;
import static org.lwjgl.opengl.GL33C.glFramebufferRenderbuffer;
import static org.lwjgl.opengl.GL33C.glGenBuffers;
import static org.lwjgl.opengl.GL33C.glGenFramebuffers;
import static org.lwjgl.opengl.GL33C.glGenRenderbuffers;
import static org.lwjgl.opengl.GL33C.glGetInteger;
import static org.lwjgl.opengl.GL33C.glReadPixels;
import static org.lwjgl.opengl.GL33C.glRenderbufferStorage;
import static org.lwjgl.opengl.GL33C.glRenderbufferStorageMultisample;
import static org.lwjgl.opengl.GL33C.glUnmapBuffer;
import static org.lwjgl.opengl.GL33C.nglMapBufferRange;

import java.nio.IntBuffer;
import org.lwjgl.BufferUtils;

/**
 * A renderbuffer-backed framebuffer (optionally multisampled) whose pixels are read back through two
 * pixel-pack buffers, so a frame is requested without stalling and collected one frame later. Pixels are
 * BGRA, rows bottom-up, which is what OpenGL produces and what Skia's native format can ingest directly.
 */
public final class OffscreenTarget implements AutoCloseable {
    private final int width;
    private final int height;
    private final int samples;
    private final int renderFramebuffer;
    private final int renderColor;
    private final int renderDepth;
    private final int resolveFramebuffer;
    private final int resolveColor;
    private final int[] readbackBuffers = new int[2];
    private final long frameBytes;
    private long framesRequested;
    private boolean mapped;
    private int mappedBuffer;

    /** Needs a current context. {@code samples} below 2 disables multisampling. */
    public OffscreenTarget(int width, int height, int requestedSamples) {
        this.width = width;
        this.height = height;
        this.samples = requestedSamples < 2 ? 0 : Math.min(requestedSamples, glGetInteger(GL_MAX_SAMPLES));
        this.frameBytes = (long) width * height * 4;

        renderFramebuffer = glGenFramebuffers();
        renderColor = glGenRenderbuffers();
        renderDepth = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, renderColor);
        allocate(GL_RGBA8);
        glBindRenderbuffer(GL_RENDERBUFFER, renderDepth);
        allocate(GL_DEPTH24_STENCIL8);
        glBindFramebuffer(GL_FRAMEBUFFER, renderFramebuffer);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, renderColor);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, renderDepth);
        requireComplete("render");

        if (this.samples > 0) {
            resolveFramebuffer = glGenFramebuffers();
            resolveColor = glGenRenderbuffers();
            glBindRenderbuffer(GL_RENDERBUFFER, resolveColor);
            glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, width, height);
            glBindFramebuffer(GL_FRAMEBUFFER, resolveFramebuffer);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, resolveColor);
            requireComplete("resolve");
        } else {
            resolveFramebuffer = 0;
            resolveColor = 0;
        }
        glBindRenderbuffer(GL_RENDERBUFFER, 0);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);

        for (int i = 0; i < readbackBuffers.length; i++) {
            readbackBuffers[i] = glGenBuffers();
            glBindBuffer(GL_PIXEL_PACK_BUFFER, readbackBuffers[i]);
            glBufferData(GL_PIXEL_PACK_BUFFER, frameBytes, GL_STREAM_READ);
        }
        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
    }

    private void allocate(int format) {
        if (samples > 0) {
            glRenderbufferStorageMultisample(GL_RENDERBUFFER, samples, format, width, height);
        } else {
            glRenderbufferStorage(GL_RENDERBUFFER, format, width, height);
        }
    }

    private static void requireComplete(String name) {
        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("the " + name + " framebuffer is incomplete: 0x" + Integer.toHexString(status));
        }
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int samples() {
        return samples;
    }

    public long frameBytes() {
        return frameBytes;
    }

    /** The framebuffer to hand to {@link FieldRenderer#render}. */
    public int framebuffer() {
        return renderFramebuffer;
    }

    /** Resolves multisampling and starts an asynchronous copy of the finished frame into a pack buffer. */
    public void requestReadback() {
        int source = resolve();
        glBindFramebuffer(GL_READ_FRAMEBUFFER, source);
        glBindBuffer(GL_PIXEL_PACK_BUFFER, readbackBuffers[(int) (framesRequested % 2)]);
        glReadPixels(0, 0, width, height, GL_BGRA, GL_UNSIGNED_BYTE, 0L);
        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        framesRequested++;
    }

    /**
     * Resolves multisampling and reads the finished frame back at once, as ARGB pixels (the layout of a Java
     * {@code BufferedImage} of type {@code TYPE_INT_ARGB}) with the top row first. It waits for the GPU, so it is for
     * screenshots and tests; the render loop reads back with {@link #requestReadback()} and does not wait.
     */
    public int[] readArgb() {
        int source = resolve();
        IntBuffer pixels = BufferUtils.createIntBuffer(width * height);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, source);
        glReadPixels(0, 0, width, height, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, pixels);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        int[] argb = new int[width * height];
        for (int row = 0; row < height; row++) {
            pixels.get((height - 1 - row) * width, argb, row * width, width);
        }
        return argb;
    }

    /** Resolves a multisampled frame into the resolve framebuffer; returns the framebuffer that holds the finished pixels. */
    private int resolve() {
        if (samples == 0) {
            return renderFramebuffer;
        }
        glBindFramebuffer(GL_READ_FRAMEBUFFER, renderFramebuffer);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, resolveFramebuffer);
        glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL_COLOR_BUFFER_BIT, GL_NEAREST);
        return resolveFramebuffer;
    }

    /**
     * Maps the frame requested before the most recent one and returns its address, or 0 while fewer than
     * two frames exist. The mapping blocks only if that transfer has not finished. Call
     * {@link #unmapPrevious()} when done with the memory.
     */
    public long mapPrevious() {
        if (framesRequested < 2) {
            return 0L;
        }
        if (mapped) {
            throw new IllegalStateException("previous frame is still mapped");
        }
        mappedBuffer = readbackBuffers[(int) ((framesRequested - 2) % 2)];
        glBindBuffer(GL_PIXEL_PACK_BUFFER, mappedBuffer);
        long address = nglMapBufferRange(GL_PIXEL_PACK_BUFFER, 0L, frameBytes, GL_MAP_READ_BIT);
        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
        mapped = address != 0L;
        return address;
    }

    public void unmapPrevious() {
        if (!mapped) {
            return;
        }
        glBindBuffer(GL_PIXEL_PACK_BUFFER, mappedBuffer);
        glUnmapBuffer(GL_PIXEL_PACK_BUFFER);
        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
        mapped = false;
    }

    @Override
    public void close() {
        unmapPrevious();
        glDeleteBuffers(readbackBuffers);
        glDeleteFramebuffers(renderFramebuffer);
        glDeleteRenderbuffers(renderColor);
        glDeleteRenderbuffers(renderDepth);
        if (samples > 0) {
            glDeleteFramebuffers(resolveFramebuffer);
            glDeleteRenderbuffers(resolveColor);
        }
    }
}
