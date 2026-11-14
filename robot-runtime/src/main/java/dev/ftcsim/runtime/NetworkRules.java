package dev.ftcsim.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The JDK's ways of listening, connecting and sending, redirected to {@code NetworkHooks} so the host decides
 * whether robot code may use the network. Each row names the JDK member; the replacement has the same name unless
 * given, and its descriptor follows from the member's: the receiver of an instance method becomes the first
 * parameter and a constructor becomes a factory that returns the new object.
 *
 * <p>Only members that bind, listen, connect or send are listed. Creating an unbound socket or channel touches no
 * network, so it stays the JDK's, except that an unbound {@code ServerSocket} or {@code ServerSocketChannel} is
 * made by the hooks, because it must be a simulated one from the start for the host to simulate the listener.
 */
final class NetworkRules {
    static final String HOOKS = "dev/ftcsim/contracts/hooks/NetworkHooks";

    private static final String SERVER_SOCKET = "java/net/ServerSocket";
    private static final String SOCKET = "java/net/Socket";
    private static final String DATAGRAM_SOCKET = "java/net/DatagramSocket";
    private static final String MULTICAST_SOCKET = "java/net/MulticastSocket";
    private static final String URL = "java/net/URL";
    private static final String HTTP_CLIENT = "java/net/http/HttpClient";
    private static final String SERVER_SOCKET_FACTORY = "javax/net/ServerSocketFactory";
    private static final String SOCKET_FACTORY = "javax/net/SocketFactory";
    private static final String SERVER_SOCKET_CHANNEL = "java/nio/channels/ServerSocketChannel";
    private static final String SOCKET_CHANNEL = "java/nio/channels/SocketChannel";
    private static final String DATAGRAM_CHANNEL = "java/nio/channels/DatagramChannel";
    private static final String NETWORK_CHANNEL = "java/nio/channels/NetworkChannel";
    private static final String ASYNC_SERVER_CHANNEL = "java/nio/channels/AsynchronousServerSocketChannel";
    private static final String ASYNC_SOCKET_CHANNEL = "java/nio/channels/AsynchronousSocketChannel";

    /**
     * Call sites name the type the receiver was declared as, so a call through a subtype misses the rule written
     * for its supertype; these subtypes share the rules of the type they refine.
     */
    static final Map<String, String> SUBTYPES = Map.of(
            MULTICAST_SOCKET, DATAGRAM_SOCKET,
            "javax/net/ssl/SSLSocket", SOCKET,
            "javax/net/ssl/SSLServerSocket", SERVER_SOCKET,
            "javax/net/ssl/SSLSocketFactory", SOCKET_FACTORY,
            "javax/net/ssl/SSLServerSocketFactory", SERVER_SOCKET_FACTORY);

    private NetworkRules() {}

