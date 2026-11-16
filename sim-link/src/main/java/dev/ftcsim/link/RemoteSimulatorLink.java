package dev.ftcsim.link;

import dev.ftcsim.contracts.robot.GamepadState;
import dev.ftcsim.contracts.session.Catalogue;
import dev.ftcsim.contracts.session.SessionRequest;
import dev.ftcsim.contracts.session.SimulatorLink;
import dev.ftcsim.link.wire.Command;
import dev.ftcsim.link.wire.Event;
import dev.ftcsim.link.wire.Frames;
import dev.ftcsim.link.wire.Handshake;
import dev.ftcsim.link.wire.HandshakeException;
import dev.ftcsim.link.wire.WireCodec;
import dev.ftcsim.link.wire.WireException;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The UI's side of a simulator that runs in a worker process, over a loopback socket.
 *
 * <p>No call blocks on the socket or the worker. Commands are queued and written by a thread of the link's own, in
 * the order they were given, except that of each gamepad only the latest state is sent when the writer is behind.
 * What the worker says is decoded by a second thread that never waits for a listener, and delivered by a third, the
 * only one that calls listeners, so that listeners are never called concurrently and a slow one is handed the latest
 * snapshot and status rather than every one it missed.
 *
 * <p>When the connection drops or the worker exits, the listeners get {@code failed} once, with how the worker ended
 * and the last lines it wrote to its error output, and the link is dead: later calls do nothing. Closing the link asks
 * the worker to end, gives it a moment, and then ends exactly that process.
 *
 * <p>A worker tells what can be asked for, its catalogue, once, as soon as it has connected. The link remembers it, so
 * a listener added after it has arrived is told it too, from the link's thread, at once. A command that the worker
 * refuses, such as arming a match while an OpMode runs, comes back as {@code refused} and changes nothing: the
 * session goes on. {@code failed} is only for a session that could not be opened or cannot go on, and for a worker
 * that is gone, which {@link #isUsable()} tells apart.
 */
public final class RemoteSimulatorLink implements SimulatorLink {

    private static final System.Logger LOG = System.getLogger(RemoteSimulatorLink.class.getName());
    private static final int BUFFER_BYTES = 64 * 1024;
    private static final int ACCEPT_POLL_MILLIS = 100;
    private static final int HANDSHAKE_TIMEOUT_MILLIS = 5_000;
    private static final Duration JOIN_GRACE = Duration.ofSeconds(2);
    private static final Duration WRITER_GRACE = Duration.ofSeconds(1);
    private static final Duration EXIT_AFTER_KILL = Duration.ofSeconds(5);

    private final Socket socket;
    private final WorkerHandle worker;
    private final Duration shutdownGrace;
    /** Touched by the dispatcher thread only, which is also the only one that calls them. */
    private final List<Listener> listeners = new ArrayList<>();
    private Catalogue catalogue;
    private final Outbox outbox = new Outbox();
    private final Mailbox mailbox = new Mailbox();
    private final AtomicLong framesReceived = new AtomicLong();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicBoolean failed = new AtomicBoolean();
    private final Thread reader = new Thread(this::readLoop, "ftcsim-link-reader");
    private final Thread writer = new Thread(this::writeLoop, "ftcsim-link-writer");
    private final Thread dispatcher = new Thread(this::dispatchLoop, "ftcsim-link-dispatcher");

    private RemoteSimulatorLink(Socket socket, WorkerHandle worker, Duration shutdownGrace) {
        this.socket = socket;
        this.worker = worker;
        this.shutdownGrace = shutdownGrace;
        for (Thread thread : List.of(reader, writer, dispatcher)) {
            thread.setDaemon(true);
        }
    }

    /**
     * Waits for the worker to connect to {@code server} and say hello with {@code token}, and welcomes it. Whatever
     * else connects, or says hello with another token, is dropped. A worker of another protocol version is turned
     * away and is an error, as is a worker that exits or does not show up in time.
     *
     * @param worker the process that is expected to connect, or null when the other end is not a process of ours
     */
    static RemoteSimulatorLink accept(ServerSocket server, String token, Duration timeout, WorkerHandle worker, Duration shutdownGrace)
            throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        server.setSoTimeout(ACCEPT_POLL_MILLIS);
        while (true) {
            if (worker != null && !worker.isAlive()) {
                throw new IOException(worker.describeEnd("The simulator worker ended before it connected"));
            }
            if (System.nanoTime() - deadline > 0) {
                String what = "The simulator worker did not connect within " + timeout.toSeconds() + " seconds";
                throw new IOException(worker == null ? what : worker.describeEnd(what));
            }
            Socket candidate;
            try {
                candidate = server.accept();
            } catch (SocketTimeoutException nobodyYet) {
                continue;
            }
            try {
                greet(candidate, token);
                RemoteSimulatorLink link = new RemoteSimulatorLink(candidate, worker, shutdownGrace);
                link.reader.start();
                link.writer.start();
                link.dispatcher.start();
                return link;
            } catch (HandshakeException refused) {
                closeQuietly(candidate);
                if (refused.reason() == HandshakeException.Reason.WRONG_VERSION) {
                    throw new IOException(refused.getMessage(), refused);
                }
            } catch (IOException stranger) {
                closeQuietly(candidate);
            }
        }
    }

    private static void greet(Socket candidate, String token) throws IOException {
        candidate.setSoTimeout(HANDSHAKE_TIMEOUT_MILLIS);
        candidate.setTcpNoDelay(true);
        byte[] hello = Frames.read(candidate.getInputStream());
        if (hello == null) {
            throw new WireException("the peer closed the connection before it said hello");
        }
        OutputStream out = candidate.getOutputStream();
        try {
            Handshake.readHello(hello, token);
        } catch (HandshakeException refused) {
            if (refused.reason() == HandshakeException.Reason.WRONG_VERSION) {
                Frames.write(out, Handshake.refused(refused.getMessage()));
                out.flush();
            }
            throw refused;
        }
        Frames.write(out, Handshake.welcome());
        out.flush();
        candidate.setSoTimeout(0);
    }

    // The port ------------------------------------------------------------------------------------------------------

    @Override
    public void addListener(Listener listener) {
        mailbox.register(Objects.requireNonNull(listener, "listener"));
    }

    @Override
    public void open(SessionRequest request) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(request.mapping(), "request.mapping");
        Objects.requireNonNull(request.program(), "request.program");
        Objects.requireNonNull(request.field(), "request.field");
        Objects.requireNonNull(request.alliance(), "request.alliance");
        send(new Command.Open(request));
    }

    @Override
    public void init(String opMode) {
        send(new Command.Init(opMode));
    }

    @Override
    public void start() {
        send(new Command.Start());
    }

    @Override
    public void stop() {
        send(new Command.Stop());
    }

    @Override
    public void setGamepad(int gamepad, GamepadState state) {
        if (gamepad != 1 && gamepad != 2) {
            throw new IllegalArgumentException("gamepad is 1 or 2: " + gamepad);
        }
        outbox.setGamepad(new Command.SetGamepad(gamepad, state));
    }

    @Override
    public void humanAction(String actionId) {
        send(new Command.PerformHumanAction(actionId));
    }

    @Override
    public void setPaused(boolean paused) {
        send(new Command.SetPaused(paused));
    }

    @Override
    public void setSpeed(double speed) {
        send(new Command.SetSpeed(speed));
    }

    @Override
    public void reset() {
        send(new Command.Reset());
    }

    @Override
    public void armMatch(String autoOpMode, String teleOpMode) {
        send(new Command.ArmMatch(autoOpMode, teleOpMode));
    }

    @Override
    public void startMatch() {
        send(new Command.StartMatch());
    }

    private void send(Command command) {
        outbox.add(command);
    }

    /** Asks the worker to end, gives it a moment, then ends exactly that process. Safe to call again and from a listener. */
    @Override
    public void close() {
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        boolean interrupted = false;
        outbox.finishWith(new Command.Close());
        interrupted |= join(writer, shutdownGrace.compareTo(WRITER_GRACE) < 0 ? shutdownGrace : WRITER_GRACE);
        if (worker != null) {
            interrupted |= stop(worker);
        } else {
            interrupted |= join(reader, shutdownGrace);
        }
        closeQuietly(socket);
        interrupted |= join(reader, JOIN_GRACE);
        mailbox.close();
        interrupted |= join(dispatcher, JOIN_GRACE);
        if (worker != null) {
            worker.release();
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean stop(WorkerHandle process) {
        try {
            if (!process.awaitExit(shutdownGrace)) {
                process.destroyForcibly();
                process.awaitExit(EXIT_AFTER_KILL);
            }
            return false;
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            return true;
        }
    }

    // What the worker is and how it ended ----------------------------------------------------------------------------

    /**
     * Whether commands still reach a worker. False once the connection has dropped or the worker has ended, which
     * listeners are told by {@code failed}, and once the link is closed. A {@code failed} that comes while this is
     * still true is about the session (it could not be opened, or the simulation stopped), and a new {@code open} or
     * a {@code reset} may be tried.
     */
    public boolean isUsable() {
        return !failed.get() && !closing.get();
    }

    /** The process id of the worker this link started, if it started one. */
    public OptionalLong workerPid() {
        return worker == null ? OptionalLong.empty() : OptionalLong.of(worker.pid());
    }

    /** How the worker process ended, or empty while it runs or when this link did not start it. */
    public OptionalInt workerExitCode() {
        return worker == null ? OptionalInt.empty() : worker.exitCode();
    }

    /** The file the worker's standard output and error are written to. */
    public Optional<Path> workerLog() {
        return worker == null ? Optional.empty() : worker.logFile();
    }

    /** How many events have been read from the worker and left for the listeners. */
    long framesReceived() {
        return framesReceived.get();
    }

    // The three threads ----------------------------------------------------------------------------------------------

    private void readLoop() {
        try {
            InputStream in = new BufferedInputStream(socket.getInputStream(), BUFFER_BYTES);
            while (true) {
                byte[] payload = Frames.read(in);
                if (payload == null) {
                    lost("The simulator worker closed the connection");
                    return;
                }
                mailbox.offer(WireCodec.decodeEvent(payload));
                framesReceived.incrementAndGet();
            }
        } catch (IOException broken) {
            lost("The connection to the simulator worker broke: " + broken.getMessage());
        }
    }

    private void writeLoop() {
        try {
            OutputStream out = new BufferedOutputStream(socket.getOutputStream(), BUFFER_BYTES);
            for (Command command = outbox.take(); command != null; command = outbox.take()) {
                Frames.write(out, WireCodec.encode(command));
                out.flush();
            }
        } catch (IOException broken) {
            lost("The simulator worker stopped taking commands: " + broken.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void dispatchLoop() {
        try {
            for (Mailbox.Item item = mailbox.take(); item != null; item = mailbox.take()) {
                switch (item) {
                    case Mailbox.Item.Deliver deliver -> deliver(deliver.event());
                    case Mailbox.Item.Register register -> register(register.listener());
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** A listener that comes late is told the catalogue it missed, which the worker sends only once. */
    private void register(Listener listener) {
        listeners.add(listener);
        if (catalogue != null) {
            tell(listener, () -> listener.catalogue(catalogue));
        }
    }

    private void deliver(Event event) {
        if (event instanceof Event.Catalogue latest) {
            catalogue = latest.catalogue();
        }
        for (Listener listener : listeners) {
            tell(listener, () -> {
                switch (event) {
                    case Event.Opened opened -> listener.opened(opened.info());
                    case Event.Snapshot snapshot -> listener.snapshot(snapshot.snapshot());
                    case Event.Status status -> listener.status(status.status());
                    case Event.Failed failure -> listener.failed(failure.message());
                    case Event.Catalogue latest -> listener.catalogue(latest.catalogue());
                    case Event.Refused refusal -> listener.refused(refusal.message());
                    case Event.Log log -> listener.log(log.lines());
                }
            });
        }
    }

    private static void tell(Listener listener, Runnable call) {
        try {
            call.run();
        } catch (RuntimeException brokenListener) {
            LOG.log(System.Logger.Level.WARNING, "a listener of the simulator link threw an exception", brokenListener);
        }
    }

    /** The worker is gone or cannot be talked to: tell the listeners once, and make sure the process is gone too. */
    private void lost(String what) {
        if (closing.get() || !failed.compareAndSet(false, true)) {
            return;
        }
        String message = worker == null ? what + "." : worker.describeEnd(what);
        outbox.abandon();
        closeQuietly(socket);
        mailbox.finishWith(new Event.Failed(message));
        if (worker != null) {
            worker.release();
        }
    }

    private static boolean join(Thread thread, Duration timeout) {
        if (thread == Thread.currentThread()) {
            return false;
        }
        try {
            thread.join(timeout.toMillis());
            return false;
        } catch (InterruptedException interrupted) {
            return true;
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException alreadyGone) {
            // Nothing is left to release.
        }
    }
}
