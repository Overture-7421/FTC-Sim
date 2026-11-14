package dev.ftcsim.contracts.hooks;

import java.net.BindException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The private network of the robot world: the ports its simulated listeners hold. Nothing outside the robot world
 * can connect to them, and one listener holds a port at a time whatever address it was bound to.
 */
final class SimulatedNetwork {
    private static final int FIRST_EPHEMERAL_PORT = 49152;
    private static final int PORTS = 65536;

    private final Map<Integer, SimulatedListener> held = new TreeMap<>();
    private int nextEphemeral = FIRST_EPHEMERAL_PORT;

    /** Gives the listener the port, or, for port 0, the next unused port from 49152 up. */
    synchronized int claim(int requestedPort, SimulatedListener listener) throws BindException {
        int port = requestedPort == 0 ? unusedEphemeralPort() : requestedPort;
        if (held.putIfAbsent(port, listener) != null) {
            throw new BindException("Address already in use: bind (a simulated listener holds port " + port + ")");
        }
        return port;
    }

    private int unusedEphemeralPort() throws BindException {
        for (int attempts = PORTS - FIRST_EPHEMERAL_PORT; attempts > 0; attempts--) {
            int candidate = nextEphemeral;
            nextEphemeral = candidate == PORTS - 1 ? FIRST_EPHEMERAL_PORT : candidate + 1;
            if (!held.containsKey(candidate)) {
                return candidate;
            }
        }
        throw new BindException("Address already in use: bind (simulated listeners hold every ephemeral port)");
    }

    synchronized void release(int port, SimulatedListener listener) {
        held.remove(port, listener);
    }

    /** Closes every listener, which is what the end of the robot's process does to its sockets. */
    void closeAll() {
        List<SimulatedListener> listeners;
        synchronized (this) {
            listeners = new ArrayList<>(held.values());
        }
        listeners.forEach(SimulatedListener::closeWithTheWorld);
    }
}
