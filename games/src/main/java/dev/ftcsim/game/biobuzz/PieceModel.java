package dev.ftcsim.game.biobuzz;

import static dev.ftcsim.game.biobuzz.Solids.in;

import dev.ftcsim.physics.PieceSpec;

/**
 * What a POLLEN or a NECTAR is made of. Diameters come from the official CAD (2.80 in and 3.62 in; the manual says
 * about 2.8 in and 3.6 in), the masses from the AndyMark listing (0.055 lb and 0.091 lb), which makes NECTAR 1.65 times
 * as heavy as POLLEN: the ratio the HIVE calibration implies (seven POLLEN against three NECTAR and two POLLEN,
 * which weigh the same). Friction, restitution and rolling resistance of the hollow polyethylene balls are estimates.
 */
final class PieceModel {

    static final double POLLEN_RADIUS_M = in(1.4);
    static final double NECTAR_RADIUS_M = in(1.81);

    // A ball that rolls out on the tiles slows at 0.4 m/s^2 with this coefficient, and stops a metre from a 1 m/s start.
    private static final float ROLLING_RESISTANCE = 0.06f;
    private static final float SPIN_DAMPING = 0.05f;

    static final PieceSpec POLLEN =
            new PieceSpec((float) POLLEN_RADIUS_M, 0.02495f, 0.5f, 0.3f, SPIN_DAMPING, ROLLING_RESISTANCE);
    static final PieceSpec NECTAR =
            new PieceSpec((float) NECTAR_RADIUS_M, 0.04128f, 0.5f, 0.3f, SPIN_DAMPING, ROLLING_RESISTANCE);

    private PieceModel() {}

    static PieceSpec specOf(int kind) {
        return kind == BiobuzzIds.KIND_POLLEN ? POLLEN : NECTAR;
    }

    static double radiusOf(int kind) {
        return kind == BiobuzzIds.KIND_POLLEN ? POLLEN_RADIUS_M : NECTAR_RADIUS_M;
    }
}
