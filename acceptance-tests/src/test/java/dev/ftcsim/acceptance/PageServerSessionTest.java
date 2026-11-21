package dev.ftcsim.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.ftcsim.simulator.SimulationSession;
import dev.ftcsim.simulator.robot.RobotDesign;
import dev.ftcsim.simulator.robot.RobotSpec;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The dashboard ports are the one place where the robot world listens on the machine: a program that serves a page the
 * way Panels does is reachable from a browser on this machine and from nowhere else, and the port is free again when
 * the session ends.
 */
@Timeout(180)
class PageServerSessionTest {
    private static final long SECOND = 1_000_000_000L;
    private static final int PORT = 8001;
    private static final String URL = "http://127.0.0.1:" + PORT + "/";

    private static String get() throws Exception {
        HttpClient client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).connectTimeout(Duration.ofSeconds(10)).build();
        HttpResponse<String> response =
                client.send(HttpRequest.newBuilder(URI.create(URL)).timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return response.body();
    }

    @Test
    void aProgramThatServesAPageOnTheDashboardPortIsReachableFromThisMachineOnTheLoopbackAddressOnly() throws Exception {
        assumeTrue(Netstat.isWindows(), "netstat -ano is the Windows one");
        RobotSpec robot = RobotDesign.decode().spec();
        long pid = ProcessHandle.current().pid();
        List<String> before = Netstat.ownedBy(Netstat.listening(), pid);

        try (TestSessions.Handle handle = TestSessions.open(robot, 0, 0, 0)) {
            SimulationSession session = handle.session;
            assertEquals("", session.dashboardUrl(), "nothing listens before the program asks");
            session.init("Page Server");
            session.runFor(SECOND / 2);
            session.runFor(SECOND / 2);

            assertEquals(URL, session.dashboardUrl());
            assertEquals("Hello from the robot world", get());
            assertEquals(1, handle.log.stream().filter(("Panels dashboard: " + URL)::equals).count(), handle.log.toString());
            assertFalse(session.problems().stream().anyMatch(problem -> problem.contains("dashboard")), session.problems().toString());

            List<String> opened = new ArrayList<>(Netstat.ownedBy(Netstat.listening(), pid));
            opened.removeAll(before);
            assertEquals(List.of("TCP 127.0.0.1:" + PORT + " 0.0.0.0:0 LISTENING " + pid), opened);
        }

        assertTrue(Netstat.canBind(PORT), "the port is free again when the session has ended");
        assertEquals(before, Netstat.ownedBy(Netstat.listening(), pid));
    }

    @Test
    void aDashboardPortThatAnotherProgramHoldsIsAProblemAndThereIsNoAddressToOpen() throws Exception {
        RobotSpec robot = RobotDesign.decode().spec();

        try (ServerSocket other = new ServerSocket(PORT, 1, InetAddress.getLoopbackAddress());
                TestSessions.Handle handle = TestSessions.open(robot, 0, 0, 0)) {
            SimulationSession session = handle.session;
            session.init("Page Server");
            session.runFor(SECOND);

            assertTrue(other.isBound());
            assertEquals("", session.dashboardUrl());
            assertTrue(
                    session.problems().stream().anyMatch(problem -> problem.contains("dashboard") && problem.contains("port " + PORT)),
                    session.problems().toString());
            assertTrue(handle.log.stream().noneMatch(line -> line.startsWith("Panels dashboard")), handle.log.toString());
        }
    }
}
