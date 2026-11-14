package dev.ftcsim.worker;

import dev.ftcsim.link.wire.Command;
import dev.ftcsim.link.wire.Event;
import dev.ftcsim.link.wire.Frames;
import dev.ftcsim.link.wire.WireCodec;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** A worker server on one end of a loopback connection and, on the other, the test, which plays the UI. */
final class ServerFixture implements AutoCloseable {

    final FakeSessions sessions = new FakeSessions();
    final List<String> diagnostics = Collections.synchronizedList(new ArrayList<>());
    final CompletableFuture<Integer> exitCode = new CompletableFuture<>();

    /** The first thing the worker sent, before any command: what can be asked for. */
    final Event.Catalogue catalogue;

    private final Socket ui;
    private final Socket serverSide;
    private final OutputStream toWorker;

    ServerFixture() throws IOException {
        this(Duration.ofSeconds(20));
    }

    ServerFixture(Duration sessionCloseLimit) throws IOException {
        InetAddress loopback = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        try (ServerSocket listener = new ServerSocket(0, 1, loopback)) {
            ui = new Socket(loopback, listener.getLocalPort());
            serverSide = listener.accept();
        }
        ui.setSoTimeout(10_000);
        toWorker = new BufferedOutputStream(ui.getOutputStream());
        WorkerServer server = new WorkerServer(
                serverSide.getInputStream(), new BufferedOutputStream(serverSide.getOutputStream()), serverSide, sessions,
                FakeSessions.CATALOGUE, diagnostics::add, sessionCloseLimit);
        Thread serving = new Thread(() -> exitCode.complete(server.serve()), "test-worker-server");
        serving.setDaemon(true);
        serving.start();
        catalogue = next(Event.Catalogue.class);
    }

    void send(Command command) throws IOException {
        sendRaw(WireCodec.encode(command));
    }

    void sendRaw(byte[] payload) throws IOException {
        Frames.write(toWorker, payload);
        toWorker.flush();
    }

    /** The next event the worker sent, waiting up to ten seconds; null if it closed the connection. */
    Event next() throws IOException {
        byte[] payload = Frames.read(ui.getInputStream());
        return payload == null ? null : WireCodec.decodeEvent(payload);
    }

    <T extends Event> T next(Class<T> type) throws IOException {
        Event event = next();
        if (!type.isInstance(event)) {
            throw new AssertionError("expected a " + type.getSimpleName() + " but got " + event);
        }
        return type.cast(event);
    }

    void closeUi() throws IOException {
        ui.close();
    }

    /** Waits for the server to end, which means that it has dealt with everything sent before. */
    int awaitExit() throws Exception {
        return exitCode.get(30, TimeUnit.SECONDS);
    }

    @Override
    public void close() throws IOException {
        if (sessions.holdClose != null) {
            sessions.holdClose.countDown();
        }
        ui.close();
        serverSide.close();
    }
}
