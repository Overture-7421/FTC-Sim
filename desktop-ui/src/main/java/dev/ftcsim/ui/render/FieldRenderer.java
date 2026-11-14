package dev.ftcsim.ui.render;

import static org.lwjgl.opengl.GL33C.GL_BACK;
import static org.lwjgl.opengl.GL33C.GL_BLEND;
import static org.lwjgl.opengl.GL33C.GL_CCW;
import static org.lwjgl.opengl.GL33C.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL33C.GL_CULL_FACE;
import static org.lwjgl.opengl.GL33C.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL33C.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL33C.GL_EQUAL;
import static org.lwjgl.opengl.GL33C.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL33C.GL_INCR;
import static org.lwjgl.opengl.GL33C.GL_KEEP;
import static org.lwjgl.opengl.GL33C.GL_LEQUAL;
import static org.lwjgl.opengl.GL33C.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL33C.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL33C.GL_STENCIL_BUFFER_BIT;
import static org.lwjgl.opengl.GL33C.GL_STENCIL_TEST;
import static org.lwjgl.opengl.GL33C.glBindFramebuffer;
import static org.lwjgl.opengl.GL33C.glBindVertexArray;
import static org.lwjgl.opengl.GL33C.glBlendFunc;
import static org.lwjgl.opengl.GL33C.glClear;
import static org.lwjgl.opengl.GL33C.glClearColor;
import static org.lwjgl.opengl.GL33C.glCullFace;
import static org.lwjgl.opengl.GL33C.glDepthFunc;
import static org.lwjgl.opengl.GL33C.glDepthMask;
import static org.lwjgl.opengl.GL33C.glDisable;
import static org.lwjgl.opengl.GL33C.glEnable;
import static org.lwjgl.opengl.GL33C.glFrontFace;
import static org.lwjgl.opengl.GL33C.glStencilFunc;
import static org.lwjgl.opengl.GL33C.glStencilOp;
import static org.lwjgl.opengl.GL33C.glUniform1f;
import static org.lwjgl.opengl.GL33C.glUniform1i;
import static org.lwjgl.opengl.GL33C.glUniform3f;
import static org.lwjgl.opengl.GL33C.glUniformMatrix4fv;
import static org.lwjgl.opengl.GL33C.glViewport;

import dev.ftcsim.ui.scene.PoseInterpolator;
import dev.ftcsim.ui.scene.SceneModel;
import org.lwjgl.opengl.GL;

/**
 * Draws a {@link SceneModel} with OpenGL 3.3 core into whatever framebuffer the caller binds: instanced boxes,
 * cylinders and spheres, the floor (the field's own, with tile seams, or a tiled one of the renderer's when the field has
 * none), one directional light and a translucent planar shadow under everything that moves. It knows nothing about
 * windows, Swing or Compose; every call needs the context that was current when {@link #init()} ran.
 */
public final class FieldRenderer implements AutoCloseable {
    private static final int[][] SPHERE_DETAIL = {{10, 6}, {16, 10}, {24, 14}};
    private static final int CYLINDER_SEGMENTS = 28;
    private static final float FLOOR_THICKNESS = 0.05f;
    // The floor surface sits a few millimetres below zero so that scenery which has a floor of its own wins.
    private static final float FLOOR_TOP = -0.004f;
    // What an instance's flag tells the shader to draw: ordinary solids, this renderer's own tiled floor, or the floor
    // a field brings, which keeps its own colour and gets the seams between the tiles.
    private static final float PLAIN = 0f;
    private static final float BUILT_IN_FLOOR = 1f;
    private static final float FIELD_FLOOR = 2f;
    private static final float LIGHT_X = 0.45f;
    private static final float LIGHT_Y = -0.35f;
    private static final float LIGHT_Z = 0.82f;

