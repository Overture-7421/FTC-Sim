package dev.ftcsim.link;

import dev.ftcsim.contracts.session.RobotInfo;
import dev.ftcsim.contracts.session.SessionInfo;
import dev.ftcsim.link.wire.Command;
import dev.ftcsim.link.wire.Event;
import dev.ftcsim.link.wire.Frames;
import dev.ftcsim.link.wire.Handshake;
import dev.ftcsim.link.wire.WireCodec;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The main class of a stand-in for the worker, which a test of the launcher starts as a real process: it connects
 * back like the worker does and then behaves as its mode says.
 *
 * <ul>
 *   <li>{@code crash}: writes to its error output and exits before it connects
 *   <li>{@code silent}: never connects
 *   <li>{@code old-version}: says hello with a protocol version nobody speaks
 *   <li>{@code serve}: answers Open with Opened and Init with Failed, and ends on Close
 *   <li>{@code ignore}: connects and then ignores everything, Close included
 *   <li>{@code explode}: serves until Start, then dies of a native crash
 * </ul>
 */
public final class FakeWorker {

    private FakeWorker() {}

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[1]);
        String mode = args[2];
        String token = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
        System.out.println("pid " + ProcessHandle.current().pid());
        System.out.println("classpath length " + System.getProperty("java.class.path").length());
        System.out.println("natives " + System.getProperty("ftcsim.nativeDirectory"));
        System.out.println("storage " + System.getProperty("ftcsim.storageDirectory"));
        endWithItsParent();
        switch (mode) {
            case "crash" -> {
                System.err.println("fatal: cannot start the simulation");
                System.err.println("caused by: nothing works");
                System.exit(3);
            }
            case "silent" -> TimeUnit.MINUTES.sleep(10);
            case "old-version" -> sayHelloWithAnotherVersion(port, token);
            default -> serve(port, token, mode);
        }
    }

    private static void sayHelloWithAnotherVersion(int port, String token) throws IOException {
        try (Socket socket = new Socket(LinkFixture.LOOPBACK, port)) {
            ByteArrayOutputStream payload = new ByteArrayOutputStream();
            DataOutputStream data = new DataOutputStream(payload);
            data.writeByte(0x01);
            data.writeInt(0x46544353);
            data.writeShort(99);
            byte[] text = token.getBytes(StandardCharsets.UTF_8);
            data.writeInt(text.length);
            data.write(text);
            data.writeLong(ProcessHandle.current().pid());
            Frames.write(socket.getOutputStream(), payload.toByteArray());
            socket.getOutputStream().flush();
            socket.getInputStream().read();
        }
    }

    private static void serve(int port, String token, String mode) throws Exception {
        try (Socket socket = new Socket(LinkFixture.LOOPBACK, port)) {
            socket.setTcpNoDelay(true);
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            InputStream in = socket.getInputStream();
            Frames.write(out, Handshake.hello(token, ProcessHandle.current().pid()));
            out.flush();
            Handshake.readWelcome(Frames.read(in));
            if (mode.equals("ignore")) {
                System.out.println("ignoring every request");
                TimeUnit.MINUTES.sleep(10);
                return;
            }
            while (true) {
                byte[] payload = Frames.read(in);
                if (payload == null) {
                    System.out.println("the connection ended without a Close");
                    System.exit(4);
                }
                switch (WireCodec.decodeCommand(payload)) {
                    case Command.Open open -> reply(out, new Event.Opened(info(open.request().profile())));
                    case Command.Init init -> reply(out, new Event.Failed("init " + init.opMode()));
                    case Command.Start start -> {
                        if (mode.equals("explode")) {
                            System.err.println("kaboom: the physics engine crashed");
                            Runtime.getRuntime().halt(9);
                        }
                    }
                    case Command.Close close -> {
                        System.out.println("closing");
                        System.exit(0);
                    }
                    default -> {}
                }
            }
        }
    }

    private static SessionInfo info(String robotName) {
        return new SessionInfo(null, List.of(), new RobotInfo(robotName, 0.4, 0.4, 0.3, List.of(), List.of(), List.of()));
    }

    private static void reply(OutputStream out, Event event) throws IOException {
        Frames.write(out, WireCodec.encode(event));
        out.flush();
    }

    /** A stand-in that outlives a test that was killed would be a stray process. */
    private static void endWithItsParent() {
        ProcessHandle.current().parent().ifPresent(parent -> parent.onExit().thenRun(() -> Runtime.getRuntime().halt(6)));
        Thread limit = new Thread(() -> {
            try {
                TimeUnit.MINUTES.sleep(5);
            } catch (InterruptedException interrupted) {
                return;
            }
            Runtime.getRuntime().halt(7);
        });
        limit.setDaemon(true);
        limit.start();
    }
}
