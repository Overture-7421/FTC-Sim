package dev.ftcsim.game.decode;

import dev.ftcsim.contracts.snapshot.BodyState;
import dev.ftcsim.contracts.snapshot.WorldSnapshot;
import dev.ftcsim.field.EntityIds;
import java.util.Map;
import java.util.TreeMap;

/**
 * Says whether everything that matters has stood still for a quarter of a second, by comparing where the bodies are
 * now with where they were at the previous sample. Positions only: a ball turning on the spot is at rest for scoring.
 */
final class RestDetector {

    private static final long SAMPLE_STEPS = 60;
    private static final double MOVED_METERS = 0.0015;

    private final boolean includeRobot;
    private Map<Integer, double[]> previous = new TreeMap<>();
    private boolean atRest;

    RestDetector(boolean includeRobot) {
        this.includeRobot = includeRobot;
    }

    /** Call once per step. */
    boolean update(WorldSnapshot snapshot) {
        if (snapshot.stepIndex() % SAMPLE_STEPS != 0) {
            return atRest;
        }
        Map<Integer, double[]> now = new TreeMap<>();
        boolean still = true;
        for (BodyState body : snapshot.bodies()) {
            if (!Kinds.isArtifact(body.kind()) && !(includeRobot && body.entityId() == EntityIds.CHASSIS)) {
                continue;
            }
            double[] before = previous.get(body.entityId());
            double[] here = {body.x(), body.y(), body.z()};
            now.put(body.entityId(), here);
            if (before == null || distance(before, here) > MOVED_METERS) {
                still = false;
            }
        }
        still &= now.size() == previous.size();
        previous = now;
        atRest = still;
        return atRest;
    }

    private static double distance(double[] a, double[] b) {
        double dx = a[0] - b[0];
        double dy = a[1] - b[1];
        double dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
