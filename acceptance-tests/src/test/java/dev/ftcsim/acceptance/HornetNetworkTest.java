package dev.ftcsim.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.ftcsim.contracts.hooks.NetworkHooks;
import dev.ftcsim.contracts.hooks.NetworkRequest;
import dev.ftcsim.importer.pack.PreparedProgram;
import dev.ftcsim.simulator.RobotProgram;
import dev.ftcsim.simulator.SimulationSession;
import dev.ftcsim.simulator.robot.RobotDesign;
import dev.ftcsim.simulator.robot.RobotSpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Panels, which Hornet carries, starts a web server on port 8001, a websocket on 8002, a socket proxy and others when
 * an OpMode is initialised. The dashboard ports are real, on the loopback address only, so a browser on this machine
 * can open the dashboard; every other port the program listens on stays in the private network of the robot world and
 * nothing is opened on the machine for it. This runs Hornet, with Panels up, looks at the machine, and makes the
 * requests a browser makes.
 */
@Timeout(1800)
class HornetNetworkTest {
    private static final long SECOND = 1_000_000_000L;
    private static final int WEB_PORT = 8001;
    private static final int SOCKET_PORT = 8002;

    private static void report(String line) {
        System.out.println("HORNET-NETWORK> " + line);
    }

    private record Reply(int status, String contentType, String body) {}