    private static final String VERTEX_SHADER = """
            #version 330 core
            layout(location = 0) in vec3 aPosition;
            layout(location = 1) in vec3 aNormal;
            layout(location = 2) in vec4 iPositionFlag;
            layout(location = 3) in vec4 iRotation;
            layout(location = 4) in vec3 iHalfSize;
            layout(location = 5) in vec3 iColor;
            uniform mat4 uViewProjection;
            uniform vec3 uLight;
            uniform int uPass;
            out vec3 vWorld;
            out vec3 vNormal;
            out vec3 vColor;
            flat out float vFlag;

            vec3 rotate(vec4 q, vec3 v) {
                return v + 2.0 * cross(q.xyz, cross(q.xyz, v) + q.w * v);
            }

            void main() {
                vec3 world = rotate(iRotation, aPosition * iHalfSize) + iPositionFlag.xyz;
                if (uPass == 1) {
                    world = vec3(world.xy - uLight.xy * (world.z / uLight.z), 0.0015);
                }
                vWorld = world;
                vNormal = rotate(iRotation, aNormal);
                vColor = iColor;
                vFlag = iPositionFlag.w;
                gl_Position = uViewProjection * vec4(world, 1.0);
            }
            """;

    private static final String FRAGMENT_SHADER = """
            #version 330 core
            in vec3 vWorld;
            in vec3 vNormal;
            in vec3 vColor;
            flat in float vFlag;
            uniform vec3 uLight;
            uniform vec3 uEye;
            uniform int uPass;
            uniform float uFieldHalf;
            uniform float uSpecular;
            out vec4 fragColor;

            const float TILE = 0.6096;

            vec3 ambientLight(vec3 n) {
                return mix(vec3(0.22, 0.24, 0.28), vec3(0.50, 0.52, 0.56), n.z * 0.5 + 0.5);
            }

            // The floor of a field with none of its own: tiles with light seams and a faint checker.
            vec3 tileColor(vec2 xy) {
                vec2 cell = xy / TILE;
                vec2 local = abs(fract(cell) - 0.5) * 2.0;
                float edge = smoothstep(0.96, 0.995, max(local.x, local.y));
                float checker = mod(floor(cell.x) + floor(cell.y), 2.0);
                vec3 base = mix(vec3(0.20, 0.25, 0.29), vec3(0.25, 0.31, 0.35), checker);
                return mix(base, vec3(0.62, 0.68, 0.72), edge);
            }

            // 1 on a seam between two tiles, a pixel wide at any distance; gone where seams are closer than a pixel.
            float seams(vec2 xy, vec2 width) {
                vec2 cell = xy / TILE;
                vec2 pixels = abs(fract(cell - 0.5) - 0.5) / max(width, vec2(1e-5));
                float line = 1.0 - min(min(pixels.x, pixels.y), 1.0);
                return line * (1.0 - smoothstep(0.25, 0.5, max(width.x, width.y)));
            }

            void main() {
                vec2 cellWidth = fwidth(vWorld.xy / TILE);
                if (uPass == 1) {
                    if (abs(vWorld.x) > uFieldHalf || abs(vWorld.y) > uFieldHalf) {
                        discard;
                    }
                    fragColor = vec4(0.0, 0.0, 0.0, 0.34);
                    return;
                }
                vec3 n = normalize(vNormal);
                vec3 base = vColor;
                float shine = uSpecular;
                if (vFlag > 1.5) {
                    // The floor the field brings: its own colour with the seams between the tiles drawn on it.
                    vec2 cell = floor(vWorld.xy / TILE);
                    float checker = mod(cell.x + cell.y, 2.0) - 0.5;
                    base = vColor * (1.0 + 0.05 * checker) * (1.0 - 0.34 * seams(vWorld.xy, cellWidth));
                    shine = 0.0;
                } else if (vFlag > 0.5) {
                    base = tileColor(vWorld.xy);
                    shine = 0.0;
                }
                float diffuse = max(dot(n, uLight), 0.0);
                vec3 halfway = normalize(uLight + normalize(uEye - vWorld));
                float specular = pow(max(dot(n, halfway), 0.0), 48.0) * shine;
                vec3 lit = base * (ambientLight(n) + vec3(1.0, 0.97, 0.90) * diffuse * 0.85) + vec3(specular);
                fragColor = vec4(lit, 1.0);
            }
            """;

