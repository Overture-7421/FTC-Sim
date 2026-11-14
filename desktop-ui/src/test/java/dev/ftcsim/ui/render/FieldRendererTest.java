package dev.ftcsim.ui.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.field.BodyKind;
import dev.ftcsim.contracts.field.FieldDescription;
import dev.ftcsim.contracts.field.FieldPart;
import dev.ftcsim.contracts.field.FieldTag;
import dev.ftcsim.contracts.field.Pose3;
import dev.ftcsim.contracts.field.Scenery;
import dev.ftcsim.contracts.field.Shape;
import dev.ftcsim.contracts.snapshot.BodyState;
import dev.ftcsim.contracts.snapshot.WorldSnapshot;
import dev.ftcsim.ui.glfw.GlfwThread;
import dev.ftcsim.ui.glfw.HiddenGlContext;
import dev.ftcsim.ui.scene.PoseInterpolator;
import dev.ftcsim.ui.scene.SceneModel;
import dev.ftcsim.ui.scene.SnapshotMailbox;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The renderer's pictures, looked at pixel by pixel on a small offscreen target. They need an OpenGL 3.3 context;
 * where there is none they are skipped, not failed.
 */
class FieldRendererTest {
    private static final int WIDTH = 320;
    private static final int HEIGHT = 240;
    private static final int BALL = 7;
    private static final int FLOOR_RGB = 0x808080;
    private static final int BALL_RGB = 0xFF00FF;

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

    private static Scenery floor() {
        return new Scenery("floor", new FieldPart(new Shape.Box(6.0, 6.0, 0.25), Pose3.IDENTITY, FLOOR_RGB), Pose3.at(0, 0, -0.25));
    }

    private static FieldDescription field(List<FieldTag> tags, boolean ball) {
        List<BodyKind> kinds = ball
                ? List.of(new BodyKind(BALL, "ball", List.of(new FieldPart(new Shape.Sphere(0.1), Pose3.IDENTITY, BALL_RGB))))
                : List.of();
        return new FieldDescription("t", "T", List.of(floor()), kinds, tags, List.of(), List.of());
    }

    private static PoseInterpolator.Poses poses(List<BodyState> bodies) {
        SnapshotMailbox mailbox = new SnapshotMailbox();
        mailbox.publish(new WorldSnapshot(1, 1, bodies), 0L);
        PoseInterpolator.Poses poses = new PoseInterpolator.Poses();
        PoseInterpolator.interpolate(mailbox.latest(), 1.0, poses);
        return poses;
    }

    private static BodyState ball(int id, double x, double y, double z) {
        return new BodyState(id, BALL, (float) x, (float) y, (float) z, 0f, 0f, 0f, 1f);
    }

    /** One frame of the scene from straight above, the field's +X up the picture. */
    private int[] top(FieldDescription description, List<BodyState> bodies) {
        renderer.setScene(SceneModel.of(description, null));
        OrbitCamera camera = new OrbitCamera();
        camera.setDriverHeading(0.0);
        camera.select(OrbitCamera.Preset.TOP);
        renderer.render(poses(bodies), camera, target.framebuffer(), WIDTH, HEIGHT);
        return target.readArgb();
    }

    private static double luminance(int argb) {
        return (0.299 * ((argb >> 16) & 0xFF) + 0.587 * ((argb >> 8) & 0xFF) + 0.114 * (argb & 0xFF)) / 255.0;
    }

    private static boolean grey(int argb) {
        int red = (argb >> 16) & 0xFF;
        int green = (argb >> 8) & 0xFF;
        int blue = argb & 0xFF;
        return Math.abs(red - green) <= 6 && Math.abs(green - blue) <= 6;
    }

    @Test
    void theFloorAFieldBringsKeepsItsOwnColourWithSeamsBetweenTilesAndNoGlare() {
        int[] picture = top(field(List.of(), false), List.of());

        // A column a little to the side of the middle crosses the seams between the tiles and runs along none.
        int column = WIDTH / 2 + 20;
        double[] down = new double[HEIGHT - 20];
        for (int row = 10; row < HEIGHT - 10; row++) {
            down[row - 10] = luminance(picture[row * WIDTH + column]);
        }
        double[] sorted = down.clone();
        Arrays.sort(sorted);
        double typical = sorted[sorted.length / 2];
        long seams = Arrays.stream(down).filter(value -> value < 0.93 * typical).count();
        assertTrue(typical > 0.3 && typical < 0.8, "the grey floor is lit like a grey floor: " + typical);
        assertTrue(seams >= 3, "the seams between the tiles are drawn: " + seams);
        assertTrue(sorted[sorted.length - 1] < 1.12 * typical, "no glare on the floor: brightest " + sorted[sorted.length - 1] + " against " + typical);
    }

