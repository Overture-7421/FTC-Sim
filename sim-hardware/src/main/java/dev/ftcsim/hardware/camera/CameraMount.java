package dev.ftcsim.hardware.camera;

import dev.ftcsim.contracts.world.Vec3;

/**
 * Where a camera is on the robot, in the robot's frame: X forward, Y left, Z up, origin at the chassis reference,
 * metres. The angles say where it looks: yaw turns it left, pitch tilts it up, and roll turns it clockwise as seen
 * from behind it (the horizon in its image turns anticlockwise); all in degrees, applied in that order about the
 * camera's own axes, so a camera that is yawed and pitched still rolls about the line it looks along.
 */
record CameraMount(double x, double y, double z, double yawDegrees, double pitchDegrees, double rollDegrees) {

    /**
     * The optical axes of an unrotated camera in the robot's frame: image right is the robot's right, image down is
     * down, and the camera looks forward. Columns are the camera's X (right), Y (down) and Z (forward).
     */
    private static final Mat3 LOOKS_FORWARD = new Mat3(0, 0, 1, -1, 0, 0, 0, -1, 0);

    /** The camera's pose in the robot's frame: it carries camera coordinates (X right, Y down, Z forward) to robot ones. */
    Rigid cameraInRobot() {
        Mat3 turn = Mat3.fromYawPitchRoll(
                StrictMath.toRadians(yawDegrees), -StrictMath.toRadians(pitchDegrees), StrictMath.toRadians(rollDegrees));
        return new Rigid(turn.times(LOOKS_FORWARD), new Vec3(x, y, z));
    }
}
