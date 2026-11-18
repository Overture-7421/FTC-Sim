package dev.ftcsim.ui.scene;

import dev.ftcsim.contracts.snapshot.WorldSnapshot;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single-producer, many-consumer hand-off of world snapshots with latest-wins semantics. It holds at
 * most the two newest snapshots, so a slow reader never makes the producer queue or block.
 */
public final class SnapshotMailbox {

    /**
     * The two newest snapshots and the {@link System#nanoTime()} at which each was published.
     * {@code previous} equals {@code current} until a second snapshot arrives.
     */
    public record Latest(WorldSnapshot previous, WorldSnapshot current, long previousNanos, long currentNanos) {}

    private final AtomicReference<Latest> latest = new AtomicReference<>();

    /**
     * Makes {@code snapshot} the newest. A snapshot of the step already held (a republished one) is ignored.
     *
     * @return whether the world the snapshot shows differs from the one before it; a world at rest publishes the same
     *     bodies over and over, and nothing needs to be drawn again for that
     */
    public boolean publish(WorldSnapshot snapshot, long publishedNanos) {
        Latest old = latest.get();
        if (old != null && old.current().stepIndex() == snapshot.stepIndex()
                && old.current().simTimeNanos() == snapshot.simTimeNanos()) {
            return false;
        }
        Latest next = old == null
                ? new Latest(snapshot, snapshot, publishedNanos, publishedNanos)
                : new Latest(old.current(), snapshot, old.currentNanos(), publishedNanos);
        latest.setRelease(next);
        return old == null || !old.current().bodies().equals(snapshot.bodies())
                || !old.current().visibleTagIds().equals(snapshot.visibleTagIds());
    }

    /** Returns the newest pair, or {@code null} before the first publish. */
    public Latest latest() {
        return latest.getAcquire();
    }

    /** Forgets every snapshot, for when a new session replaces the old one. */
    public void clear() {
        latest.setRelease(null);
    }
}
