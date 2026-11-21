package dev.ftcsim.ui.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.field.FieldDescription;
import dev.ftcsim.contracts.field.FieldPart;
import dev.ftcsim.contracts.field.Pose3;
import dev.ftcsim.contracts.field.Scenery;
import dev.ftcsim.contracts.field.Shape;
import dev.ftcsim.contracts.session.RobotInfo;
import dev.ftcsim.contracts.snapshot.BodyKinds;
import dev.ftcsim.contracts.snapshot.BodyState;
import dev.ftcsim.contracts.snapshot.RobotJoint;
import dev.ftcsim.contracts.snapshot.WorldSnapshot;
import dev.ftcsim.ui.glfw.GlfwThread;
import dev.ftcsim.ui.glfw.HiddenGlContext;
import dev.ftcsim.ui.scene.PoseInterpolator;
import dev.ftcsim.ui.scene.SceneModel;
import dev.ftcsim.ui.scene.SnapshotMailbox;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The BIOBUZZ robot drawn from its CAD, on an offscreen target. Skipped, not failed, where there is no OpenGL 3.3. */
class RobotModelRenderTest {
    private static final int WIDTH = 960;
    private static final int HEIGHT = 720;
    private static final int FLOOR_RGB = 0x808080;
    private static final Path SCREENS = Path.of("build", "screens");

    private GlfwThread glfw;
    private HiddenGlContext context;
    private FieldRenderer renderer;
    private OffscreenTarget target;

    @BeforeEach
    void openContext() {
        try {
            glfw = GlfwThread.start();
            context = HiddenGlContext.create(glfw);
            context.makeCurrent();
            renderer = new FieldRenderer();
            renderer.init();
            target = new OffscreenTarget(WIDTH, HEIGHT, 4);
        } catch (Exception | LinkageError failure) {
            closeContext();
            Assumptions.abort("no OpenGL 3.3 context here: " + failure);
        }
    }

    @AfterEach
    void closeContext() {
        if (target != null) {
            target.close();
            target = null;
        }
        if (renderer != null) {
            renderer.close();
            renderer = null;
        }
        if (context != null) {
            context.release();
            context.close();
            context = null;
        }
        if (glfw != null) {
            glfw.close();
            glfw = null;
        }
    }

    private static SceneModel scene() {
        FieldDescription field = new FieldDescription(
                "t", "T", List.of(new Scenery("floor", new FieldPart(new Shape.Box(6.0, 6.0, 0.25), Pose3.IDENTITY, FLOOR_RGB), Pose3.at(0, 0, -0.25))),
                List.of(), List.of(), List.of(), List.of());
        RobotInfo robot = new RobotInfo("robot", 0.4572, 0.4572, 0.3048, List.of(), List.of(), List.of(), RobotInfo.BIOBUZZ_MODEL);
        return SceneModel.of(field, robot);
    }

    private static BodyState body(int id, int kind, double x, double y, double z, double yaw) {
        double half = yaw / 2.0;
        return new BodyState(id, kind, (float) x, (float) y, (float) z, 0f, 0f, (float) Math.sin(half), (float) Math.cos(half));
    }

    private static PoseInterpolator.Poses poses(double turretYaw, double[] joints) {
        List<BodyState> bodies = new ArrayList<>();
        bodies.add(body(1, BodyKinds.CHASSIS, 0.0, 0.0, 0.1824, 0.0));
        bodies.add(body(2, BodyKinds.TURRET, 0.089, 0.0, 0.1824, turretYaw));
        for (RobotJoint joint : RobotJoint.values()) {
            bodies.add(new BodyState(joint.kind(), joint.kind(), (float) joints[joint.ordinal()], 0f, 0f, 0f, 0f, 0f, 1f));
        }
        SnapshotMailbox mailbox = new SnapshotMailbox();
        mailbox.publish(new WorldSnapshot(1, 1, bodies), 0L);
        PoseInterpolator.Poses poses = new PoseInterpolator.Poses();
        PoseInterpolator.interpolate(mailbox.latest(), 1.0, poses);
        return poses;
    }

