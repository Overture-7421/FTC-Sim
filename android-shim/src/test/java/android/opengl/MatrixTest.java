package android.opengl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The matrix functions the SDK's OpenGLMatrix calls, against results worked out by hand. */
class MatrixTest {

    private static final float TOLERANCE = 1e-6f;

    /** The point {@code (x, y, z)} carried through a column-major 4x4 matrix, as the first three components. */
    private static float[] apply(float[] m, float x, float y, float z) {
        float[] result = new float[3];
        for (int row = 0; row < 3; row++) {
            result[row] = m[row] * x + m[4 + row] * y + m[8 + row] * z + m[12 + row];
        }
        return result;
    }

    private static float[] identity() {
        float[] m = new float[16];
        Matrix.setIdentityM(m, 0);
        return m;
    }

    @Test
    void theIdentityHasOnesOnTheDiagonalOnly() {
        float[] m = new float[18];
        java.util.Arrays.fill(m, 7.0f);

        Matrix.setIdentityM(m, 1);

        assertEquals(7.0f, m[0]);
        assertEquals(7.0f, m[17]);
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 4; column++) {
                assertEquals(row == column ? 1.0f : 0.0f, m[1 + 4 * column + row]);
            }
        }
    }

    @Test
    void aQuarterTurnAboutZTurnsXIntoYAndYIntoMinusX() {
        float[] m = new float[16];

        Matrix.setRotateM(m, 0, 90.0f, 0, 0, 1);

        assertArrayEquals(new float[] {0, 1, 0}, apply(m, 1, 0, 0), TOLERANCE);
        assertArrayEquals(new float[] {-1, 0, 0}, apply(m, 0, 1, 0), TOLERANCE);
        assertArrayEquals(new float[] {0, 0, 1}, apply(m, 0, 0, 1), TOLERANCE);
    }

    @Test
    void aQuarterTurnAboutXTurnsYIntoZAndAboutYTurnsZIntoX() {
        float[] aboutX = new float[16];
        float[] aboutY = new float[16];

        Matrix.setRotateM(aboutX, 0, 90.0f, 1, 0, 0);
        Matrix.setRotateM(aboutY, 0, 90.0f, 0, 1, 0);

        assertArrayEquals(new float[] {0, 0, 1}, apply(aboutX, 0, 1, 0), TOLERANCE);
        assertArrayEquals(new float[] {1, 0, 0}, apply(aboutY, 0, 0, 1), TOLERANCE);
    }

    @Test
    void aHalfTurnAboutADiagonalAxisSwapsXAndYAndTheAxisNeedNotBeAUnitVector() {
        float[] m = new float[16];

        Matrix.setRotateM(m, 0, 180.0f, 3, 3, 0);

        assertArrayEquals(new float[] {0, 1, 0}, apply(m, 1, 0, 0), TOLERANCE);
        assertArrayEquals(new float[] {1, 0, 0}, apply(m, 0, 1, 0), TOLERANCE);
        assertArrayEquals(new float[] {0, 0, -1}, apply(m, 0, 0, 1), TOLERANCE);
    }

    @Test
    void rotatingAMatrixAppliesTheRotationFirstToTheVectorsItIsGiven() {
        // m * R: the rotation acts on the point before the translation of m does.
        float[] m = identity();
        Matrix.translateM(m, 0, 10, 0, 0);

        Matrix.rotateM(m, 0, 90.0f, 0, 0, 1);

        assertArrayEquals(new float[] {10, 1, 0}, apply(m, 1, 0, 0), TOLERANCE);
    }

    @Test
    void theFormsWithASeparateResultLeaveTheSourceAlone() {
        float[] source = identity();
        Matrix.translateM(source, 0, 1, 2, 3);
        float[] original = source.clone();
        float[] rotated = new float[17];
        float[] scaled = new float[16];
        float[] translated = new float[16];

        Matrix.rotateM(rotated, 1, source, 0, 90.0f, 0, 0, 1);
        Matrix.scaleM(scaled, 0, source, 0, 2, 3, 4);
        Matrix.translateM(translated, 0, source, 0, 1, 1, 1);

        assertArrayEquals(original, source);
        assertArrayEquals(new float[] {1, 3, 3}, apply(java.util.Arrays.copyOfRange(rotated, 1, 17), 1, 0, 0), TOLERANCE);
        assertArrayEquals(new float[] {3, 2 + 3, 3 + 4}, apply(scaled, 1, 1, 1), TOLERANCE);
        assertArrayEquals(new float[] {3, 4, 5}, apply(translated, 1, 1, 1), TOLERANCE);
    }

    @Test
    void scalingStretchesEachAxisAndTranslatingMovesTheOrigin() {
        float[] m = identity();

        Matrix.scaleM(m, 0, 2, 3, 4);
        Matrix.translateM(m, 0, 1, 1, 1);

        // m * T then acts on a point as scale(translate(p)).
        assertArrayEquals(new float[] {4, 6, 8}, apply(m, 1, 1, 1), TOLERANCE);
    }

    @Test
    void multiplyingAppliesTheRightMatrixFirst() {
        float[] turn = new float[16];
        Matrix.setRotateM(turn, 0, 90.0f, 0, 0, 1);
        float[] slide = identity();
        Matrix.translateM(slide, 0, 5, 0, 0);
        float[] both = new float[16];

        Matrix.multiplyMM(both, 0, slide, 0, turn, 0);

        assertArrayEquals(new float[] {5, 1, 0}, apply(both, 1, 0, 0), TOLERANCE);
    }

    @Test
    void theInverseOfARotationAndTranslationUndoesThemAndASingularMatrixHasNone() {
        float[] m = new float[16];
        Matrix.setRotateM(m, 0, 33.0f, 1, 2, 3);
        Matrix.translateM(m, 0, 4, -5, 6);
        float[] inverse = new float[16];

        assertTrue(Matrix.invertM(inverse, 0, m, 0));

        float[] product = new float[16];
        Matrix.multiplyMM(product, 0, m, 0, inverse, 0);
        assertArrayEquals(identity(), product, 1e-5f);

        float[] singular = identity();
        singular[5] = 0.0f;
        float[] untouched = new float[16];
        assertFalse(Matrix.invertM(untouched, 0, singular, 0));
        assertArrayEquals(new float[16], untouched);
    }
}
