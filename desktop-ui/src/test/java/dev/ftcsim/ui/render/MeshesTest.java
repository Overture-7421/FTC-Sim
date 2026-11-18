package dev.ftcsim.ui.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MeshesTest {
    private static void assertWellFormed(Meshes.Mesh mesh) {
        int vertices = mesh.vertexCount();
        for (int index : mesh.indices()) {
            assertTrue(index >= 0 && index < vertices, "index out of range: " + index);
        }
        assertEquals(0, mesh.indices().length % 3);
        for (int v = 0; v < vertices; v++) {
            int base = v * Meshes.VERTEX_FLOATS;
            double length = Math.sqrt(Math.pow(mesh.vertices()[base + 3], 2)
                    + Math.pow(mesh.vertices()[base + 4], 2)
                    + Math.pow(mesh.vertices()[base + 5], 2));
            assertEquals(1.0, length, 1e-5, "normal of vertex " + v + " is not a unit vector");
        }
    }

    @Test
    void boxHasSixFlatShadedFaces() {
        Meshes.Mesh box = Meshes.box();
        assertWellFormed(box);
        assertEquals(24, box.vertexCount());
        assertEquals(36, box.indices().length);
    }

    @Test
    void sphereVerticesLieOnTheUnitSphereWithOutwardNormals() {
        Meshes.Mesh sphere = Meshes.sphere(12, 8);
        assertWellFormed(sphere);
        for (int v = 0; v < sphere.vertexCount(); v++) {
            int base = v * Meshes.VERTEX_FLOATS;
            float[] data = sphere.vertices();
            assertEquals(1.0, Math.sqrt(data[base] * data[base] + data[base + 1] * data[base + 1] + data[base + 2] * data[base + 2]), 1e-5);
            assertEquals(data[base], data[base + 3], 1e-6);
        }
    }

    @Test
    void cylinderHasSidesOnTheUnitCircleAndFlatCapsAtPlusAndMinusOne() {
        Meshes.Mesh cylinder = Meshes.cylinder(16);
        assertWellFormed(cylinder);
        float[] data = cylinder.vertices();
        boolean sawTopCap = false;
        boolean sawBottomCap = false;
        for (int v = 0; v < cylinder.vertexCount(); v++) {
            int base = v * Meshes.VERTEX_FLOATS;
            double radius = Math.hypot(data[base], data[base + 1]);
            assertTrue(radius <= 1.0 + 1e-6 && Math.abs(data[base + 2]) == 1f, "vertex " + v + " is off the cylinder");
            if (data[base + 5] == 1f) {
                sawTopCap = true;
            } else if (data[base + 5] == -1f) {
                sawBottomCap = true;
            } else {
                assertEquals(0f, data[base + 5], 0f);
                assertEquals(1.0, radius, 1e-6, "side vertices lie on the circle");
            }
        }
        assertTrue(sawTopCap && sawBottomCap);
        assertEquals(16 * 12, cylinder.indices().length);
    }

    @Test
    void everyCylinderTriangleFacesAwayFromTheAxis() {
        Meshes.Mesh cylinder = Meshes.cylinder(12);
        float[] data = cylinder.vertices();
        int[] indices = cylinder.indices();
        for (int i = 0; i < indices.length; i += 3) {
            float[] a = position(data, indices[i]);
            float[] b = position(data, indices[i + 1]);
            float[] c = position(data, indices[i + 2]);
            float[] normal = cross(sub(b, a), sub(c, a));
            float[] stored = normalOf(data, indices[i]);
            float dot = normal[0] * stored[0] + normal[1] * stored[1] + normal[2] * stored[2];
            assertTrue(dot > 0f, "triangle " + i / 3 + " winds against its normal");
        }
    }

    @Test
    void wedgeIsATriangularPrismWhoseTrianglesFaceOutward() {
        Meshes.Mesh wedge = Meshes.wedge();
        assertWellFormed(wedge);
        assertEquals(24, wedge.indices().length);
        float[] data = wedge.vertices();
        for (int v = 0; v < wedge.vertexCount(); v++) {
            float[] p = position(data, v);
            assertTrue(Math.abs(p[0]) == 1f && p[2] >= -1f && p[2] <= 1f && Math.abs(p[1]) <= 1f - (p[2] + 1f) / 2f + 1e-6f,
                    "vertex " + v + " is off the prism");
        }
        int[] indices = wedge.indices();
        for (int i = 0; i < indices.length; i += 3) {
            float[] a = position(data, indices[i]);
            float[] normal = cross(sub(position(data, indices[i + 1]), a), sub(position(data, indices[i + 2]), a));
            float[] stored = normalOf(data, indices[i]);
            float dot = normal[0] * stored[0] + normal[1] * stored[1] + normal[2] * stored[2];
            assertTrue(dot > 0f, "triangle " + i / 3 + " winds against its normal");
        }
    }

    private static float[] position(float[] data, int vertex) {
        int base = vertex * Meshes.VERTEX_FLOATS;
        return new float[] {data[base], data[base + 1], data[base + 2]};
    }

    private static float[] normalOf(float[] data, int vertex) {
        int base = vertex * Meshes.VERTEX_FLOATS;
        return new float[] {data[base + 3], data[base + 4], data[base + 5]};
    }

    private static float[] sub(float[] a, float[] b) {
        return new float[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static float[] cross(float[] a, float[] b) {
        return new float[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }
}
