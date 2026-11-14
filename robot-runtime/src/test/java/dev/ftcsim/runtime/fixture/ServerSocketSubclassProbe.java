package dev.ftcsim.runtime.fixture;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketAddress;

/**
 * A program's own subclass of a socket type. Its constructor and its calls to the superclass are not redirected,
 * so listening through it is not subject to the host's policy.
 */
public final class ServerSocketSubclassProbe extends ServerSocket {
    public ServerSocketSubclassProbe() throws IOException {
        super(0, 1, InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));
    }

    @Override
    public void bind(SocketAddress endpoint, int backlog) throws IOException {
        super.bind(endpoint, backlog);
    }
}
