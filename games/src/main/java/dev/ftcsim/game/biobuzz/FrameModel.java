package dev.ftcsim.game.biobuzz;

import java.util.ArrayList;
import java.util.List;

/**
 * The aluminium frame in the middle of the field that carries the two HIVE pivots: two A-frames that lean outwards,
 * joined at the top by a bar, with a panel on each side. Boxes in inches in the field frame, taken from the CAD by
 * fitting an oriented box to each part; the plastic feet and the strips under the tiles are left out.
 */
final class FrameModel {

    private static final int ALUMINIUM = 0xB0BEC5;
    private static final int STEEL = 0x546E7A;
    private static final int DARK = 0x37474F;
    private static final int PANEL = 0xFFC107;

    private FrameModel() {}

    static List<Solid> solids() {
        List<Solid> solids = new ArrayList<>();
        for (int sx : new int[] {-1, 1}) {
            for (int sy : new int[] {-1, 1}) {
                double[] u = Solids.unit(-sx * 0.394, -sy * 0.245, 0.886);
                double[] v = Solids.unit(0.1 * sx, -0.969 * sy, -0.224);
                solids.add(Solids.orientedBox(
                        "frame leg " + sx + sy, sx * 9.53, sy * 18.27, 20.81, u, v, Solids.cross(u, v),
                        22.88, 0.58, 0.50, ALUMINIUM, true));
            }
        }
        for (int sy : new int[] {-1, 1}) {
            solids.add(Solids.box("frame foot bar " + sy, 0, sy * 23.74, 1.075, 38.94, 1.98, 2.15, STEEL, true));
            solids.add(Solids.box("frame top corner " + sy, 0, sy * 12.135, 41.89, 3.06, 3.25, 4.80, DARK, true));
        }
        solids.add(Solids.box("frame top bar", 0, 0, 41.45, 1.0, 24.0, 1.0, ALUMINIUM, true));
        for (int side : new int[] {1, -1}) {
            double[] along = {0, 1, 0};
            double[] slope = Solids.unit(-0.407 * side, 0, 0.914);
            solids.add(Solids.orientedBox(
                    "frame panel " + side, 2.92 * side, 0, 37.05, along, slope, Solids.cross(along, slope),
                    15.08, 3.23, 0.15, PANEL, true));
        }
        return solids;
    }
}
