package dev.ftcsim.game.biobuzz;

import static dev.ftcsim.game.biobuzz.Solids.in;

import java.util.ArrayList;
import java.util.List;

/**
 * One FLOWER: three rings and four pipes standing on a wall of the field. All numbers are the CAD's, in inches, in a
 * frame whose origin is the axis of the pipes on the tiles, with {@code a} towards the perimeter wall, {@code b}
 * along the wall and {@code c} up. The simplifications are the segmented rings (sixteen straight pieces each) and
 * the left-out screws and brackets under the wall.
 *
 * <p>The lower ring holds one POLLEN in a pocket 0.35 in deep, so that a POLLEN at rest cannot leave unless it is pushed
 * over the lip. Between it and the middle ring, 3.55 in higher, is the retrieval opening: a POLLEN (2.8 in) goes
 * through it, a NECTAR (3.6 in) that sits in the pocket does not, since it would have to rise over the lip and
 * then meet the middle ring. The middle ring has a seat narrower than a NECTAR, so a NECTAR dropped in from the top rests
 * in the ring and a POLLEN goes on down into the pocket.
 */
final class FlowerModel {

    static final double LIP_TOP = 0.35;
    /**
     * The CAD puts the underside of the middle ring at 3.90 in, a retrieval opening of 3.55 in. The model uses 3.86 in, an
     * opening of 3.51 in, so that a 3.62 in NECTAR cannot squeeze through within the contact tolerance of the engine.
     */
    static final double MIDDLE_RING_BOTTOM = 3.86;
    static final double TOP_RING_BOTTOM = 20.25;
    static final double TOP_RING_TOP = 21.40;

    /** A ball is in the scoring volume when it reaches into the space between the middle and the top ring. */
    static final double SCORING_BOTTOM_M = in(MIDDLE_RING_BOTTOM);
    static final double SCORING_TOP_M = in(TOP_RING_BOTTOM);
    /** A ball is in the column when its centre is closer than this to the axis. */
    static final double COLUMN_RADIUS_M = in(2.44);

    /** Heights of the four POLLEN each FLOWER starts with, one on the other in the column. */
    static final double[] START_HEIGHTS = {1.40, 4.29, 7.18, 10.07};

    private static final int SEGMENTS = 16;
    private static final int RING_RGB = 0x37474F;
    private static final int TOP_RING_RGB = 0xEF6C00;
    private static final int PIPE_RGB = 0x2E7D32;
    private static final int POST_RGB = 0x9E9E9E;
    private static final int BACKSTOP_RGB = 0x7B1FA2;
    private static final int BRACKET_RGB = 0x263238;

    private FlowerModel() {}

    /** The four FLOWERS: axis position in inches, and the unit direction from the axis to the wall behind it. */
    record Site(double axisX, double axisY, double wallX, double wallY) {}

    static final List<Site> SITES = List.of(
            new Site(-23.39, 68.04, 0, 1),
            new Site(-68.04, -23.39, -1, 0),
            new Site(68.04, 23.39, 1, 0),
            new Site(23.39, -68.04, 0, -1));

    /** All the solids of the FLOWER on {@code site}; every one of them collides. */
    static List<Solid> solids(int number, Site site) {
        List<Solid> solids = new ArrayList<>();
        String name = "FLOWER " + number + " ";
        ring(solids, name + "bottom ring", site, 1.395, 2.435, 0, LIP_TOP, RING_RGB);
        ring(solids, name + "middle ring seat", site, 1.70, 2.55, MIDDLE_RING_BOTTOM, 4.12, RING_RGB);
        ring(solids, name + "middle ring", site, 1.93, 2.55, 4.12, 5.25, RING_RGB);
        ring(solids, name + "top ring", site, 2.0, 2.5, TOP_RING_BOTTOM, TOP_RING_TOP, TOP_RING_RGB);
        for (int i = 0; i < 2; i++) {
            double b = i == 0 ? -1.255 : 1.255;
            double[] p = place(site, 1.82, b);
            solids.add(Solids.box(name + "post " + i, p[0], p[1], (LIP_TOP + 4.10) / 2, 1.28, 1.28, 4.10 - LIP_TOP, POST_RGB, true));
        }
        for (int i = 0; i < 4; i++) {
            double[] p = place(site, i < 2 ? 1.725 : -1.725, i % 2 == 0 ? -1.725 : 1.725);
            solids.add(Solids.upright(name + "pipe " + i, p[0], p[1], 4.25, 0.525, 17.0, PIPE_RGB, true));
        }
        double[] backstop = place(site, 2.125, 0);
        solids.add(plate(name + "backstop middle", site, backstop, 0.0, 1.0, 2.2, 0.55, 22.40, 22.65, BACKSTOP_RGB));
        for (int i = 0; i < 2; i++) {
            double side = i == 0 ? -1 : 1;
            double[] arm = place(site, 1.45, side * 1.8);
            solids.add(plate(
                    name + "backstop arm " + i, site, arm, -1.4, side * 1.45, 2.0, 0.55, 22.40, 22.65, BACKSTOP_RGB));
        }
        ring(solids, name + "wall bracket ring", site, 2.05, 2.55, 11.44, 11.84, BRACKET_RGB);
        double[] bracket = place(site, 3.0, 0);
        solids.add(plate(name + "wall bracket", site, bracket, 1.0, 0.0, 1.5, 6.0, 11.44, 11.84, BRACKET_RGB));
        return solids;
    }

    /** Where a point {@code a} towards the wall and {@code b} along it from the axis lies, in field inches. */
    private static double[] place(Site site, double a, double b) {
        return new double[] {
            site.axisX() + a * site.wallX() - b * site.wallY(),
            site.axisY() + a * site.wallY() + b * site.wallX()};
    }

    /**
     * A flat bar or plate whose length runs along the direction {@code (dirA, dirB)} of the FLOWER frame, centred at
     * {@code center}, between the heights {@code z0} and {@code z1}.
     */
    private static Solid plate(
            String name, Site site, double[] center, double dirA, double dirB, double length, double width,
            double z0, double z1, int rgb) {
        double fieldX = dirA * site.wallX() - dirB * site.wallY();
        double fieldY = dirA * site.wallY() + dirB * site.wallX();
        return Solids.yawedBox(name, center[0], center[1], (z0 + z1) / 2, length, width, z1 - z0, StrictMath.atan2(fieldY, fieldX), rgb, true);
    }

    private static void ring(
            List<Solid> out, String name, Site site, double inner, double outer, double z0, double z1, int rgb) {
        double middle = 0.5 * (inner + outer);
        double width = 2.0 * outer * StrictMath.tan(Math.PI / SEGMENTS);
        for (int k = 0; k < SEGMENTS; k++) {
            double phi = 2.0 * Math.PI * k / SEGMENTS;
            out.add(Solids.yawedBox(
                    name + " " + k,
                    site.axisX() + middle * StrictMath.cos(phi), site.axisY() + middle * StrictMath.sin(phi), (z0 + z1) / 2,
                    outer - inner, width, z1 - z0, phi, rgb, true));
        }
    }
}
