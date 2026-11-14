package dev.ftcsim.acceptance;

import dev.ftcsim.contracts.field.Alliance;
import dev.ftcsim.contracts.field.FieldDescription;
import dev.ftcsim.contracts.session.RobotInfo;
import dev.ftcsim.contracts.snapshot.BodyKinds;
import dev.ftcsim.contracts.snapshot.WorldSnapshot;
import dev.ftcsim.ui.glfw.GlfwThread;
import dev.ftcsim.ui.glfw.HiddenGlContext;
import dev.ftcsim.ui.render.FieldRenderer;
import dev.ftcsim.ui.render.GlInfo;
import dev.ftcsim.ui.render.GpuTimer;
import dev.ftcsim.ui.render.OffscreenTarget;
import dev.ftcsim.ui.render.OrbitCamera;
import dev.ftcsim.ui.scene.PoseInterpolator;
import dev.ftcsim.ui.scene.SceneModel;
import dev.ftcsim.ui.scene.SnapshotMailbox;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * The application's own viewport renderer on a hidden OpenGL context, for drawing what a real session holds into
 * picture files and for counting what that costs. Everything runs on the thread that opened it, which owns the
 * context; {@link #open()} fails where there is no OpenGL 3.3 context, which the tests turn into a skip.
 */
final class ViewportShots implements AutoCloseable {
    /** Pictures go below the module's build folder, as the other screenshot tests do. */
    static final Path FOLDER = Path.of("build", "screens");

    static final int WIDTH = 1280;
    static final int HEIGHT = 720;
    static final int SAMPLES = 4;

    /**
     * What drawing a frame cost: the median and 99th percentile in milliseconds of what the CPU spent in the renderer
     * (submit), of what the GPU spent (gpu) and of the whole loop body including the read-back (frame), and the Java
     * heap allocated per frame by the thread that draws.
     */
    record Cost(
            double submitP50, double submitP99, double gpuP50, double gpuP99, double frameP50, double frameP99,
            long allocatedBytesPerFrame, int frames) {}

    private final GlfwThread glfw;
    private final HiddenGlContext context;
    private final FieldRenderer renderer = new FieldRenderer();
    private final PoseInterpolator.Poses poses = new PoseInterpolator.Poses();
    private final OrbitCamera camera = new OrbitCamera();
    private OffscreenTarget target;
    private int[] lastPicture = new int[0];

    private ViewportShots(GlfwThread glfw, HiddenGlContext context) {
        this.glfw = glfw;
        this.context = context;
    }

    /** Opens a hidden context and makes it current on the calling thread; throws when the machine has none. */
    static ViewportShots open() throws Exception {
        GlfwThread glfw = GlfwThread.start();
        try {
            HiddenGlContext context = HiddenGlContext.create(glfw);
            try {
                context.makeCurrent();
                ViewportShots shots = new ViewportShots(glfw, context);
                shots.renderer.init();
                return shots;
            } catch (Throwable failure) {
                context.release();
                context.close();
                throw failure;
            }
        } catch (Throwable failure) {
            glfw.close();
            throw failure;
        }
    }

    /** What a worker tells the window about the robot before anything runs: the name and the chassis box. */
    static RobotInfo robotInfo(String name, double lengthM, double widthM, double heightM) {
        return new RobotInfo(name, lengthM, widthM, heightM, List.of(), List.of(), List.of());
    }

    OrbitCamera camera() {
        return camera;
    }

    FieldRenderer renderer() {
        return renderer;
    }

    /** Draws this field and robot from now on, with the bodies of {@code world} in place. */
    void show(FieldDescription field, RobotInfo robot, Alliance alliance, WorldSnapshot world) {
        SceneModel scene = SceneModel.of(field, robot, SceneModel.robotRgb(alliance));
        renderer.setScene(scene);
        camera.setObstacles(scene);
        move(world);
    }

    /** Puts the bodies where {@code world} has them, as the render loop does at the end of a snapshot interval. */
    void move(WorldSnapshot world) {
        SnapshotMailbox mailbox = new SnapshotMailbox();
        mailbox.publish(world, 0L);
        PoseInterpolator.interpolate(mailbox.latest(), 1.0, poses);
    }

    /**
     * Lets the chase camera settle behind the robot, as the render loop does with every frame, and returns where the
     * robot is: x, y and heading.
     */
    double[] followRobot() {
        int robot = poses.indexOfKind(BodyKinds.CHASSIS);
        if (robot < 0) {
            throw new IllegalStateException("the robot is not in the snapshot");
        }
        int offset = robot * PoseInterpolator.POSE_STRIDE;
        double x = poses.values[offset];
        double y = poses.values[offset + 1];
        double heading = poses.yaw(robot);
        camera.track(x, y, heading, 30.0);
        return new double[] {x, y, heading};
    }

    /** One frame at the given size as ARGB pixels, top row first. */
    int[] draw(int width, int height, int samples) {
        OffscreenTarget current = targetOf(width, height, samples);
        renderer.render(poses, camera, current.framebuffer(), width, height);
        return current.readArgb();
    }

    /** The default 1280 by 720 picture with 4x multisampling, written as {@code build/screens/<name>.png}. */
    Path shoot(String name) throws IOException {
        lastPicture = draw(WIDTH, HEIGHT, SAMPLES);
        return save(name, lastPicture, WIDTH, HEIGHT);
    }

    /** The pixels of the picture {@link #shoot} took last: ARGB, top row first, {@value #WIDTH} by {@value #HEIGHT}. */
    int[] lastPicture() {
        return lastPicture;
    }

    /** The pixels of a rectangle of {@code picture}, [fromX, toX) by [fromY, toY), that satisfy {@code colour}. */
    static int count(int[] picture, int fromX, int fromY, int toX, int toY, Colour colour) {
        int found = 0;
        for (int y = fromY; y < toY; y++) {
            for (int x = fromX; x < toX; x++) {
                int pixel = picture[y * WIDTH + x];
                if (colour.matches((pixel >> 16) & 0xFF, (pixel >> 8) & 0xFF, pixel & 0xFF)) {
                    found++;
                }
            }
        }
        return found;
    }

    /** A test of the red, green and blue of a pixel. */
    interface Colour {
        boolean matches(int red, int green, int blue);
    }

    static Path save(String name, int[] argb, int width, int height) throws IOException {
        Files.createDirectories(FOLDER);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, width, height, argb, 0, width);
        Path file = FOLDER.resolve(name + ".png");
        if (!ImageIO.write(image, "png", file.toFile())) {
            throw new IOException("no PNG writer for " + file);
        }
        return file;
    }

    /**
     * Draws {@code frames} frames back to back at the given size the way the render loop does (draw, request the
     * read-back, map the frame before) and reports what each cost. Frame time is the whole loop body; submit time is
     * what the CPU spends inside the renderer. GPU time is measured in a second run, one frame at a time with a timer
     * query around the draw and a wait for its result, so that frames do not overlap.
     */
    Cost measure(int width, int height, int samples, int warmup, int frames) {
        OffscreenTarget current = targetOf(width, height, samples);
        double[] submit = new double[frames];
        double[] total = new double[frames];
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        long allocatedBefore = 0;
        for (int i = 0; i < warmup + frames; i++) {
            if (i == warmup) {
                allocatedBefore = threads.getThreadAllocatedBytes(thread);
            }
            long start = System.nanoTime();
            renderer.render(poses, camera, current.framebuffer(), width, height);
            long submitted = System.nanoTime();
            current.requestReadback();
            if (current.mapPrevious() != 0L) {
                current.unmapPrevious();
            }
            long end = System.nanoTime();
            if (i >= warmup) {
                submit[i - warmup] = (submitted - start) / 1e6;
                total[i - warmup] = (end - start) / 1e6;
            }
        }
        long allocated = threads.getThreadAllocatedBytes(thread) - allocatedBefore;
        double[] gpu = new double[frames];
        try (GpuTimer timer = new GpuTimer()) {
            for (int i = 0; i < warmup + frames; i++) {
                timer.begin();
                renderer.render(poses, camera, current.framebuffer(), width, height);
                timer.end();
                double[] result = {Double.NaN};
                long giveUp = System.nanoTime() + 2_000_000_000L;
                while (Double.isNaN(result[0]) && System.nanoTime() < giveUp) {
                    timer.collect(nanos -> result[0] = nanos / 1e6);
                }
                if (i >= warmup) {
                    gpu[i - warmup] = result[0];
                }
            }
        }
        return new Cost(
                percentile(submit, 0.50), percentile(submit, 0.99), percentile(gpu, 0.50), percentile(gpu, 0.99),
                percentile(total, 0.50), percentile(total, 0.99), allocated / frames, frames);
    }

    /** What the driver and the renderer are, for the record of a measurement. */
    String glInfo() {
        return GlInfo.describe();
    }

    /** What the last frame drawn asked the GPU to do. */
    FieldRenderer.FrameStats lastFrame() {
        return renderer.lastFrame();
    }

    static double percentile(double[] values, double fraction) {
        if (values.length == 0) {
            return Double.NaN;
        }
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[(int) Math.min(sorted.length - 1, Math.floor(fraction * sorted.length))];
    }

    private OffscreenTarget targetOf(int width, int height, int samples) {
        if (target == null || target.width() != width || target.height() != height || target.samples() != (samples < 2 ? 0 : samples)) {
            if (target != null) {
                target.close();
            }
            target = new OffscreenTarget(width, height, samples);
        }
        return target;
    }

    @Override
    public void close() {
        if (target != null) {
            target.close();
        }
        renderer.close();
        context.release();
        context.close();
        glfw.close();
    }
}
