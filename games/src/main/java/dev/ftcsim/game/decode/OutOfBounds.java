package dev.ftcsim.game.decode;

import dev.ftcsim.contracts.snapshot.BodyState;
import dev.ftcsim.contracts.snapshot.WorldSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Finds the pieces that have left the field and stayed away. Field staff return such pieces to a human player at the
 * earliest safe moment, so after a short while the field takes them out of the world and hands them back.
 */
final class OutOfBounds {

    private static final double MARGIN_METERS = 0.30;
    private static final int PATIENCE_STEPS = 360;
    private static final double BOTTOMLESS = -0.5;

    private final Map<Integer, Integer> stepsAway = new TreeMap<>();

    /** Call once per step; returns the ids of the pieces to take back, ascending. */
    List<Integer> update(WorldSnapshot snapshot) {
        List<Integer> returned = new ArrayList<>();
        Map<Integer, Integer> now = new TreeMap<>();
        double limit = Dimensions.WALL + MARGIN_METERS;
        for (BodyState body : snapshot.bodies()) {
            if (!Kinds.isArtifact(body.kind())) {
                continue;
            }
            boolean away = Math.abs(body.x()) > limit || Math.abs(body.y()) > limit || body.z() < BOTTOMLESS;
            if (!away) {
                continue;
            }
            int steps = stepsAway.getOrDefault(body.entityId(), 0) + 1;
            if (steps >= PATIENCE_STEPS || body.z() < BOTTOMLESS) {
                returned.add(body.entityId());
            } else {
                now.put(body.entityId(), steps);
            }
        }
        stepsAway.clear();
        stepsAway.putAll(now);
        return returned;
    }
}