    static List<RewriteRule> all() {
        List<RewriteRule> rules = new ArrayList<>();

        constructors(rules, SERVER_SOCKET, "newServerSocket", "()V", "(I)V", "(II)V", "(IILjava/net/InetAddress;)V");
        instance(rules, SERVER_SOCKET, "bind", "bind", "(Ljava/net/SocketAddress;)V", "(Ljava/net/SocketAddress;I)V");
        instance(rules, SERVER_SOCKET_FACTORY, "createServerSocket", "createServerSocket",
                "()Ljava/net/ServerSocket;", "(I)Ljava/net/ServerSocket;", "(II)Ljava/net/ServerSocket;",
                "(IILjava/net/InetAddress;)Ljava/net/ServerSocket;");

        constructors(rules, SOCKET, "newSocket",
                "(Ljava/lang/String;I)V",
                "(Ljava/net/InetAddress;I)V",
                "(Ljava/lang/String;ILjava/net/InetAddress;I)V",
                "(Ljava/net/InetAddress;ILjava/net/InetAddress;I)V",
                "(Ljava/lang/String;IZ)V",
                "(Ljava/net/InetAddress;IZ)V");
        instance(rules, SOCKET, "connect", "connect", "(Ljava/net/SocketAddress;)V", "(Ljava/net/SocketAddress;I)V");
        instance(rules, SOCKET_FACTORY, "createSocket", "createSocket",
                "(Ljava/lang/String;I)Ljava/net/Socket;",
                "(Ljava/net/InetAddress;I)Ljava/net/Socket;",
                "(Ljava/lang/String;ILjava/net/InetAddress;I)Ljava/net/Socket;",
                "(Ljava/net/InetAddress;ILjava/net/InetAddress;I)Ljava/net/Socket;");

        constructors(rules, DATAGRAM_SOCKET, "newDatagramSocket", "()V", "(I)V", "(ILjava/net/InetAddress;)V", "(Ljava/net/SocketAddress;)V");
        constructors(rules, MULTICAST_SOCKET, "newMulticastSocket", "()V", "(I)V", "(Ljava/net/SocketAddress;)V");
        instance(rules, DATAGRAM_SOCKET, "bind", "bind", "(Ljava/net/SocketAddress;)V");
        instance(rules, DATAGRAM_SOCKET, "connect", "connect", "(Ljava/net/SocketAddress;)V", "(Ljava/net/InetAddress;I)V");
        instance(rules, DATAGRAM_SOCKET, "send", "send", "(Ljava/net/DatagramPacket;)V");
        instance(rules, MULTICAST_SOCKET, "send", "send", "(Ljava/net/DatagramPacket;B)V");

        rules.add(new RewriteRule(SERVER_SOCKET_CHANNEL, "open", "()Ljava/nio/channels/ServerSocketChannel;",
                HOOKS, "openServerSocketChannel", "()Ljava/nio/channels/ServerSocketChannel;", false));
        instance(rules, SERVER_SOCKET_CHANNEL, "bind", "bind",
                "(Ljava/net/SocketAddress;)Ljava/nio/channels/ServerSocketChannel;",
                "(Ljava/net/SocketAddress;I)Ljava/nio/channels/ServerSocketChannel;");
        instance(rules, ASYNC_SERVER_CHANNEL, "bind", "bind",
                "(Ljava/net/SocketAddress;)Ljava/nio/channels/AsynchronousServerSocketChannel;",
                "(Ljava/net/SocketAddress;I)Ljava/nio/channels/AsynchronousServerSocketChannel;");
        instance(rules, DATAGRAM_CHANNEL, "bind", "bind", "(Ljava/net/SocketAddress;)Ljava/nio/channels/DatagramChannel;");
        instance(rules, DATAGRAM_CHANNEL, "connect", "connect", "(Ljava/net/SocketAddress;)Ljava/nio/channels/DatagramChannel;");
        instance(rules, DATAGRAM_CHANNEL, "send", "send", "(Ljava/nio/ByteBuffer;Ljava/net/SocketAddress;)I");
        instance(rules, NETWORK_CHANNEL, "bind", "bind", "(Ljava/net/SocketAddress;)Ljava/nio/channels/NetworkChannel;");

        rules.add(new RewriteRule(SOCKET_CHANNEL, "open", "(Ljava/net/SocketAddress;)Ljava/nio/channels/SocketChannel;",
                HOOKS, "openSocketChannel", "(Ljava/net/SocketAddress;)Ljava/nio/channels/SocketChannel;", false));
        instance(rules, SOCKET_CHANNEL, "connect", "connect", "(Ljava/net/SocketAddress;)Z");
        instance(rules, ASYNC_SOCKET_CHANNEL, "connect", "connect",
                "(Ljava/net/SocketAddress;)Ljava/util/concurrent/Future;",
                "(Ljava/net/SocketAddress;Ljava/lang/Object;Ljava/nio/channels/CompletionHandler;)V");

        instance(rules, URL, "openConnection", "openConnection",
                "()Ljava/net/URLConnection;", "(Ljava/net/Proxy;)Ljava/net/URLConnection;");
        instance(rules, URL, "openStream", "openStream", "()Ljava/io/InputStream;");
        instance(rules, URL, "getContent", "getContent", "()Ljava/lang/Object;", "([Ljava/lang/Class;)Ljava/lang/Object;");

        instance(rules, HTTP_CLIENT, "send", "send",
                "(Ljava/net/http/HttpRequest;Ljava/net/http/HttpResponse$BodyHandler;)Ljava/net/http/HttpResponse;");
        instance(rules, HTTP_CLIENT, "sendAsync", "sendAsync",
                "(Ljava/net/http/HttpRequest;Ljava/net/http/HttpResponse$BodyHandler;)Ljava/util/concurrent/CompletableFuture;",
                "(Ljava/net/http/HttpRequest;Ljava/net/http/HttpResponse$BodyHandler;Ljava/net/http/HttpResponse$PushPromiseHandler;)"
                        + "Ljava/util/concurrent/CompletableFuture;");
        return List.copyOf(rules);
    }

    private static void constructors(List<RewriteRule> rules, String owner, String factory, String... descriptors) {
        for (String descriptor : descriptors) {
            String returning = descriptor.substring(0, descriptor.length() - 1) + 'L' + owner + ';';
            rules.add(new RewriteRule(owner, RewriteRule.CONSTRUCTOR, descriptor, HOOKS, factory, returning, false));
        }
    }

    private static void instance(List<RewriteRule> rules, String owner, String name, String hook, String... descriptors) {
        for (String descriptor : descriptors) {
            String withReceiver = "(L" + owner + ';' + descriptor.substring(1);
            rules.add(new RewriteRule(owner, name, descriptor, HOOKS, hook, withReceiver, true));
        }
    }
}
