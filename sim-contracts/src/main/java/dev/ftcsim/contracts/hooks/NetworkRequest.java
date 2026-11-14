package dev.ftcsim.contracts.hooks;

import java.io.IOException;
import java.net.BindException;
import java.net.ConnectException;
import java.net.PortUnreachableException;
import java.util.Objects;

/**
 * Something robot code asked the network to do, as the host's policy sees it.
 *
 * @param kind   what the program wants to do
 * @param target where, as {@code host:port} with {@code *} for the wildcard address or {@code scheme://host:port}
 *               for a URL; the name is never resolved to build it, so describing a request touches no network
 */
public record NetworkRequest(Kind kind, String target) {

    public enum Kind {
        /** Wait for connections or datagrams from anywhere: a listening socket or a bound datagram socket. */
        LISTEN,
        /** Open a connection to a remote endpoint. */
        CONNECT,
        /** Send datagrams, or fix the remote endpoint of a datagram socket. */
        SEND
    }

    public NetworkRequest {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(target, "target");
    }

    /** What a program sees when the simulator does not provide the access: the failure a closed port produces. */
    IOException refusal() {
        String why = " (the simulator does not provide network access to robot code)";
        return switch (kind) {
            case LISTEN -> new BindException("Permission denied: cannot listen on " + target + why);
            case CONNECT -> new ConnectException("Connection refused: " + target + why);
            case SEND -> new PortUnreachableException("ICMP Port Unreachable: " + target + why);
        };
    }
}
