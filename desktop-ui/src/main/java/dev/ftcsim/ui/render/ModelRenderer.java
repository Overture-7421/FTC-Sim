package dev.ftcsim.ui.render;

import static org.lwjgl.opengl.GL33C.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL33C.GL_BLEND;
import static org.lwjgl.opengl.GL33C.GL_CULL_FACE;
import static org.lwjgl.opengl.GL33C.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL33C.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL33C.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL33C.GL_SHORT;
import static org.lwjgl.opengl.GL33C.GL_STATIC_DRAW;
import static org.lwjgl.opengl.GL33C.GL_TRIANGLES;
import static org.lwjgl.opengl.GL33C.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL33C.GL_UNSIGNED_SHORT;
import static org.lwjgl.opengl.GL33C.glBindBuffer;
import static org.lwjgl.opengl.GL33C.glBindVertexArray;
import static org.lwjgl.opengl.GL33C.glBlendFunc;
import static org.lwjgl.opengl.GL33C.glBufferData;
import static org.lwjgl.opengl.GL33C.glDeleteBuffers;
import static org.lwjgl.opengl.GL33C.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL33C.glDepthMask;
import static org.lwjgl.opengl.GL33C.glDisable;
import static org.lwjgl.opengl.GL33C.glDrawElements;
import static org.lwjgl.opengl.GL33C.glEnable;
import static org.lwjgl.opengl.GL33C.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL33C.glGenBuffers;
import static org.lwjgl.opengl.GL33C.glGenVertexArrays;
import static org.lwjgl.opengl.GL33C.glUniform1f;
import static org.lwjgl.opengl.GL33C.glUniform1i;
import static org.lwjgl.opengl.GL33C.glUniform3f;
import static org.lwjgl.opengl.GL33C.glUniformMatrix4fv;
import static org.lwjgl.opengl.GL33C.glVertexAttribIPointer;
import static org.lwjgl.opengl.GL33C.glVertexAttribPointer;

import dev.ftcsim.contracts.snapshot.BodyKinds;
import dev.ftcsim.contracts.snapshot.RobotJoint;
import dev.ftcsim.ui.model.ModelPoser;
import dev.ftcsim.ui.model.RobotModel;
import dev.ftcsim.ui.scene.PoseInterpolator;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws a {@link RobotModel}: one static GPU mesh per chunk of every group, uploaded once, and one draw call per chunk
 * with the group's matrix as a uniform. A frame costs a few dozen draw calls and no per-frame upload however many
 * triangles the CAD has. Shading is flat, from screen derivatives, so no normals are stored.
 */
final class ModelRenderer implements AutoCloseable {
    private static final String VERTEX_SHADER = """
            #version 330 core
            layout(location = 0) in ivec3 aPosition;
            layout(location = 1) in vec3 aColor;
            uniform mat4 uViewProjection;
            uniform mat4 uModel;
            uniform vec3 uMin;
            uniform vec3 uSpan;
            uniform vec3 uLight;
            uniform int uPass;
            out vec3 vWorld;
            out vec3 vColor;

            void main() {
                vec3 local = uMin + (vec3(aPosition) + 32768.0) * (uSpan / 65535.0);
                vec3 world = (uModel * vec4(local, 1.0)).xyz;
                if (uPass == 1) {
                    world = vec3(world.xy - uLight.xy * (world.z / uLight.z), 0.0015);
                }
                vWorld = world;
                vColor = aColor;
                gl_Position = uViewProjection * vec4(world, 1.0);
            }
            """;

    private static final String FRAGMENT_SHADER = """
            #version 330 core
            in vec3 vWorld;
            in vec3 vColor;
            uniform vec3 uLight;
            uniform vec3 uEye;
            uniform int uPass;
            uniform float uFieldHalf;
            uniform float uAlpha;
            out vec4 fragColor;

            vec3 ambientLight(vec3 n) {
                return mix(vec3(0.22, 0.24, 0.28), vec3(0.50, 0.52, 0.56), n.z * 0.5 + 0.5);
            }

            void main() {
                if (uPass == 1) {
                    if (abs(vWorld.x) > uFieldHalf || abs(vWorld.y) > uFieldHalf) {
                        discard;
                    }
                    fragColor = vec4(0.0, 0.0, 0.0, 0.34);
                    return;
                }
                vec3 n = normalize(cross(dFdx(vWorld), dFdy(vWorld)));
                float diffuse = max(dot(n, uLight), 0.0);
                vec3 halfway = normalize(uLight + normalize(uEye - vWorld));
                float specular = pow(max(dot(n, halfway), 0.0), 32.0) * 0.12;
                vec3 lit = vColor * (ambientLight(n) + vec3(1.0, 0.97, 0.90) * diffuse * 0.85) + vec3(specular);
                fragColor = vec4(lit, uAlpha);
            }
            """;

    private record Mesh(
            int groupIndex, int vertexArray, int positions, int colours, int indices, int triangles, float alpha, float[] min,
            float[] span) {}

    private final RobotModel model;
    private final ModelPoser poser;
    private final GlProgram program;
    private final List<Mesh> meshes = new ArrayList<>();
    private final int uViewProjection;
    private final int uModel;
    private final int uMin;
    private final int uSpan;
    private final int uLight;
    private final int uEye;
    private final int uPass;
    private final int uFieldHalf;
    private final int uAlpha;
    private final float[] chassis = new float[7];
    private final float[] turret = new float[7];
    private final float[] joints = new float[RobotJoint.values().length];
    private final float[] model16 = new float[16];
    private float[] matrices = new float[0];
    private int drawCalls;
    private long drawnTriangles;

