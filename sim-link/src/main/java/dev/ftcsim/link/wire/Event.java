package dev.ftcsim.link.wire;

import dev.ftcsim.contracts.session.SessionInfo;
import dev.ftcsim.contracts.session.SessionStatus;
import dev.ftcsim.contracts.snapshot.WorldSnapshot;
import java.util.Objects;

/** What the worker tells the UI side; one record for every callback of {@code SimulatorLink.Listener}. */
public sealed interface Event {

    /** The session asked for is running. */
    record Opened(SessionInfo info) implements Event {
        public Opened {
            Objects.requireNonNull(info, "info");
        }
    }

    /** The world after a physics step. */
    record Snapshot(WorldSnapshot snapshot) implements Event {
        public Snapshot {
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    record Status(SessionStatus status) implements Event {
        public Status {
            Objects.requireNonNull(status, "status");
        }
    }

    /** A session that was asked for could not be opened, or the one that is open cannot go on; the worker itself still serves. */
    record Failed(String message) implements Event {
        public Failed {
            Objects.requireNonNull(message, "message");
        }
    }

    /** What can be asked for; the worker sends it first, right after the handshake. */
    record Catalogue(dev.ftcsim.contracts.session.Catalogue catalogue) implements Event {
        public Catalogue {
            Objects.requireNonNull(catalogue, "catalogue");
        }
    }

    /** A command was refused and nothing else changed; the session, if there is one, goes on as it was. */
    record Refused(String message) implements Event {
        public Refused {
            Objects.requireNonNull(message, "message");
        }
    }
}
