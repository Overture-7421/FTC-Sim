package dev.ftcsim.hardware.camera;

import dev.ftcsim.contracts.world.BodyPose;
import dev.ftcsim.contracts.world.Vec3;

/**
 * The recent past of the bodies a camera cares about, one sample per physics step, in a ring of fixed size. A frame is
 * processed some milliseconds after it was captured, so the camera must know where it and the tags' hosts were then.
 * Between two samples the position is interpolated linearly and the orientation by normalised linear interpolation of
 * the quaternions; outside the recorded span the nearest sample stands in.
 */
final class PoseHistory {

    private final int bodies;
    private final long[] times;
    private final BodyPose[][] poses;
    private int count;
    private int newest = -1;

    /** @param bodies how many bodies each sample holds; the chassis is body 0 */
    PoseHistory(int capacity, int bodies) {
        this.bodies = bodies;
        this.times = new long[capacity];
        this.poses = new BodyPose[capacity][];
    }

    /** Records where every body was at {@code nanos}; a second record at the same instant replaces the first. */
    void record(long nanos, BodyPose[] bodyPoses) {
        if (bodyPoses.length != bodies) {
            throw new IllegalArgumentException("expected " + bodies + " poses, got " + bodyPoses.length);
        }
        if (count > 0 && times[newest] == nanos) {
            poses[newest] = bodyPoses.clone();
            return;
        }
        newest = (newest + 1) % times.length;
        times[newest] = nanos;
        poses[newest] = bodyPoses.clone();
        count = Math.min(count + 1, times.length);
    }

    boolean isEmpty() {
        return count == 0;
    }

    long newestNanos() {
        return times[newest];
    }

    /** The pose of a body at the instant, as the transform from the body's frame to the field. */
    Rigid at(int body, long nanos) {
        int upper = -1;
        int lower = -1;
        for (int age = 0; age < count; age++) {
            int index = Math.floorMod(newest - age, times.length);
            if (times[index] <= nanos) {
                lower = index;
                break;
            }
            upper = index;
        }
        if (lower < 0) {
            return rigid(poses[Math.floorMod(newest - count + 1, times.length)][body]);
        }
        if (upper < 0) {
            return rigid(poses[lower][body]);
        }
        double alpha = (double) (nanos - times[lower]) / (double) (times[upper] - times[lower]);
        BodyPose a = poses[lower][body];
        BodyPose b = poses[upper][body];
        double sign = a.qw() * b.qw() + a.qx() * b.qx() + a.qy() * b.qy() + a.qz() * b.qz() < 0 ? -1.0 : 1.0;
        Mat3 rotation = Mat3.fromQuaternion(
                a.qw() + alpha * (sign * b.qw() - a.qw()),
                a.qx() + alpha * (sign * b.qx() - a.qx()),
                a.qy() + alpha * (sign * b.qy() - a.qy()),
                a.qz() + alpha * (sign * b.qz() - a.qz()));
        return new Rigid(rotation, new Vec3(
                a.x() + alpha * (b.x() - a.x()), a.y() + alpha * (b.y() - a.y()), a.z() + alpha * (b.z() - a.z())));
    }

    private static Rigid rigid(BodyPose pose) {
        return new Rigid(Mat3.fromQuaternion(pose.qw(), pose.qx(), pose.qy(), pose.qz()), new Vec3(pose.x(), pose.y(), pose.z()));
    }
}
