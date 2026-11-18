package dev.ftcsim.contracts.snapshot;

import java.util.List;

/**
 * Immutable view of the simulated world at one fixed physics step. Bodies are ordered by ascending
 * {@link BodyState#entityId()} so equal worlds produce equal snapshots.
 *
 * @param stepIndex    number of fixed steps taken since the session started
 * @param simTimeNanos simulated time, derived from the step index rather than any wall clock
 * @param visibleTagIds AprilTag ids in the Limelight's latest completed frame
 */
public record WorldSnapshot(long stepIndex, long simTimeNanos, List<BodyState> bodies, List<Integer> visibleTagIds) {

    public WorldSnapshot(long stepIndex, long simTimeNanos, List<BodyState> bodies) {
        this(stepIndex, simTimeNanos, bodies, List.of());
    }

    public WorldSnapshot {
        bodies = List.copyOf(bodies);
        visibleTagIds = List.copyOf(visibleTagIds);
        for (int i = 1; i < bodies.size(); i++) {
            if (bodies.get(i - 1).entityId() >= bodies.get(i).entityId()) {
                throw new IllegalArgumentException(
                        "bodies must be strictly ordered by entityId, but "
                                + bodies.get(i - 1).entityId() + " precedes " + bodies.get(i).entityId());
            }
        }
    }
}
