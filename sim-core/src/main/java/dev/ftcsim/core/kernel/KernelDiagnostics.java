package dev.ftcsim.core.kernel;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Things the kernel noticed that weaken the determinism guarantee. A deterministic run has none of the
 * {@link #problems()}; a run with some is still executed but is reported as not reproducible.
 */
public final class KernelDiagnostics {
    /** The robot program asked to end the process, with System.exit or Runtime.exit, or Runtime.halt. */
    public record ExitRequest(int status, boolean halt, String thread) {}

    private final List<String> problems = new CopyOnWriteArrayList<>();
    private final List<String> declinedNativeLibraries = new CopyOnWriteArrayList<>();
    private final List<String> abandonedThreads = new CopyOnWriteArrayList<>();
    private final List<ExitRequest> exitRequests = new CopyOnWriteArrayList<>();
    private final AtomicLong handoffs = new AtomicLong();
    private final AtomicLong unwinds = new AtomicLong();
    private final AtomicLong worldWaits = new AtomicLong();
    private final AtomicLong worldWaitNanos = new AtomicLong();

    void problem(String description) {
        problems.add(description);
    }

    void worldWaited(long realNanos) {
        worldWaits.incrementAndGet();
        worldWaitNanos.addAndGet(realNanos);
    }

    void nativeLibraryDeclined(String name) {
        declinedNativeLibraries.add(name);
    }

    void handoff() {
        handoffs.incrementAndGet();
    }

    void unwound() {
        unwinds.incrementAndGet();
    }

    /** A robot thread the kernel stopped scheduling because it would not end; it is also a {@linkplain #problems() problem}. */
    void abandoned(String thread) {
        abandonedThreads.add(thread);
    }

    void exitRequested(ExitRequest request) {
        exitRequests.add(request);
    }

    /** Conditions under which the run cannot be promised reproducible, in the order they were found. */
    public List<String> problems() {
        return List.copyOf(problems);
    }

    /** Native libraries the robot program asked for and the simulator declined, in request order. */
    public List<String> declinedNativeLibraries() {
        return List.copyOf(declinedNativeLibraries);
    }

    /** How many times control passed between the simulator and the robot thread. */
    public long handoffs() {
        return handoffs.get();
    }

    /** How many times the robot thread threw {@code RobotUnwind} because it was asked to end. */
    public long unwinds() {
        return unwinds.get();
    }

    /**
     * How many times the simulator held simulated time back to let a thread of the robot world, other than the robot
     * thread, come to rest: a library's own thread that starts the library up, or a timer that fires. Such a thread runs in
     * real time; what it does is finished before the robot thread's next turn, so it never depends on how fast it ran.
     */
    public long worldWaits() {
        return worldWaits.get();
    }

    /** The real time those waits took altogether, in nanoseconds. */
    public long worldWaitNanos() {
        return worldWaitNanos.get();
    }

    /** Names of the robot threads the kernel gave up on, which may still be running outside the simulation. */
    public List<String> abandonedThreads() {
        return List.copyOf(abandonedThreads);
    }

    /** What the robot program asked to end the process with, in order; the kernel never ends it. */
    public List<ExitRequest> exitRequests() {
        return List.copyOf(exitRequests);
    }
}
