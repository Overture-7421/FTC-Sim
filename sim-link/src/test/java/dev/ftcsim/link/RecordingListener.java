package dev.ftcsim.link;

import dev.ftcsim.contracts.session.Catalogue;
import dev.ftcsim.contracts.session.SessionInfo;
import dev.ftcsim.contracts.session.SessionStatus;
import dev.ftcsim.contracts.session.SimulatorLink;
import dev.ftcsim.contracts.snapshot.WorldSnapshot;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Records what a link tells its listener, and from which threads. */
final class RecordingListener implements SimulatorLink.Listener {

    /** A command that the worker refused, to be told apart from the text of a failure. */
    record Refusal(String message) {}

    /** What came, in order: a {@link SessionInfo}, {@link WorldSnapshot}, {@link SessionStatus}, {@link Catalogue}, a {@link Refusal} or the text of a failure. */
    final BlockingQueue<Object> received = new LinkedBlockingQueue<>();

    final Set<String> threadNames = ConcurrentHashMap.newKeySet();

    /** Runs at the start of every call, in the thread of the link, so that a test can make the listener slow. */
    volatile Runnable beforeEachCall = () -> {};

    private void enter(Object what) {
        threadNames.add(Thread.currentThread().getName());
        beforeEachCall.run();
        received.add(what);
    }

    @Override
    public void opened(SessionInfo info) {
        enter(info);
    }

    @Override
    public void snapshot(WorldSnapshot snapshot) {
        enter(snapshot);
    }

    @Override
    public void status(SessionStatus status) {
        enter(status);
    }

    @Override
    public void failed(String message) {
        enter(message);
    }

    @Override
    public void refused(String message) {
        enter(new Refusal(message));
    }

    @Override
    public void catalogue(Catalogue catalogue) {
        enter(catalogue);
    }

    /** The next thing that arrives, within ten seconds, which must be of the given type. */
    <T> T next(Class<T> type) throws InterruptedException {
        Object next = received.poll(10, TimeUnit.SECONDS);
        if (next == null) {
            throw new AssertionError("nothing arrived for 10 s, expected a " + type.getSimpleName());
        }
        if (!type.isInstance(next)) {
            throw new AssertionError("expected a " + type.getSimpleName() + " but got " + next);
        }
        return type.cast(next);
    }
}
