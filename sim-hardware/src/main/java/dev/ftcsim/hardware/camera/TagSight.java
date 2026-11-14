package dev.ftcsim.hardware.camera;

import dev.ftcsim.contracts.world.RayView;
import dev.ftcsim.contracts.world.SurfaceHit;
import dev.ftcsim.contracts.world.Vec3;

/**
 * Whether a camera at a place can see a tag, and where its corners fall in the image. The tests are the ones a tag
 * detector imposes: the tag must be in front of the camera, face it, lie wholly inside the image, be big enough in
 * pixels to be decoded, and have nothing in the way. The thresholds are estimates (see {@code docs/devices/limelight.md}).
 */
final class TagSight {

    /** The shortest side of the tag's image, in pixels, at which the detector still decodes it. Estimate. */
    static final double MIN_SIDE_PIXELS = 24.0;
    /** Tags seen at more than this angle from their normal are not decoded. Estimate. */
    static final double MAX_VIEW_ANGLE_DEGREES = 80.0;
    /** A tag whose corners come closer than this to the edge of the image is cut off. */
    static final double EDGE_MARGIN_PIXELS = 2.0;
    static final double MIN_DEPTH_METERS = 0.05;
    /** Surfaces this much nearer than the tag's own are the surface it is stuck on, not something in front of it. */
    static final double MOUNT_MARGIN_METERS = 0.03;
    /** Rays aimed at the corners are pulled this far towards the tag's centre, to stay on the tag's face. */
    private static final double CORNER_PULL = 0.15;

    private static final double MIN_VIEW_COSINE = StrictMath.cos(StrictMath.toRadians(MAX_VIEW_ANGLE_DEGREES));

    /** Why a tag is or is not seen, in the order the tests are made. */
    enum Visibility {
        VISIBLE,
        BEHIND_CAMERA,
        FACING_AWAY,
        OUTSIDE_IMAGE,
        TOO_SMALL
    }

    /**
     * @param fieldCorners x, y, z of each corner in the field
     * @param pixels       where the corners fall in an ideal image: u, v of each corner
     * @param centre       the middle of the tag in the field
     * @param distance     from the camera to the middle of the tag
     * @param shortestSide the shortest side of the tag's image, in pixels
     */
    record Look(
            Visibility visibility, double[] fieldCorners, double[] pixels, Vec3 centre, double distance, double shortestSide) {}

    private TagSight() {}

    static Look look(Pinhole camera, Rigid cameraInField, SceneTag tag, Rigid fieldFromHost) {
        Rigid fieldFromTag = fieldFromHost.compose(tag.inHost());
        double[] corners = tag.fieldCorners(fieldFromHost);
        Vec3 centre = fieldFromTag.translation();
        Vec3 eye = cameraInField.translation();
        Rigid toCamera = cameraInField.inverse();

        double[] pixels = new double[8];
        for (int i = 0; i < 4; i++) {
            Vec3 inCamera = toCamera.apply(new Vec3(corners[3 * i], corners[3 * i + 1], corners[3 * i + 2]));
            if (inCamera.z() < MIN_DEPTH_METERS) {
                return new Look(Visibility.BEHIND_CAMERA, corners, pixels, centre, eye.minus(centre).length(), 0.0);
            }
            double[] pixel = camera.project(inCamera);
            pixels[2 * i] = pixel[0];
            pixels[2 * i + 1] = pixel[1];
        }
        Vec3 toEye = eye.minus(centre);
        double distance = toEye.length();
        double shortest = shortestSide(pixels);
        double facing = fieldFromTag.rotation().column(2).dot(toEye) / distance;
        if (facing < MIN_VIEW_COSINE) {
            return new Look(Visibility.FACING_AWAY, corners, pixels, centre, distance, shortest);
        }
        for (int i = 0; i < 4; i++) {
            if (!camera.contains(pixels[2 * i], pixels[2 * i + 1], EDGE_MARGIN_PIXELS)) {
                return new Look(Visibility.OUTSIDE_IMAGE, corners, pixels, centre, distance, shortest);
            }
        }
        if (shortest < MIN_SIDE_PIXELS) {
            return new Look(Visibility.TOO_SMALL, corners, pixels, centre, distance, shortest);
        }
        return new Look(Visibility.VISIBLE, corners, pixels, centre, distance, shortest);
    }

    /**
     * Whether something stands between the camera and the tag: a ray towards the middle of the tag and each of its
     * corners meets a surface that is nearer than the tag by more than the thickness of its mount. The tag's own host
     * body never blocks it, and neither does the surface a fixed tag is stuck on.
     */
    static boolean blocked(RayView rays, Vec3 eye, SceneTag tag, Look look) {
        for (int i = -1; i < 4; i++) {
            Vec3 target = look.centre();
            if (i >= 0) {
                Vec3 corner = new Vec3(look.fieldCorners()[3 * i], look.fieldCorners()[3 * i + 1], look.fieldCorners()[3 * i + 2]);
                target = target.plus(corner.minus(target).times(1.0 - CORNER_PULL));
            }
            Vec3 along = target.minus(eye);
            double distance = along.length();
            SurfaceHit hit = rays.cast(eye, along.times(1.0 / distance), distance + MOUNT_MARGIN_METERS);
            if (hit == null) {
                continue;
            }
            boolean ownBody = tag.hostEntityId() >= 0 && hit.entityId() == tag.hostEntityId();
            if (!ownBody && hit.distanceMeters() < distance - MOUNT_MARGIN_METERS) {
                return true;
            }
        }
        return false;
    }

    private static double shortestSide(double[] pixels) {
        double shortest = Double.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            int next = (i + 1) % 4;
            shortest = Math.min(shortest, Math.sqrt(
                    (pixels[2 * next] - pixels[2 * i]) * (pixels[2 * next] - pixels[2 * i])
                            + (pixels[2 * next + 1] - pixels[2 * i + 1]) * (pixels[2 * next + 1] - pixels[2 * i + 1])));
        }
        return shortest;
    }
}
