package dev.ftcsim.worker;

import dev.ftcsim.contracts.field.FieldDescription;
import dev.ftcsim.contracts.robot.GamepadState;
import dev.ftcsim.contracts.robot.OpModeDescriptor;
import dev.ftcsim.contracts.session.Catalogue;
import dev.ftcsim.contracts.session.SessionRequest;
import dev.ftcsim.contracts.session.SessionStatus;
import dev.ftcsim.contracts.snapshot.WorldSnapshot;
import dev.ftcsim.link.wire.Command;
import dev.ftcsim.link.wire.Event;
import dev.ftcsim.link.wire.Frames;
import dev.ftcsim.link.wire.WireCodec;
import dev.ftcsim.link.wire.WireException;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Serves the port of {@code SimulatorLink} to one UI: sends the catalogue of what can be asked for, then reads commands
 * from the connection, carries them out on the session, and publishes what the session shows. One session is open at a
 * time; opening another, or resetting, closes the one before.
 *
 * <p>A command that cannot be carried out is answered with {@code Refused} and the reason in words a person can act on,
 * and nothing else changes: no session is open, the OpMode does not exist, the match is armed or running, the simulation
 * refuses. A command whose answer depends on what the simulation holds (Init, Start, the match, a human action) is
 * decided on the simulation's own thread, which this one waits for. {@code Failed} is only for a session that could not
 * be opened or cannot go on, and for a worker that has to end.
 *
 * <p>The thread that calls {@link #serve} does everything that changes the session; a thread of its own publishes
 * snapshots and statuses, so that nothing the UI does, or fails to do, can hold the simulation up: the simulation never
 * calls into this class, it is only read.
 */
final class WorkerServer {

    /** The UI asked the worker to end. */
    static final int EXIT_CLOSED = 0;

    /** The connection ended without a request to close. */
    static final int EXIT_CONNECTION_LOST = 3;

    /** The UI sent something that is not a command of this protocol. */
    static final int EXIT_MALFORMED = 4;

    /** A session did not stop, and nothing else in this process can run robot code until it does. */
    static final int EXIT_STUCK = 5;

    private static final Duration SESSION_CLOSE_LIMIT = Duration.ofSeconds(20);
    private static final long PUBLISH_PERIOD_NANOS = 4_000_000L;

    private final InputStream in;
    private final OutputStream out;
    private final Closeable connection;
    private final SessionOpener sessions;
    private final Catalogue catalogue;
    private final Consumer<String> diagnostics;
    private final Duration sessionCloseLimit;
    private final Object writeLock = new Object();
    private final GamepadState[] gamepads = {GamepadState.NEUTRAL, GamepadState.NEUTRAL};

    private volatile boolean connectionLost;
    private RunningSession session;
    private Publisher publisher;
    private SessionRequest request;
    private double speed;
    private boolean stuck;

    /**
     * @param connection  closed when the other end cannot be written to, which ends {@link #serve}
     * @param catalogue   what a request may name, sent to the UI before anything else
     * @param diagnostics where the worker tells whoever reads its error output what it is doing wrong
     */
    WorkerServer(
            InputStream in,
            OutputStream out,
            Closeable connection,
            SessionOpener sessions,
            Catalogue catalogue,
            Consumer<String> diagnostics) {
        this(in, out, connection, sessions, catalogue, diagnostics, SESSION_CLOSE_LIMIT);
    }

    /** @param sessionCloseLimit how long a session may take to stop before the worker gives up on it */
    WorkerServer(
            InputStream in,
            OutputStream out,
            Closeable connection,
            SessionOpener sessions,
            Catalogue catalogue,
            Consumer<String> diagnostics,
            Duration sessionCloseLimit) {
        this.in = in;
        this.out = out;
        this.connection = connection;
        this.sessions = sessions;
        this.catalogue = catalogue;
        this.diagnostics = diagnostics;
        this.sessionCloseLimit = sessionCloseLimit;
    }

    /** Serves until the UI says close or the connection ends; closes the session before it returns. */
    int serve() {
        int code = EXIT_CLOSED;
        send(new Event.Catalogue(catalogue));
        try {
            for (boolean going = true; going; ) {
                byte[] frame = Frames.read(in);
                if (frame == null) {
                    diagnostics.accept("the connection was closed by the other end");
                    code = EXIT_CONNECTION_LOST;
                    break;
                }
                going = handle(WireCodec.decodeCommand(frame));
            }
        } catch (WireException malformed) {
            diagnostics.accept("received a message it cannot read: " + malformed.getMessage());
            send(new Event.Failed("The simulator worker received a message it cannot read: " + malformed.getMessage()));
            code = EXIT_MALFORMED;
        } catch (IOException lost) {
            diagnostics.accept("the connection was lost: " + lost.getMessage());
            code = EXIT_CONNECTION_LOST;
        }
        closeSession();
        return stuck ? EXIT_STUCK : code;
    }

    /** @return false when the worker is to end */
    private boolean handle(Command command) {
        try {
            switch (command) {
                case Command.Open open -> open(open.request());
                case Command.Reset _ -> reset();
                case Command.Close _ -> {
                    return false;
                }
                case Command.Init init -> init(init.opMode());
                case Command.Start _ -> {
                    if (hasSession("start")) {
                        carryOut(() -> session.start());
                    }
                }
                case Command.Stop _ -> {
                    if (hasSession("stop")) {
                        session.stop();
                    }
                }
                case Command.SetGamepad pad -> setGamepad(pad.gamepad(), pad.state());
                case Command.PerformHumanAction action -> humanAction(action.actionId());
                case Command.ArmMatch arm -> {
                    if (hasSession("arm a match for")) {
                        carryOut(() -> session.armMatch(arm.autoOpMode(), arm.teleOpMode()));
                    }
                }
                case Command.StartMatch _ -> {
                    if (hasSession("start a match in")) {
                        carryOut(() -> session.startMatch());
                    }
                }
                case Command.SetPaused paused -> {
                    if (session != null) {
                        session.setPaused(paused.paused());
                    }
                }
                case Command.SetSpeed newSpeed -> {
                    speed = newSpeed.speed();
                    if (session != null) {
                        session.setSpeed(speed);
                    }
                }
            }
        } catch (RuntimeException failure) {
            diagnostics.accept("a command failed: " + failure);
            refuse("The simulator worker could not carry out the command: " + reasonOf(failure));
        }
        return !stuck;
    }

    private void open(SessionRequest asked) {
        if (!closeSession()) {
            return;
        }
        try {
            RunningSession opened = sessions.open(asked);
            session = opened;
            request = asked;
            speed = asked.speed();
            opened.setGamepad(1, gamepads[0]);
            opened.setGamepad(2, gamepads[1]);
            send(new Event.Opened(opened.info()));
            publisher = new Publisher(opened);
        } catch (IOException | RuntimeException | LinkageError failure) {
            diagnostics.accept("could not open a session: " + failure);
            fail("Could not open the session: " + describe(failure));
        }
    }

    /**
     * The world of the session as it began, with the robot program still loaded: nothing is opened or closed, so it is quick.
     * The window is told with a new {@code Opened}, as it was when a reset opened a session again.
     */
    private void reset() {
        if (!hasSession("reset")) {
            return;
        }
        carryOut(() -> session.reset());
        send(new Event.Opened(session.info()));
    }

    private void init(String opMode) {
        if (!hasSession("initialise")) {
            return;
        }
        List<OpModeDescriptor> opModes = session.info().opModes();
        if (opModes.stream().noneMatch(declared -> declared.name().equals(opMode))) {
            refuse("There is no OpMode '" + opMode + "' in this program"
                    + (opModes.isEmpty() ? ", which declares none." : "; it has "
                            + opModes.stream().map(OpModeDescriptor::name).collect(Collectors.joining(", ")) + "."));
            return;
        }
        carryOut(() -> session.init(opMode));
    }

    private void setGamepad(int pad, GamepadState state) {
        if (pad != 1 && pad != 2) {
            refuse("There are two gamepads, 1 and 2, and no gamepad " + pad + ".");
            return;
        }
        gamepads[pad - 1] = state;
        if (session != null) {
            session.setGamepad(pad, state);
        }
    }

    private void humanAction(String actionId) {
        if (!hasSession("act on")) {
            return;
        }
        FieldDescription field = session.info().field();
        if (field == null) {
            refuse("This session has no field, so there is nobody to act at it.");
        } else if (field.humanActions().stream().noneMatch(action -> action.id().equals(actionId))) {
            refuse("The field has no action '" + actionId + "'; it has "
                    + field.humanActions().stream().map(action -> action.id()).collect(Collectors.joining(", ")) + ".");
        } else {
            carryOut(() -> session.humanAction(actionId));
        }
    }

    private boolean hasSession(String what) {
        if (session == null) {
            refuse("No session is open, so there is nothing to " + what + ".");
        }
        return session != null;
    }

    /** What the simulation refuses is told to the UI in the simulation's own words; the session goes on as it was. */
    private void carryOut(Runnable command) {
        try {
            command.run();
        } catch (IllegalStateException | IllegalArgumentException refused) {
            refuse(reasonOf(refused));
        }
    }

    /** Stops publishing and closes the session. @return false if it would not stop within the limit */
    private boolean closeSession() {
        if (publisher != null) {
            publisher.stop();
            publisher = null;
        }
        RunningSession ending = session;
        session = null;
        if (ending == null) {
            return true;
        }
        Thread closer = new Thread(ending::close, "ftcsim-worker-closer");
        closer.setDaemon(true);
        closer.start();
        try {
            closer.join(sessionCloseLimit.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (closer.isAlive()) {
            stuck = true;
            diagnostics.accept("the session did not stop within " + sessionCloseLimit.toSeconds() + " seconds");
            fail("The robot program did not stop within " + sessionCloseLimit.toSeconds()
                    + " seconds, so the simulator worker is ending. Start a new one.");
            return false;
        }
        return true;
    }

    /** Tells the window what the robot program and the SDK logged; lines are dropped, not queued, once the connection is gone. */
    void log(java.util.List<String> lines) {
        if (!lines.isEmpty()) {
            send(new Event.Log(lines));
        }
    }

    private void fail(String message) {
        send(new Event.Failed(message));
    }

    private void refuse(String message) {
        send(new Event.Refused(message));
    }

    /** The reason an exception gives, or what it is when it gives none. */
    private static String reasonOf(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.toString() : message;
    }

    /** Writes an event to the UI. An event that cannot be represented is replaced by a failure that says so. */
    private void send(Event event) {
        if (connectionLost) {
            return;
        }
        byte[] payload;
        try {
            payload = WireCodec.encode(event);
        } catch (IllegalArgumentException unrepresentable) {
            String what = event.getClass().getSimpleName();
            diagnostics.accept("cannot send a " + what + ": " + unrepresentable.getMessage());
            if (!(event instanceof Event.Failed)) {
                send(new Event.Failed("The simulator worker could not send a " + what + ": " + unrepresentable.getMessage()));
            }
            return;
        }
        synchronized (writeLock) {
            if (connectionLost) {
                return;
            }
            try {
                Frames.write(out, payload);
                out.flush();
            } catch (IOException lost) {
                connectionLost = true;
                diagnostics.accept("cannot write to the connection: " + lost.getMessage());
                try {
                    connection.close();
                } catch (IOException alreadyClosed) {
                    // The reader finds out by itself.
                }
            }
        }
    }

    static String describe(Throwable failure) {
        boolean plain = failure instanceof IOException || failure instanceof IllegalArgumentException;
        return plain && failure.getMessage() != null ? failure.getMessage() : failure.toString();
    }

    /** Sends the UI what a session shows, whenever it shows something new, on a thread of its own. */
    private final class Publisher implements Runnable {
        private final RunningSession source;
        private final Thread thread;
        private volatile boolean stopped;

        Publisher(RunningSession source) {
            this.source = source;
            this.thread = new Thread(this, "ftcsim-worker-publisher");
            this.thread.setDaemon(true);
            this.thread.start();
        }

        void stop() {
            stopped = true;
            LockSupport.unpark(thread);
            try {
                thread.join(5000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void run() {
            WorldSnapshot lastSnapshot = null;
            SessionStatus lastStatus = null;
            boolean reported = false;
            while (!stopped) {
                WorldSnapshot snapshot = source.snapshot();
                if (snapshot != null && snapshot != lastSnapshot) {
                    lastSnapshot = snapshot;
                    send(new Event.Snapshot(snapshot));
                }
                SessionStatus status = source.status();
                if (status != null && status != lastStatus) {
                    lastStatus = status;
                    send(new Event.Status(status));
                }
                Throwable failure = source.failure();
                if (failure != null && !reported) {
                    reported = true;
                    fail("The simulation stopped: " + describe(failure));
                }
                LockSupport.parkNanos(PUBLISH_PERIOD_NANOS);
            }
        }
    }
}
