package dev.ftcsim.ui.render;

import static org.lwjgl.opengl.GL33C.GL_QUERY_RESULT;
import static org.lwjgl.opengl.GL33C.GL_QUERY_RESULT_AVAILABLE;
import static org.lwjgl.opengl.GL33C.GL_TIME_ELAPSED;
import static org.lwjgl.opengl.GL33C.glBeginQuery;
import static org.lwjgl.opengl.GL33C.glDeleteQueries;
import static org.lwjgl.opengl.GL33C.glEndQuery;
import static org.lwjgl.opengl.GL33C.glGenQueries;
import static org.lwjgl.opengl.GL33C.glGetQueryObjecti;
import static org.lwjgl.opengl.GL33C.glGetQueryObjectui64;

import java.util.function.LongConsumer;

/**
 * Measures how long the GPU spends on the commands between {@link #begin()} and {@link #end()}. Results
 * arrive a few frames later and are drained with {@link #collect(LongConsumer)}; a frame is skipped when
 * every query slot is still waiting for its result.
 */
public final class GpuTimer implements AutoCloseable {
    private static final int SLOTS = 4;

    private final int[] queries = new int[SLOTS];
    private final boolean[] pending = new boolean[SLOTS];
    private int next;
    private boolean active;

    public GpuTimer() {
        for (int i = 0; i < SLOTS; i++) {
            queries[i] = glGenQueries();
        }
    }

    public void begin() {
        if (pending[next]) {
            return;
        }
        glBeginQuery(GL_TIME_ELAPSED, queries[next]);
        active = true;
    }

    public void end() {
        if (!active) {
            return;
        }
        glEndQuery(GL_TIME_ELAPSED);
        pending[next] = true;
        next = (next + 1) % SLOTS;
        active = false;
    }

    /** Hands every finished measurement, in nanoseconds and oldest first, to {@code sink}. */
    public void collect(LongConsumer sink) {
        for (int offset = 0; offset < SLOTS; offset++) {
            int slot = (next + offset) % SLOTS;
            if (pending[slot] && glGetQueryObjecti(queries[slot], GL_QUERY_RESULT_AVAILABLE) != 0) {
                sink.accept(glGetQueryObjectui64(queries[slot], GL_QUERY_RESULT));
                pending[slot] = false;
            }
        }
    }

    @Override
    public void close() {
        for (int query : queries) {
            glDeleteQueries(query);
        }
    }
}
