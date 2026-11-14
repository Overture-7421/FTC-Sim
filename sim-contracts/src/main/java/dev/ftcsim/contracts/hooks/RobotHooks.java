package dev.ftcsim.contracts.hooks;

import java.util.concurrent.TimeUnit;

/**
 * What the simulator substitutes for JDK calls that would otherwise make a robot program depend on the
 * real world: clocks, sleeping and native libraries. The load-time rewriter redirects those call sites to
 * {@link Hooks}, which forwards here.
 *
 * <p>The signatures mirror the JDK methods they replace so that a rewrite never changes a program's
 * behaviour except for the clock it observes.
 */
public interface RobotHooks {

    long nanoTime();

    long currentTimeMillis();

    /**
     * The wall-clock time robot code sees, in nanoseconds since 1970, which is what {@link #currentTimeMillis} says with
     * the precision a clock of a robot controller has. Everything that asks the JDK for today's date goes through here.
     * The default has a millisecond's precision.
     */
    default long currentTimeNanos() {
        return currentTimeMillis() * 1_000_000L;
    }

    /**
     * A seed for a pseudo-random generator that robot code created without one, which the JDK would seed from the
     * clock and the operating system, so that two runs of the same program get different numbers. A host that wants
     * runs to repeat answers with a number derived from its own seed, in an order that does not depend on how fast
     * the machine is. The default is unpredictable, as the JDK's own.
     */
    default long randomSeed() {
        return java.util.concurrent.ThreadLocalRandom.current().nextLong();
    }

    /** The next number of {@code Math.random}; the default is the JDK's own. */
    default double mathRandom() {
        return Math.random();
    }

    /**
     * Robot code did something that the simulator cannot make reproducible, such as asking {@code ThreadLocalRandom}
     * for numbers on a JVM that does not let its seed be set. A host with diagnostics records it. The default ignores it.
     */
    default void nondeterministic(String description) {
    }

    void sleep(long millis) throws InterruptedException;

    void sleep(long millis, int nanos) throws InterruptedException;

    void sleep(TimeUnit unit, long duration) throws InterruptedException;

    void yieldThread();

    /**
     * A robot program asked to load a native library. Android's native code cannot run on a desktop JVM, so a
     * host normally records the request and declines; the program only fails if it later calls a native method.
     */
    void loadLibrary(String name);

    void load(String absolutePath);

    /**
     * The calling robot thread spends {@code nanos} of simulated time on something the simulator models but the
     * JDK cannot see, such as waiting for a hub to answer a command. Unlike a sleep it is not cut short by an
     * interrupt; an interrupt stays pending for the program's next blocking call. The default charges nothing,
     * so a host without a simulated clock sees no difference.
     */
    default void elapse(long nanos) {
    }

    /**
     * {@code Object.wait} on a monitor the caller owns, {@code timeoutMillis} of zero meaning no timeout. The
     * defaults perform the real JDK operation, so a host that does not virtualise monitors changes nothing.
     */
    default void wait(Object monitor, long timeoutMillis, int nanos) throws InterruptedException {
        if (timeoutMillis == 0 && nanos == 0) {
            monitor.wait();
        } else {
            monitor.wait(timeoutMillis, nanos);
        }
    }

    default void notify(Object monitor) {
        monitor.notify();
    }

    default void notifyAll(Object monitor) {
        monitor.notifyAll();
    }

    /**
     * The calling thread is about to jump backwards, which is how every loop repeats. A host charges the robot
     * thread a little simulated time here, so that a loop waiting for something to change, with no clock read or
     * sleep in it, still lets simulated time pass and the thing change.
     */
    default void backEdge() {
    }

    /**
     * The calling thread has just acquired a monitor, by entering a {@code synchronized} block or method. Lets a
     * host know when a thread is inside a critical section, which it must not be held up in.
     */
    default void lockAcquired() {
    }

    /** The calling thread has just released a monitor acquired through {@link #lockAcquired}. */
    default void lockReleased() {
    }

    /**
     * The calling thread is at the very first instruction of robot-world code it runs. A host that schedules the
     * robot thread uses this to take control of it before it can touch anything.
     */
    default void threadStarted() {
    }

    /**
     * Robot code is about to listen on, connect to or send to the network, through one of the JDK entry points the
     * rewriter redirects (sockets, channels, URL connections). Returning {@code false} makes the call fail the way a
     * closed port fails, with an {@link java.io.IOException} that names the target, and records the request for
     * diagnostics; returning {@code true} lets the real JDK call proceed. The default declines everything, because a
     * listening socket makes Windows ask the user about its firewall and any traffic makes a run depend on the
     * outside world.
     */
    default boolean allowNetwork(NetworkRequest request) {
        return false;
    }

    /**
     * Whether robot code that listens, through a {@code ServerSocket} or a {@code ServerSocketChannel}, gets a
     * simulated listener instead of the policy of {@link #allowNetwork}. A simulated listener holds a port in a
     * private network of the robot world: it binds nothing on the machine, {@code accept} on it waits until it is
     * closed, and no client can ever connect to it, because the robot world has no clients. Dashboards that start
     * a web server to be opened in a browser, such as Panels, then start as they do on a robot, and nothing on the
     * machine can reach them. Connecting out stays refused. The default is yes; a host that answers no gets the
     * per-request policy of {@link #allowNetwork}.
     */
    default boolean simulateListeners() {
        return true;
    }

    /**
     * Whether the calling thread is one the host orders in simulated time: the robot thread and the simulator
     * thread. Any other thread, such as a library's timer, runs in real time next to the simulation, so what it does
     * to the simulated world cannot be reproduced; code that reaches into the host, such as the hub wire, asks so
     * that the host can treat such a call apart. A host with no scheduler has no other kind of thread, which is
     * what the default says.
     */
    default boolean isScheduledThread() {
        return true;
    }

    /**
     * Runs {@code action} once, {@code delayNanos} from now on the host's clock, unless the returned canceller is
     * run first. The host chooses the thread: a host with a simulated clock runs it on the simulator thread, at the
     * simulated time, even while the simulator waits inside another action, which is how a timeout can be measured
     * in simulated time. The default is a real-time timer on a thread of its own.
     */
    default Runnable watchdog(long delayNanos, Runnable action) {
        return RealTimeWatchdogs.schedule(delayNanos, action);
    }

    /**
     * Ends the robot thread: it throws {@link RobotUnwind} at its next hook, which is its next clock read, sleep,
     * wait, yield or turn of a loop, and if it is blocked in something the host does not control it is interrupted.
     * Every {@code finally} block runs on the way out. Returns whether there was a live robot thread to ask; the
     * request lapses once the thread has thrown, so a thread that swallows the error has to be asked again.
     */
    default boolean unwindRobotThread() {
        return false;
    }

    /**
     * Gives up on the robot thread that did not end when asked to: the host stops scheduling it and lets a new robot
     * thread take its place, and the abandoned thread is parked for good at its next hook. It cannot be killed, so
     * it may still hold resources. Returns whether there was a live robot thread to abandon.
     */
    default boolean abandonRobotThread() {
        return false;
    }

    /**
     * The robot program called {@code System.exit} or {@code Runtime.exit}. The simulator's process is not the robot's
     * to end, so the host records the request and the call ends the calling thread by throwing {@link RobotExit}.
     * The default does the same without recording.
     */
    default void exit(int status) {
        throw new RobotExit(status, false);
    }

    /** {@code Runtime.halt}, which is {@code exit} without the shutdown hooks; see {@link #exit}. */
    default void halt(int status) {
        throw new RobotExit(status, true);
    }
}
