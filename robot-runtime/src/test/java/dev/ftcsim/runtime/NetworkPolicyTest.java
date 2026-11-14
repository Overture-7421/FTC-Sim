package dev.ftcsim.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.hooks.Hooks;
import dev.ftcsim.contracts.hooks.NetworkHooks;
import dev.ftcsim.contracts.hooks.NetworkRequest;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NetworkPolicyTest {
    private static final String LOOPBACK = "127.0.0.1:";

    @BeforeEach
    void startFromNothingDeclined() {
        NetworkHooks.clearDeclinedRequests();
    }

    @AfterEach
    void restoreHooks() {
        Hooks.installDefault();
        NetworkHooks.clearDeclinedRequests();
    }

    private static List<String> declined() {
        return NetworkHooks.declinedRequests().keySet().stream().map(NetworkPolicyTest::describe).toList();
    }

    private static List<String> simulated() {
        return NetworkHooks.simulatedRequests().keySet().stream().map(NetworkPolicyTest::describe).toList();
    }

    private static String describe(NetworkRequest request) {
        return request.kind() + " " + request.target();
    }

    @Test
    void everyRedirectedOperationFailsLikeAClosedPortAndNothingIsOpened() throws Exception {
        NetworkPolicyHooks policy = new NetworkPolicyHooks(request -> false);
        Hooks.install(policy);

        try (RobotClassLoader loader = FixtureWorld.loader()) {
            List<String> outcomes = FixtureWorld.run(loader, "NetworkProbe");

            assertEquals(
                    """
                    ServerSocket(int) -> BindException
                    ServerSocket(int,int) -> BindException
                    ServerSocket(int,int,InetAddress) -> BindException
                    ServerSocket.bind(SocketAddress) -> BindException
                    ServerSocket.bind(SocketAddress,int) -> BindException
                    SSLServerSocket.bind(SocketAddress) -> BindException
                    ServerSocketFactory.createServerSocket(int) -> BindException
                    ServerSocketFactory.createServerSocket(int,int) -> BindException
                    SSLServerSocketFactory.createServerSocket(int,int,InetAddress) -> BindException
                    ServerSocketChannel.bind(SocketAddress) -> BindException
                    ServerSocketChannel.bind(SocketAddress,int) -> BindException
                    NetworkChannel.bind(SocketAddress) on a server channel -> BindException
                    NetworkChannel.bind(SocketAddress) on a client channel -> ok
                    AsynchronousServerSocketChannel.bind(SocketAddress) -> BindException
                    AsynchronousServerSocketChannel.bind(SocketAddress,int) -> BindException
                    Socket(String,int) -> ConnectException
                    Socket(InetAddress,int) -> ConnectException
                    Socket(String,int,InetAddress,int) -> ConnectException
                    Socket(InetAddress,int,InetAddress,int) -> ConnectException
                    Socket(String,int,boolean) -> ConnectException
                    Socket(InetAddress,int,boolean) -> ConnectException
                    Socket(String,int) with a null host -> ConnectException
                    Socket.connect(SocketAddress) -> ConnectException
                    Socket.connect(SocketAddress,int) -> ConnectException
                    SSLSocket.connect(SocketAddress) -> ConnectException
                    SocketFactory.createSocket(String,int) -> ConnectException
                    SocketFactory.createSocket(InetAddress,int) -> ConnectException
                    SSLSocketFactory.createSocket(String,int,InetAddress,int) -> ConnectException
                    SocketFactory.createSocket(InetAddress,int,InetAddress,int) -> ConnectException
                    SocketChannel.open(SocketAddress) -> ConnectException
                    SocketChannel.connect(SocketAddress) -> ConnectException
                    AsynchronousSocketChannel.connect(SocketAddress) -> ExecutionException(ConnectException)
                    AsynchronousSocketChannel.connect(SocketAddress,A,CompletionHandler) -> ConnectException
                    DatagramSocket() -> BindException
                    DatagramSocket(int) -> BindException
                    DatagramSocket(int,InetAddress) -> BindException
                    DatagramSocket(SocketAddress) -> BindException
                    DatagramSocket((SocketAddress) null) stays unbound -> ok
                    DatagramSocket.bind(SocketAddress) -> BindException
                    DatagramSocket.connect(SocketAddress) -> PortUnreachableException
                    DatagramSocket.connect(InetAddress,int) leaves the socket unconnected -> ok
                    DatagramSocket.send(DatagramPacket) -> PortUnreachableException
                    MulticastSocket() -> BindException
                    MulticastSocket(int) -> BindException
                    MulticastSocket(SocketAddress) -> BindException
                    MulticastSocket.send(DatagramPacket) through the subtype -> PortUnreachableException
                    MulticastSocket.send(DatagramPacket,byte) -> PortUnreachableException
                    DatagramChannel.bind(SocketAddress) -> BindException
                    DatagramChannel.connect(SocketAddress) -> PortUnreachableException
                    DatagramChannel.send(ByteBuffer,SocketAddress) -> PortUnreachableException
                    URL.openConnection() for http -> ConnectException
                    URL.openConnection(Proxy) for https -> ConnectException
                    URL.openStream() for ftp -> ConnectException
                    URL.getContent() for http -> ConnectException
                    URL.getContent(Class[]) for http -> ConnectException
                    URL.openStream() for a jar fetched over http -> ConnectException
                    URL.openStream() for a resource of the program -> ok
                    HttpClient.send(HttpRequest,BodyHandler) -> ConnectException
                    HttpClient.sendAsync(HttpRequest,BodyHandler) -> ExecutionException(ConnectException)
                    HttpClient.sendAsync(HttpRequest,BodyHandler,PushPromiseHandler) -> ExecutionException(ConnectException)
                    """,
                    String.join("\n", outcomes) + "\n");
            assertEquals(
                    """
                    LISTEN *:18001
                    LISTEN *:18002
                    LISTEN 127.0.0.1:18003
                    LISTEN *:18100
                    LISTEN *:18100
                    LISTEN *:18100
                    LISTEN *:18004
                    LISTEN *:18005
                    LISTEN 127.0.0.1:18006
                    LISTEN *:18100
                    LISTEN *:18100
                    LISTEN *:18100
                    LISTEN *:18100
                    LISTEN *:18100
                    CONNECT example.invalid:80
                    CONNECT 10.0.0.1:81
                    CONNECT example.invalid:82
                    CONNECT 10.0.0.1:83
                    CONNECT example.invalid:84
                    CONNECT 10.0.0.1:85
                    CONNECT localhost:86
                    CONNECT example.invalid:80
                    CONNECT example.invalid:80
                    CONNECT example.invalid:80
                    CONNECT example.invalid:87
                    CONNECT 10.0.0.1:88
                    CONNECT example.invalid:89
                    CONNECT 10.0.0.1:90
                    CONNECT example.invalid:80
                    CONNECT example.invalid:80
                    CONNECT example.invalid:80
                    CONNECT example.invalid:80
                    LISTEN *:0
                    LISTEN *:18101
                    LISTEN 127.0.0.1:18102
                    LISTEN *:18100
                    LISTEN *:18100
                    SEND example.invalid:80
                    SEND 10.0.0.1:18201
                    SEND 10.0.0.1:18200
                    LISTEN *:0
                    LISTEN *:18103
                    LISTEN *:18100
                    SEND 10.0.0.1:18200
                    SEND 10.0.0.1:18200
                    LISTEN *:18100
                    SEND example.invalid:80
                    SEND example.invalid:80
                    CONNECT http://example.invalid:8080
                    CONNECT https://example.invalid:443
                    CONNECT ftp://example.invalid:21
                    CONNECT http://example.invalid:80
                    CONNECT http://example.invalid:80
                    CONNECT jar:http://example.invalid/d.jar
                    CONNECT http://example.invalid:80
                    CONNECT https://example.invalid:443
                    CONNECT http://example.invalid:80
                    """,
                    String.join("\n", policy.requests) + "\n");
        }
    }

    @Test
    void everyDeclinedRequestIsRecordedWithHowOftenItWasMade() throws Exception {
        Hooks.install(new NetworkPolicyHooks(request -> false));

        try (RobotClassLoader loader = FixtureWorld.loader()) {
            FixtureWorld.run(loader, "NetworkProbe");
            FixtureWorld.run(loader, "NetworkProbe");
        }

        Map<NetworkRequest, Long> recorded = NetworkHooks.declinedRequests();
        assertEquals(2L, recorded.get(new NetworkRequest(NetworkRequest.Kind.LISTEN, "*:18001")));
        assertEquals(2L * 12, recorded.get(new NetworkRequest(NetworkRequest.Kind.LISTEN, "*:18100")));
    }

    @Test
    void withoutAHostPolicyListeningIsSimulatedAndEverythingElseIsDeclinedWithAMessageThatNamesTheTarget() throws Exception {
        Hooks.installDefault();

        try (RobotClassLoader loader = FixtureWorld.loader()) {
            List<String> messages = FixtureWorld.run(loader, "NetworkMessageProbe");

            String why = " (the simulator does not provide network access to robot code)";
            assertEquals(
                    List.of(
                            "java.net.ConnectException: Connection refused: example.invalid:81" + why,
                            "java.net.PortUnreachableException: ICMP Port Unreachable: 10.0.0.1:18200" + why),
                    messages);
        }
        assertEquals(List.of("CONNECT example.invalid:81", "SEND 10.0.0.1:18200"), declined());
        assertEquals(List.of("LISTEN *:18080"), simulated());
    }

    @Test
    void aHostThatDoesNotSimulateListenersRefusesThemWithAMessageThatNamesTheTarget() throws Exception {
        Hooks.install(new NetworkPolicyHooks(request -> false));

        try (RobotClassLoader loader = FixtureWorld.loader()) {
            List<String> messages = FixtureWorld.run(loader, "NetworkMessageProbe");

            String why = " (the simulator does not provide network access to robot code)";
            assertEquals(
                    List.of(
                            "java.net.BindException: Permission denied: cannot listen on *:18080" + why,
                            "java.net.ConnectException: Connection refused: example.invalid:81" + why,
                            "java.net.PortUnreachableException: ICMP Port Unreachable: 10.0.0.1:18200" + why),
                    messages);
        }
        assertEquals(List.of("LISTEN *:18080", "CONNECT example.invalid:81", "SEND 10.0.0.1:18200"), declined());
        assertTrue(simulated().isEmpty());
    }

    @Test
    void simulatedListenersTakeThePlaceOfTheListenersThePolicyWouldRefuse() throws Exception {
        NetworkPolicyHooks policy = new NetworkPolicyHooks(request -> false, true);
        Hooks.install(policy);

        try (RobotClassLoader loader = FixtureWorld.loader()) {
            List<String> outcomes = FixtureWorld.run(loader, "ListenerProbe");

            assertEquals(
                    """
                    ServerSocket(int) -> SimulatedServerSocket:18301
                    ServerSocket(int,int) -> SimulatedServerSocket:18302
                    ServerSocket(int,int,InetAddress) -> SimulatedServerSocket:18303
                    ServerSocket() -> SimulatedServerSocket:-1
                    ServerSocket.bind(SocketAddress) -> SimulatedServerSocket:18304
                    ServerSocket.bind(SocketAddress,int) -> SimulatedServerSocket:18305
                    ServerSocket(0) -> SimulatedServerSocket:49152
                    ServerSocket(int) on a port that is held -> BindException
                    default factory createServerSocket(int) -> SimulatedServerSocket:18306
                    default factory createServerSocket() -> SimulatedServerSocket:18307
                    default factory createServerSocket(int,int) -> SimulatedServerSocket:18308
                    default factory createServerSocket(int,int,InetAddress) -> SimulatedServerSocket:18309
                    SSLServerSocketFactory.createServerSocket(int) stays under the policy -> BindException
                    ServerSocketChannel.open() -> SimulatedServerSocketChannel:unbound
                    ServerSocketChannel.bind(SocketAddress) -> SimulatedServerSocketChannel:18311
                    ServerSocketChannel.bind(SocketAddress,int) -> SimulatedServerSocketChannel:18312
                    NetworkChannel.bind(SocketAddress) -> SimulatedServerSocketChannel:18313
                    ServerSocketChannel.socket().bind(SocketAddress) -> SimulatedServerSocketChannel:18314
                    ServerSocket.accept() with a timeout -> SocketTimeoutException
                    ServerSocketChannel.accept() when nothing is pending -> null
                    AsynchronousServerSocketChannel.bind(SocketAddress) stays under the policy -> BindException
                    DatagramSocket(int) stays under the policy -> BindException
                    """,
                    String.join("\n", outcomes) + "\n");
        }
        assertEquals(List.of("LISTEN *:18310", "LISTEN *:18317", "LISTEN *:18318"), policy.requests.stream().toList());
        assertEquals(List.of("LISTEN *:18310", "LISTEN *:18317", "LISTEN *:18318"), declined());
        assertEquals(
                List.of(
                        "LISTEN *:18301",
                        "LISTEN *:18302",
                        "LISTEN 127.0.0.1:18303",
                        "LISTEN *:18304",
                        "LISTEN 127.0.0.1:18305",
                        "LISTEN *:0",
                        "LISTEN *:18306",
                        "LISTEN *:18307",
                        "LISTEN *:18308",
                        "LISTEN 127.0.0.1:18309",
                        "LISTEN *:18311",
                        "LISTEN *:18312",
                        "LISTEN *:18313",
                        "LISTEN *:18314",
                        "LISTEN *:18315",
                        "LISTEN *:18316"),
                simulated());
    }

    @Test
    void loopbackTrafficRunsForRealWhenTheHostAllowsIt() throws Exception {
        NetworkPolicyHooks policy = new NetworkPolicyHooks(request -> request.target().startsWith(LOOPBACK));
        Hooks.install(policy);

        try (RobotClassLoader loader = FixtureWorld.loader()) {
            List<String> log = FixtureWorld.run(loader, "LoopbackProbe");

            assertEquals(List.of("socket constructors 7", "bind and connect 9", "channels 3", "datagrams 5"), log);
        }
        assertEquals(
                List.of(
                        "LISTEN 127.0.0.1:N",
                        "CONNECT 127.0.0.1:N",
                        "LISTEN 127.0.0.1:N",
                        "CONNECT 127.0.0.1:N",
                        "LISTEN 127.0.0.1:N",
                        "CONNECT 127.0.0.1:N",
                        "LISTEN 127.0.0.1:N",
                        "LISTEN 127.0.0.1:N",
                        "SEND 127.0.0.1:N"),
                policy.requests.stream().map(request -> request.replaceAll(":\\d+$", ":N")).collect(Collectors.toList()));
        assertTrue(declined().isEmpty(), "nothing was declined: " + declined());
    }

    @Test
    void everyShapeOfConstructionVerifiesAndStillYieldsTheObject() throws Exception {
        Hooks.install(new NetworkPolicyHooks(request -> request.target().startsWith(LOOPBACK)));

        try (RobotClassLoader loader = FixtureWorld.loader()) {
            List<String> shapes = FixtureWorld.run(loader, "ConstructionShapesProbe");

            assertEquals(
                    List.of(
                            "statement",
                            "branch true",
                            "nested same type true",
                            "nested other type true",
                            "protected region refused",
                            "constructor reference true",
                            "method reference true",
                            "lambda true"),
                    shapes);
        }
    }
}
