package dev.ftcsim.ui.render;

import java.util.Arrays;

/** Column-major 4x4 float matrices in the layout OpenGL expects, with only what the viewport needs. */
final class Mat4 {
    private Mat4() {}

    /** Right-handed perspective projection with a [-1, 1] clip-space depth range. */
    static void perspective(float fovYRadians, float aspect, float near, float far, float[] out) {
        float f = (float) (1.0 / Math.tan(fovYRadians / 2.0));
        Arrays.fill(out, 0, 16, 0f);
        out[0] = f / aspect;
        out[5] = f;
        out[10] = (far + near) / (near - far);
        out[11] = -1f;
        out[14] = 2f * far * near / (near - far);
    }

    /** View matrix for a Z-up world; the viewing direction must not be parallel to Z. */
    static void lookAt(
            float eyeX, float eyeY, float eyeZ, float targetX, float targetY, float targetZ, float[] out) {
        float fx = targetX - eyeX;
        float fy = targetY - eyeY;
        float fz = targetZ - eyeZ;
        float forwardLength = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
        fx /= forwardLength;
        fy /= forwardLength;
        fz /= forwardLength;
        float sx = fy;
        float sy = -fx;
        float sideLength = (float) Math.sqrt(sx * sx + sy * sy);
        sx /= sideLength;
        sy /= sideLength;
        float ux = sy * fz;
        float uy = -sx * fz;
        float uz = sx * fy - sy * fx;
        out[0] = sx;
        out[1] = ux;
        out[2] = -fx;
        out[3] = 0f;
        out[4] = sy;
        out[5] = uy;
        out[6] = -fy;
        out[7] = 0f;
        out[8] = 0f;
        out[9] = uz;
        out[10] = -fz;
        out[11] = 0f;
        out[12] = -(sx * eyeX + sy * eyeY);
        out[13] = -(ux * eyeX + uy * eyeY + uz * eyeZ);
        out[14] = fx * eyeX + fy * eyeY + fz * eyeZ;
        out[15] = 1f;
    }

    /** {@code out = a * b}; {@code out} must not alias either input. */
    static void multiply(float[] a, float[] b, float[] out) {
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                float sum = 0f;
                for (int k = 0; k < 4; k++) {
                    sum += a[k * 4 + row] * b[column * 4 + k];
                }
                out[column * 4 + row] = sum;
            }
        }
    }
}
