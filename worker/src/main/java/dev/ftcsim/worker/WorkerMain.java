package dev.ftcsim.worker;

import dev.ftcsim.game.biobuzz.BiobuzzField;
import dev.ftcsim.game.decode.DecodeField;
import dev.ftcsim.link.WorkerEnvironment;
import dev.ftcsim.link.wire.Frames;
import dev.ftcsim.link.wire.Handshake;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The worker process: connects back to the launcher that started it, proves it is the worker of that launch with the
 * token it was given on standard input, and serves the simulator port until the launcher says close, the connection
 * ends or the launcher itself is gone. Whatever the robot program logs goes to standard output; what the worker has to
 * say about itself goes to standard error.
 *
 * <p>Arguments: {@code --port <loopback port of the launcher>}. System properties:
 * {@value WorkerEnvironment#NATIVE_DIRECTORY_PROPERTY} and {@value WorkerEnvironment#STORAGE_DIRECTORY_PROPERTY}.
 */
public final class WorkerMain {

    private static final int EXIT_CRASHED = 1;
    private static final int EXIT_USAGE = 2;
    private static final int EXIT_NO_LAUNCHER = 6;
    private static final int EXIT_LAUNCHER_GONE = 7;
    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final int HANDSHAKE_TIMEOUT_MILLIS = 10_000;
    private static final int DRAIN_MILLIS = 500;
    private static final long LAUNCHER_GONE_PATIENCE_MILLIS = 30_000;

    private WorkerMain() {}

    public static void main(String[] args) {
        int code = EXIT_CRASHED;
        try {
            code = run(args);
        } catch (Throwable crash) {
            crash.printStackTrace();
        }
        System.out.flush();
        System.err.flush();
        // Nothing the robot world left behind may keep a process alive that nobody can talk to any more.
        Runtime.getRuntime().halt(code);
    }

    private static int run(String[] args) throws IOException {
        int port;
        try {
            port = port(args);
        } catch (IllegalArgumentException usage) {
            say(usage.getMessage());
            return EXIT_USAGE;
        }
        String token = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
        if (token == null || token.isBlank()) {
            say("the launcher did not send its token on standard input");
            return EXIT_USAGE;
        }
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), port), CONNECT_TIMEOUT_MILLIS);
            socket.setTcpNoDelay(true);
            greet(socket, token);
        } catch (IOException failure) {
            say("cannot reach the launcher on port " + port + ": " + failure.getMessage());
            closeQuietly(socket);
            return EXIT_NO_LAUNCHER;
        }
        endWithTheLauncher(socket);
        int code;
        PrintStream stdout = System.out;
        PrintStream stderr = System.err;
        try (WorkerLog log = new WorkerLog(stdout)) {
            // What team code prints goes to the log too, so that the window's console shows it as logcat would.
            System.setOut(LineCapture.stream(log, "I/System.out"));
            System.setErr(LineCapture.stream(log, "W/System.err"));
            Path natives = property(WorkerEnvironment.NATIVE_DIRECTORY_PROPERTY).orElseGet(WorkerEnvironment::defaultNativeDirectory);
            Path storage = property(WorkerEnvironment.STORAGE_DIRECTORY_PROPERTY).orElse(null);
            boolean ownStorage = storage == null;
            if (ownStorage) {
                storage = Files.createTempDirectory("ftcsim-storage-");
            }
            try {
                SessionFactory factory = new SessionFactory(programs(), fields(), natives, log);
                OutputStream out = new BufferedOutputStream(socket.getOutputStream());
                WorkerServer server = new WorkerServer(
                        socket.getInputStream(), out, socket, new LiveSessions(factory, storage), factory.catalogue(), WorkerMain::say);
                log.sink(server::log);
                code = server.serve();
            } finally {
                System.setOut(stdout);
                System.setErr(stderr);
                if (ownStorage) {
                    WorkerEnvironment.deleteTree(storage);
                }
            }
        }
        goodbye(socket, code);
        return code;
    }

    /** The layouts a prepared program may have on disk. */
    private static List<ProgramResolver> programs() {
        List<Path> simulator = new ArrayList<>();
        for (String entry : System.getProperty(WorkerEnvironment.SIMULATOR_CLASSPATH_PROPERTY, "").split(File.pathSeparator)) {
            if (!entry.isBlank()) {
                simulator.add(Path.of(entry));
            }
        }
        return List.of(new DirectoryProgramResolver(), new PreparedProgramResolver(simulator));
    }

    /** The fields a request may name. */
    private static FieldRegistry fields() {
        return new FieldRegistry().register("decode", DecodeField::new).register("biobuzz", BiobuzzField::new);
    }

    private static int port(String[] args) {
        for (int i = 0; i + 1 < args.length; i++) {
            if (args[i].equals(WorkerEnvironment.PORT_ARGUMENT)) {
                try {
                    int port = Integer.parseInt(args[i + 1]);
                    if (port < 1 || port > 65535) {
                        throw new IllegalArgumentException("the port " + port + " is not a port");
                    }
                    return port;
                } catch (NumberFormatException notANumber) {
                    throw new IllegalArgumentException("the port '" + args[i + 1] + "' is not a number");
                }
            }
        }
        throw new IllegalArgumentException("usage: WorkerMain " + WorkerEnvironment.PORT_ARGUMENT + " <the launcher's loopback port>");
    }

    private static void greet(Socket socket, String token) throws IOException {
        socket.setSoTimeout(HANDSHAKE_TIMEOUT_MILLIS);
        OutputStream out = socket.getOutputStream();
        Frames.write(out, Handshake.hello(token, ProcessHandle.current().pid()));
        out.flush();
        byte[] answer = Frames.read(socket.getInputStream());
        if (answer == null) {
            throw new IOException("the launcher closed the connection instead of answering the hello");
        }
        Handshake.readWelcome(answer);
        socket.setSoTimeout(0);
    }

    /** A worker whose launcher is gone has nobody to serve: the connection ends with the launcher, and so does the worker. */
    private static void endWithTheLauncher(Socket socket) {
        ProcessHandle.current().parent().ifPresent(launcher -> launcher.onExit().thenRun(() -> {
            if (launcher.isAlive()) {
                return;
            }
            say("the launcher has ended, so the worker ends too");
            closeQuietly(socket);
            Thread patience = new Thread(() -> {
                try {
                    Thread.sleep(LAUNCHER_GONE_PATIENCE_MILLIS);
                } catch (InterruptedException interrupted) {
                    return;
                }
                Runtime.getRuntime().halt(EXIT_LAUNCHER_GONE);
            });
            patience.setDaemon(true);
            patience.start();
        }));
    }

    /**
     * Ends the connection without losing the last thing written to it: a socket closed with unread data resets the
     * connection, and a reset makes the other end drop what it had received but not yet read.
     */
    private static void goodbye(Socket socket, int code) {
        if (code != WorkerServer.EXIT_CLOSED) {
            try {
                socket.shutdownOutput();
                socket.setSoTimeout(DRAIN_MILLIS);
                byte[] trash = new byte[4096];
                while (socket.getInputStream().read(trash) >= 0) {
                    // The other end sees the end of what we wrote when it has been read, and closes.
                }
            } catch (IOException done) {
                // Whatever ended the drain ends the connection too.
            }
        }
        closeQuietly(socket);
    }

    private static Optional<Path> property(String name) {
        String value = System.getProperty(name);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(Path.of(value));
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException alreadyClosed) {
            // Nothing is left to release.
        }
    }

    private static void say(String message) {
        System.err.println("ftcsim-worker: " + message);
    }
}
