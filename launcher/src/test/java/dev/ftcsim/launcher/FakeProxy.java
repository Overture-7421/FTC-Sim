package dev.ftcsim.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A proxy that answers every request with "407 Proxy Authentication Required", like a school proxy that wants a login
 * FTC-Sim cannot give. It records each request line, so a test can see that traffic really went through the proxy.
 * (.NET never sends loopback addresses through a proxy, which is why the tests that use it name real https hosts.)
 */
final class FakeProxy implements AutoCloseable {

    private final ServerSocket server;
    private final List<String> requestLines = new CopyOnWriteArrayList<>();
    private final Thread acceptor;

    private FakeProxy(ServerSocket server) {
        this.server = server;
        this.acceptor = new Thread(this::serve, "fake-proxy");
        this.acceptor.setDaemon(true);
    }

    static FakeProxy requiringCredentials() throws IOException {
        FakeProxy proxy = new FakeProxy(new ServerSocket(0, 20, InetAddress.getLoopbackAddress()));
        proxy.acceptor.start();
        return proxy;
    }

    String url() {
        return "http://127.0.0.1:" + server.getLocalPort();
    }

    List<String> requestLines() {
        return List.copyOf(requestLines);
    }

    private void serve() {
        while (!server.isClosed()) {
            try (Socket client = server.accept()) {
                client.setSoTimeout(5000);
                requestLines.add(readRequestLine(client.getInputStream()));
                OutputStream out = client.getOutputStream();
                out.write(("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"School\"\r\n"
                        + "Content-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.flush();
            } catch (IOException e) {
                // The client gave up or the proxy is being closed; either way there is nothing left to answer.
            }
        }
    }

    /** Reads up to the blank line that ends the request head and returns its first line. */
    private static String readRequestLine(InputStream in) throws IOException {
        StringBuilder head = new StringBuilder();
        while (!head.toString().endsWith("\r\n\r\n")) {
            int next = in.read();
            if (next < 0) {
                break;
            }
            head.append((char) next);
        }
        return head.toString().lines().findFirst().orElse("");
    }

    @Override
    public void close() throws IOException {
        server.close();
    }
}