    /** Uploads {@code model}; the target context must be current. */
    ModelRenderer(RobotModel model) {
        this.model = model;
        this.poser = new ModelPoser(model);
        this.program = new GlProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        uViewProjection = program.uniform("uViewProjection");
        uModel = program.uniform("uModel");
        uMin = program.uniform("uMin");
        uSpan = program.uniform("uSpan");
        uLight = program.uniform("uLight");
        uEye = program.uniform("uEye");
        uPass = program.uniform("uPass");
        uFieldHalf = program.uniform("uFieldHalf");
        uAlpha = program.uniform("uAlpha");
        for (int g = 0; g < model.groups().size(); g++) {
            for (RobotModel.Chunk chunk : model.groups().get(g).chunks()) {
                int vao = glGenVertexArrays();
                int positions = glGenBuffers();
                int colours = glGenBuffers();
                int indices = glGenBuffers();
                glBindVertexArray(vao);
                glBindBuffer(GL_ARRAY_BUFFER, positions);
                glBufferData(GL_ARRAY_BUFFER, chunk.positions(), GL_STATIC_DRAW);
                glVertexAttribIPointer(0, 3, GL_SHORT, 6, 0L);
                glEnableVertexAttribArray(0);
                glBindBuffer(GL_ARRAY_BUFFER, colours);
                glBufferData(GL_ARRAY_BUFFER, chunk.colours(), GL_STATIC_DRAW);
                glVertexAttribPointer(1, 3, GL_UNSIGNED_BYTE, true, 3, 0L);
                glEnableVertexAttribArray(1);
                glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indices);
                glBufferData(GL_ELEMENT_ARRAY_BUFFER, chunk.indices(), GL_STATIC_DRAW);
                glBindVertexArray(0);
                float[] span = {chunk.max()[0] - chunk.min()[0], chunk.max()[1] - chunk.min()[1], chunk.max()[2] - chunk.min()[2]};
                meshes.add(new Mesh(g, vao, positions, colours, indices, chunk.triangles(), chunk.alpha(), chunk.min(), span));
            }
        }
    }

    /** Poses the model from the snapshot's bodies; false when the snapshot has no chassis. */
    boolean place(PoseInterpolator.Poses poses) {
        int chassisAt = poses.indexOfKind(BodyKinds.CHASSIS);
        if (chassisAt < 0) {
            return false;
        }
        System.arraycopy(poses.values, chassisAt * PoseInterpolator.POSE_STRIDE, chassis, 0, 7);
        int turretAt = poses.indexOfKind(BodyKinds.TURRET);
        if (turretAt >= 0) {
            System.arraycopy(poses.values, turretAt * PoseInterpolator.POSE_STRIDE, turret, 0, 7);
        }
        for (RobotJoint joint : RobotJoint.values()) {
            int at = poses.indexOfKind(joint.kind());
            joints[joint.ordinal()] = at < 0 ? 0f : poses.values[at * PoseInterpolator.POSE_STRIDE];
        }
        matrices = poser.pose(chassis, turretAt >= 0 ? turret : null, joints);
        return true;
    }

    /** Sets what is the same for every draw of the frame. */
    void begin(float[] viewProjection, float[] light, float[] eye, float fieldHalf) {
        program.use();
        glUniformMatrix4fv(uViewProjection, false, viewProjection);
        glUniform3f(uLight, light[0], light[1], light[2]);
        glUniform3f(uEye, eye[0], eye[1], eye[2]);
        glUniform1f(uFieldHalf, fieldHalf);
        drawCalls = 0;
        drawnTriangles = 0;
    }

    /** Draws every chunk; {@code pass} is 0 for the lit robot and 1 for its shadow. Translucent chunks (lexan) come last and cast no shadow. */
    void draw(int pass) {
        program.use();
        glUniform1i(uPass, pass);
        if (pass == 0) {
            glDisable(GL_CULL_FACE);
        }
        glUniform1f(uAlpha, 1f);
        for (Mesh mesh : meshes) {
            if (mesh.alpha() >= 1f) {
                drawMesh(mesh);
            }
        }
        if (pass == 0) {
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            glDepthMask(false);
            for (Mesh mesh : meshes) {
                if (mesh.alpha() < 1f) {
                    glUniform1f(uAlpha, mesh.alpha());
                    drawMesh(mesh);
                }
            }
            glDepthMask(true);
            glDisable(GL_BLEND);
            glEnable(GL_CULL_FACE);
        }
        glBindVertexArray(0);
    }

    private void drawMesh(Mesh mesh) {
        System.arraycopy(matrices, 16 * mesh.groupIndex(), model16, 0, 16);
        glUniformMatrix4fv(uModel, false, model16);
        glUniform3f(uMin, mesh.min()[0], mesh.min()[1], mesh.min()[2]);
        glUniform3f(uSpan, mesh.span()[0], mesh.span()[1], mesh.span()[2]);
        glBindVertexArray(mesh.vertexArray());
        glDrawElements(GL_TRIANGLES, mesh.triangles() * 3, GL_UNSIGNED_SHORT, 0L);
        drawCalls++;
        drawnTriangles += mesh.triangles();
    }

    int drawCalls() {
        return drawCalls;
    }

    long drawnTriangles() {
        return drawnTriangles;
    }

    RobotModel model() {
        return model;
    }

    @Override
    public void close() {
        for (Mesh mesh : meshes) {
            glDeleteVertexArrays(mesh.vertexArray());
            glDeleteBuffers(mesh.positions());
            glDeleteBuffers(mesh.colours());
            glDeleteBuffers(mesh.indices());
        }
        meshes.clear();
        program.close();
    }
}
