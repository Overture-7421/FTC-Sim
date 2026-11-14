package dev.ftcsim.hardware.camera;

/** The few dense linear-algebra operations the pose solvers need, on small row-major matrices. */
final class Linear {

    private static final double SINGULAR = 1e-300;

    private Linear() {}

    /**
     * Solves {@code a x = b} by Gaussian elimination with partial pivoting.
     *
     * @param augmented the matrix with the right-hand side as its last column; destroyed
     * @return the solution, or null if the matrix is singular
     */
    static double[] solve(double[][] augmented) {
        int n = augmented.length;
        for (int column = 0; column < n; column++) {
            int pivot = column;
            for (int row = column + 1; row < n; row++) {
                if (Math.abs(augmented[row][column]) > Math.abs(augmented[pivot][column])) {
                    pivot = row;
                }
            }
            if (Math.abs(augmented[pivot][column]) < SINGULAR) {
                return null;
            }
            double[] swap = augmented[pivot];
            augmented[pivot] = augmented[column];
            augmented[column] = swap;
            for (int row = column + 1; row < n; row++) {
                double factor = augmented[row][column] / augmented[column][column];
                for (int k = column; k <= n; k++) {
                    augmented[row][k] -= factor * augmented[column][k];
                }
            }
        }
        double[] x = new double[n];
        for (int row = n - 1; row >= 0; row--) {
            double sum = augmented[row][n];
            for (int k = row + 1; k < n; k++) {
                sum -= augmented[row][k] * x[k];
            }
            x[row] = sum / augmented[row][row];
        }
        return x;
    }

    /** The inverse of a square matrix by Gauss-Jordan elimination, or null if it is singular. */
    static double[][] invert(double[][] matrix) {
        int n = matrix.length;
        double[][] work = new double[n][2 * n];
        for (int row = 0; row < n; row++) {
            System.arraycopy(matrix[row], 0, work[row], 0, n);
            work[row][n + row] = 1.0;
        }
        for (int column = 0; column < n; column++) {
            int pivot = column;
            for (int row = column + 1; row < n; row++) {
                if (Math.abs(work[row][column]) > Math.abs(work[pivot][column])) {
                    pivot = row;
                }
            }
            if (Math.abs(work[pivot][column]) < SINGULAR) {
                return null;
            }
            double[] swap = work[pivot];
            work[pivot] = work[column];
            work[column] = swap;
            double scale = work[column][column];
            for (int k = 0; k < 2 * n; k++) {
                work[column][k] /= scale;
            }
            for (int row = 0; row < n; row++) {
                if (row != column) {
                    double factor = work[row][column];
                    for (int k = 0; k < 2 * n; k++) {
                        work[row][k] -= factor * work[column][k];
                    }
                }
            }
        }
        double[][] inverse = new double[n][n];
        for (int row = 0; row < n; row++) {
            System.arraycopy(work[row], n, inverse[row], 0, n);
        }
        return inverse;
    }
}
