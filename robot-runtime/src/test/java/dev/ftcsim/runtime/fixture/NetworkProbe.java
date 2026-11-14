package dev.ftcsim.runtime.fixture;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.nio.channels.DatagramChannel;
import java.nio.channels.NetworkChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.net.ServerSocketFactory;
import javax.net.SocketFactory;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Every network operation the rewriter redirects, each tried once and reported as {@code label -> outcome}. Run
 * under a policy that declines everything, none of them may touch the network; the receivers that would be heavy to
 * create, such as SSL factories and the HTTP client, are null because a declined call never reaches its receiver.
 */
public final class NetworkProbe implements Callable<List<String>> {
    @FunctionalInterface
    private interface Attempt {
        Object run() throws Exception;
    }

    private final List<String> outcomes = new ArrayList<>();

    private void attempt(String label, Attempt attempt) {
        String outcome;
        try {
            Object result = attempt.run();
            if (result instanceof AutoCloseable closeable) {
                closeable.close();
            }
            outcome = "ok";
        } catch (ExecutionException e) {
            outcome = "ExecutionException(" + e.getCause().getClass().getSimpleName() + ")";
        } catch (Exception e) {
            outcome = e.getClass().getSimpleName();
        }
        outcomes.add(label + " -> " + outcome);
    }