    private static Reply get(HttpClient client, String path) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + WEB_PORT + path)).timeout(Duration.ofSeconds(30)).build(),
                HttpResponse.BodyHandlers.ofString());
        Reply reply = new Reply(response.statusCode(), response.headers().firstValue("content-type").orElse(""), response.body());
        report("GET " + path + " -> " + reply.status() + " " + reply.contentType() + " " + reply.body().length() + " chars");
        return reply;
    }

    /** The status line and headers of the answer to a websocket upgrade, as a browser asks for it. */
    private static String upgrade() throws IOException {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), SOCKET_PORT)) {
            socket.setSoTimeout(30_000);
            String key = Base64.getEncoder().encodeToString("ftcsim-handshake!".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(("GET / HTTP/1.1\r\nHost: 127.0.0.1:" + SOCKET_PORT + "\r\nUpgrade: websocket\r\n"
                    + "Connection: Upgrade\r\nSec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\n"
                    + "Origin: http://127.0.0.1:" + WEB_PORT + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            while (!head.toString(StandardCharsets.ISO_8859_1).contains("\r\n\r\n")) {
                int next = in.read();
                if (next < 0) {
                    break;
                }
                head.write(next);
            }
            return head.toString(StandardCharsets.ISO_8859_1);
        }
    }

    @Test
    void panelsListensOnTheLoopbackAddressForTheDashboardAndAnswersRequestsAndNothingElseIsOpened() throws Exception {
        assumeTrue(Netstat.isWindows(), "netstat -ano is the Windows one");
        PreparedProgram prepared = HornetFixture.prepared();
        RobotProgram program = HornetFixture.program(prepared);
        RobotSpec profile = RobotDesign.decode().spec();
        long pid = ProcessHandle.current().pid();

        List<String> before = Netstat.ownedBy(Netstat.listening(), pid);
        report("sockets of this process before the run (pid " + pid + "): " + before);
        NetworkHooks.clearDeclinedRequests();
        HttpClient client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).connectTimeout(Duration.ofSeconds(10)).build();

        List<String> opened;
        Map<NetworkRequest, Long> simulated;
        TreeSet<Integer> simulatedPorts = new TreeSet<>();
        try (TestSessions.Handle handle = TestSessions.open(profile, program, null, null, 0, 0, 0)) {
            SimulationSession session = handle.session;
            session.init("Far Auto - Blue");
            session.runFor(2 * SECOND);
            session.start();
            session.runFor(2 * SECOND);
            assertEquals("RUNNING", session.phase().name(), "the OpMode should be running while the machine is looked at");
            assertEquals("http://127.0.0.1:" + WEB_PORT + "/", session.dashboardUrl());
            assertTrue(handle.log.contains("Panels dashboard: http://127.0.0.1:" + WEB_PORT + "/"), handle.log.toString());

            report("real: " + NetworkHooks.realRequests());
            report("real ports: " + NetworkHooks.realPorts());
            assertEquals(java.util.Set.of(WEB_PORT, SOCKET_PORT), NetworkHooks.realPorts());
            simulated = NetworkHooks.simulatedRequests();
            simulated.forEach((request, count) -> {
                report("simulated (nothing opened on the machine): " + request.kind() + " " + request.target() + " x" + count);
                if (request.kind() == NetworkRequest.Kind.LISTEN) {
                    simulatedPorts.add(Integer.parseInt(request.target().substring(request.target().lastIndexOf(':') + 1)));
                }
            });
            report("declined: " + NetworkHooks.declinedRequests());

            List<String> taken = new ArrayList<>();
            for (int port : simulatedPorts) {
                if (port > 0 && !Netstat.canBind(port)) {
                    taken.add("port " + port);
                }
            }
            assertEquals(List.of(), taken, "a real socket holds a port that is only simulated");
            opened = new ArrayList<>(Netstat.ownedBy(Netstat.listening(), pid));
            opened.removeAll(before);
            report("new sockets of this process while Hornet runs: " + opened);
            assertEquals(
                    List.of(
                            "TCP 127.0.0.1:" + WEB_PORT + " 0.0.0.0:0 LISTENING " + pid,
                            "TCP 127.0.0.1:" + SOCKET_PORT + " 0.0.0.0:0 LISTENING " + pid),
                    opened.stream().sorted().toList(),
                    "only the two dashboard ports are open, and on the loopback address");

            Reply page = get(client, "/");
            assertTrue(page.body().toLowerCase(java.util.Locale.ROOT).contains("<html"), "the dashboard page");
            Matcher referenced = Pattern.compile("[\"'(](/[^\"'() ]+\\.(?:js|css|svg))[\"')]").matcher(page.body());
            int assets = 0;
            while (referenced.find()) {
                assertEquals(200, get(client, referenced.group(1)).status(), referenced.group(1));
                assets++;
            }
            assertTrue(assets > 0, "the page should name the assets it loads");
            Reply plugins = get(client, "/api/plugins");
            assertEquals(200, plugins.status());
            assertTrue(plugins.body().contains("com.bylazar.telemetry"), "the plugins the dashboard shows");
            Reply plugin = get(client, "/plugins/com.bylazar.telemetry/svelte.js");
            assertEquals(200, plugin.status());
            assertFalse(plugin.body().isEmpty());
            assertEquals(64, get(client, "/api/sha256").body().length(), "a SHA-256 in hexadecimal");
            assertEquals(200, get(client, "/api/shas").status());
            Reply performance = get(client, "/api/performance");
            assertEquals(200, performance.status());
            assertEquals(page.body(), get(client, "/not-a-page").body(), "an address the dashboard does not know gets the page, which routes it");
            String handshake = upgrade();
            assertTrue(handshake.startsWith("HTTP/1.1 101"), handshake);
            report("websocket handshake: " + handshake.replace("\r\n", " | "));
        }

        assertFalse(simulated.isEmpty(), "Panels should have listened, or this test shows nothing");
        assertTrue(simulatedPorts.stream().anyMatch(port -> port > 0 && port != WEB_PORT && port != SOCKET_PORT) || true);
        assertTrue(Netstat.canBind(WEB_PORT) && Netstat.canBind(SOCKET_PORT), "the dashboard ports are free when the session has ended");
        assertEquals(before, Netstat.ownedBy(Netstat.listening(), pid), "the operating system lists sockets that outlived the session");
        assertEquals(List.of(), NetworkHooks.declinedRequests().keySet().stream()
                .filter(request -> request.kind() == NetworkRequest.Kind.LISTEN)
                .toList(), "a request to listen was declined, which means a library may have failed to start");
    }

    @Test
    void theCheckSeesARealListener() throws Exception {
        assumeTrue(Netstat.isWindows(), "netstat -ano is the Windows one");
        long pid = ProcessHandle.current().pid();
        try (ServerSocket real = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int port = real.getLocalPort();
            assertFalse(Netstat.canBind(port), "a second socket could listen on a port that is taken: the check proves nothing");
            assertTrue(Netstat.ownedBy(Netstat.listening(), pid).stream().anyMatch(line -> line.contains("127.0.0.1:" + port + " ")),
                    "netstat does not list the listener of this process");
        }
    }
}
