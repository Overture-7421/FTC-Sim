package dev.ftcsim.contracts.hooks;

import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.URL;
import java.net.URLConnection;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import javax.net.ServerSocketFactory;
import javax.net.SocketFactory;

/**
 * Entry points that rewritten robot bytecode calls instead of the JDK's network operations. Each one asks the
 * installed hooks whether the access is allowed ({@link RobotHooks#allowNetwork}); if so it performs the real
 * JDK operation, so an allowed call behaves exactly as it would unmodified, and if not it fails the way a closed
 * port fails and remembers the request, see {@link #declinedRequests()}.
 *
 * <p>Listening is different when the host simulates listeners ({@link RobotHooks#simulateListeners}, which is the
 * default): a {@code ServerSocket} or {@code ServerSocketChannel} then gets a listener in the private network of
 * the robot world, which binds nothing on the machine and which nobody can connect to. The request is remembered in
 * {@link #simulatedRequests()}, so that a host can tell its user that the program's ports are simulated.
 *
 * <p>An instance method becomes a static method whose first parameter is the receiver, and a constructor becomes
 * a factory method returning the new object. Only operations that bind, listen, connect or send are covered;
 * creating an unbound socket or channel touches no network, so it stays the JDK's, except where the object has to
 * be one of the simulated kind from the start.
 */
public final class NetworkHooks {
    private static final int MAX_DISTINCT_RECORDED = 256;
    private static final int DEFAULT_BACKLOG = 50;
    private static final String ANY_ADDRESS = "*";

    private static final RequestLog DECLINED = new RequestLog();
    private static final RequestLog SIMULATED = new RequestLog();
    private static volatile SimulatedNetwork network = new SimulatedNetwork();

    private NetworkHooks() {}

    /** The requests the policy declined, oldest first, with how often each was made; the most recent 256 distinct ones. */
    public static Map<NetworkRequest, Long> declinedRequests() {
        return DECLINED.snapshot();
    }

    /**
     * The requests to listen that were answered with a simulated listener, oldest first, with how often each was
     * made; the most recent 256 distinct ones. Nothing on the machine listens for them and no client can connect.
     */
    public static Map<NetworkRequest, Long> simulatedRequests() {
        return SIMULATED.snapshot();
    }

    /** Forgets the recorded requests, the declined and the simulated ones. */
    public static void clearDeclinedRequests() {
        DECLINED.clear();
        SIMULATED.clear();
    }

    /**
     * Closes the listeners of the robot world, which wakes the threads that wait to accept on them with the exception a
     * closed socket gives. The end of a robot's process does that to its sockets; a host calls it when it ends the robot
     * world and the JVM goes on.
     */
    public static void closeSimulatedListeners() {
        network.closeAll();
    }

    /**
     * Starts an empty private network; hosts do this by installing their hooks, so that the ports of one world are
     * free in the next. The listeners of the network that ended are left as they are: their world is gone, and a
     * library that loops on {@code accept} would spin if they were closed under it.
     */
    static void reset() {
        network = new SimulatedNetwork();
    }

