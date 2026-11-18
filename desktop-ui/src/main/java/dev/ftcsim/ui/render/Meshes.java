package dev.ftcsim.ui.render;

/** Unit-sized triangle meshes: interleaved position and normal, counter-clockwise front faces. */
final class Meshes {
    /** Floats per vertex: position then normal. */
    static final int VERTEX_FLOATS = 6;

    record Mesh(float[] vertices, int[] indices) {
        int vertexCount() {
            return vertices.length / VERTEX_FLOATS;
        }
    }

    private Meshes() {}

    /** Cube spanning [-1, 1] on every axis; instance scale turns it into any box by its half extents. */
    static Mesh box() {
        float[][][] faces = {
            {{0, 0, 1}, {1, 0, 0}, {0, 1, 0}},
            {{0, 0, -1}, {0, 1, 0}, {1, 0, 0}},
            {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}},
            {{-1, 0, 0}, {0, 0, 1}, {0, 1, 0}},
            {{0, 1, 0}, {0, 0, 1}, {1, 0, 0}},
            {{0, -1, 0}, {1, 0, 0}, {0, 0, 1}},
        };
        float[] vertices = new float[faces.length * 4 * VERTEX_FLOATS];
        int[] indices = new int[faces.length * 6];
        int[][] corners = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
        int vertex = 0;
        int index = 0;
        for (float[][] face : faces) {
            float[] n = face[0];
            float[] u = face[1];
            float[] v = face[2];
            int first = vertex;
            for (int[] corner : corners) {
                int base = vertex * VERTEX_FLOATS;
                for (int axis = 0; axis < 3; axis++) {
                    vertices[base + axis] = n[axis] + u[axis] * corner[0] + v[axis] * corner[1];
                    vertices[base + 3 + axis] = n[axis];
                }
                vertex++;
            }
            indices[index++] = first;
            indices[index++] = first + 1;
            indices[index++] = first + 2;
            indices[index++] = first;
            indices[index++] = first + 2;
            indices[index++] = first + 3;
        }
        return new Mesh(vertices, indices);
    }

    /** Sphere of radius 1 centred on the origin with its poles on the Z axis. */
    static Mesh sphere(int slices, int stacks) {
        if (slices < 3 || stacks < 2) {
            throw new IllegalArgumentException("a sphere needs at least 3 slices and 2 stacks");
        }
        int columns = slices + 1;
        float[] vertices = new float[columns * (stacks + 1) * VERTEX_FLOATS];
        int out = 0;
        for (int i = 0; i <= stacks; i++) {
            double polar = Math.PI * i / stacks;
            float z = (float) Math.cos(polar);
            double ring = Math.sin(polar);
            for (int j = 0; j <= slices; j++) {
                double azimuth = 2.0 * Math.PI * j / slices;
                float x = (float) (ring * Math.cos(azimuth));
                float y = (float) (ring * Math.sin(azimuth));
                vertices[out++] = x;
                vertices[out++] = y;
                vertices[out++] = z;
                vertices[out++] = x;
                vertices[out++] = y;
                vertices[out++] = z;
            }
        }
        int[] indices = new int[slices * stacks * 6];
        int index = 0;
        for (int i = 0; i < stacks; i++) {
            for (int j = 0; j < slices; j++) {
                int a = i * columns + j;
                int b = a + 1;
                int c = a + columns;
                int d = c + 1;
                indices[index++] = a;
                indices[index++] = c;
                indices[index++] = d;
                indices[index++] = a;
                indices[index++] = d;
                indices[index++] = b;
            }
        }
        return new Mesh(vertices, indices);
    }

    /** Cylinder of radius 1 and half height 1 whose axis is the Z axis, closed by two caps. */
    static Mesh cylinder(int segments) {
        if (segments < 3) {
            throw new IllegalArgumentException("a cylinder needs at least 3 segments");
        }
        int ringVertices = segments + 1;
        // Layout: side bottom ring, side top ring, top cap centre and ring, bottom cap centre and ring.
        int sideBottom = 0;
        int sideTop = ringVertices;
        int topCentre = 2 * ringVertices;
        int topRing = topCentre + 1;
        int bottomCentre = topRing + ringVertices;
        int bottomRing = bottomCentre + 1;
        float[] vertices = new float[(bottomRing + ringVertices) * VERTEX_FLOATS];
        for (int i = 0; i <= segments; i++) {
            double azimuth = 2.0 * Math.PI * i / segments;
            float x = (float) Math.cos(azimuth);
            float y = (float) Math.sin(azimuth);
            putVertex(vertices, sideBottom + i, x, y, -1f, x, y, 0f);
            putVertex(vertices, sideTop + i, x, y, 1f, x, y, 0f);
            putVertex(vertices, topRing + i, x, y, 1f, 0f, 0f, 1f);
            putVertex(vertices, bottomRing + i, x, y, -1f, 0f, 0f, -1f);
        }
        putVertex(vertices, topCentre, 0f, 0f, 1f, 0f, 0f, 1f);
        putVertex(vertices, bottomCentre, 0f, 0f, -1f, 0f, 0f, -1f);
        int[] indices = new int[segments * 12];
        int index = 0;
        for (int i = 0; i < segments; i++) {
            indices[index++] = sideBottom + i;
            indices[index++] = sideBottom + i + 1;
            indices[index++] = sideTop + i + 1;
            indices[index++] = sideBottom + i;
            indices[index++] = sideTop + i + 1;
            indices[index++] = sideTop + i;
            indices[index++] = topCentre;
            indices[index++] = topRing + i;
            indices[index++] = topRing + i + 1;
            indices[index++] = bottomCentre;
            indices[index++] = bottomRing + i + 1;
            indices[index++] = bottomRing + i;
        }
        return new Mesh(vertices, indices);
    }

    /**
     * Triangular prism: the triangle with base corners (y, z) = (-1, -1) and (1, -1) and apex (0, 1), extruded over x in
     * [-1, 1]. The instance scale turns it into any isosceles gable; the normals of its two slanted faces are those of
     * the unit proportions.
     */
    static Mesh wedge() {
        float[][] triangle = {{-1, -1}, {1, -1}, {0, 1}};
        float slant = (float) Math.sqrt(5.0);
        float[] vertices = new float[(2 * 3 + 3 * 4) * VERTEX_FLOATS];
        int[] indices = new int[(2 + 3 * 2) * 3];
        int vertex = 0;
        int index = 0;
        for (float x : new float[] {1f, -1f}) {
            int first = vertex;
            for (float[] corner : triangle) {
                putVertex(vertices, vertex++, x, corner[0], corner[1], x, 0f, 0f);
            }
            boolean flip = x < 0;
            indices[index++] = first;
            indices[index++] = flip ? first + 2 : first + 1;
            indices[index++] = flip ? first + 1 : first + 2;
        }
        float[][] normals = {{0f, -1f}, {2f / slant, 1f / slant}, {-2f / slant, 1f / slant}};
        for (int edge = 0; edge < 3; edge++) {
            float[] a = triangle[edge];
            float[] b = triangle[(edge + 1) % 3];
            float[] n = {0f, normals[edge][0], normals[edge][1]};
            int first = vertex;
            putVertex(vertices, vertex++, -1f, a[0], a[1], 0f, n[1], n[2]);
            putVertex(vertices, vertex++, 1f, a[0], a[1], 0f, n[1], n[2]);
            putVertex(vertices, vertex++, 1f, b[0], b[1], 0f, n[1], n[2]);
            putVertex(vertices, vertex++, -1f, b[0], b[1], 0f, n[1], n[2]);
            indices[index++] = first;
            indices[index++] = first + 2;
            indices[index++] = first + 1;
            indices[index++] = first;
            indices[index++] = first + 3;
            indices[index++] = first + 2;
        }
        return new Mesh(vertices, indices);
    }

    private static void putVertex(float[] vertices, int vertex, float x, float y, float z, float nx, float ny, float nz) {
        int base = vertex * VERTEX_FLOATS;
        vertices[base] = x;
        vertices[base + 1] = y;
        vertices[base + 2] = z;
        vertices[base + 3] = nx;
        vertices[base + 4] = ny;
        vertices[base + 5] = nz;
    }
}
