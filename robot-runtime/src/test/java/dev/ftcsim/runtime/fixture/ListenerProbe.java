package dev.ftcsim.runtime.fixture;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.NetworkChannel;
import java.nio.channels.ServerSocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import javax.net.ServerSocketFactory;
import javax.net.ssl.SSLServerSocketFactory;

/**
 * Every way a program listens, tried once and reported as {@code label -> outcome}: the kind of listener it got and
 * the port it holds. Run under a host that simulates listeners, every one that can be simulated is, and the others
 * stay under the policy of the host.
 */
public final class ListenerProbe implements Callable<List<String>> {
    @FunctionalInterface
    private interface Attempt {
        Object run() throws Exception;
    }

    private final List<String> outcomes = new ArrayList<>();
    private final List<AutoCloseable> held = new ArrayList<>();

    private static <T> T nullReceiver() {
        return null;
    }

    private static String describe(Object listener) throws IOException {
        if (listener instanceof ServerSocket socket) {
            return socket.getClass().getSimpleName() + ':' + socket.getLocalPort();
        }
        if (listener instanceof NetworkChannel channel) {
            SocketAddress local = channel.getLocalAddress();
            return channel.getClass().getSimpleName() + ':' + (local == null ? "unbound" : ((InetSocketAddress) local).getPort());
        }
        return String.valueOf(listener);
    }

    private void attempt(String label, Attempt attempt) throws Exception {
        String outcome;
        try {
            Object result = attempt.run();
            if (result instanceof AutoCloseable closeable) {
                held.add(closeable);
            }
            outcome = describe(result);
        } catch (IOException e) {
            outcome = e.getClass().getSimpleName();
        }
        outcomes.add(label + " -> " + outcome);
    }

    @Override
    public List<String> call() throws Exception {
        InetAddress loopback = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        try {
            attempt("ServerSocket(int)", () -> new ServerSocket(18301));
            attempt("ServerSocket(int,int)", () -> new ServerSocket(18302, 5));
            attempt("ServerSocket(int,int,InetAddress)", () -> new ServerSocket(18303, 5, loopback));
            attempt("ServerSocket()", ServerSocket::new);
            attempt("ServerSocket.bind(SocketAddress)", () -> {
                ServerSocket socket = new ServerSocket();
                socket.bind(new InetSocketAddress(18304));
                return socket;
            });
            attempt("ServerSocket.bind(SocketAddress,int)", () -> {
                ServerSocket socket = new ServerSocket();
                socket.bind(new InetSocketAddress(loopback, 18305), 5);
                return socket;
            });
            attempt("ServerSocket(0)", () -> new ServerSocket(0));
            attempt("ServerSocket(int) on a port that is held", () -> new ServerSocket(18301));

            attempt("default factory createServerSocket(int)", () -> ServerSocketFactory.getDefault().createServerSocket(18306));
            attempt("default factory createServerSocket()", () -> {
                ServerSocket socket = ServerSocketFactory.getDefault().createServerSocket();
                socket.bind(new InetSocketAddress(18307));
                return socket;
            });
            attempt("default factory createServerSocket(int,int)", () -> ServerSocketFactory.getDefault().createServerSocket(18308, 5));
            attempt(
                    "default factory createServerSocket(int,int,InetAddress)",
                    () -> ServerSocketFactory.getDefault().createServerSocket(18309, 5, loopback));
            attempt("SSLServerSocketFactory.createServerSocket(int) stays under the policy", () -> {
                SSLServerSocketFactory factory = nullReceiver();
                return factory.createServerSocket(18310);
            });

            attempt("ServerSocketChannel.open()", ServerSocketChannel::open);
            attempt("ServerSocketChannel.bind(SocketAddress)", () -> ServerSocketChannel.open().bind(new InetSocketAddress(18311)));
            attempt("ServerSocketChannel.bind(SocketAddress,int)", () -> ServerSocketChannel.open().bind(new InetSocketAddress(18312), 5));
            attempt("NetworkChannel.bind(SocketAddress)", () -> {
                NetworkChannel channel = ServerSocketChannel.open();
                return channel.bind(new InetSocketAddress(18313));
            });
            attempt("ServerSocketChannel.socket().bind(SocketAddress)", () -> {
                ServerSocketChannel channel = ServerSocketChannel.open();
                channel.socket().bind(new InetSocketAddress(18314));
                return channel;
            });

            attempt("ServerSocket.accept() with a timeout", () -> {
                try (ServerSocket socket = new ServerSocket(18315)) {
                    socket.setSoTimeout(20);
                    return socket.accept();
                }
            });
            attempt("ServerSocketChannel.accept() when nothing is pending", () -> {
                try (ServerSocketChannel channel = ServerSocketChannel.open()) {
                    channel.bind(new InetSocketAddress(18316));
                    channel.configureBlocking(false);
                    return channel.accept();
                }
            });

            attempt("AsynchronousServerSocketChannel.bind(SocketAddress) stays under the policy", () -> {
                try (AsynchronousServerSocketChannel channel = AsynchronousServerSocketChannel.open()) {
                    return channel.bind(new InetSocketAddress(18317));
                }
            });
            attempt("DatagramSocket(int) stays under the policy", () -> new DatagramSocket(18318));
        } finally {
            for (AutoCloseable listener : held) {
                listener.close();
            }
        }
        return outcomes;
    }
}
