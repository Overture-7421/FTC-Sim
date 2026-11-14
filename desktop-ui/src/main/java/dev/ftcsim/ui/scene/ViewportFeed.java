package dev.ftcsim.ui.scene;

import dev.ftcsim.contracts.snapshot.WorldSnapshot;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The hand-off from whoever receives a session's events to the render thread: the scene to draw and the latest
 * snapshots. Both sides are lock-free, a slow renderer never holds anything up, and the renderer is told when the world
 * changed, so that it can sleep while nothing moves.
 */
public final class ViewportFeed {
    private final SnapshotMailbox mailbox = new SnapshotMailbox();
    private final AtomicReference<SceneModel> scene = new AtomicReference<>();
    private final AtomicLong received = new AtomicLong();
    private volatile Runnable changed = () -> {};

    /** Called after every publish and scene change, from the thread that made it; keep it cheap. */
    public void onChange(Runnable listener) {
        changed = listener;
    }

    public void publish(WorldSnapshot snapshot) {
        received.incrementAndGet();
        if (mailbox.publish(snapshot, System.nanoTime())) {
            changed.run();
        }
    }

    /** How many snapshots have been published, whether or not they showed anything new. */
    public long snapshotCount() {
        return received.get();
    }

    /** Replaces what is drawn; snapshots of the previous session are forgotten. */
    public void show(SceneModel newScene) {
        mailbox.clear();
        scene.set(newScene);
        changed.run();
    }

    public SnapshotMailbox mailbox() {
        return mailbox;
    }

    /** The scene to draw, or null before a session has opened. */
    public SceneModel scene() {
        return scene.get();
    }
}