    @Override
    @SuppressWarnings({"deprecation", "removal"})
    public List<String> call() throws Exception {
        InetAddress ten = InetAddress.getByAddress(new byte[] {10, 0, 0, 1});
        InetAddress loopback = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        SocketAddress anywhere = new InetSocketAddress(18100);
        SocketAddress remote = InetSocketAddress.createUnresolved("example.invalid", 80);
        DatagramPacket packet = new DatagramPacket(new byte[4], 4, ten, 18200);
        ByteBuffer buffer = ByteBuffer.allocate(4);

        attempt("ServerSocket(int)", () -> new ServerSocket(18001));
        attempt("ServerSocket(int,int)", () -> new ServerSocket(18002, 5));
        attempt("ServerSocket(int,int,InetAddress)", () -> new ServerSocket(18003, 5, loopback));
        attempt("ServerSocket.bind(SocketAddress)", () -> {
            try (ServerSocket socket = new ServerSocket()) {
                socket.bind(anywhere);
                return socket;
            }
        });
        attempt("ServerSocket.bind(SocketAddress,int)", () -> {
            try (ServerSocket socket = new ServerSocket()) {
                socket.bind(anywhere, 5);
                return socket;
            }
        });
        attempt("SSLServerSocket.bind(SocketAddress)", () -> {
            SSLServerSocket socket = null;
            socket.bind(anywhere);
            return socket;
        });
        attempt("ServerSocketFactory.createServerSocket(int)", () -> {
            ServerSocketFactory factory = null;
            return factory.createServerSocket(18004);
        });
        attempt("ServerSocketFactory.createServerSocket(int,int)", () -> {
            ServerSocketFactory factory = null;
            return factory.createServerSocket(18005, 5);
        });
        attempt("SSLServerSocketFactory.createServerSocket(int,int,InetAddress)", () -> {
            SSLServerSocketFactory factory = null;
            return factory.createServerSocket(18006, 5, loopback);
        });
        attempt("ServerSocketChannel.bind(SocketAddress)", () -> {
            try (ServerSocketChannel channel = ServerSocketChannel.open()) {
                return channel.bind(anywhere);
            }
        });
        attempt("ServerSocketChannel.bind(SocketAddress,int)", () -> {
            try (ServerSocketChannel channel = ServerSocketChannel.open()) {
                return channel.bind(anywhere, 5);
            }
        });
        attempt("NetworkChannel.bind(SocketAddress) on a server channel", () -> {
            try (ServerSocketChannel channel = ServerSocketChannel.open()) {
                NetworkChannel network = channel;
                return network.bind(anywhere);
            }
        });
        attempt("NetworkChannel.bind(SocketAddress) on a client channel", () -> {
            try (SocketChannel channel = SocketChannel.open()) {
                NetworkChannel network = channel;
                return network.bind(new InetSocketAddress(loopback, 0));
            }
        });
        attempt("AsynchronousServerSocketChannel.bind(SocketAddress)", () -> {
            try (AsynchronousServerSocketChannel channel = AsynchronousServerSocketChannel.open()) {
                return channel.bind(anywhere);
            }
        });
        attempt("AsynchronousServerSocketChannel.bind(SocketAddress,int)", () -> {
            try (AsynchronousServerSocketChannel channel = AsynchronousServerSocketChannel.open()) {
                return channel.bind(anywhere, 5);
            }
        });

        attempt("Socket(String,int)", () -> new Socket("example.invalid", 80));
        attempt("Socket(InetAddress,int)", () -> new Socket(ten, 81));
        attempt("Socket(String,int,InetAddress,int)", () -> new Socket("example.invalid", 82, loopback, 0));
        attempt("Socket(InetAddress,int,InetAddress,int)", () -> new Socket(ten, 83, loopback, 0));
        attempt("Socket(String,int,boolean)", () -> new Socket("example.invalid", 84, true));
        attempt("Socket(InetAddress,int,boolean)", () -> new Socket(ten, 85, true));
        attempt("Socket(String,int) with a null host", () -> new Socket((String) null, 86));
        attempt("Socket.connect(SocketAddress)", () -> {
            try (Socket socket = new Socket()) {
                socket.connect(remote);
                return socket;
            }
        });
        attempt("Socket.connect(SocketAddress,int)", () -> {
            try (Socket socket = new Socket()) {
                socket.connect(remote, 500);
                return socket;
            }
        });
        attempt("SSLSocket.connect(SocketAddress)", () -> {
            SSLSocket socket = null;
            socket.connect(remote);
            return socket;
        });
        attempt("SocketFactory.createSocket(String,int)", () -> {
            SocketFactory factory = null;
            return factory.createSocket("example.invalid", 87);
        });
        attempt("SocketFactory.createSocket(InetAddress,int)", () -> {
            SocketFactory factory = null;
            return factory.createSocket(ten, 88);
        });
        attempt("SSLSocketFactory.createSocket(String,int,InetAddress,int)", () -> {
            SSLSocketFactory factory = null;
            return factory.createSocket("example.invalid", 89, loopback, 0);
        });
        attempt("SocketFactory.createSocket(InetAddress,int,InetAddress,int)", () -> {
            SocketFactory factory = null;
            return factory.createSocket(ten, 90, loopback, 0);
        });
        attempt("SocketChannel.open(SocketAddress)", () -> SocketChannel.open(remote));
        attempt("SocketChannel.connect(SocketAddress)", () -> {
            try (SocketChannel channel = SocketChannel.open()) {
                return channel.connect(remote);
            }
        });
        attempt("AsynchronousSocketChannel.connect(SocketAddress)", () -> {
            try (AsynchronousSocketChannel channel = AsynchronousSocketChannel.open()) {
                Future<Void> connected = channel.connect(remote);
                connected.get(5, TimeUnit.SECONDS);
                return channel;
            }
        });
        attempt("AsynchronousSocketChannel.connect(SocketAddress,A,CompletionHandler)", () -> {
            try (AsynchronousSocketChannel channel = AsynchronousSocketChannel.open()) {
                List<Throwable> failures = new ArrayList<>();
                channel.connect(remote, "attachment", new CompletionHandler<Void, String>() {
                    @Override
                    public void completed(Void result, String attachment) {
                        failures.add(null);
                    }

                    @Override
                    public void failed(Throwable failure, String attachment) {
                        failures.add(failure);
                    }
                });
                throw (IOException) failures.get(0);
            }
        });

        attempt("DatagramSocket()", () -> new DatagramSocket());
        attempt("DatagramSocket(int)", () -> new DatagramSocket(18101));
        attempt("DatagramSocket(int,InetAddress)", () -> new DatagramSocket(18102, loopback));
        attempt("DatagramSocket(SocketAddress)", () -> new DatagramSocket(anywhere));
        attempt("DatagramSocket((SocketAddress) null) stays unbound", () -> new DatagramSocket((SocketAddress) null));
        attempt("DatagramSocket.bind(SocketAddress)", () -> {
            try (DatagramSocket socket = new DatagramSocket((SocketAddress) null)) {
                socket.bind(anywhere);
                return socket;
            }
        });
        attempt("DatagramSocket.connect(SocketAddress)", () -> {
            try (DatagramSocket socket = new DatagramSocket((SocketAddress) null)) {
                socket.connect(remote);
                return socket;
            }
        });
        attempt("DatagramSocket.connect(InetAddress,int) leaves the socket unconnected", () -> {
            try (DatagramSocket socket = new DatagramSocket((SocketAddress) null)) {
                socket.connect(ten, 18201);
                if (socket.isConnected()) {
                    throw new IllegalStateException("connected");
                }
                return socket;
            }
        });
        attempt("DatagramSocket.send(DatagramPacket)", () -> {
            try (DatagramSocket socket = new DatagramSocket((SocketAddress) null)) {
                socket.send(packet);
                return socket;
            }
        });
        attempt("MulticastSocket()", () -> new MulticastSocket());
        attempt("MulticastSocket(int)", () -> new MulticastSocket(18103));
        attempt("MulticastSocket(SocketAddress)", () -> new MulticastSocket(anywhere));
        attempt("MulticastSocket.send(DatagramPacket) through the subtype", () -> {
            try (MulticastSocket socket = new MulticastSocket((SocketAddress) null)) {
                socket.send(packet);
                return socket;
            }
        });
        attempt("MulticastSocket.send(DatagramPacket,byte)", () -> {
            try (MulticastSocket socket = new MulticastSocket((SocketAddress) null)) {
                socket.send(packet, (byte) 1);
                return socket;
            }
        });
        attempt("DatagramChannel.bind(SocketAddress)", () -> {
            try (DatagramChannel channel = DatagramChannel.open()) {
                return channel.bind(anywhere);
            }
        });
        attempt("DatagramChannel.connect(SocketAddress)", () -> {
            try (DatagramChannel channel = DatagramChannel.open()) {
                return channel.connect(remote);
            }
        });
        attempt("DatagramChannel.send(ByteBuffer,SocketAddress)", () -> {
            try (DatagramChannel channel = DatagramChannel.open()) {
                return channel.send(buffer, remote);
            }
        });

        attempt("URL.openConnection() for http", () -> URI.create("http://example.invalid:8080/a").toURL().openConnection());
        attempt("URL.openConnection(Proxy) for https", () -> URI.create("https://example.invalid/b").toURL().openConnection(Proxy.NO_PROXY));
        attempt("URL.openStream() for ftp", () -> URI.create("ftp://example.invalid/c").toURL().openStream());
        attempt("URL.getContent() for http", () -> URI.create("http://example.invalid/i").toURL().getContent());
        attempt("URL.getContent(Class[]) for http", () -> URI.create("http://example.invalid/j").toURL().getContent(new Class<?>[] {String.class}));
        attempt("URL.openStream() for a jar fetched over http", () -> URI.create("jar:http://example.invalid/d.jar!/e").toURL().openStream());
        attempt("URL.openStream() for a resource of the program", () -> {
            try (var stream = NetworkProbe.class.getResource("NetworkProbe.class").openStream()) {
                return stream.readNBytes(4).length == 4 ? "read" : "short";
            }
        });
        attempt("HttpClient.send(HttpRequest,BodyHandler)", () -> {
            HttpClient client = null;
            return client.send(HttpRequest.newBuilder(URI.create("http://example.invalid/f")).build(), HttpResponse.BodyHandlers.ofString());
        });
        attempt("HttpClient.sendAsync(HttpRequest,BodyHandler)", () -> {
            HttpClient client = null;
            return client.sendAsync(HttpRequest.newBuilder(URI.create("https://example.invalid/g")).build(), HttpResponse.BodyHandlers.ofString())
                    .get(5, TimeUnit.SECONDS);
        });
        attempt("HttpClient.sendAsync(HttpRequest,BodyHandler,PushPromiseHandler)", () -> {
            HttpClient client = null;
            return client.sendAsync(
                            HttpRequest.newBuilder(URI.create("http://example.invalid/h")).build(),
                            HttpResponse.BodyHandlers.ofString(),
                            null)
                    .get(5, TimeUnit.SECONDS);
        });
        return outcomes;
    }
}
