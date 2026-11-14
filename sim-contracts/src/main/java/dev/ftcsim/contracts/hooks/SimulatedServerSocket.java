package dev.ftcsim.contracts.hooks;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketImpl;
import java.net.SocketOption;
import java.net.StandardSocketOptions;
import java.nio.channels.ServerSocketChannel;
import java.util.Set;

/**
 * A {@code ServerSocket} that listens in the private network of the robot world: it binds nothing on the machine,
 * {@code accept} waits until it is closed and then fails as it does on a socket closed during an accept, and no
 * client can ever connect, because there are none. It answers everything else from what it was given, as a
 * socket that is open does.
 */
final class SimulatedServerSocket extends ServerSocket {
    private static final int DEFAULT_RECEIVE_BUFFER = 65_536;

    private final SimulatedListener listener;
    private final ServerSocketChannel channel;
    private int timeoutMillis;
    private boolean reuseAddress;
    private int receiveBuffer = DEFAULT_RECEIVE_BUFFER;

    /** @param channel the channel this socket belongs to, or null for a socket that is not a channel's */
    SimulatedServerSocket(SimulatedListener listener, ServerSocketChannel channel) {
        super(new NoSocketImpl());
        this.listener = listener;
        this.channel = channel;
    }

    @Override
    public void bind(SocketAddress endpoint) throws IOException {
        bind(endpoint, 50);
    }

    @Override
    public void bind(SocketAddress endpoint, int backlog) throws IOException {
        NetworkHooks.bindSimulated(listener, endpoint);
    }

    @Override
    public InetAddress getInetAddress() {
        return listener.address();
    }

    @Override
    public int getLocalPort() {
        return listener.port();
    }

    @Override
    public SocketAddress getLocalSocketAddress() {
        return listener.local();
    }

    @Override
    public Socket accept() throws IOException {
        if (isClosed()) {
            listener.blockIfEnded();
            throw new SocketException("Socket is closed");
        }
        if (!isBound()) {
            throw new SocketException("Socket is not bound yet");
        }
        listener.awaitClose(timeoutMillis);
        throw new SocketException("Socket closed");
    }

    @Override
    public void close() {
        listener.close();
    }

    @Override
    public ServerSocketChannel getChannel() {
        return channel;
    }

    @Override
    public boolean isBound() {
        return listener.isBound();
    }

    @Override
    public boolean isClosed() {
        return listener.isClosed();
    }

    @Override
    public void setSoTimeout(int timeout) throws SocketException {
        requireOpen();
        if (timeout < 0) {
            throw new IllegalArgumentException("timeout < 0");
        }
        timeoutMillis = timeout;
    }

    @Override
    public int getSoTimeout() throws IOException {
        requireOpen();
        return timeoutMillis;
    }

    @Override
    public void setReuseAddress(boolean on) throws SocketException {
        requireOpen();
        reuseAddress = on;
    }

    @Override
    public boolean getReuseAddress() throws SocketException {
        requireOpen();
        return reuseAddress;
    }

    @Override
    public void setReceiveBufferSize(int size) throws SocketException {
        requireOpen();
        if (size <= 0) {
            throw new IllegalArgumentException("negative receive size");
        }
        receiveBuffer = size;
    }

    @Override
    public int getReceiveBufferSize() throws SocketException {
        requireOpen();
        return receiveBuffer;
    }

    @Override
    public void setPerformancePreferences(int connectionTime, int latency, int bandwidth) {}

    @Override
    @SuppressWarnings("unchecked")
    public <T> ServerSocket setOption(SocketOption<T> name, T value) throws IOException {
        if (name == StandardSocketOptions.SO_REUSEADDR) {
            setReuseAddress((Boolean) value);
        } else if (name == StandardSocketOptions.SO_RCVBUF) {
            setReceiveBufferSize((Integer) value);
        } else {
            throw new UnsupportedOperationException("'" + name + "' not supported");
        }
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getOption(SocketOption<T> name) throws IOException {
        if (name == StandardSocketOptions.SO_REUSEADDR) {
            return (T) Boolean.valueOf(getReuseAddress());
        } else if (name == StandardSocketOptions.SO_RCVBUF) {
            return (T) Integer.valueOf(getReceiveBufferSize());
        }
        throw new UnsupportedOperationException("'" + name + "' not supported");
    }

    @Override
    public Set<SocketOption<?>> supportedOptions() {
        return Set.of(StandardSocketOptions.SO_RCVBUF, StandardSocketOptions.SO_REUSEADDR);
    }

    @Override
    public String toString() {
        return isBound() ? "ServerSocket[addr=" + getInetAddress() + ",localport=" + getLocalPort() + "]" : "ServerSocket[unbound]";
    }

    private void requireOpen() throws SocketException {
        if (isClosed()) {
            throw new SocketException("Socket is closed");
        }
    }

    /** The implementation every {@code ServerSocket} needs to be created; every operation is the socket's own, so none is used. */
    private static final class NoSocketImpl extends SocketImpl {
        private static UnsupportedOperationException unused() {
            return new UnsupportedOperationException("a simulated listener has no socket implementation");
        }

        @Override
        protected void create(boolean stream) {
            throw unused();
        }

        @Override
        protected void connect(String host, int port) {
            throw unused();
        }

        @Override
        protected void connect(InetAddress address, int port) {
            throw unused();
        }

        @Override
        protected void connect(SocketAddress address, int timeout) {
            throw unused();
        }

        @Override
        protected void bind(InetAddress host, int port) {
            throw unused();
        }

        @Override
        protected void listen(int backlog) {
            throw unused();
        }

        @Override
        protected void accept(SocketImpl socket) {
            throw unused();
        }

        @Override
        protected InputStream getInputStream() {
            throw unused();
        }

        @Override
        protected OutputStream getOutputStream() {
            throw unused();
        }

        @Override
        protected int available() {
            throw unused();
        }

        @Override
        protected void close() {
            throw unused();
        }

        @Override
        protected void sendUrgentData(int data) {
            throw unused();
        }

        @Override
        public void setOption(int optionId, Object value) {
            throw unused();
        }

        @Override
        public Object getOption(int optionId) {
            throw unused();
        }
    }
}