    @Test
    void aShadowIsATranslucentDarkeningOfTheFloorThatOverlappingShadowsDoNotDeepen() {
        FieldDescription description = field(List.of(), true);
        int[] bare = top(description, List.of());
        int[] one = top(description, List.of(ball(1, 0.6, 0.1, 0.5)));
        int[] two = top(description, List.of(ball(1, 0.6, 0.1, 0.5), ball(2, 0.6, 0.1, 0.6)));

        double deepestOne = deepestShadow(bare, one);
        double deepestTwo = deepestShadow(bare, two);
        assertTrue(shadowPixels(bare, one) > 30, "the ball has a shadow on the floor: " + shadowPixels(bare, one));
        assertTrue(deepestOne > 0.55 && deepestOne < 0.8, "a shadow dims the floor by about a third and is not black: " + deepestOne);
        assertTrue(deepestTwo > 0.55, "two shadows on the same spot are one shadow: " + deepestTwo);
        assertTrue(shadowPixels(bare, two) > shadowPixels(bare, one), "the second ball adds its own part of shadow");
    }

    /** The darkest grey pixel of {@code lit} against the same pixel of {@code bare}, as a share of it; the ball itself is not grey. */
    private static double deepestShadow(int[] bare, int[] lit) {
        double deepest = 1.0;
        for (int i = 0; i < bare.length; i++) {
            if (grey(lit[i]) && luminance(lit[i]) < 0.97 * luminance(bare[i])) {
                deepest = Math.min(deepest, luminance(lit[i]) / luminance(bare[i]));
            }
        }
        return deepest;
    }

    private static int shadowPixels(int[] bare, int[] lit) {
        int count = 0;
        for (int i = 0; i < bare.length; i++) {
            if (grey(lit[i]) && luminance(lit[i]) < 0.8 * luminance(bare[i])) {
                count++;
            }
        }
        return count;
    }

    @Test
    void aTagLyingOnTheFloorIsABlackSquareWithAWhiteOneOnItAndABlackCore() {
        FieldTag tag = new FieldTag(20, 0.5, -1, Pose3.at(0.3, 0.3, 0.0));
        int[] picture = top(field(List.of(tag), false), List.of());

        // Find the tag as everything that is nearly black or nearly white, then read it along its middle row.
        int minX = WIDTH;
        int maxX = -1;
        int minY = HEIGHT;
        int maxY = -1;
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                double lum = luminance(picture[y * WIDTH + x]);
                if (lum < 0.12 || lum > 0.8) {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        assertTrue(maxX - minX > 15 && maxY - minY > 15, "the tag is on screen: " + (maxX - minX) + " by " + (maxY - minY));
        int middleRow = (minY + maxY) / 2;
        StringBuilder bands = new StringBuilder();
        char last = ' ';
        for (int x = minX; x <= maxX; x++) {
            double lum = luminance(picture[middleRow * WIDTH + x]);
            char band = lum < 0.2 ? 'D' : lum > 0.7 ? 'L' : ' ';
            if (band != ' ' && band != last) {
                bands.append(band);
                last = band;
            }
        }
        assertEquals("DLDLD", bands.toString(), "across the tag: border, white square, core, white square, border");
    }

    @Test
    void thePictureComesBackTheRightWayUpWithTheBackgroundAboveTheFloor() {
        renderer.setScene(SceneModel.of(field(List.of(), false), null));
        OrbitCamera camera = new OrbitCamera();
        camera.setDriverHeading(0.0);
        camera.select(OrbitCamera.Preset.DRIVER);
        renderer.render(poses(List.of()), camera, target.framebuffer(), WIDTH, HEIGHT);

        int[] picture = target.readArgb();

        int sky = picture[5 * WIDTH + WIDTH / 2];
        int ground = picture[(HEIGHT - 5) * WIDTH + WIDTH / 2];
        assertEquals(18, (sky >> 16) & 0xFF, 2, "the top row is background");
        assertTrue(luminance(ground) > 3 * luminance(sky), "the bottom row is floor");
    }

    @Test
    void aFrameReportsWhatItAskedTheGpuToDraw() {
        renderer.setScene(SceneModel.of(field(List.of(), true), null));
        OrbitCamera camera = new OrbitCamera();
        camera.select(OrbitCamera.Preset.DRIVER);
        List<BodyState> bodies = new ArrayList<>(List.of(ball(1, 0.5, 0.5, 0.3)));

        renderer.render(poses(bodies), camera, target.framebuffer(), WIDTH, HEIGHT);

        FieldRenderer.FrameStats stats = renderer.lastFrame();
        assertEquals(1, stats.fixedInstances(), "the field's own floor; the renderer adds none under it");
        assertEquals(1, stats.movingInstances());
        assertEquals(3, stats.instances(), "the floor, the ball and the ball's shadow");
        assertEquals(3, stats.drawCalls(), "one for the boxes, one for the ball and one for its shadow");
        assertTrue(stats.triangles() > 12 + 2 * 24 * 14);
    }
}
