package dev.ftcsim.game.decode;

import dev.ftcsim.physics.Vector3;
import java.util.ArrayList;
import java.util.List;

/**
 * The tape on the floor: launch lines, zones, spike marks. It is drawn and read by colour sensors but is too thin to be
 * felt, so it is not part of the physics world.
 */
final class DecodeMarkings {

    private static final int WHITE = 0xF2F2F2;
    private static final double TAPE_HALF_WIDTH = 0.0127;
    private static final double HEIGHT = 0.0006;
    private static final double SPIKE_HALF_LENGTH = 0.127;

    private DecodeMarkings() {}

    static List<Part> all() {
        List<Part> tapes = new ArrayList<>();
        double w = Dimensions.WALL;
        tapes.add(line("launch-line-back-minus-y", -w, -w, 0, 0, WHITE));
        tapes.add(line("launch-line-back-plus-y", -w, w, 0, 0, WHITE));
        tapes.add(line("launch-line-front-minus-y", 1.1957, 0, 1.7867, -0.59, WHITE));
        tapes.add(line("launch-line-front-plus-y", 1.1957, 0, 1.7867, 0.59, WHITE));
        for (int side : new int[] {-1, 1}) {
            // The goal of the blue alliance is on the -Y side, and its other zones are on the opposite side.
            String name = side < 0 ? "blue" : "red";
            int color = side < 0 ? DecodeSolids.BLUE : DecodeSolids.RED;
            double tx = Dimensions.frontTangent().x() * Zones.DEPOT_HALF_LENGTH;
            double ty = Dimensions.frontTangent().y() * Zones.DEPOT_HALF_LENGTH;
            tapes.add(line(name + "-depot", Zones.DEPOT_X - tx, -side * (Zones.DEPOT_Y - ty),
                    Zones.DEPOT_X + tx, -side * (Zones.DEPOT_Y + ty), WHITE));
            for (int strip : new int[] {-1, 1}) {
                tapes.add(Part.bounds(name + "-gate-zone-" + strip,
                        strip * 0.0103 + (strip < 0 ? -0.0254 : 0), strip * 0.0103 + (strip > 0 ? 0.0254 : 0),
                        side * 1.2073 + (side < 0 ? -0.254 : 0), side * 1.2073 + (side > 0 ? 0.254 : 0),
                        0, HEIGHT, color));
            }
            tapes.add(secretTunnel(-side, color));
            tapes.addAll(base(-side, color));
            tapes.addAll(loadingZone(-side));
            for (int mark = 0; mark < Layout.SPIKE_X.length; mark++) {
                tapes.add(new Part(
                        "spike-mark-" + name + "-" + mark, new Vector3(Layout.SPIKE_X[mark], side * 1.2, 0.5 * HEIGHT),
                        new Vector3(TAPE_HALF_WIDTH, SPIKE_HALF_LENGTH, 0.5 * HEIGHT), Orientation.IDENTITY, WHITE));
            }
        }
        return tapes;
    }

    private static Part line(String name, double x1, double y1, double x2, double y2, int rgb) {
        double length = StrictMath.hypot(x2 - x1, y2 - y1);
        return new Part(
                name,
                new Vector3(0.5 * (x1 + x2), 0.5 * (y1 + y2), 0.5 * HEIGHT),
                new Vector3(0.5 * length, TAPE_HALF_WIDTH, 0.5 * HEIGHT),
                Orientation.yaw(StrictMath.atan2(y2 - y1, x2 - x1)),
                rgb);
    }

    private static Part secretTunnel(int side, int rgb) {
        double inner = side * 1.6327;
        double outer = side * 1.6581;
        return Part.bounds(
                "secret-tunnel-" + side, 0.0103, 1.1914, Math.min(inner, outer), Math.max(inner, outer), 0, HEIGHT, rgb);
    }

    // Four strips that outline the 18 in square of the base, on the side given.
    private static List<Part> base(int side, int rgb) {
        double minX = 0.7295;
        double maxX = 1.1867;
        double minY = side > 0 ? 0.6104 : -1.0660;
        double maxY = side > 0 ? 1.0676 : -0.6088;
        double t = 2 * TAPE_HALF_WIDTH;
        String name = "base-" + side + "-";
        return List.of(
                Part.bounds(name + "near", minX, maxX, minY, minY + t, 0, HEIGHT, rgb),
                Part.bounds(name + "far", minX, maxX, maxY - t, maxY, 0, HEIGHT, rgb),
                Part.bounds(name + "left", minX, minX + t, minY, maxY, 0, HEIGHT, rgb),
                Part.bounds(name + "right", maxX - t, maxX, minY, maxY, 0, HEIGHT, rgb));
    }

    // The two inner edges of the square corner in which a human player puts pieces.
    private static List<Part> loadingZone(int side) {
        double inner = 1.2073;
        double outer = 1.7883;
        double t = 2 * TAPE_HALF_WIDTH;
        double lo = Math.min(side * inner, side * outer);
        double hi = Math.max(side * inner, side * outer);
        double edgeLo = side > 0 ? side * inner : side * inner - t;
        double edgeHi = side > 0 ? side * inner + t : side * inner;
        String name = "loading-zone-" + side + "-";
        return List.of(
                Part.bounds(name + "across", inner, outer, edgeLo, edgeHi, 0, HEIGHT, WHITE),
                Part.bounds(name + "along", inner, inner + t, lo, hi, 0, HEIGHT, WHITE));
    }
}
