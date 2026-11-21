package dev.ftcsim.contracts.hooks;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Static entry points that rewritten robot bytecode calls. Every method forwards to the installed
 * {@link RobotHooks}; the indirection is a single volatile read, which the JIT handles well, and it is the
 * only place instrumented code needs to know about.
 *
 * <p>There is one robot session per JVM, so the delegate is process-wide state. Tests that install their own
 * must restore {@link #installDefault()} afterwards.
 */
public final class Hooks {
    /** The name of the robot world's class loader, which is what a stack frame of robot code reports as its class loader name. */
    public static final String WORLD_LOADER_NAME = "robot";

    private static volatile RobotHooks delegate = new RealWorldHooks();
    private static volatile long generation;
    private static final ShutdownHooks SHUTDOWN_HOOKS = new ShutdownHooks();

    private Hooks() {}

    public static RobotHooks install(RobotHooks hooks) {
        RobotHooks previous = delegate;
        delegate = java.util.Objects.requireNonNull(hooks, "hooks");
        generation++;
        NetworkHooks.reset();
        SHUTDOWN_HOOKS.clear();
        return previous;
    }

    public static void installDefault() {
        delegate = new RealWorldHooks();
        generation++;
        NetworkHooks.reset();
        SHUTDOWN_HOOKS.clear();
    }

    /** Changes whenever hooks are installed, which is when a new robot world begins; state kept per world is stale then. */
    static long generation() {
        return generation;
    }

    public static long nanoTime() {
        return delegate.nanoTime();
    }

    public static long currentTimeMillis() {
        return delegate.currentTimeMillis();
    }

    /** The wall-clock time robot code sees, in nanoseconds since 1970; see {@link RobotHooks#currentTimeNanos}. */
    public static long currentTimeNanos() {
        return delegate.currentTimeNanos();
    }

    public static void sleep(long millis) throws InterruptedException {
        delegate.sleep(millis);
    }

    public static void sleep(long millis, int nanos) throws InterruptedException {
        delegate.sleep(millis, nanos);
    }

    /** Replaces {@code TimeUnit.sleep}, whose receiver becomes the first argument. */
    public static void sleep(TimeUnit unit, long duration) throws InterruptedException {
        delegate.sleep(unit, duration);
    }

    public static void yieldThread() {
        delegate.yieldThread();
    }

    public static void loadLibrary(String name) {
        delegate.loadLibrary(name);
    }

    public static void load(String absolutePath) {
        delegate.load(absolutePath);
    }

    /** Charges the calling robot thread {@code nanos} of simulated time; see {@link RobotHooks#elapse}. */
    public static void elapse(long nanos) {
        delegate.elapse(nanos);
    }

    /** Replaces {@code Object.wait()}, whose receiver becomes the first argument. */
    public static void wait(Object monitor) throws InterruptedException {
        delegate.wait(monitor, 0L, 0);
    }

    public static void wait(Object monitor, long timeoutMillis) throws InterruptedException {
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("timeout value is negative");
        }
        delegate.wait(monitor, timeoutMillis, 0);
    }

    public static void wait(Object monitor, long timeoutMillis, int nanos) throws InterruptedException {
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("timeout value is negative");
        }
        if (nanos < 0 || nanos > 999_999) {
            throw new IllegalArgumentException("nanosecond timeout value out of range");
        }
        delegate.wait(monitor, timeoutMillis, nanos);
    }

    public static void notify(Object monitor) {
        delegate.notify(monitor);
    }

    public static void notifyAll(Object monitor) {
        delegate.notifyAll(monitor);
    }

    /** Called before every backward jump in robot-world code; see {@link RobotHooks#backEdge}. */
    public static void backEdge() {
        delegate.backEdge();
    }

    /** Called after every monitor acquisition in robot-world code; see {@link RobotHooks#lockAcquired}. */
    public static void lockAcquired() {
        delegate.lockAcquired();
    }

    /** Called after every monitor release in robot-world code; see {@link RobotHooks#lockReleased}. */
    public static void lockReleased() {
        delegate.lockReleased();
    }

    /** Called at the entry point of threads the SDK creates; see {@link RobotHooks#threadStarted}. */
    public static void threadStarted() {
        delegate.threadStarted();
    }

    /** Asks the installed hooks on behalf of {@link NetworkHooks}, the entry points for network access. */
    static boolean allowNetwork(NetworkRequest request) {
        return delegate.allowNetwork(request);
    }

    /** Asks the installed hooks on behalf of {@link NetworkHooks} whether listeners are simulated. */
    static boolean simulateListeners() {
        return delegate.simulateListeners();
    }

    /** Asks the installed hooks on behalf of {@link NetworkHooks} whether a listener on the port is a real loopback one. */
    static boolean listenOnLoopback(int port) {
        return delegate.listenOnLoopback(port);
    }

    /**
     * Whether the calling thread is one the host orders in simulated time, for the adapters that reach from the
     * robot world into the host; see {@link RobotHooks#isScheduledThread}.
     */
    public static boolean isScheduledThread() {
        return delegate.isScheduledThread();
    }

    /** Runs {@code action} once after {@code delayNanos} on the host's clock; see {@link RobotHooks#watchdog}. */
    public static Runnable watchdog(long delayNanos, Runnable action) {
        return delegate.watchdog(delayNanos, action);
    }

    /** Ends the robot thread; see {@link RobotHooks#unwindRobotThread}. */
    public static boolean unwindRobotThread() {
        return delegate.unwindRobotThread();
    }

    /** Gives up on the robot thread; see {@link RobotHooks#abandonRobotThread}. */
    public static boolean abandonRobotThread() {
        return delegate.abandonRobotThread();
    }

    /** Replaces {@code System.exit}; see {@link RobotHooks#exit}. */
    public static void exit(int status) {
        delegate.exit(status);
    }

    /** Replaces {@code Runtime.exit}, whose receiver becomes the first argument. */
    public static void exit(Runtime runtime, int status) {
        delegate.exit(status);
    }

    /** Replaces {@code Runtime.halt}, whose receiver becomes the first argument. */
    public static void halt(Runtime runtime, int status) {
        delegate.halt(status);
    }

    /**
     * Replaces {@code Runtime.addShutdownHook}. The robot program's shutdown hooks are remembered, see
     * {@link #shutdownHooks()}, and never run: the robot's process is never shut down, the simulator's is.
     */
    public static void addShutdownHook(Runtime runtime, Thread hook) {
        SHUTDOWN_HOOKS.add(hook);
    }

    public static boolean removeShutdownHook(Runtime runtime, Thread hook) {
        return SHUTDOWN_HOOKS.remove(hook);
    }

    /** The shutdown hooks the robot program registered and has not removed, in registration order. */
    public static List<Thread> shutdownHooks() {
        return SHUTDOWN_HOOKS.registered();
    }

    /** Behaviour of the unmodified JDK, used until a host installs something else. */
    private static final class RealWorldHooks implements RobotHooks {
        @Override
        public long nanoTime() {
            return System.nanoTime();
        }

        @Override
        public long currentTimeMillis() {
            return System.currentTimeMillis();
        }

        @Override
        public long currentTimeNanos() {
            java.time.Instant now = java.time.Instant.now();
            return now.getEpochSecond() * 1_000_000_000L + now.getNano();
        }

        @Override
        public void sleep(long millis) throws InterruptedException {
            Thread.sleep(millis);
        }

        @Override
        public void sleep(long millis, int nanos) throws InterruptedException {
            Thread.sleep(millis, nanos);
        }

        @Override
        public void sleep(TimeUnit unit, long duration) throws InterruptedException {
            unit.sleep(duration);
        }

        @Override
        public void yieldThread() {
            Thread.yield();
        }

        @Override
        public void loadLibrary(String name) {
            throw new UnsatisfiedLinkError("No native library policy is installed, so '" + name + "' was not loaded");
        }

        @Override
        public void load(String absolutePath) {
            throw new UnsatisfiedLinkError("No native library policy is installed, so '" + absolutePath + "' was not loaded");
        }
    }
}
