package dev.ftcsim.ui.scene;

import dev.ftcsim.contracts.snapshot.BodyState;
import java.util.Arrays;
import java.util.List;

/**
 * Blends the two newest snapshots into render poses without allocating. Bodies are matched by entity
 * id with a merge walk, which the snapshot ordering guarantee makes linear.
 */
public final class PoseInterpolator {
    /** Floats stored per body: x, y, z, qx, qy, qz, qw. */
    public static final int POSE_STRIDE = 7;

    /** Reusable output; arrays grow on demand and only the first {@code count} entries are valid. */
    public static final class Poses {
        public int count;
        public int[] entityIds = new int[0];
        public int[] kinds = new int[0];
        public float[] values = new float[0];
        public List<Integer> visibleTagIds = List.of();

        void ensureCapacity(int bodies) {
            if (entityIds.length < bodies) {
                entityIds = new int[bodies];
                kinds = new int[bodies];
                values = new float[bodies * POSE_STRIDE];
            }
        }

        /** Index of the first body of the given kind, or -1. */
        public int indexOfKind(int kind) {
            for (int i = 0; i < count; i++) {
                if (kinds[i] == kind) {
                    return i;
                }
            }
            return -1;
        }

        /** Whether both hold the same bodies at the same poses. */
        public boolean sameAs(Poses other) {
            if (count != other.count || !visibleTagIds.equals(other.visibleTagIds)) {
                return false;
            }
            for (int i = 0; i < count; i++) {
                if (kinds[i] != other.kinds[i] || entityIds[i] != other.entityIds[i]) {
                    return false;
                }
            }
            return Arrays.equals(values, 0, count * POSE_STRIDE, other.values, 0, count * POSE_STRIDE);
        }

        /** Makes this a copy of {@code other}. */
        public void copyFrom(Poses other) {
            ensureCapacity(other.count);
            count = other.count;
            visibleTagIds = other.visibleTagIds;
            System.arraycopy(other.entityIds, 0, entityIds, 0, count);
            System.arraycopy(other.kinds, 0, kinds, 0, count);
            System.arraycopy(other.values, 0, values, 0, count * POSE_STRIDE);
        }

        /** Heading of body {@code index} about the Z axis, counter-clockwise from +X, in radians. */
        public double yaw(int index) {
            int offset = index * POSE_STRIDE;
            double qx = values[offset + 3];
            double qy = values[offset + 4];
            double qz = values[offset + 5];
            double qw = values[offset + 6];
            return Math.atan2(2.0 * (qw * qz + qx * qy), 1.0 - 2.0 * (qy * qy + qz * qz));
        }
    }

    /** A snapshot pair whose arrival spacing exceeds its simulated spacing by more than this is a pause, not slow motion. */
    private static final long MAX_SLOWDOWN = 8;

    private PoseInterpolator() {}

    /**
     * Interpolation factor for a render at {@code renderNanos}. The picture runs one snapshot interval behind the
     * newest snapshot: it moves from the previous snapshot to the current one over the time that separated their
     * arrival, so there is always a newer sample to blend toward whatever rate the snapshots come at. The result is
     * clamped, which holds the last pose instead of extrapolating when the producer stalls, and a pair that is not
     * consecutive in simulated time (a restart, a long pause) is shown as it is rather than blended.
     */
    public static double alpha(SnapshotMailbox.Latest latest, long renderNanos) {
        long simSpan = latest.current().simTimeNanos() - latest.previous().simTimeNanos();
        if (simSpan <= 0 || latest.current().stepIndex() <= latest.previous().stepIndex()) {
            return 1.0;
        }
        long arrivalSpan = latest.currentNanos() - latest.previousNanos();
        long span = Math.min(arrivalSpan, simSpan * MAX_SLOWDOWN);
        if (span <= 0) {
            return 1.0;
        }
        double a = (double) (renderNanos - latest.currentNanos()) / span;
        return Math.min(1.0, Math.max(0.0, a));
    }

    public static void interpolate(SnapshotMailbox.Latest latest, double alpha, Poses out) {
        List<BodyState> from = latest.previous().bodies();
        List<BodyState> to = latest.current().bodies();
        out.ensureCapacity(to.size());
        float t = (float) alpha;
        int fromIndex = 0;
        int count = 0;
        for (int i = 0; i < to.size(); i++) {
            BodyState target = to.get(i);
            while (fromIndex < from.size() && from.get(fromIndex).entityId() < target.entityId()) {
                fromIndex++;
            }
            BodyState source = fromIndex < from.size() && from.get(fromIndex).entityId() == target.entityId()
                    ? from.get(fromIndex)
                    : target;
            out.entityIds[count] = target.entityId();
            out.kinds[count] = target.kind();
            blend(source, target, t, out.values, count * POSE_STRIDE);
            count++;
        }
        out.count = count;
        out.visibleTagIds = latest.current().visibleTagIds();
    }

    private static void blend(BodyState a, BodyState b, float t, float[] out, int offset) {
        out[offset] = a.x() + (b.x() - a.x()) * t;
        out[offset + 1] = a.y() + (b.y() - a.y()) * t;
        out[offset + 2] = a.z() + (b.z() - a.z()) * t;
        float dot = a.qx() * b.qx() + a.qy() * b.qy() + a.qz() * b.qz() + a.qw() * b.qw();
        float sign = dot < 0f ? -1f : 1f;
        float qx = a.qx() + (sign * b.qx() - a.qx()) * t;
        float qy = a.qy() + (sign * b.qy() - a.qy()) * t;
        float qz = a.qz() + (sign * b.qz() - a.qz()) * t;
        float qw = a.qw() + (sign * b.qw() - a.qw()) * t;
        float inverseLength = (float) (1.0 / Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw));
        out[offset + 3] = qx * inverseLength;
        out[offset + 4] = qy * inverseLength;
        out[offset + 5] = qz * inverseLength;
        out[offset + 6] = qw * inverseLength;
    }
}
