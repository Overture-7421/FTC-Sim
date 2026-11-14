package dev.ftcsim.contracts.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.IllegalSelectorException;
import java.nio.channels.NotYetBoundException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SimulatedListenersTest {
    private static final InetAddress LOOPBACK = loopback();
    private static final long PATIENCE_MILLIS = 5_000;

    /** Without the name, which is how the hosts' requests are written. */
    private static InetAddress loopback() {
        try {
            return InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        } catch (UnknownHostException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @BeforeEach
    void startWithAnEmptyNetwork() {
        Hooks.installDefault();
        NetworkHooks.clearDeclinedRequests();
    }

    @AfterEach
    void endTheNetwork() {
        Hooks.installDefault();
        NetworkHooks.clearDeclinedRequests();
    }

    private static int freePortOnTheMachine() throws IOException {
        try (ServerSocket probe = new ServerSocket(0, 1, LOOPBACK)) {
            return probe.getLocalPort();
        }
    }

    /** Accepts on a thread of its own and keeps how the accept ended. */
    private static final class Acceptor {
        final Thread thread;
        private volatile Throwable failure;
        private volatile Object accepted;
        private volatile boolean interruptPending;

        Acceptor(Callable<?> accept) {
            thread = new Thread(() -> {
                try {
                    accepted = accept.call();
                } catch (Throwable ended) {
                    failure = ended;
                } finally {
                    interruptPending = Thread.currentThread().isInterrupted();
                }
            }, "acceptor");
            thread.setDaemon(true);
            thread.start();
        }

        Acceptor awaitWaiting() throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PATIENCE_MILLIS);
            while (thread.getState() != Thread.State.WAITING && thread.getState() != Thread.State.TIMED_WAITING) {
                if (System.nanoTime() > deadline) {
                    fail("the accept did not start waiting, the thread is " + thread.getState());
                }
                Thread.sleep(1);
            }
            return this;
        }

        Throwable awaitEnd() throws InterruptedException {
            thread.join(PATIENCE_MILLIS);
            assertFalse(thread.isAlive(), "the accept did not end");
            assertNull(accepted, "nobody can have connected");
            return failure;
        }

        boolean interruptWasLeftPending() {
            return interruptPending;
        }
    }

    @Test
    void aListenerHoldsItsPortInTheRobotWorldAndBindsNothingOnTheMachine() throws IOException {
        int port = freePortOnTheMachine();

        try (ServerSocket simulated = NetworkHooks.newServerSocket(port, 5, LOOPBACK)) {
            assertTrue(simulated.isBound());
            assertEquals(port, simulated.getLocalPort());
            assertEquals(LOOPBACK, simulated.getInetAddress());
            assertEquals(new InetSocketAddress(LOOPBACK, port), simulated.getLocalSocketAddress());

            try (ServerSocket onTheMachine = new ServerSocket(port, 5, LOOPBACK)) {
                assertEquals(port, onTheMachine.getLocalPort(), "the machine's port was free, nothing was bound there");
            }
        }
    }

    @Test
    void anUnboundListenerSaysSoAndCannotAccept() throws IOException {
        try (ServerSocket socket = NetworkHooks.newServerSocket()) {
            assertFalse(socket.isBound());
            assertFalse(socket.isClosed());
            assertEquals(-1, socket.getLocalPort());
            assertNull(socket.getInetAddress());
            assertNull(socket.getLocalSocketAddress());
            assertEquals("Socket is not bound yet", assertThrows(SocketException.class, socket::accept).getMessage());
        }
    }

    @Test
    void anAcceptWaitsUntilTheListenerIsClosedAndThenFailsLikeAClosedSocket() throws Exception {
        ServerSocket socket = NetworkHooks.newServerSocket(18401);
        Acceptor acceptor = new Acceptor(socket::accept).awaitWaiting();

        socket.close();

        Throwable failure = acceptor.awaitEnd();
        assertInstanceOf(SocketException.class, failure);
        assertEquals("Socket closed", failure.getMessage());
        assertTrue(socket.isClosed());
        assertEquals("Socket is closed", assertThrows(SocketException.class, socket::accept).getMessage());
    }

    @Test
    void whenTheRobotWorldEndsItsListenersCloseAndALoopThatAcceptsOnAClosedSocketWaitsInsteadOfSpinning() throws Exception {
        ServerSocket socket = NetworkHooks.newServerSocket(18403);
        AtomicLong accepts = new AtomicLong();
        Thread loop = new Thread(() -> {
            while (true) {
                try {
                    accepts.incrementAndGet();
                    socket.accept();
                } catch (IOException ignored) {
                    // a library that logs the failure and tries again
                }
            }
        }, "accept-loop");
        loop.setDaemon(true);
        loop.start();
        try {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PATIENCE_MILLIS);
            while (loop.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }

            NetworkHooks.closeSimulatedListeners();

            assertTrue(socket.isClosed());
            long settled = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PATIENCE_MILLIS);
            while (accepts.get() < 2 && System.nanoTime() < settled) {
                Thread.sleep(1);
            }
            Thread.sleep(100);
            assertEquals(2, accepts.get(), "the second accept on the closed socket waits and does not fail again and again");
            assertEquals(Thread.State.WAITING, loop.getState());
            loop.interrupt();
            Thread.sleep(100);
            assertEquals(2, accepts.get(), "an interrupt does not set it free either");
        } finally {
            loop.interrupt();
        }
    }

    @Test
    void anAcceptWithATimeoutEndsWithTheJdksTimeout() throws IOException {
        try (ServerSocket socket = NetworkHooks.newServerSocket(18402)) {
            socket.setSoTimeout(30);
            assertEquals(30, socket.getSoTimeout());

            long start = System.nanoTime();
            SocketTimeoutException timeout = assertThrows(SocketTimeoutException.class, socket::accept);

            assertEquals("Accept timed out", timeout.getMessage());
            assertTrue(System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(30));
            assertFalse(socket.isClosed(), "a timeout leaves the socket open");
        }
    }

    @Test
    void aClassicAcceptTakesNoNoticeOfAnInterruptAndLeavesItPending() throws Exception {
        ServerSocket socket = NetworkHooks.newServerSocket(18403);
        Acceptor acceptor = new Acceptor(socket::accept).awaitWaiting();

        acceptor.thread.interrupt();
        Thread.sleep(50);
        assertTrue(acceptor.thread.isAlive(), "the accept goes on waiting");

        socket.close();
        assertInstanceOf(SocketException.class, acceptor.awaitEnd());
        assertTrue(acceptor.interruptWasLeftPending(), "the interrupt is still there for the thread to see");
    }

    @Test
    void aPortIsHeldByOneListenerAtATimeWhateverTheAddress() throws IOException {
        ServerSocket first = NetworkHooks.newServerSocket(18404, 5, LOOPBACK);

        BindException refused = assertThrows(BindException.class, () -> NetworkHooks.newServerSocket(18404));
        assertTrue(refused.getMessage().startsWith("Address already in use: bind"), refused.getMessage());

        first.close();
        NetworkHooks.newServerSocket(18404).close();
    }

    @Test
    void aFailedBindLeavesNoPortHeldAndNoSocketOpen() throws IOException {
        try (ServerSocket first = NetworkHooks.newServerSocket(18405)) {
            assertThrows(BindException.class, () -> NetworkHooks.newServerSocket(18405));
            assertEquals(18405, first.getLocalPort());
        }
    }

    @Test
    void portZeroGivesTheNextUnusedPortFromTheEphemeralRange() throws IOException {
        try (ServerSocket first = NetworkHooks.newServerSocket(0);
                ServerSocket second = NetworkHooks.newServerSocket(0)) {
            assertEquals(49152, first.getLocalPort());
            assertEquals(49153, second.getLocalPort());
        }
    }

    @Test
    void aChannelBlocksUntilClosedAndIsToldSo() throws Exception {
        ServerSocketChannel channel = NetworkHooks.openServerSocketChannel();
        assertThrows(NotYetBoundException.class, channel::accept);

        NetworkHooks.bind(channel, new InetSocketAddress(LOOPBACK, 18411));
        assertEquals(new InetSocketAddress(LOOPBACK, 18411), channel.getLocalAddress());
        Acceptor acceptor = new Acceptor(channel::accept).awaitWaiting();

        channel.close();

        assertInstanceOf(AsynchronousCloseException.class, acceptor.awaitEnd());
        assertFalse(channel.isOpen());
    }

    @Test
    void aChannelInNonBlockingModeFindsNothingPending() throws IOException {
        try (ServerSocketChannel channel = NetworkHooks.openServerSocketChannel()) {
            NetworkHooks.bind(channel, new InetSocketAddress(18412), 5);
            channel.configureBlocking(false);

            assertNull(channel.accept());
        }
    }

    @Test
    void aChannelThatIsInterruptedWhileItAcceptsIsClosedByIt() throws Exception {
        ServerSocketChannel channel = NetworkHooks.openServerSocketChannel();
        NetworkHooks.bind(channel, new InetSocketAddress(18413));
        Acceptor acceptor = new Acceptor(channel::accept).awaitWaiting();

        acceptor.thread.interrupt();

        assertInstanceOf(ClosedByInterruptException.class, acceptor.awaitEnd());
        assertFalse(channel.isOpen());
        assertTrue(acceptor.interruptWasLeftPending());
        NetworkHooks.newServerSocket(18413).close();
    }

    @Test
    void theSocketOfAChannelIsTheSameListenerAsTheChannel() throws IOException {
        try (ServerSocketChannel channel = NetworkHooks.openServerSocketChannel()) {
            ServerSocket socket = channel.socket();
            assertSame(channel, socket.getChannel());

            NetworkHooks.bind(socket, new InetSocketAddress(LOOPBACK, 18414));

            assertEquals(new InetSocketAddress(LOOPBACK, 18414), channel.getLocalAddress());
            assertThrows(BindException.class, () -> NetworkHooks.newServerSocket(18414));
        }
    }

    @Test
    void aSelectorCannotTakeASimulatedChannelBecauseTheJdksSelectorsOnlyTakeTheJdksChannels() throws IOException {
        try (ServerSocketChannel channel = NetworkHooks.openServerSocketChannel();
                Selector selector = Selector.open()) {
            NetworkHooks.bind(channel, new InetSocketAddress(18415));
            channel.configureBlocking(false);

            assertThrows(IllegalSelectorException.class, () -> channel.register(selector, SelectionKey.OP_ACCEPT));
        }
    }

    @Test
    void theHostIsToldWhatWasSimulatedAndNothingIsDeclined() throws IOException {
        NetworkHooks.newServerSocket(18420).close();
        try (ServerSocketChannel channel = NetworkHooks.openServerSocketChannel()) {
            NetworkHooks.bind(channel, new InetSocketAddress(LOOPBACK, 18421));
        }
        NetworkHooks.newServerSocket(18420).close();

        List<String> simulated = NetworkHooks.simulatedRequests().entrySet().stream()
                .map(entry -> entry.getKey().kind() + " " + entry.getKey().target() + " x" + entry.getValue())
                .toList();
        assertEquals(List.of("LISTEN *:18420 x2", "LISTEN 127.0.0.1:18421 x1"), simulated);
        assertTrue(NetworkHooks.declinedRequests().isEmpty());

        NetworkHooks.clearDeclinedRequests();
        assertTrue(NetworkHooks.simulatedRequests().isEmpty());
    }

    @Test
    void installingHooksStartsAnEmptyNetworkAndLeavesTheEndedOnesListenersAlone() throws IOException {
        ServerSocket ended = NetworkHooks.newServerSocket(18430);

        Hooks.installDefault();

        try (ServerSocket fresh = NetworkHooks.newServerSocket(18430)) {
            ended.close();
            assertThrows(BindException.class, () -> NetworkHooks.newServerSocket(18430), "the new network's port is still held");
            assertFalse(fresh.isClosed());
        }
    }

    /** The hooks' clock moves only when a thread waits, by as long as it waits, and nobody really waits. */
    private static final class VirtualWaitHooks implements RobotHooks {
        final AtomicLong now = new AtomicLong();
        int waits;

        @Override
        public long nanoTime() {
            return now.get();
        }

        @Override
        public void wait(Object monitor, long timeoutMillis, int nanos) {
            waits++;
            now.addAndGet(TimeUnit.MILLISECONDS.toNanos(timeoutMillis) + nanos);
        }

        @Override
        public long currentTimeMillis() {
            return TimeUnit.NANOSECONDS.toMillis(now.get());
        }

        @Override
        public void sleep(long millis) {}

        @Override
        public void sleep(long millis, int nanos) {}

        @Override
        public void sleep(TimeUnit unit, long duration) {}

        @Override
        public void yieldThread() {}

        @Override
        public void loadLibrary(String name) {}

        @Override
        public void load(String absolutePath) {}
    }

    @Test
    void aTimeoutIsMeasuredAndWaitedOnTheHooksClock() throws IOException {
        VirtualWaitHooks hooks = new VirtualWaitHooks();
        Hooks.install(hooks);

        try (ServerSocket socket = NetworkHooks.newServerSocket(18440)) {
            socket.setSoTimeout(1000);

            assertThrows(SocketTimeoutException.class, socket::accept);
        }

        assertEquals(TimeUnit.SECONDS.toNanos(1), hooks.now.get(), "a second passed on the hooks' clock");
        assertEquals(20, hooks.waits, "in the slices in which a thread outside the schedule looks at the clock again");
    }
}
