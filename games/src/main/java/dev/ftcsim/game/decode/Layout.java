package dev.ftcsim.game.decode;

import dev.ftcsim.contracts.field.Alliance;
import dev.ftcsim.field.EntityIds;
import dev.ftcsim.physics.Vector3;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;

/**
 * Where the 36 ARTIFACTS start and what colour each is: 3 on each of the 6 SPIKE MARKS and 3 in each LOADING ZONE are
 * on the field, and 6 wait in each alliance area. A piece keeps its id and colour for the whole run. The seed picks the
 * pattern the OBELISK shows, how the alliance areas' pieces are ordered and the few millimetres by which a person
 * placing the pieces would miss the tape.
 */
final class Layout {

    /** A piece that starts on the field. */
    record Placement(int id, int kind, Vector3 position) {}

    static final int PIECE_COUNT = 36;

    // The marks are at these x, listed from the audience side to the goals; their balls are listed from the middle of
    // the field out to the perimeter and read GPP, PGP and PPG that way.
    static final double[] SPIKE_X = {0.8961, 0.2961, -0.3040};
    private static final double[] SPIKE_Y = {1.073, 1.200, 1.327};
    private static final String[] SPIKE_COLORS = {"GPP", "PGP", "PPG"};
    private static final double[] LOADING_X = {1.461, 1.588, 1.715};
    private static final double LOADING_Y = 1.7215;
    private static final String LOADING_COLORS = "PGP";
    private static final String ALLIANCE_AREA_COLORS = "PPPPGG";
    private static final double MISS = 0.004;

    private final Map<Integer, Integer> kinds = new TreeMap<>();
    private final List<Placement> onField = new ArrayList<>();
    private final Map<Alliance, List<Integer>> allianceAreas = new TreeMap<>();
    private final Motif motif;

    Layout(long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        motif = Motif.values()[random.nextInt(Motif.values().length)];
        int id = EntityIds.FIELD_BASE;
        for (int side : new int[] {1, -1}) {
            for (int mark = 0; mark < SPIKE_X.length; mark++) {
                for (int ball = 0; ball < SPIKE_Y.length; ball++) {
                    place(id++, SPIKE_COLORS[mark].charAt(ball), SPIKE_X[mark], side * SPIKE_Y[ball], random);
                }
            }
        }
        for (int side : new int[] {1, -1}) {
            for (int ball = 0; ball < LOADING_X.length; ball++) {
                place(id++, LOADING_COLORS.charAt(ball), LOADING_X[ball], side * LOADING_Y, random);
            }
        }
        for (Alliance alliance : new Alliance[] {Alliance.BLUE, Alliance.RED}) {
            char[] colors = ALLIANCE_AREA_COLORS.toCharArray();
            for (int i = colors.length - 1; i > 0; i--) {
                int j = random.nextInt(i + 1);
                char swap = colors[i];
                colors[i] = colors[j];
                colors[j] = swap;
            }
            List<Integer> ids = new ArrayList<>();
            for (char color : colors) {
                kinds.put(id, kindOf(color));
                ids.add(id++);
            }
            allianceAreas.put(alliance, ids);
        }
    }

    private void place(int id, char color, double x, double y, SplittableRandom random) {
        double dx = (random.nextDouble() - 0.5) * 2 * MISS;
        double dy = (random.nextDouble() - 0.5) * 2 * MISS;
        int kind = kindOf(color);
        kinds.put(id, kind);
        onField.add(new Placement(id, kind, new Vector3(x + dx, y + dy, Dimensions.ARTIFACT_RADIUS + 0.0005)));
    }

    private static int kindOf(char color) {
        return color == 'G' ? Kinds.GREEN : Kinds.PURPLE;
    }

    Motif motif() {
        return motif;
    }

    /** The pieces that start on the field, in ascending id order. */
    List<Placement> onField() {
        return onField;
    }

    /** The ids of the pieces in an alliance's area, in ascending order. */
    List<Integer> allianceArea(Alliance alliance) {
        return allianceAreas.get(alliance);
    }

    int kindOf(int id) {
        return kinds.get(id);
    }
}