    private int[] shoot(String name, double yaw, double pitch, double distance, double turretYaw, double[] joints) throws IOException {
        renderer.setScene(scene());
        OrbitCamera camera = new OrbitCamera(new OrbitCamera.Limits(0.3, 6.0, -10.0, 89.0, 0.2));
        camera.setTarget(0.0, 0.0, 0.22);
        camera.set(yaw, pitch, distance);
        renderer.render(poses(turretYaw, joints), camera, target.framebuffer(), WIDTH, HEIGHT);
        int[] pixels = target.readArgb();
        Files.createDirectories(SCREENS);
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, WIDTH, HEIGHT, pixels, 0, WIDTH);
        ImageIO.write(image, "png", SCREENS.resolve(name + ".png").toFile());
        return pixels;
    }

    private static long differing(int[] a, int[] b) {
        long count = 0;
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                count++;
            }
        }
        return count;
    }

    @Test
    void theWholeCadRobotCostsALittleOfAFrame() throws IOException {
        double[] rest = new double[RobotJoint.values().length];
        renderer.setScene(scene());
        OrbitCamera camera = new OrbitCamera(new OrbitCamera.Limits(0.3, 6.0, -10.0, 89.0, 0.2));
        camera.setTarget(0.0, 0.0, 0.22);
        camera.set(30.0, 25.0, 1.4);
        PoseInterpolator.Poses poses = poses(0.3, rest);
        for (int i = 0; i < 5; i++) {
            renderer.render(poses, camera, target.framebuffer(), WIDTH, HEIGHT);
        }
        target.readArgb();
        int frames = 60;
        long start = System.nanoTime();
        for (int i = 0; i < frames; i++) {
            renderer.render(poses, camera, target.framebuffer(), WIDTH, HEIGHT);
        }
        target.readArgb();
        double millis = (System.nanoTime() - start) / 1e6 / frames;
        System.out.printf("BIOBUZZ CAD robot: %.2f ms per frame, %d triangles in the model%n", millis, scene().robotModel().triangles());
        assertTrue(millis < 50.0, "a frame of the robot took " + millis + " ms");
    }

    @Test
    void theCadRobotIsDrawnAndItsTurretAndColibriAndWheelsMoveWithTheirJoints() throws IOException {
        assertNotNull(scene().robotModel(), "the build carries the BIOBUZZ model");
        double[] rest = new double[RobotJoint.values().length];
        rest[RobotJoint.HOOD.ordinal()] = Math.toRadians(1.9);

        int[] front = shoot("biobuzz-cad-front", 30.0, 25.0, 1.4, 0.0, rest);
        shoot("biobuzz-cad-side", 90.0, 0.0, 1.2, 0.0, rest);
        shoot("biobuzz-cad-top", 0.0, 80.0, 1.4, 0.0, rest);
        shoot("biobuzz-cad-shooter", 55.0, 35.0, 0.85, 0.0, rest);
        int[] turned = shoot("biobuzz-cad-turret-90", 30.0, 25.0, 1.4, Math.PI / 2, rest);
        double[] moved = rest.clone();
        moved[RobotJoint.COLIBRI_LEFT.ordinal()] = Math.toRadians(60);
        moved[RobotJoint.COLIBRI_RIGHT.ordinal()] = Math.toRadians(60);
        moved[RobotJoint.HOOD.ordinal()] = Math.toRadians(25);
        moved[RobotJoint.WHEEL_FRONT_LEFT.ordinal()] = 0.7;
        int[] arms = shoot("biobuzz-cad-colibri-up", 30.0, 25.0, 1.4, 0.0, moved);

        int robot = 0;
        for (int pixel : front) {
            if ((pixel & 0xFFFFFF) != 0x808080 && Math.abs(((pixel >> 16) & 0xFF) - ((pixel >> 8) & 0xFF)) > 3) {
                robot++;
            }
        }
        assertTrue(robot > 3000, "something coloured stands on the floor: " + robot);
        assertTrue(differing(front, turned) > 2000, "turning the turret changes the picture");
        assertTrue(differing(front, arms) > 1500, "raising the colibri arms and the hood changes the picture");
        FieldRenderer.FrameStats stats = renderer.lastFrame();
        assertTrue(stats.triangles() > 10_000 && stats.triangles() <= 60_000, "the model is drawn in full, lightly: " + stats);
        assertTrue(stats.drawCalls() < 400, "in a few dozen calls, not one per part: " + stats);
        assertEquals(1, stats.fixedInstances() > 0 ? 1 : 0);
    }
}
