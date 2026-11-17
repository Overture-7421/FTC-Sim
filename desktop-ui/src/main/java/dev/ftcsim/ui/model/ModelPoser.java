package dev.ftcsim.ui.model;

import dev.ftcsim.contracts.snapshot.RobotJoint;
import java.util.List;

/**
 * Where each group of a {@link RobotModel} is, from the chassis's pose, the turret's pose and the joint values the
 * snapshot carries. Column-major 4x4 matrices in the layout OpenGL expects, reused between calls.
 */
public final class ModelPoser {
    private final RobotModel model;
    private final float[] matrices;
    private final float[] scratch = new float[16];
    private final float[] shift = new float[16];
    private final float[] turn = new float[16];

    public ModelPoser(RobotModel model) {
        this.model = model;
        this.matrices = new float[16 * model.groups().size()];
    }

    /**
     * Poses every group.
     *
     * @param chassis the chassis body: x, y, z and the rotation as x, y, z, w
     * @param turret the turret body in the same layout, or null when the robot has none, which leaves the turret facing forward
     * @param joints each {@link RobotJoint}'s value by ordinal, 0 where the snapshot has none
     * @return one matrix per group at 16 floats each, valid until the next call
     */
    public float[] pose(float[] chassis, float[] turret, float[] joints) {
        List<RobotModel.Group> groups = model.groups();
        for (int g = 0; g < groups.size(); g++) {
            RobotModel.Group group = groups.get(g);
            int out = 16 * g;
            if (group.parent() < 0) {
                fromPose(chassis, matrices, out);
                continue;
            }
            RobotModel.Group parent = groups.get(group.parent());
            if (RobotModel.TURRET_JOINT.equals(group.jointName())) {
                if (turret != null) {
                    fromPose(turret, matrices, out);
                    translate(0f, 0f, group.pivot()[2], shift);
                    multiply(matrices, out, shift, scratch);
                    System.arraycopy(scratch, 0, matrices, out, 16);
                } else {
                    placeOn(group, parent, 0f, g);
                }
                continue;
            }
            float angle = 0f;
            if (group.joint() != null) {
                angle = group.sign() * (joints[group.joint().ordinal()] - group.rest());
            }
            placeOn(group, parent, angle, g);
        }
        return matrices;
    }

    private void placeOn(RobotModel.Group group, RobotModel.Group parent, float angle, int index) {
        int out = 16 * index;
        int parentOffset = 16 * group.parent();
        translate(
                group.pivot()[0] - parent.pivot()[0], group.pivot()[1] - parent.pivot()[1], group.pivot()[2] - parent.pivot()[2], shift);
        multiply(matrices, parentOffset, shift, scratch);
        if (angle == 0f) {
            System.arraycopy(scratch, 0, matrices, out, 16);
            return;
        }
        rotation(group.axis(), angle, turn);
        multiply(scratch, 0, turn, matrices, out);
    }

    private static void fromPose(float[] pose, float[] out, int offset) {
        float x = pose[3];
        float y = pose[4];
        float z = pose[5];
        float w = pose[6];
        out[offset] = 1f - 2f * (y * y + z * z);
        out[offset + 1] = 2f * (x * y + z * w);
        out[offset + 2] = 2f * (x * z - y * w);
        out[offset + 3] = 0f;
        out[offset + 4] = 2f * (x * y - z * w);
        out[offset + 5] = 1f - 2f * (x * x + z * z);
        out[offset + 6] = 2f * (y * z + x * w);
        out[offset + 7] = 0f;
        out[offset + 8] = 2f * (x * z + y * w);
        out[offset + 9] = 2f * (y * z - x * w);
        out[offset + 10] = 1f - 2f * (x * x + y * y);
        out[offset + 11] = 0f;
        out[offset + 12] = pose[0];
        out[offset + 13] = pose[1];
        out[offset + 14] = pose[2];
        out[offset + 15] = 1f;
    }

    private static void translate(float x, float y, float z, float[] out) {
        java.util.Arrays.fill(out, 0f);
        out[0] = 1f;
        out[5] = 1f;
        out[10] = 1f;
        out[15] = 1f;
        out[12] = x;
        out[13] = y;
        out[14] = z;
    }

    /** Rodrigues' rotation of {@code angle} radians about the unit {@code axis}. */
    private static void rotation(float[] axis, float angle, float[] out) {
        float c = (float) Math.cos(angle);
        float s = (float) Math.sin(angle);
        float t = 1f - c;
        float x = axis[0];
        float y = axis[1];
        float z = axis[2];
        out[0] = t * x * x + c;
        out[1] = t * x * y + s * z;
        out[2] = t * x * z - s * y;
        out[3] = 0f;
        out[4] = t * x * y - s * z;
        out[5] = t * y * y + c;
        out[6] = t * y * z + s * x;
        out[7] = 0f;
        out[8] = t * x * z + s * y;
        out[9] = t * y * z - s * x;
        out[10] = t * z * z + c;
        out[11] = 0f;
        out[12] = 0f;
        out[13] = 0f;
        out[14] = 0f;
        out[15] = 1f;
    }

    /** {@code out = a[aOffset..] * b}; {@code out} must not alias {@code a}'s part. */
    private static void multiply(float[] a, int aOffset, float[] b, float[] out) {
        multiply(a, aOffset, b, out, 0);
    }

    private static void multiply(float[] a, int aOffset, float[] b, float[] out, int outOffset) {
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                float sum = 0f;
                for (int k = 0; k < 4; k++) {
                    sum += a[aOffset + k * 4 + row] * b[column * 4 + k];
                }
                out[outOffset + column * 4 + row] = sum;
            }
        }
    }
}
