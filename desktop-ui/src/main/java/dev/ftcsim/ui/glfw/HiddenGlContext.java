package dev.ftcsim.ui.glfw;

import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR;
import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR;
import static org.lwjgl.glfw.GLFW.GLFW_FALSE;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_CORE_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_FORWARD_COMPAT;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_TRUE;
import static org.lwjgl.glfw.GLFW.GLFW_VISIBLE;
import static org.lwjgl.glfw.GLFW.glfwCreateWindow;
import static org.lwjgl.glfw.GLFW.glfwDefaultWindowHints;
import static org.lwjgl.glfw.GLFW.glfwDestroyWindow;
import static org.lwjgl.glfw.GLFW.glfwMakeContextCurrent;
import static org.lwjgl.glfw.GLFW.glfwSwapInterval;
import static org.lwjgl.glfw.GLFW.glfwWindowHint;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * An OpenGL 3.3 core context on a hidden GLFW window, for rendering that never reaches the screen
 * directly. The window is created and destroyed on the {@link GlfwThread}; any one thread at a time
 * may make the context current.
 */
public final class HiddenGlContext implements AutoCloseable {
    private final GlfwThread glfw;
    private final long window;

    private HiddenGlContext(GlfwThread glfw, long window) {
        this.glfw = glfw;
        this.window = window;
    }

    public static HiddenGlContext create(GlfwThread glfw) throws Exception {
        long handle = glfw.call(() -> {
            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
            glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
            long created = glfwCreateWindow(64, 64, "offscreen", NULL, NULL);
            if (created == NULL) {
                throw new IllegalStateException("no OpenGL 3.3 core context: " + glfw.lastError());
            }
            return created;
        });
        return new HiddenGlContext(glfw, handle);
    }

    /** Makes the context current on the calling thread; there is no swap chain, so vsync is off. */
    public void makeCurrent() {
        glfwMakeContextCurrent(window);
        glfwSwapInterval(0);
    }

    public void release() {
        glfwMakeContextCurrent(NULL);
    }

    @Override
    public void close() {
        try {
            glfw.call(() -> {
                glfwDestroyWindow(window);
                return null;
            });
        } catch (Exception e) {
            throw new IllegalStateException("could not destroy the hidden window", e);
        }
    }
}
