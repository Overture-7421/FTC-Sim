package dev.ftcsim.acceptance;

import dev.ftcsim.contracts.robot.RobotPhase;
import dev.ftcsim.contracts.session.Catalogue;
import dev.ftcsim.contracts.session.MatchState;
import dev.ftcsim.contracts.session.SessionInfo;
import dev.ftcsim.contracts.session.SessionStatus;
import dev.ftcsim.contracts.session.SimulatorLink;
import dev.ftcsim.contracts.snapshot.BodyState;
import dev.ftcsim.contracts.snapshot.WorldSnapshot;
import dev.ftcsim.field.EntityIds;
import dev.ftcsim.link.RemoteSimulatorLink;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * What the UI would be told over a link, as the UI would hold it: the latest of everything and what happened in which
 * order, with a patient wait for a condition that, when it runs out, says what the UI heard and how the worker's log ends.
 * It listens as soon as it is made.
 */
final class LinkRecorder implements SimulatorLink.Listener {
    final List<SessionInfo> opened = Collections.synchronizedList(new ArrayList<>());
    final List<String> failures = Collections.synchronizedList(new ArrayList<>());
    final List<String> refusals = Collections.synchronizedList(new ArrayList<>());
    final List<RobotPhase> phases = Collections.synchronizedList(new ArrayList<>());
    final List<MatchState> matchStates = Collections.synchronizedList(new ArrayList<>());
    final List<Catalogue> catalogues = Collections.synchronizedList(new ArrayList<>());
    final List<String> threads = Collections.synchronizedList(new ArrayList<>());
    volatile WorldSnapshot snapshot;
    volatile SessionStatus status;

    private final RemoteSimulatorLink link;
    private final Duration patience;

    LinkRecorder(RemoteSimulatorLink link, Duration patience) {
        this.link = link;
        this.patience = patience;
        link.addListener(this);
    }

    @Override
    public void opened(SessionInfo info) {
        threads.add(Thread.currentThread().getName());
        opened.add(info);
    }

    @Override
    public void snapshot(WorldSnapshot latest) {
        threads.add(Thread.currentThread().getName());
        snapshot = latest;
    }

    @Override
    public void status(SessionStatus latest) {
        threads.add(Thread.currentThread().getName());
        synchronized (phases) {
            if (phases.isEmpty() || phases.get(phases.size() - 1) != latest.phase()) {
                phases.add(latest.phase());
            }
        }
        synchronized (matchStates) {
            if (matchStates.isEmpty() || matchStates.get(matchStates.size() - 1) != latest.match().state()) {
                matchStates.add(latest.match().state());
            }
        }
        status = latest;
    }

    @Override
    public void failed(String message) {
        threads.add(Thread.currentThread().getName());
        failures.add(message);
    }

    @Override
    public void refused(String message) {
        threads.add(Thread.currentThread().getName());
        refusals.add(message);
    }

    @Override
    public void catalogue(Catalogue latest) {
        threads.add(Thread.currentThread().getName());
        catalogues.add(latest);
    }

    /** The chassis in a snapshot: the body with the chassis's entity id. */
    static Optional<BodyState> chassis(WorldSnapshot snapshot) {
        return snapshot == null ? Optional.empty() : snapshot.bodies().stream().filter(body -> body.entityId() == EntityIds.CHASSIS).findFirst();
    }

    /** Waits, assuming nothing about how fast the worker goes, until the condition holds. */
    void waitFor(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + patience.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) {
                throw new AssertionError("gave up waiting for " + what + "; the UI was told of failures " + failures + ", of refusals "
                        + refusals + " and the worker's log ends with: " + logTail());
            }
            TimeUnit.MILLISECONDS.sleep(5);
        }
    }

    private String logTail() {
        try {
            List<String> lines = Files.readAllLines(link.workerLog().orElseThrow());
            return String.join(" | ", lines.subList(Math.max(0, lines.size() - 10), lines.size()));
        } catch (IOException | RuntimeException unreadable) {
            return "(unreadable: " + unreadable + ")";
        }
    }
}
