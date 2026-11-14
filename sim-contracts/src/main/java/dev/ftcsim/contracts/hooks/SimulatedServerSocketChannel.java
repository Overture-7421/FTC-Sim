package dev.ftcsim.contracts.hooks;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.nio.channels.AlreadyBoundException;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NotYetBoundException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.Set;

/**
 * A {@code ServerSocketChannel} that listens in the private network of the robot world, as {@link
 * SimulatedServerSocket} does for a classic socket. A blocking {@code accept} waits until the channel is closed; one
 * on a channel in non-blocking mode finds no connection pending and returns null, which is also the truth. A
 * selector cannot take the channel, because the JDK's selectors only work with channels of the JDK's own.
 */
final class SimulatedServerSocketChannel extends ServerSocketChannel {
    private final SimulatedListener listener;
    private final SimulatedServerSocket socket;

    SimulatedServerSocketChannel(SimulatedListener listener) {
        super(SelectorProvider.provider());
        this.listener = listener;
        this.socket = new SimulatedServerSocket(listener, this);
    }

    @Override
    public ServerSocketChannel bind(SocketAddress local, int backlog) throws IOException {
        if (!isOpen()) {
            throw new ClosedChannelException();
        }
        if (listener.isBound()) {
            throw new AlreadyBoundException();
        }
        NetworkHooks.bindSimulated(listener, local);
        return this;
    }

    @Override
    public <T> ServerSocketChannel setOption(SocketOption<T> name, T value) throws IOException {
        socket.setOption(name, value);
        return this;
    }

    @Override
    public <T> T getOption(SocketOption<T> name) throws IOException {
        return socket.getOption(name);
    }

    @Override
    public Set<SocketOption<?>> supportedOptions() {
        return socket.supportedOptions();
    }

    @Override
    public ServerSocket socket() {
        return socket;
    }

    @Override
    public SocketChannel accept() throws IOException {
        if (!isOpen() || listener.isClosed()) {
            listener.blockIfEnded();
            throw new ClosedChannelException();
        }
        if (!listener.isBound()) {
            throw new NotYetBoundException();
        }
        if (!isBlocking()) {
            return null;
        }
        try {
            begin();
            listener.awaitClose(0);
        } finally {
            end(false);
        }
        // the listener was closed from the network's side, not through the channel
        close();
        throw new AsynchronousCloseException();
    }

    @Override
    public SocketAddress getLocalAddress() throws IOException {
        if (!isOpen()) {
            throw new ClosedChannelException();
        }
        return listener.local();
    }

    @Override
    protected void implCloseSelectableChannel() {
        listener.close();
    }

    @Override
    protected void implConfigureBlocking(boolean block) {}
}
