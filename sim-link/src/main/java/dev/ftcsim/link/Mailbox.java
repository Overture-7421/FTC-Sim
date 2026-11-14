package dev.ftcsim.link;

import dev.ftcsim.contracts.session.SimulatorLink;
import dev.ftcsim.link.wire.Event;
import java.util.ArrayDeque;
import java.util.Objects;

/**
 * Where the reader thread leaves what the worker said, and callers leave the listeners they add, until the
 * dispatcher thread gets to them. Everything leaves in the order it arrived, except that a snapshot and a status only
 * matter while they are the latest: a newer one replaces an older one that was never delivered, and keeps the place
 * of the newer. So a listener that is slow never builds a backlog of worlds, and never sees a world from before a
 * session it was told about.
 */
final class Mailbox {

    /** What the dispatcher does next. */
    sealed interface Item {

        /** Tells every listener registered so far. */
        record Deliver(Event event) implements Item {}

        /** Takes on a listener, which hears what comes after this item. */
        record Register(SimulatorLink.Listener listener) implements Item {}
    }

    private record Entry(long sequence, Item item) {}

    private final Object lock = new Object();
    private final ArrayDeque<Entry> reliable = new ArrayDeque<>();
    private Entry snapshot;
    private Entry status;
    private long sequence;
    private boolean finishing;
    private boolean closed;

    void offer(Event event) {
        synchronized (lock) {
            if (closed || finishing) {
                return;
            }
            Entry entry = new Entry(++sequence, new Item.Deliver(event));
            switch (event) {
                case Event.Snapshot _ -> snapshot = entry;
                case Event.Status _ -> status = entry;
                case Event.Opened _ -> {
                    snapshot = null;
                    status = null;
                    reliable.addLast(entry);
                }
                case Event.Failed _ -> reliable.addLast(entry);
                case Event.Catalogue _ -> reliable.addLast(entry);
                case Event.Refused _ -> reliable.addLast(entry);
            }
            lock.notifyAll();
        }
    }

    /** Has the dispatcher take on a listener, after everything offered so far. Ignored once the link is over. */
    void register(SimulatorLink.Listener listener) {
        Objects.requireNonNull(listener, "listener");
        synchronized (lock) {
            if (closed || finishing) {
                return;
            }
            reliable.addLast(new Entry(++sequence, new Item.Register(listener)));
            lock.notifyAll();
        }
    }

    /** The link is dead: what waits for delivery is stale, so only the failure is delivered, and then nothing. */
    void finishWith(Event.Failed failure) {
        synchronized (lock) {
            if (closed || finishing) {
                return;
            }
            snapshot = null;
            status = null;
            reliable.addLast(new Entry(++sequence, new Item.Deliver(failure)));
            finishing = true;
            lock.notifyAll();
        }
    }

    /** Drops everything that waits; {@link #take} returns null from now on. */
    void close() {
        synchronized (lock) {
            closed = true;
            reliable.clear();
            snapshot = null;
            status = null;
            lock.notifyAll();
        }
    }

    /** The next thing to do, waiting for one; null once the mailbox is closed or has delivered its failure. */
    Item take() throws InterruptedException {
        synchronized (lock) {
            while (!closed) {
                Entry next = earliest();
                if (next != null) {
                    if (next == snapshot) {
                        snapshot = null;
                    } else if (next == status) {
                        status = null;
                    } else {
                        reliable.removeFirst();
                    }
                    return next.item();
                }
                if (finishing) {
                    return null;
                }
                lock.wait();
            }
            return null;
        }
    }

    private Entry earliest() {
        Entry best = reliable.peekFirst();
        for (Entry latest : new Entry[] {snapshot, status}) {
            if (latest != null && (best == null || latest.sequence() < best.sequence())) {
                best = latest;
            }
        }
        return best;
    }
}
