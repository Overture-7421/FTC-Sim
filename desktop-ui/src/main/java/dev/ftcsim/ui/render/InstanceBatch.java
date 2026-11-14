package dev.ftcsim.ui.render;

import static org.lwjgl.opengl.GL33C.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL33C.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL33C.GL_FLOAT;
import static org.lwjgl.opengl.GL33C.GL_STATIC_DRAW;
import static org.lwjgl.opengl.GL33C.GL_STREAM_DRAW;
import static org.lwjgl.opengl.GL33C.GL_TRIANGLES;
import static org.lwjgl.opengl.GL33C.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL33C.glBindBuffer;
import static org.lwjgl.opengl.GL33C.glBindVertexArray;
import static org.lwjgl.opengl.GL33C.glBufferData;
import static org.lwjgl.opengl.GL33C.glBufferSubData;
import static org.lwjgl.opengl.GL33C.glDeleteBuffers;
import static org.lwjgl.opengl.GL33C.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL33C.glDrawElementsInstanced;
import static org.lwjgl.opengl.GL33C.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL33C.glGenBuffers;
import static org.lwjgl.opengl.GL33C.glGenVertexArrays;
import static org.lwjgl.opengl.GL33C.glVertexAttribDivisor;
import static org.lwjgl.opengl.GL33C.glVertexAttribPointer;

import java.nio.FloatBuffer;
import org.lwjgl.BufferUtils;

/**
 * One mesh drawn once per instance. Instance data (pose, half extents, colour) is collected on the CPU between
 * {@link #begin()} and {@link #upload()}; a batch that never changes is uploaded once and drawn every frame.
 */
final class InstanceBatch implements AutoCloseable {
    private static final int INSTANCE_FLOATS = 14;
    private static final int INSTANCE_BYTES = INSTANCE_FLOATS * Float.BYTES;
    private static final int VERTEX_BYTES = Meshes.VERTEX_FLOATS * Float.BYTES;

    private final Meshes.Mesh mesh;
    private final int vertexArray;
    private final int meshBuffer;
    private final int elementBuffer;
    private final int instanceBuffer;
    private FloatBuffer data;
    private int capacity;
    private int count;

    InstanceBatch(Meshes.Mesh mesh, int initialCapacity) {
        this.mesh = mesh;
        this.capacity = initialCapacity;
        this.data = BufferUtils.createFloatBuffer(initialCapacity * INSTANCE_FLOATS);
        vertexArray = glGenVertexArrays();
        meshBuffer = glGenBuffers();
        elementBuffer = glGenBuffers();
        instanceBuffer = glGenBuffers();
        glBindVertexArray(vertexArray);
        glBindBuffer(GL_ARRAY_BUFFER, meshBuffer);
        glBufferData(GL_ARRAY_BUFFER, mesh.vertices(), GL_STATIC_DRAW);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, VERTEX_BYTES, 0L);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, VERTEX_BYTES, 3L * Float.BYTES);
        glEnableVertexAttribArray(0);
        glEnableVertexAttribArray(1);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, elementBuffer);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, mesh.indices(), GL_STATIC_DRAW);
        glBindBuffer(GL_ARRAY_BUFFER, instanceBuffer);
        instanceAttribute(2, 4, 0);
        instanceAttribute(3, 4, 4);
        instanceAttribute(4, 3, 8);
        instanceAttribute(5, 3, 11);
        glBindVertexArray(0);
    }

    private static void instanceAttribute(int location, int components, int floatOffset) {
        glVertexAttribPointer(location, components, GL_FLOAT, false, INSTANCE_BYTES, (long) floatOffset * Float.BYTES);
        glEnableVertexAttribArray(location);
        glVertexAttribDivisor(location, 1);
    }

    void begin() {
        data.clear();
        count = 0;
    }

    int count() {
        return count;
    }

    /** Triangles in one instance. */
    int triangles() {
        return mesh.indices().length / 3;
    }

    /**
     * Adds one instance.
     *
     * @param flag 1 for the tiled floor, 0 for everything else
     */
    void add(
            float x, float y, float z, float flag,
            float qx, float qy, float qz, float qw,
            float halfX, float halfY, float halfZ,
            float red, float green, float blue) {
        if (count == capacity) {
            grow();
        }
        data.put(x).put(y).put(z).put(flag);
        data.put(qx).put(qy).put(qz).put(qw);
        data.put(halfX).put(halfY).put(halfZ);
        data.put(red).put(green).put(blue);
        count++;
    }

    private void grow() {
        FloatBuffer bigger = BufferUtils.createFloatBuffer(capacity * 2 * INSTANCE_FLOATS);
        data.flip();
        bigger.put(data);
        data = bigger;
        capacity *= 2;
    }

    void upload() {
        if (count == 0) {
            return;
        }
        data.flip();
        glBindBuffer(GL_ARRAY_BUFFER, instanceBuffer);
        // Re-specifying the store orphans the old one, so the driver never waits on the previous frame.
        glBufferData(GL_ARRAY_BUFFER, (long) capacity * INSTANCE_BYTES, GL_STREAM_DRAW);
        glBufferSubData(GL_ARRAY_BUFFER, 0L, data);
    }

    /** Draws every instance with one call; returns how many there were, 0 when nothing was drawn. */
    int draw() {
        if (count == 0) {
            return 0;
        }
        glBindVertexArray(vertexArray);
        glDrawElementsInstanced(GL_TRIANGLES, mesh.indices().length, GL_UNSIGNED_INT, 0L, count);
        return count;
    }

    @Override
    public void close() {
        glDeleteVertexArrays(vertexArray);
        glDeleteBuffers(meshBuffer);
        glDeleteBuffers(elementBuffer);
        glDeleteBuffers(instanceBuffer);
    }
}
