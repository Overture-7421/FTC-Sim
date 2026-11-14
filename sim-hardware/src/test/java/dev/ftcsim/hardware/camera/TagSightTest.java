package dev.ftcsim.hardware.camera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.world.RayView;
import dev.ftcsim.contracts.world.SurfaceHit;
import dev.ftcsim.contracts.world.Vec3;
import org.junit.jupiter.api.Test;

class TagSightTest {

    private static final SceneTag TAG = TestScene.blueGoal();
    private static final Vec3 CENTRE = TAG.inHost().translation();
    private static final Vec3 NORMAL = TAG.inHost().rotation().column(2);
    private static final Vec3 RIGHT = TAG.inHost().rotation().column(0);

    /** A camera at {@code eye} whose optical axis points at {@code target}, with the image's horizon level. */
    private static Rigid cameraLooking(Vec3 eye, Vec3 target) {
        Vec3 forward = target.minus(eye);
        forward = forward.times(1.0 / forward.length());
        Vec3 right = forward.cross(new Vec3(0, 0, 1));
        right = right.times(1.0 / right.length());
        Vec3 down = forward.cross(right);
        return new Rigid(Mat3.columns(right, down, forward), eye);
    }

    private static TagSight.Look look(Rigid camera) {
        return TagSight.look(TestScene.CAMERA, camera, TAG, Rigid.IDENTITY);
    }

    private static Vec3 inFront(double distance, double sideways) {
        return CENTRE.plus(NORMAL.times(distance)).plus(RIGHT.times(sideways));
    }

    @Test
    void aTagFacingTheCameraInsideTheImageAndBigEnoughIsSeen() {
        TagSight.Look look = look(cameraLooking(inFront(2.0, 0.3), CENTRE));

        assertEquals(TagSight.Visibility.VISIBLE, look.visibility());
        assertEquals(2.0, look.distance(), 0.05);
        assertTrue(look.shortestSide() > 50 && look.shortestSide() < 60, "0.165 m at 2 m is about 52 px: " + look.shortestSide());
    }

    @Test
    void aTagBehindTheCameraIsNotSeen() {
        Vec3 eye = inFront(2.0, 0.0);
        TagSight.Look look = look(cameraLooking(eye, eye.plus(NORMAL.times(3.0))));

        assertEquals(TagSight.Visibility.BEHIND_CAMERA, look.visibility());
    }

    @Test
    void theBackOfATagIsNotSeenAndNeitherIsAFaceSeenTooObliquely() {
        assertEquals(TagSight.Visibility.FACING_AWAY, look(cameraLooking(inFront(-2.0, 0.0), CENTRE)).visibility(), "from behind");
        assertEquals(TagSight.Visibility.VISIBLE, look(cameraLooking(inFront(0.4, 1.0), CENTRE)).visibility(), "at 68 degrees");
        assertEquals(TagSight.Visibility.FACING_AWAY, look(cameraLooking(inFront(0.15, 1.0), CENTRE)).visibility(), "at 81 degrees");
    }

    @Test
    void aTagThatIsNotWhollyInsideTheImageIsNotSeen() {
        Rigid camera = cameraLooking(inFront(2.0, 0.0), CENTRE);
        Vec3 aside = CENTRE.plus(RIGHT.times(1.4));

        assertEquals(TagSight.Visibility.OUTSIDE_IMAGE, look(cameraLooking(inFront(2.0, 0.0), aside)).visibility(), "looking elsewhere");
        // Half a tag over the edge of the image: pan until the tag's middle is at the edge.
        double edge = 2.0 * StrictMath.tan(StrictMath.toRadians(27.25));
        assertEquals(TagSight.Visibility.OUTSIDE_IMAGE, look(cameraLooking(inFront(2.0, 0.0), CENTRE.plus(RIGHT.times(-edge)))).visibility());
        assertEquals(TagSight.Visibility.VISIBLE, look(camera).visibility());
    }

    @Test
    void aTagSmallerThanTheDetectorsLimitIsNotSeenAndTheLimitIsWhereTheDocumentationSaysItIs() {
        // 24 px for a 0.1651 m tag at 621 px focal length is 4.27 m.
        assertEquals(TagSight.Visibility.VISIBLE, look(cameraLooking(inFront(4.2, 0.0), CENTRE)).visibility());
        assertEquals(TagSight.Visibility.TOO_SMALL, look(cameraLooking(inFront(4.4, 0.0), CENTRE)).visibility());
        TagSight.Look far = look(cameraLooking(inFront(8.0, 0.0), CENTRE));
        assertEquals(TagSight.Visibility.TOO_SMALL, far.visibility());
        assertTrue(far.shortestSide() < TagSight.MIN_SIDE_PIXELS);
    }

    @Test
    void somethingNearerThanTheTagBlocksItButItsOwnMountAndItsHostBodyDoNot() {
        Vec3 eye = inFront(2.0, 0.0);
        TagSight.Look look = look(cameraLooking(eye, CENTRE));
        RayView nothing = (origin, direction, maxMeters) -> null;

        assertFalse(TagSight.blocked(nothing, eye, TAG, look), "nothing in the way");
        assertTrue(TagSight.blocked(surface(1.0, 1200), eye, TAG, look), "a body halfway");
        assertFalse(TagSight.blocked(surface(1.99, -1), eye, TAG, look), "the surface the tag is stuck on, a centimetre in front of it");
        SceneTag stuckOnHive = new SceneTag(40, TestScene.TAG_SIZE, 1200, TAG.inHost());
        assertFalse(TagSight.blocked(surface(1.0, 1200), eye, stuckOnHive, look), "the body that carries the tag never hides it");
        assertTrue(TagSight.blocked(surface(1.0, 1300), eye, stuckOnHive, look), "but another body does");
    }

    /** Rays that meet a surface of the given entity {@code distance} metres from where they start. */
    private static RayView surface(double distance, int entity) {
        return (origin, direction, maxMeters) ->
                distance <= maxMeters ? new SurfaceHit(distance, SurfaceHit.SCENERY, entity, 0x808080) : null;
    }
}