    // Spheres are glossy, everything else is matte plastic, foam and painted wood.
    private static final float[] SPECULAR_BY_MESH = {0.05f, 0.05f, 0.30f, 0.30f, 0.30f};

    private final float[] viewProjection = new float[16];
    private final float[] eye = new float[3];
    private final float[] placed = new float[7];
    private GlProgram program;
    private InstanceBatch[] fixed;
    private InstanceBatch[] moving;
    private SceneModel scene;
    private float floorHalf;
    private int uViewProjection;
    private int uLight;
    private int uEye;
    private int uPass;
    private int uFieldHalf;
    private int uSpecular;
    private int drawCalls;
    private int drawnInstances;
    private long drawnTriangles;

    /** What one frame submitted to the GPU: the cost drivers that do not depend on the machine. */
    public record FrameStats(int drawCalls, int instances, long triangles, int fixedInstances, int movingInstances) {}

    /** Creates the GL objects and the thread's LWJGL capabilities; the target context must be current. */
    public void init() {
        GL.createCapabilities();
        program = new GlProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        uViewProjection = program.uniform("uViewProjection");
        uLight = program.uniform("uLight");
        uEye = program.uniform("uEye");
        uPass = program.uniform("uPass");
        uFieldHalf = program.uniform("uFieldHalf");
        uSpecular = program.uniform("uSpecular");
        fixed = createBatches(8);
        moving = createBatches(128);
    }

    private static InstanceBatch[] createBatches(int initialCapacity) {
        SceneModel.Mesh[] kinds = SceneModel.Mesh.values();
        InstanceBatch[] batches = new InstanceBatch[kinds.length];
        for (SceneModel.Mesh kind : kinds) {
            Meshes.Mesh mesh = switch (kind) {
                case BOX -> Meshes.box();
                case CYLINDER -> Meshes.cylinder(CYLINDER_SEGMENTS);
                case SPHERE_COARSE -> Meshes.sphere(SPHERE_DETAIL[0][0], SPHERE_DETAIL[0][1]);
                case SPHERE_MEDIUM -> Meshes.sphere(SPHERE_DETAIL[1][0], SPHERE_DETAIL[1][1]);
                case SPHERE_FINE -> Meshes.sphere(SPHERE_DETAIL[2][0], SPHERE_DETAIL[2][1]);
            };
            batches[kind.ordinal()] = new InstanceBatch(mesh, initialCapacity);
        }
        return batches;
    }

    /** Replaces what is drawn; the fixed scenery is uploaded here once and drawn unchanged every frame. */
    public void setScene(SceneModel newScene) {
        scene = newScene;
        floorHalf = newScene.floorHalfExtent();
        for (InstanceBatch batch : fixed) {
            batch.begin();
        }
        InstanceBatch boxes = fixed[SceneModel.Mesh.BOX.ordinal()];
        if (!newScene.bringsItsOwnFloor()) {
            boxes.add(
                    0f, 0f, FLOOR_TOP - FLOOR_THICKNESS, BUILT_IN_FLOOR, 0f, 0f, 0f, 1f,
                    floorHalf, floorHalf, FLOOR_THICKNESS, 0f, 0f, 0f);
        }
        for (SceneModel.Part part : newScene.scenery()) {
            fixed[part.mesh().ordinal()].add(
                    part.x(), part.y(), part.z(), part.floor() ? FIELD_FLOOR : PLAIN, part.qx(), part.qy(), part.qz(), part.qw(),
                    part.halfX(), part.halfY(), part.halfZ(), part.red(), part.green(), part.blue());
        }
        for (InstanceBatch batch : fixed) {
            batch.upload();
        }
    }

