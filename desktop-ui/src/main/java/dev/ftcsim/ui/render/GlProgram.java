package dev.ftcsim.ui.render;

import static org.lwjgl.opengl.GL33C.GL_COMPILE_STATUS;
import static org.lwjgl.opengl.GL33C.GL_FRAGMENT_SHADER;
import static org.lwjgl.opengl.GL33C.GL_LINK_STATUS;
import static org.lwjgl.opengl.GL33C.GL_VERTEX_SHADER;
import static org.lwjgl.opengl.GL33C.glAttachShader;
import static org.lwjgl.opengl.GL33C.glCompileShader;
import static org.lwjgl.opengl.GL33C.glCreateProgram;
import static org.lwjgl.opengl.GL33C.glCreateShader;
import static org.lwjgl.opengl.GL33C.glDeleteProgram;
import static org.lwjgl.opengl.GL33C.glDeleteShader;
import static org.lwjgl.opengl.GL33C.glGetProgramInfoLog;
import static org.lwjgl.opengl.GL33C.glGetProgrami;
import static org.lwjgl.opengl.GL33C.glGetShaderInfoLog;
import static org.lwjgl.opengl.GL33C.glGetShaderi;
import static org.lwjgl.opengl.GL33C.glGetUniformLocation;
import static org.lwjgl.opengl.GL33C.glLinkProgram;
import static org.lwjgl.opengl.GL33C.glShaderSource;
import static org.lwjgl.opengl.GL33C.glUseProgram;

/** A linked vertex and fragment shader pair; needs a current OpenGL context. */
final class GlProgram implements AutoCloseable {
    private final int id;

    GlProgram(String vertexSource, String fragmentSource) {
        int vertex = compile(GL_VERTEX_SHADER, vertexSource);
        int fragment = compile(GL_FRAGMENT_SHADER, fragmentSource);
        id = glCreateProgram();
        glAttachShader(id, vertex);
        glAttachShader(id, fragment);
        glLinkProgram(id);
        glDeleteShader(vertex);
        glDeleteShader(fragment);
        if (glGetProgrami(id, GL_LINK_STATUS) == 0) {
            String log = glGetProgramInfoLog(id);
            glDeleteProgram(id);
            throw new IllegalStateException("shader link failed: " + log);
        }
    }

    void use() {
        glUseProgram(id);
    }

    int uniform(String name) {
        return glGetUniformLocation(id, name);
    }

    @Override
    public void close() {
        glDeleteProgram(id);
    }

    private static int compile(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == 0) {
            String log = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new IllegalStateException("shader compile failed: " + log);
        }
        return shader;
    }
}
