package dev.ftcsim.hardware.camera;

import dev.ftcsim.contracts.field.FieldTag;
import dev.ftcsim.contracts.field.Pose3;
import dev.ftcsim.contracts.world.Vec3;

/**
 * An AprilTag as the camera model works with it: the size of its printed square and where it is, in the frame of
 * the body it is stuck on (the field itself when {@code hostEntityId} is -1). The tag's frame has +X to the right
 * and +Y up as seen from the front, and +Z out of the face.
 */
record SceneTag(int id, double sizeMeters, int hostEntityId, Rigid inHost) {

    static SceneTag of(FieldTag tag) {
        Pose3 pose = tag.local();
        return new SceneTag(
                tag.id(),
                tag.sizeMeters(),
                tag.hostEntityId(),
                new Rigid(Mat3.fromQuaternion(pose.qw(), pose.qx(), pose.qy(), pose.qz()), new Vec3(pose.x(), pose.y(), pose.z())));
    }

    boolean isFixed() {
        return hostEntityId == -1;
    }

    /** The corners of the printed square in the tag's own frame, counter-clockwise as seen from the front. */
    static Vec3[] corners(double sizeMeters) {
        double h = 0.5 * sizeMeters;
        return new Vec3[] {new Vec3(-h, -h, 0), new Vec3(h, -h, 0), new Vec3(h, h, 0), new Vec3(-h, h, 0)};
    }

    /** The corners in the field, given where the tag's host is: x, y, z of corner 0, then of corner 1, and so on. */
    double[] fieldCorners(Rigid fieldFromHost) {
        Rigid fieldFromTag = fieldFromHost.compose(inHost);
        double[] corners = new double[12];
        Vec3[] local = corners(sizeMeters);
        for (int i = 0; i < 4; i++) {
            Vec3 point = fieldFromTag.apply(local[i]);
            corners[3 * i] = point.x();
            corners[3 * i + 1] = point.y();
            corners[3 * i + 2] = point.z();
        }
        return corners;
    }
}
