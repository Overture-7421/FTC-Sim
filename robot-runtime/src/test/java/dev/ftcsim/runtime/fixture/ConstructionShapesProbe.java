package dev.ftcsim.runtime.fixture;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * The shapes in which a program can construct a socket, on the loopback interface only: as a statement, with
 * arguments computed across a branch, nested in another construction of the same and of another type, inside a
 * protected region, by constructor reference and inside a lambda. The verifier checks every one of them when the
 * class loads, so a rewrite that left a frame wrong fails before the first line runs.
 */
public final class ConstructionShapesProbe implements Callable<List<String>> {
    @FunctionalInterface
    private interface Maker {
        ServerSocket make(int port, int backlog, InetAddress address) throws IOException;
    }

    @FunctionalInterface
    private interface Connector {
        void connect(Socket socket, SocketAddress address, int timeoutMillis) throws IOException;
    }

    private static int portOf(ServerSocket socket) throws IOException {
        try (socket) {
            return socket.getLocalPort();
        }
    }

    @Override
    public List<String> call() throws Exception {
        List<String> shapes = new ArrayList<>();
        InetAddress loopback = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});

        new ServerSocket(0, 1, loopback).close();
        shapes.add("statement");

        boolean flag = shapes.size() == 1;
        try (ServerSocket socket = new ServerSocket(0, flag ? 5 : 6, loopback)) {
            shapes.add("branch " + (socket.getLocalPort() > 0));
        }

        try (ServerSocket socket = new ServerSocket(0, portOf(new ServerSocket(0, 1, loopback)) > 0 ? 3 : 2, loopback)) {
            shapes.add("nested same type " + (socket.getLocalPort() > 0));
        }

        try (ServerSocket server = new ServerSocket(0, 1, loopback);
                Socket client = new Socket(loopback, server.getLocalPort())) {
            shapes.add("nested other type " + client.isConnected());
        }

        int closedPort = portOf(new ServerSocket(0, 1, loopback));
        try {
            new Socket(loopback, closedPort).close();
            shapes.add("protected region connected");
        } catch (ConnectException refused) {
            shapes.add("protected region refused");
        }

        Maker maker = ServerSocket::new;
        try (ServerSocket socket = maker.make(0, 1, loopback)) {
            shapes.add("constructor reference " + (socket.getLocalPort() > 0));
        }

        Connector connector = Socket::connect;
        try (ServerSocket server = new ServerSocket(0, 1, loopback);
                Socket client = new Socket()) {
            connector.connect(client, new InetSocketAddress(loopback, server.getLocalPort()), 2000);
            shapes.add("method reference " + client.isConnected());
        }

        Callable<ServerSocket> lazily = () -> new ServerSocket(0, 1, loopback);
        try (ServerSocket socket = lazily.call()) {
            shapes.add("lambda " + (socket.getLocalPort() > 0));
        }
        return shapes;
    }
}
