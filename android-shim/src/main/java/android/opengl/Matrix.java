package android.opengl;

/**
 * The part of Android's {@code android.opengl.Matrix} that the FTC SDK's {@code OpenGLMatrix} calls, which is what the
 * rotation arithmetic of {@code Orientation.getRotationMatrix} runs on, and with it every
 * {@code RevHubOrientationOnRobot} a program builds to tell the IMU driver how its hub is mounted. Matrices are 4x4,
 * stored in column-major order in an array of floats from an offset, so that the element of a row and a column is at
 * {@code offset + 4 * column + row}; angles are in degrees. Written from the definitions of these matrices.
 *
 * <p>It lives next to the SDK adapter because the clean-room Android shim is not open for change; the class name is
 * the one the SDK imports, so moving it there is only moving the file.
 */
public final class Matrix {

    private static final int SIZE = 16;

    private Matrix() {}

    /** Makes {@code sm} the identity matrix. */
    public static void setIdentityM(float[] sm, int smOffset) {
        for (int i = 0; i < SIZE; i++) {
            sm[smOffset + i] = i % 5 == 0 ? 1.0f : 0.0f;
        }
    }

    /** Makes {@code rm} the rotation by {@code a} degrees about the axis {@code (x, y, z)}, which need not be a unit vector. */
    public static void setRotateM(float[] rm, int rmOffset, float a, float x, float y, float z) {
        double radians = StrictMath.toRadians(a);
        float c = (float) StrictMath.cos(radians);
        float s = (float) StrictMath.sin(radians);
        float length = (float) StrictMath.sqrt(x * x + y * y + z * z);
        float ux = x / length;
        float uy = y / length;
        float uz = z / length;
        float k = 1.0f - c;
        // Rodrigues' formula: R = c I + s [u]x + (1 - c) u u^T, written by columns.
        setColumn(rm, rmOffset, 0, c + ux * ux * k, uz * s + uy * ux * k, -uy * s + uz * ux * k);
        setColumn(rm, rmOffset, 1, -uz * s + ux * uy * k, c + uy * uy * k, ux * s + uz * uy * k);
        setColumn(rm, rmOffset, 2, uy * s + ux * uz * k, -ux * s + uy * uz * k, c + uz * uz * k);
        rm[rmOffset + 12] = 0.0f;
        rm[rmOffset + 13] = 0.0f;
        rm[rmOffset + 14] = 0.0f;
        rm[rmOffset + 15] = 1.0f;
    }

    private static void setColumn(float[] m, int offset, int column, float x, float y, float z) {
        m[offset + 4 * column] = x;
        m[offset + 4 * column + 1] = y;
        m[offset + 4 * column + 2] = z;
        m[offset + 4 * column + 3] = 0.0f;
    }

    /** Sets {@code result} to {@code lhs * rhs}; the arrays may be the same one. */
    public static void multiplyMM(float[] result, int resultOffset, float[] lhs, int lhsOffset, float[] rhs, int rhsOffset) {
        float[] product = new float[SIZE];
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                float sum = 0.0f;
                for (int k = 0; k < 4; k++) {
                    sum += lhs[lhsOffset + 4 * k + row] * rhs[rhsOffset + 4 * column + k];
                }
                product[4 * column + row] = sum;
            }
        }
        System.arraycopy(product, 0, result, resultOffset, SIZE);
    }

    /** Rotates {@code m} in place: {@code m = m * R}. */
    public static void rotateM(float[] m, int mOffset, float a, float x, float y, float z) {
        rotateM(m, mOffset, m, mOffset, a, x, y, z);
    }

    /** Sets {@code rm} to {@code m * R}, for the rotation R by {@code a} degrees about {@code (x, y, z)}. */
    public static void rotateM(float[] rm, int rmOffset, float[] m, int mOffset, float a, float x, float y, float z) {
        float[] rotation = new float[SIZE];
        setRotateM(rotation, 0, a, x, y, z);
        multiplyMM(rm, rmOffset, m, mOffset, rotation, 0);
    }

    /** Scales {@code m} in place: {@code m = m * S}. */
    public static void scaleM(float[] m, int mOffset, float x, float y, float z) {
        scaleM(m, mOffset, m, mOffset, x, y, z);
    }

    /** Sets {@code sm} to {@code m * S}, for the scaling S by {@code (x, y, z)}. */
    public static void scaleM(float[] sm, int smOffset, float[] m, int mOffset, float x, float y, float z) {
        float[] factors = {x, y, z, 1.0f};
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                sm[smOffset + 4 * column + row] = m[mOffset + 4 * column + row] * factors[column];
            }
        }
    }

    /** Translates {@code m} in place: {@code m = m * T}. */
    public static void translateM(float[] m, int mOffset, float x, float y, float z) {
        translateM(m, mOffset, m, mOffset, x, y, z);
    }

    /** Sets {@code tm} to {@code m * T}, for the translation T by {@code (x, y, z)}. */
    public static void translateM(float[] tm, int tmOffset, float[] m, int mOffset, float x, float y, float z) {
        float[] last = new float[4];
        for (int row = 0; row < 4; row++) {
            last[row] = m[mOffset + 12 + row] + m[mOffset + row] * x + m[mOffset + 4 + row] * y + m[mOffset + 8 + row] * z;
        }
        System.arraycopy(m, mOffset, tm, tmOffset, 12);
        System.arraycopy(last, 0, tm, tmOffset + 12, 4);
    }

    /** Sets {@code mInv} to the inverse of {@code m}; false, with {@code mInv} untouched, if {@code m} has none. */
    public static boolean invertM(float[] mInv, int mInvOffset, float[] m, int mOffset) {
        // Gauss-Jordan elimination with partial pivoting on [m | I], in double precision.
        double[][] a = new double[4][8];
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 4; column++) {
                a[row][column] = m[mOffset + 4 * column + row];
            }
            a[row][4 + row] = 1.0;
        }
        for (int column = 0; column < 4; column++) {
            int pivot = column;
            for (int row = column + 1; row < 4; row++) {
                if (Math.abs(a[row][column]) > Math.abs(a[pivot][column])) {
                    pivot = row;
                }
            }
            if (a[pivot][column] == 0.0) {
                return false;
            }
            double[] swap = a[column];
            a[column] = a[pivot];
            a[pivot] = swap;
            double scale = 1.0 / a[column][column];
            for (int k = 0; k < 8; k++) {
                a[column][k] *= scale;
            }
            for (int row = 0; row < 4; row++) {
                if (row != column) {
                    double factor = a[row][column];
                    for (int k = 0; k < 8; k++) {
                        a[row][k] -= factor * a[column][k];
                    }
                }
            }
        }
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 4; column++) {
                mInv[mInvOffset + 4 * column + row] = (float) a[row][4 + column];
            }
        }
        return true;
    }
}
