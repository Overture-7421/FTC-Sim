package dev.ftcsim.ui.render;

import static org.lwjgl.opengl.GL33C.GL_RENDERER;
import static org.lwjgl.opengl.GL33C.GL_VENDOR;
import static org.lwjgl.opengl.GL33C.GL_VERSION;
import static org.lwjgl.opengl.GL33C.glGetString;

/** Identifies the GPU and driver behind the current OpenGL context. */
public final class GlInfo {
    private GlInfo() {}

    /** Needs a current context with LWJGL capabilities created, as after {@link FieldRenderer#init()}. */
    public static String describe() {
        return glGetString(GL_RENDERER) + " | " + glGetString(GL_VENDOR) + " | " + glGetString(GL_VERSION);
    }
}
