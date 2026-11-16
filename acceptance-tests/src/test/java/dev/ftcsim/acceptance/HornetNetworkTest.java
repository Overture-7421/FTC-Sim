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
import dev.ftcsim.simulator.robot.RobotSpec;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Panels, which Hornet carries, starts a web server, a socket proxy and others when an OpMode is initialised. A real
 * listening socket makes Windows ask a student whether the program may be let through its firewall, and a connection
 * from outside would make a run depend on the network, so the robot world listens only in a private network of its own
 * and nothing is opened on the machine. This runs Hornet, with Panels up, and looks at the machine: no port the program
 * listens on can be taken by anyone else, and the operating system lists no new socket for this process.
 */
@Timeout(1800)
class HornetNetworkTest {
    private static final long SECOND = 1_000_000_000L;

    private static void report(String line) {
        System.out.println("HORNET-NETWORK> " + line);
    }

    /** Whether a real socket can listen on the loopback address at the port, which it cannot while anything else does. */
    private static boolean canBind(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            return true;
        } catch (IOException taken) {
            return false;
        }
    }

    /** What {@code netstat -ano} lists, one line for each TCP socket that listens and each UDP socket that is bound. */
    private static List<String> netstat() throws Exception {
        Process process = new ProcessBuilder("netstat", "-ano").redirectErrorStream(true).start();
        String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "netstat did not end");
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String trimmed = line.trim().replaceAll("\\s+", " ");
            if (trimmed.startsWith("TCP ") && trimmed.contains(" LISTENING ") || trimmed.startsWith("UDP ")) {
                lines.add(trimmed);
            }
        }
        return lines;
    }

    private static List<String> ownedBy(List<String> lines, long pid) {
        return lines.stream().filter(line -> line.endsWith(" " + pid)).toList();
    }

    @Test
    void panelsListensOnlyInsideTheRobotWorldAndNoRealSocketIsOpenedForIt() throws Exception {
        assumeTrue(System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("windows"), "netstat -ano is the Windows one");
        PreparedProgram prepared = HornetFixture.prepared();
        RobotProgram program = HornetFixture.program(prepared);
        RobotSpec profile = HornetMapping.spec();
        long pid = ProcessHandle.current().pid();

        List<String> before = ownedBy(netstat(), pid);
        report("sockets of this process before the run (pid " + pid + "): " + before);
        NetworkHooks.clearDeclinedRequests();

        List<String> during;
        Map<NetworkRequest, Long> simulated;
        TreeSet<Integer> ports = new TreeSet<>();
        try (TestSessions.Handle handle = TestSessions.open(profile, program, null, null, 0, 0, 0)) {
            SimulationSession session = handle.session;
            session.init("Far Auto - Blue");
            session.runFor(2 * SECOND);
            session.start();
            session.runFor(2 * SECOND);
            assertEquals("RUNNING", session.phase().name(), "the OpMode should be running while the machine is looked at");

            simulated = NetworkHooks.simulatedRequests();
            simulated.forEach((request, count) -> {
                report("simulated (nothing opened on the machine): " + request.kind() + " " + request.target() + " x" + count);
                if (request.kind() == NetworkRequest.Kind.LISTEN) {
                    ports.add(Integer.parseInt(request.target().substring(request.target().lastIndexOf(':') + 1)));
                }
            });
            report("declined: " + NetworkHooks.declinedRequests());

            List<String> taken = new ArrayList<>();
            for (int port : ports) {
                if (port > 0 && !canBind(port)) {
                    taken.add("port " + port);
                }
            }
            during = ownedBy(netstat(), pid);
            report("sockets of this process while Hornet runs: " + during);
            report("ports the robot world listens on, which a real socket could not take: " + taken);
            assertEquals(List.of(), taken, "a real socket holds a port that the robot world listens on");
        }

        assertFalse(simulated.isEmpty(), "Panels should have listened, or this test shows nothing");
        assertTrue(ports.stream().anyMatch(port -> port > 0), "Panels should have listened on ports, not only on port 0: " + simulated);
        assertEquals(before, during, "the operating system lists sockets that the robot world opened");
        assertEquals(List.of(), NetworkHooks.declinedRequests().keySet().stream()
                .filter(request -> request.kind() == NetworkRequest.Kind.LISTEN)
                .toList(), "a request to listen was declined, which means a library may have failed to start");
    }

    @Test
    void theCheckSeesARealListener() throws Exception {
        assumeTrue(System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("windows"), "netstat -ano is the Windows one");
        long pid = ProcessHandle.current().pid();
        try (ServerSocket real = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int port = real.getLocalPort();
            assertFalse(canBind(port), "a second socket could listen on a port that is taken: the check proves nothing");
            assertTrue(ownedBy(netstat(), pid).stream().anyMatch(line -> line.contains("127.0.0.1:" + port + " ")),
                    "netstat does not list the listener of this process");
        }
    }
}
