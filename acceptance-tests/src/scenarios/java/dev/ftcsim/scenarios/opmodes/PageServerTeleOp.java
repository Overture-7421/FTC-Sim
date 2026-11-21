package dev.ftcsim.scenarios.opmodes;

import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Serves one tiny page the way NanoHTTPD, which Panels is built on, does: an unbound {@code ServerSocket}, the reuse
 * flag, a bind to the wildcard address and a thread of its own that accepts and answers.
 */
@TeleOp(name = "Page Server", group = "Network")
public final class PageServerTeleOp extends OpMode {
    /** One of the ports the simulator lets listen on the loopback address of the machine. */
    public static final int PORT = 8001;

    public static final String PAGE = "Hello from the robot world";

    private ServerSocket server;

    @Override
    public void init() {
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(PORT));
        } catch (IOException failure) {
            throw new IllegalStateException("could not listen on " + PORT, failure);
        }
        Thread acceptor = new Thread(this::serve, "page-server");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    private void serve() {
        while (!server.isClosed()) {
            try (Socket client = server.accept()) {
                BufferedReader request = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                for (String line = request.readLine(); line != null && !line.isEmpty(); line = request.readLine()) {
                    // the headers do not matter
                }
                byte[] body = PAGE.getBytes(StandardCharsets.UTF_8);
                client.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: " + body.length
                        + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                client.getOutputStream().write(body);
            } catch (IOException ended) {
                if (server.isClosed()) {
                    return;
                }
            }
        }
    }

    @Override
    public void loop() {}
}
