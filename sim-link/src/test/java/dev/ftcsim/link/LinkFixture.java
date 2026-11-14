package dev.ftcsim.link;

import dev.ftcsim.link.wire.Command;
import dev.ftcsim.link.wire.Event;
import dev.ftcsim.link.wire.Frames;
import dev.ftcsim.link.wire.Handshake;
import dev.ftcsim.link.wire.WireCodec;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** A link and, on the other end of its socket, a worker that is the test itself: no process, no robot. */
final class LinkFixture implements AutoCloseable {

    static final Duration GRACE = Duration.ofMillis(300);
    static final InetAddress LOOPBACK = loopback();

    final RemoteSimulatorLink link;
    final RecordingListener events = new RecordingListener();
    final Socket worker;
    private final InputStream in;
    private final OutputStream out;

    private LinkFixture(RemoteSimulatorLink link, Socket worker) throws IOException {
        this.link = link;
        this.worker = worker;
        this.in = worker.getInputStream();
        this.out = new BufferedOutputStream(worker.getOutputStream());
        worker.setSoTimeout(10_000);
        link.addListener(events);
    }

    static InetAddress loopback() {
        try {
            return InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    static LinkFixture open() throws Exception {
        return open(null);
    }

    /** Connects a fake worker that says hello with the right token and is welcomed. */
    static LinkFixture open(WorkerHandle process) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 4, LOOPBACK)) {
            String token = Handshake.newToken();
            CompletableFuture<RemoteSimulatorLink> accepted = CompletableFuture.supplyAsync(() -> {
                try {
                    return RemoteSimulatorLink.accept(server, token, Duration.ofSeconds(20), process, GRACE);
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            });
            Socket worker = new Socket(LOOPBACK, server.getLocalPort());
            worker.setSoTimeout(10_000);
            Frames.write(worker.getOutputStream(), Handshake.hello(token, ProcessHandle.current().pid()));
            worker.getOutputStream().flush();
            Handshake.readWelcome(Frames.read(worker.getInputStream()));
            return new LinkFixture(accepted.get(20, TimeUnit.SECONDS), worker);
        }
    }

    /** The next command the link wrote, waiting up to ten seconds; null if the link closed the connection. */
    Command nextCommand() throws IOException {
        byte[] payload = Frames.read(in);
        return payload == null ? null : WireCodec.decodeCommand(payload);
    }

    void send(Event event) throws IOException {
        sendRaw(WireCodec.encode(event));
    }

    void sendRaw(byte[] payload) throws IOException {
        Frames.write(out, payload);
        out.flush();
    }

    void dropConnection() throws IOException {
        worker.close();
    }

    @Override
    public void close() throws IOException {
        link.close();
        worker.close();
    }
}
