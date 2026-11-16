package dev.ftcsim.core.kernel;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Things the kernel noticed that the team or the user may want to know about: a robot thread that came back from a
 * wait the simulator does not control, a second thread that looks like the robot thread, a thread that would not end
 * and was given up on. A run with {@link #problems()} is still executed.
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

    void problem(String description) {
        problems.add(description);
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

    /** What the kernel noticed about the robot thread that the user should hear of, in the order it was found. */
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

    /** Names of the robot threads the kernel gave up on, which may still be running outside the simulation. */
    public List<String> abandonedThreads() {
        return List.copyOf(abandonedThreads);
    }

    /** What the robot program asked to end the process with, in order; the kernel never ends it. */
    public List<ExitRequest> exitRequests() {
        return List.copyOf(exitRequests);
    }
}
