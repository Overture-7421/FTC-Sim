package dev.ftcsim.acceptance;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** What the operating system says about the sockets of this process, through {@code netstat -ano}. */
final class Netstat {
    private Netstat() {}

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("windows");
    }

    /** Whether a real socket can listen on the loopback address at the port, which it cannot while anything else does. */
    static boolean canBind(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            return true;
        } catch (IOException taken) {
            return false;
        }
    }

    /** One line for each TCP socket that listens and each UDP socket that is bound, whitespace collapsed. */
    static List<String> listening() throws Exception {
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

    static List<String> ownedBy(List<String> lines, long pid) {
        return lines.stream().filter(line -> line.endsWith(" " + pid)).toList();
    }
}