    /** What was asked, with how often, most recent 256 distinct requests. */
    private static final class RequestLog {
        private final Map<NetworkRequest, Long> requests = new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<NetworkRequest, Long> eldest) {
                return size() > MAX_DISTINCT_RECORDED;
            }
        };

        synchronized void record(NetworkRequest request) {
            requests.merge(request, 1L, Long::sum);
        }

        synchronized Map<NetworkRequest, Long> snapshot() {
            return new LinkedHashMap<>(requests);
        }

        synchronized void clear() {
            requests.clear();
        }
    }

    /** The failure to report when the request is declined, already recorded; {@code null} when it is allowed. */
    private static IOException check(NetworkRequest.Kind kind, String target) {
        NetworkRequest request = new NetworkRequest(kind, target);
        if (Hooks.allowNetwork(request)) {
            return null;
        }
        DECLINED.record(request);
        return request.refusal();
    }

    private static void require(NetworkRequest.Kind kind, String target) throws IOException {
        IOException refusal = check(kind, target);
        if (refusal != null) {
            throw refusal;
        }
    }

    private static String endpoint(SocketAddress address) {
        if (address instanceof InetSocketAddress inet) {
            return inet.isUnresolved() ? inet.getHostString() + ':' + inet.getPort() : endpoint(inet.getAddress(), inet.getPort());
        }
        return address == null ? ANY_ADDRESS + ":0" : address.toString();
    }

    /** A null address stands for the wildcard address, which is what the JDK binds to when given none. */
    private static String endpoint(InetAddress address, int port) {
        if (address == null || address.isAnyLocalAddress()) {
            return ANY_ADDRESS + ':' + port;
        }
        // toString is "name/literal", or "/literal" when no name is known; unlike getHostName it never asks DNS
        String text = address.toString();
        int slash = text.indexOf('/');
        return (slash > 0 ? text.substring(0, slash) : address.getHostAddress()) + ':' + port;
    }

    private static String endpoint(String host, int port) {
        return (host == null ? "localhost" : host) + ':' + port;
    }

    /**
     * Binds a simulated listener, which is where the request is remembered. Binding takes a port of the private
     * network, so a second listener on a port that is held, whatever the address, fails as it would on a machine.
     */
    static void bindSimulated(SimulatedListener listener, SocketAddress endpoint) throws IOException {
        if (endpoint != null && !(endpoint instanceof InetSocketAddress)) {
            throw new IllegalArgumentException("Unsupported address type");
        }
        InetSocketAddress address = endpoint == null ? new InetSocketAddress(0) : (InetSocketAddress) endpoint;
        if (address.isUnresolved()) {
            throw new SocketException("Unresolved address");
        }
        listener.bind(address);
        SIMULATED.record(new NetworkRequest(NetworkRequest.Kind.LISTEN, endpoint(address)));
    }

    // ServerSocket

    /** An unbound socket has to be a simulated one from the start, because only it can wait for an accept that never comes. */
    public static ServerSocket newServerSocket() throws IOException {
        return Hooks.simulateListeners() ? new SimulatedServerSocket(new SimulatedListener(network), null) : new ServerSocket();
    }

    public static ServerSocket newServerSocket(int port) throws IOException {
        return newServerSocket(port, DEFAULT_BACKLOG, null);
    }

    public static ServerSocket newServerSocket(int port, int backlog) throws IOException {
        return newServerSocket(port, backlog, null);
    }

    public static ServerSocket newServerSocket(int port, int backlog, InetAddress bindAddress) throws IOException {
        if (Hooks.simulateListeners()) {
            ServerSocket socket = newServerSocket();
            try {
                socket.bind(new InetSocketAddress(bindAddress, port), backlog);
            } catch (IOException | RuntimeException failure) {
                socket.close();
                throw failure;
            }
            return socket;
        }
        require(NetworkRequest.Kind.LISTEN, endpoint(bindAddress, port));
        return new ServerSocket(port, backlog, bindAddress);
    }

    public static void bind(ServerSocket socket, SocketAddress endpoint) throws IOException {
        if (!(socket instanceof SimulatedServerSocket)) {
            require(NetworkRequest.Kind.LISTEN, endpoint(endpoint));
        }
        socket.bind(endpoint);
    }

    public static void bind(ServerSocket socket, SocketAddress endpoint, int backlog) throws IOException {
        if (!(socket instanceof SimulatedServerSocket)) {
            require(NetworkRequest.Kind.LISTEN, endpoint(endpoint));
        }
        socket.bind(endpoint, backlog);
    }

    /** Only the default factory simulates; it is the one that, as the JDK's, makes {@code new ServerSocket()}. */
    private static boolean simulates(ServerSocketFactory factory) {
        return Hooks.simulateListeners() && factory == ServerSocketFactory.getDefault();
    }

    public static ServerSocket createServerSocket(ServerSocketFactory factory) throws IOException {
        return simulates(factory) ? newServerSocket() : factory.createServerSocket();
    }

    public static ServerSocket createServerSocket(ServerSocketFactory factory, int port) throws IOException {
        return createServerSocket(factory, port, DEFAULT_BACKLOG, null);
    }

    public static ServerSocket createServerSocket(ServerSocketFactory factory, int port, int backlog) throws IOException {
        return createServerSocket(factory, port, backlog, null);
    }

    public static ServerSocket createServerSocket(ServerSocketFactory factory, int port, int backlog, InetAddress bindAddress)
            throws IOException {
        if (simulates(factory)) {
            return newServerSocket(port, backlog, bindAddress);
        }
        require(NetworkRequest.Kind.LISTEN, endpoint(bindAddress, port));
        return factory.createServerSocket(port, backlog, bindAddress);
    }

    // Socket

    public static Socket newSocket(String host, int port) throws IOException {
        require(NetworkRequest.Kind.CONNECT, endpoint(host, port));
        return new Socket(host, port);
    }

    public static Socket newSocket(InetAddress address, int port) throws IOException {
        require(NetworkRequest.Kind.CONNECT, endpoint(address, port));
        return new Socket(address, port);
    }

    public static Socket newSocket(String host, int port, InetAddress localAddress, int localPort) throws IOException {
        require(NetworkRequest.Kind.CONNECT, endpoint(host, port));
        return new Socket(host, port, localAddress, localPort);
    }

    public static Socket newSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
        require(NetworkRequest.Kind.CONNECT, endpoint(address, port));
        return new Socket(address, port, localAddress, localPort);
    }

    @SuppressWarnings({"deprecation", "removal"})
    public static Socket newSocket(String host, int port, boolean stream) throws IOException {
        require(NetworkRequest.Kind.CONNECT, endpoint(host, port));
        return new Socket(host, port, stream);
    }

    @SuppressWarnings({"deprecation", "removal"})
    public static Socket newSocket(InetAddress address, int port, boolean stream) throws IOException {
        require(NetworkRequest.Kind.CONNECT, endpoint(address, port));
        return new Socket(address, port, stream);
    }

    public static void connect(Socket socket, SocketAddress endpoint) throws IOException {
        require(NetworkRequest.Kind.CONNECT, endpoint(endpoint));
        socket.connect(endpoint);
    }

    public static void connect(Socket socket, SocketAddress endpoint, int timeoutMillis) throws IOException {
        require(NetworkRequest.Kind.CONNECT, endpoint(endpoint));
        socket.connect(endpoint, timeoutMillis);
    }

    public static Socket createSocket(SocketFactory factory, String host, int port) throws IOException {
        require(NetworkRequest.Kind.CONNECT, endpoint(host, port));
        return factory.createSocket(host, port);
    }

    public static Socket createSocket(SocketFactory factory, InetAddress address, int port) throws IOException {
        require(NetworkRequest.Kind.CONNECT, endpoint(address, port));
        return factory.createSocket(address, port);
    }

    public static Socket createSocket(SocketFactory factory, String host, int port, InetAddress localAddress, int localPort)
            throws IOException {
        require(NetworkRequest.Kind.CONNECT, endpoint(host, port));
        return factory.createSocket(host, port, localAddress, localPort);
    }

    public static Socket createSocket(SocketFactory factory, InetAddress address, int port, InetAddress localAddress, int localPort)
            throws IOException {
        require(NetworkRequest.Kind.CONNECT, endpoint(address, port));
        return factory.createSocket(address, port, localAddress, localPort);
    }

    // Datagram sockets

    public static DatagramSocket newDatagramSocket() throws IOException {
        require(NetworkRequest.Kind.LISTEN, endpoint((InetAddress) null, 0));
        return new DatagramSocket();
    }

    public static DatagramSocket newDatagramSocket(int port) throws IOException {
        require(NetworkRequest.Kind.LISTEN, endpoint((InetAddress) null, port));
        return new DatagramSocket(port);
    }

    public static DatagramSocket newDatagramSocket(int port, InetAddress localAddress) throws IOException {
        require(NetworkRequest.Kind.LISTEN, endpoint(localAddress, port));
        return new DatagramSocket(port, localAddress);
    }

    /** A null address creates a socket that is not bound, which touches no network. */
    public static DatagramSocket newDatagramSocket(SocketAddress bindAddress) throws IOException {
        if (bindAddress != null) {
            require(NetworkRequest.Kind.LISTEN, endpoint(bindAddress));
        }
        return new DatagramSocket(bindAddress);
    }

    public static MulticastSocket newMulticastSocket() throws IOException {
        require(NetworkRequest.Kind.LISTEN, endpoint((InetAddress) null, 0));
        return new MulticastSocket();
    }

    public static MulticastSocket newMulticastSocket(int port) throws IOException {
        require(NetworkRequest.Kind.LISTEN, endpoint((InetAddress) null, port));
        return new MulticastSocket(port);
    }

    public static MulticastSocket newMulticastSocket(SocketAddress bindAddress) throws IOException {
        if (bindAddress != null) {
            require(NetworkRequest.Kind.LISTEN, endpoint(bindAddress));
        }
        return new MulticastSocket(bindAddress);
    }

    public static void bind(DatagramSocket socket, SocketAddress address) throws IOException {
        require(NetworkRequest.Kind.LISTEN, endpoint(address));
        socket.bind(address);
    }

    public static void connect(DatagramSocket socket, SocketAddress address) throws IOException {
        require(NetworkRequest.Kind.SEND, endpoint(address));
        socket.connect(address);
    }

    /**
     * The JDK ignores a failure to connect here, so a declined connect leaves the socket unconnected and, as with
     * a failed one, the next send reports the problem.
     */
    public static void connect(DatagramSocket socket, InetAddress address, int port) {
        if (check(NetworkRequest.Kind.SEND, endpoint(address, port)) == null) {
            socket.connect(address, port);
        }
    }

    public static void send(DatagramSocket socket, DatagramPacket packet) throws IOException {
        SocketAddress destination = packet.getAddress() == null ? socket.getRemoteSocketAddress() : packet.getSocketAddress();
        require(NetworkRequest.Kind.SEND, endpoint(destination));
        socket.send(packet);
    }

    @SuppressWarnings({"deprecation", "removal"})
    public static void send(MulticastSocket socket, DatagramPacket packet, byte ttl) throws IOException {
        require(NetworkRequest.Kind.SEND, endpoint(packet.getSocketAddress()));
        socket.send(packet, ttl);
    }

    // Channels

    /** An unbound channel has to be a simulated one from the start, because only it can wait for an accept that never comes. */
    public static ServerSocketChannel openServerSocketChannel() throws IOException {
        return Hooks.simulateListeners()
                ? new SimulatedServerSocketChannel(new SimulatedListener(network))
                : ServerSocketChannel.open();
    }

    public static ServerSocketChannel bind(ServerSocketChannel channel, SocketAddress local) throws IOException {
        if (!(channel instanceof SimulatedServerSocketChannel)) {
            require(NetworkRequest.Kind.LISTEN, endpoint(local));
        }
        return channel.bind(local);
    }

    public static ServerSocketChannel bind(ServerSocketChannel channel, SocketAddress local, int backlog) throws IOException {
        if (!(channel instanceof SimulatedServerSocketChannel)) {
            require(NetworkRequest.Kind.LISTEN, endpoint(local));
        }
        return channel.bind(local, backlog);
    }

    public static AsynchronousServerSocketChannel bind(AsynchronousServerSocketChannel channel, SocketAddress local)
            throws IOException {
        require(NetworkRequest.Kind.LISTEN, endpoint(local));
        return channel.bind(local);
    }

    public static AsynchronousServerSocketChannel bind(AsynchronousServerSocketChannel channel, SocketAddress local, int backlog)
            throws IOException {
        require(NetworkRequest.Kind.LISTEN, endpoint(local));
        return channel.bind(local, backlog);
    }

    public static DatagramChannel bind(DatagramChannel channel, SocketAddress local) throws IOException {
        require(NetworkRequest.Kind.LISTEN, endpoint(local));
        return channel.bind(local);
    }

    /**
     * Code written against the common interface; only the channels that accept traffic from anywhere need the
     * host's permission, because a client channel's local address is not a listener.
     */
    public static NetworkChannel bind(NetworkChannel channel, SocketAddress local) throws IOException {
        if ((channel instanceof ServerSocketChannel && !(channel instanceof SimulatedServerSocketChannel))
                || channel instanceof AsynchronousServerSocketChannel
                || channel instanceof DatagramChannel) {
            require(NetworkRequest.Kind.LISTEN, endpoint(local));
        }
        return channel.bind(local);
    }

    public static SocketChannel openSocketChannel(SocketAddress remote) throws IOException {
        require(NetworkRequest.Kind.CONNECT, endpoint(remote));
        return SocketChannel.open(remote);
    }

    public static boolean connect(SocketChannel channel, SocketAddress remote) throws IOException {
        require(NetworkRequest.Kind.CONNECT, endpoint(remote));
        return channel.connect(remote);
    }

    public static Future<Void> connect(AsynchronousSocketChannel channel, SocketAddress remote) {
        IOException refusal = check(NetworkRequest.Kind.CONNECT, endpoint(remote));
        return refusal == null ? channel.connect(remote) : CompletableFuture.failedFuture(refusal);
    }

    public static <A> void connect(
            AsynchronousSocketChannel channel, SocketAddress remote, A attachment, CompletionHandler<Void, ? super A> handler) {
        IOException refusal = check(NetworkRequest.Kind.CONNECT, endpoint(remote));
        if (refusal == null) {
            channel.connect(remote, attachment, handler);
        } else {
            handler.failed(refusal, attachment);
        }
    }

    public static DatagramChannel connect(DatagramChannel channel, SocketAddress remote) throws IOException {
        require(NetworkRequest.Kind.SEND, endpoint(remote));
        return channel.connect(remote);
    }

    public static int send(DatagramChannel channel, ByteBuffer source, SocketAddress target) throws IOException {
        require(NetworkRequest.Kind.SEND, endpoint(target));
        return channel.send(source, target);
    }

    // URLs and HTTP

    public static URLConnection openConnection(URL url) throws IOException {
        requireForUrl(url);
        return url.openConnection();
    }

    public static URLConnection openConnection(URL url, Proxy proxy) throws IOException {
        requireForUrl(url);
        return url.openConnection(proxy);
    }

    public static InputStream openStream(URL url) throws IOException {
        requireForUrl(url);
        return url.openStream();
    }

    public static Object getContent(URL url) throws IOException {
        requireForUrl(url);
        return url.getContent();
    }

    public static Object getContent(URL url, Class<?>[] types) throws IOException {
        requireForUrl(url);
        return url.getContent(types);
    }

    /** Resources are read through {@code file:} and {@code jar:file:} URLs; only the protocols that reach a server count. */
    private static void requireForUrl(URL url) throws IOException {
        String protocol = url.getProtocol();
        if ("jar".equals(protocol)) {
            String inner = url.getPath();
            int end = inner.indexOf("!/");
            String nested = end < 0 ? inner : inner.substring(0, end);
            if (nested.startsWith("http:") || nested.startsWith("https:") || nested.startsWith("ftp:")) {
                require(NetworkRequest.Kind.CONNECT, "jar:" + nested);
            }
        } else if ("http".equals(protocol) || "https".equals(protocol) || "ftp".equals(protocol)) {
            int port = url.getPort() < 0 ? url.getDefaultPort() : url.getPort();
            require(NetworkRequest.Kind.CONNECT, protocol + "://" + url.getHost() + ':' + port);
        }
    }

    public static <T> HttpResponse<T> send(HttpClient client, HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        require(NetworkRequest.Kind.CONNECT, requestTarget(request));
        return client.send(request, handler);
    }

    public static <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpClient client, HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        IOException refusal = check(NetworkRequest.Kind.CONNECT, requestTarget(request));
        return refusal == null ? client.sendAsync(request, handler) : CompletableFuture.failedFuture(refusal);
    }

    public static <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpClient client,
            HttpRequest request,
            HttpResponse.BodyHandler<T> handler,
            HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
        IOException refusal = check(NetworkRequest.Kind.CONNECT, requestTarget(request));
        return refusal == null
                ? client.sendAsync(request, handler, pushPromiseHandler)
                : CompletableFuture.failedFuture(refusal);
    }

    private static String requestTarget(HttpRequest request) {
        java.net.URI uri = request.uri();
        int port = uri.getPort() < 0 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort();
        return uri.getScheme() + "://" + uri.getHost() + ':' + port;
    }
}