    /**
     * Draws one frame into {@code framebuffer} (0 is the window's default framebuffer) covering the
     * {@code width} by {@code height} pixels from the origin. Nothing but the background is drawn before the
     * first {@link #setScene}.
     */
    public void render(PoseInterpolator.Poses poses, OrbitCamera camera, int framebuffer, int width, int height) {
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        glViewport(0, 0, width, height);
        glClearColor(0.07f, 0.08f, 0.10f, 1f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);
        if (scene == null) {
            return;
        }
        collect(poses);
        drawCalls = 0;
        drawnInstances = 0;
        drawnTriangles = 0;
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        glDisable(GL_BLEND);
        glFrontFace(GL_CCW);
        glCullFace(GL_BACK);

        camera.viewProjection(width / (float) Math.max(1, height), viewProjection, eye);
        program.use();
        glUniformMatrix4fv(uViewProjection, false, viewProjection);
        float lightLength = (float) Math.sqrt(LIGHT_X * LIGHT_X + LIGHT_Y * LIGHT_Y + LIGHT_Z * LIGHT_Z);
        glUniform3f(uLight, LIGHT_X / lightLength, LIGHT_Y / lightLength, LIGHT_Z / lightLength);
        glUniform3f(uEye, eye[0], eye[1], eye[2]);
        glUniform1f(uFieldHalf, floorHalf);

        glEnable(GL_CULL_FACE);
        glUniform1i(uPass, 0);
        drawAll(fixed, true);
        drawAll(moving, true);

        // Shadows are translucent decals on the floor; the stencil lets each pixel be darkened once however many
        // shadows overlap there, and nothing is written to the depth buffer.
        glDisable(GL_CULL_FACE);
        glDepthMask(false);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glEnable(GL_STENCIL_TEST);
        glStencilFunc(GL_EQUAL, 0, 0xFF);
        glStencilOp(GL_KEEP, GL_KEEP, GL_INCR);
        glUniform1i(uPass, 1);
        drawAll(moving, false);
        glDisable(GL_STENCIL_TEST);
        glDisable(GL_BLEND);
        glDepthMask(true);
        glBindVertexArray(0);
    }

    /** What the last {@link #render} drew: draw calls, instances and triangles over all passes, and how the instances split. */
    public FrameStats lastFrame() {
        if (fixed == null) {
            return new FrameStats(0, 0, 0, 0, 0);
        }
        return new FrameStats(drawCalls, drawnInstances, drawnTriangles, countOf(fixed), countOf(moving));
    }

    private static int countOf(InstanceBatch[] batches) {
        int total = 0;
        for (InstanceBatch batch : batches) {
            total += batch.count();
        }
        return total;
    }

    private void drawAll(InstanceBatch[] batches, boolean lit) {
        for (int mesh = 0; mesh < batches.length; mesh++) {
            InstanceBatch batch = batches[mesh];
            if (batch.count() == 0) {
                continue;
            }
            if (lit) {
                glUniform1f(uSpecular, SPECULAR_BY_MESH[mesh]);
            }
            int count = batch.draw();
            drawCalls++;
            drawnInstances += count;
            drawnTriangles += (long) count * batch.triangles();
        }
    }

    private void collect(PoseInterpolator.Poses poses) {
        for (InstanceBatch batch : moving) {
            batch.begin();
        }
        float[] values = poses.values;
        for (int i = 0; i < poses.count; i++) {
            int offset = i * PoseInterpolator.POSE_STRIDE;
            addParts(scene.parts(poses.kinds[i]), values, offset);
            addParts(scene.carriedBy(poses.entityIds[i]), values, offset);
        }
        for (InstanceBatch batch : moving) {
            batch.upload();
        }
    }

    private void addParts(SceneModel.Part[] parts, float[] values, int offset) {
        for (SceneModel.Part part : parts) {
            part.placeOn(values, offset, placed);
            moving[part.mesh().ordinal()].add(
                    placed[0], placed[1], placed[2], 0f, placed[3], placed[4], placed[5], placed[6],
                    part.halfX(), part.halfY(), part.halfZ(), part.red(), part.green(), part.blue());
        }
    }

    @Override
    public void close() {
        if (program == null) {
            return;
        }
        for (InstanceBatch batch : fixed) {
            batch.close();
        }
        for (InstanceBatch batch : moving) {
            batch.close();
        }
        program.close();
    }
}
