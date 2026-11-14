package dev.ftcsim.hardware.camera;

import dev.ftcsim.contracts.world.Vec3;

/** A camera, a mount and two tags like the DECODE goal's, for tests of the geometry. */
final class TestScene {

    static final Pinhole CAMERA = Pinhole.fromFieldOfView(640, 480, 54.5, 42);
    static final CameraMount MOUNT = new CameraMount(0.15, 0.02, 0.20, 3, 12, 1);
    static final double TAG_SIZE = 0.1651;

    private TestScene() {}

    /** The blue goal's tag: on the goal panel, facing 54 degrees counter-clockwise from +X. */
    static SceneTag blueGoal() {
        return facing(20, -1.4826, -1.4133, 0.7493, StrictMath.toRadians(54.0));
    }

    /** A tag on the same wall's neighbour, facing another way, so that the two do not share a plane. */
    static SceneTag redGoal() {
        return facing(24, -1.4826, 1.4133, 0.7493, StrictMath.toRadians(-54.0));
    }

    /** A tag standing upright at the place, its face looking along {@code facingYaw}. */
    static SceneTag facing(int id, double x, double y, double z, double facingYaw) {
        Mat3 upright = Mat3.columns(new Vec3(0, 1, 0), new Vec3(0, 0, 1), new Vec3(1, 0, 0));
        return new SceneTag(id, TAG_SIZE, -1, new Rigid(Mat3.rotZ(facingYaw).times(upright), new Vec3(x, y, z)));
    }

    static Rigid robot(double x, double y, double z, double yaw) {
        return new Rigid(Mat3.rotZ(yaw), new Vec3(x, y, z));
    }

    static Rigid cameraInField(Rigid robot) {
        return robot.compose(MOUNT.cameraInRobot());
    }

    /** The robot a metre or so in front of the tag, looking at it, {@code distance} metres from it along its normal. */
    static Rigid robotFacing(SceneTag tag, double distance, double sideways, double extraYaw) {
        Vec3 normal = tag.inHost().rotation().column(2);
        Vec3 right = tag.inHost().rotation().column(0);
        Vec3 position = tag.inHost().translation().plus(normal.times(distance)).plus(right.times(sideways));
        double yaw = StrictMath.atan2(-normal.y(), -normal.x()) + extraYaw;
        return robot(position.x(), position.y(), 0.1, yaw);
    }

    /** The four corners where an ideal camera on the robot sees them. */
    static double[] pixels(Rigid robot, SceneTag tag) {
        Rigid fieldToCamera = cameraInField(robot).inverse();
        double[] corners = tag.fieldCorners(Rigid.IDENTITY);
        double[] pixels = new double[8];
        for (int i = 0; i < 4; i++) {
            Vec3 inCamera = fieldToCamera.apply(new Vec3(corners[3 * i], corners[3 * i + 1], corners[3 * i + 2]));
            double[] pixel = CAMERA.project(inCamera);
            pixels[2 * i] = pixel[0];
            pixels[2 * i + 1] = pixel[1];
        }
        return pixels;
    }

    static PoseSolver.Observation observe(Rigid robot, SceneTag tag) {
        return new PoseSolver.Observation(tag.fieldCorners(Rigid.IDENTITY), pixels(robot, tag));
    }

    static PoseSolver solver() {
        return new PoseSolver(CAMERA, MOUNT.cameraInRobot());
    }

    static double distance(Vec3 a, Vec3 b) {
        return a.minus(b).length();
    }
}
