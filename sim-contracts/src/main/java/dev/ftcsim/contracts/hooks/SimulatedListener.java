package dev.ftcsim.contracts.hooks;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.concurrent.locks.LockSupport;

/**
 * One simulated listener: the port it holds in the private network of the robot world and the wait of whoever
 * accepts on it. Nobody can connect to it, so the only thing that ever ends a wait is that it is closed. Waits go
 * through the hooks, so a robot thread that accepts waits in simulated time like it does for every other wait.
 */
final class SimulatedListener {
    /** How often a thread that is not scheduled looks at the simulated clock again while it waits for a timeout. */
    private static final long RECHECK_NANOS = 50_000_000L;

    private final SimulatedNetwork network;
    private final Object monitor = new Object();
    private InetAddress address;
    private int port = -1;
    private boolean closed;
    private boolean worldEnded;

    SimulatedListener(SimulatedNetwork network) {
        this.network = network;
    }

    void bind(InetSocketAddress requested) throws IOException {
        synchronized (monitor) {
            if (closed) {
                throw new SocketException("Socket is closed");
            }
            if (port >= 0) {
                throw new SocketException("Already bound");
            }
            port = network.claim(requested.getPort(), this);
            address = requested.getAddress();
        }
    }

    boolean isBound() {
        synchronized (monitor) {
            return port >= 0;
        }
    }

    boolean isClosed() {
        synchronized (monitor) {
            return closed;
        }
    }

    InetAddress address() {
        synchronized (monitor) {
            return address;
        }
    }

    /** The port held, or -1 while the listener is not bound. */
    int port() {
        synchronized (monitor) {
            return port;
        }
    }

    InetSocketAddress local() {
        synchronized (monitor) {
            return port < 0 ? null : new InetSocketAddress(address, port);
        }
    }

    void close() {
        synchronized (monitor) {
            closeLocked();
        }
    }

    /**
     * The robot world ended and its sockets are closed with it. A library that goes on accepting on its closed socket,
     * where the JDK would throw each time, would spin; its thread waits for good in {@link #blockIfEnded} instead.
     */
    void closeWithTheWorld() {
        synchronized (monitor) {
            worldEnded = true;
            closeLocked();
        }
    }

    /** Where a thread that accepts on a socket closed with the robot world waits, for good: nothing will ever come. */
    void blockIfEnded() {
        boolean ended;
        synchronized (monitor) {
            ended = worldEnded;
        }
        while (ended) {
            Thread.interrupted();
            LockSupport.park(this);
        }
    }

    private void closeLocked() {
        if (closed) {
            return;
        }
        closed = true;
        if (port >= 0) {
            network.release(port, this);
        }
        Hooks.notifyAll(monitor);
    }

    /**
     * Waits until the listener is closed. A positive timeout, in milliseconds, ends the wait early with a
     * {@link SocketTimeoutException}. An interrupt does not end the wait, as it does not end the wait of a classic
     * socket: it stays pending for the thread to see afterwards. A channel is closed by the interrupt, which
     * is the JDK's doing, not the wait's, and that closes the listener.
     */
    void awaitClose(int timeoutMillis) throws IOException {
        boolean interrupted = false;
        try {
            synchronized (monitor) {
                long deadline = timeoutMillis > 0 ? Hooks.nanoTime() + timeoutMillis * 1_000_000L : 0;
                while (!closed) {
                    try {
                        if (timeoutMillis > 0) {
                            long remaining = deadline - Hooks.nanoTime();
                            if (remaining <= 0) {
                                throw new SocketTimeoutException("Accept timed out");
                            }
                            long wait = Math.min(remaining, RECHECK_NANOS);
                            Hooks.wait(monitor, wait / 1_000_000L, (int) (wait % 1_000_000L));
                        } else {
                            Hooks.wait(monitor);
                        }
                    } catch (InterruptedException interrupt) {
                        interrupted = true;
                    }
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
