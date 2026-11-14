package dev.ftcsim.core.kernel;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Predicate;

/**
 * The threads of the robot world other than the robot thread: a library starts threads of its own when it starts, and its
 * timers fire in real time. Nothing schedules them, so what they do next to the robot thread would be a matter of how fast
 * the machine happens to be, and the robot thread's loops would take as many turns as the library had got done at the
 * moment they looked, each turn a nanosecond of simulated time.
 *
 * <p>The simulator therefore lets such a thread finish before it moves time or hands the robot thread the baton: it waits,
 * in real time, until every one of them has come to rest, which is when it is waiting for something, blocked, or ended. The
 * thread runs in no simulated time and between two turns of the robot thread, and what it did is the same however fast it
 * ran. A thread that does not come to rest within the limit is recorded as a problem and not waited for again until it has
 * rested.
 *
 * <p>A thread is taken to run robot code when a frame of its stack belongs to the robot world. A thread that was started a
 * moment ago and has not yet reached its first frame outside the JDK is given a short time to do so, because the robot
 * thread that started it goes on at once. What this cannot see is a thread that wakes up while the robot thread holds the
 * baton; a library's timers that fire in real time therefore remain a reason to doubt a run, and the cure for that is to
 * give the library's waits a simulated clock, which the kernel does for the JDK calls the robot code makes itself.
 */
final class WorldThreads {
    private enum Kind {
        /** A frame of the robot world is on its stack. */
        WORLD,
        /** It runs something that is not robot code. */
        OTHER,
        /** It has no frame at all: it has not run yet, or it is a thread of the JVM that never runs Java. */
        NO_FRAMES,
        /** It has not yet left the JDK's own start-up frames. */
        JDK_ONLY
    }

    private static final long POLL_NANOS = 200_000L;

    /** How long a thread that was just started is given to show what it runs. */
    private static final long STARTING_GRACE_NANOS = 100_000_000L;

    private final KernelDiagnostics diagnostics;
    private final Predicate<StackTraceElement> worldFrame;
    private final long quietNanos;

    private final Set<Thread> world = new HashSet<>();
    private final Set<Thread> inert = new HashSet<>();
    private final Set<Thread> noisy = new HashSet<>();
    /** The threads that were there before the simulator started; they have had their time to start. */
    private final Set<Thread> existing = new HashSet<>();
    private final Map<Thread, Long> startingSince = new HashMap<>();
    private ThreadGroup root;

    WorldThreads(KernelDiagnostics diagnostics, Predicate<StackTraceElement> worldFrame, long quietMillis) {
        this.diagnostics = diagnostics;
        this.worldFrame = worldFrame;
        this.quietNanos = quietMillis * 1_000_000L;
        existing.addAll(Arrays.asList(liveThreads()));
    }

    /**
     * Waits until no thread that runs robot code is running.
     *
     * @param ignored the threads that are not this class's business: the robot thread, and threads that look like it
     * @return whether it had to wait
     */
    synchronized boolean awaitQuiet(Predicate<Thread> ignored) {
        Thread busy = busyThread(ignored);
        if (busy == null) {
            return false;
        }
        long startedAt = System.nanoTime();
        long deadline = startedAt + quietNanos;
        while (busy != null) {
            if (System.nanoTime() >= deadline) {
                noisy.add(busy);
                diagnostics.problem("the robot-world thread '" + busy.getName() + "' kept running for " + quietNanos / 1_000_000L
                        + " ms of real time while the simulator waited for it to rest (" + RobotGate.site(busy) + "); what it does"
                        + " to the robot from here on is not reproducible");
                deadline = System.nanoTime() + quietNanos;
            } else {
                LockSupport.parkNanos(POLL_NANOS);
            }
            busy = busyThread(ignored);
        }
        diagnostics.worldWaited(System.nanoTime() - startedAt);
        return true;
    }

    /** Forgets the threads that have ended. */
    synchronized void prune() {
        world.removeIf(thread -> !thread.isAlive());
        inert.removeIf(thread -> !thread.isAlive());
        noisy.removeIf(thread -> !thread.isAlive());
        existing.removeIf(thread -> !thread.isAlive());
        startingSince.keySet().removeIf(thread -> !thread.isAlive());
    }

    private Thread busyThread(Predicate<Thread> ignored) {
        Thread self = Thread.currentThread();
        long now = System.nanoTime();
        for (Thread thread : liveThreads()) {
            if (thread == self) {
                continue;
            }
            if (thread.getState() != Thread.State.RUNNABLE) {
                noisy.remove(thread);
                startingSince.remove(thread);
                continue;
            }
            if (ignored.test(thread) || noisy.contains(thread) || inert.contains(thread)) {
                continue;
            }
            Kind kind = world.contains(thread) ? Kind.WORLD : classify(thread);
            switch (kind) {
                case WORLD -> {
                    world.add(thread);
                    startingSince.remove(thread);
                    return thread;
                }
                case OTHER -> startingSince.remove(thread);
                case NO_FRAMES, JDK_ONLY -> {
                    long since = startingSince.computeIfAbsent(thread, key -> existing.contains(key) ? now - STARTING_GRACE_NANOS : now);
                    if (now - since < STARTING_GRACE_NANOS) {
                        return thread;
                    }
                    if (kind == Kind.NO_FRAMES) {
                        inert.add(thread);
                    }
                }
            }
        }
        return null;
    }

    private Kind classify(Thread thread) {
        StackTraceElement[] stack = thread.getStackTrace();
        if (stack.length == 0) {
            return Kind.NO_FRAMES;
        }
        boolean outsideJdk = false;
        for (StackTraceElement frame : stack) {
            if (worldFrame.test(frame)) {
                return Kind.WORLD;
            }
            outsideJdk |= !isJdk(frame);
        }
        return outsideJdk ? Kind.OTHER : Kind.JDK_ONLY;
    }

    private static boolean isJdk(StackTraceElement frame) {
        String module = frame.getModuleName();
        return module != null && (module.startsWith("java.") || module.startsWith("jdk."));
    }

    /** Every live platform thread of the process, which unlike a dump of all stacks costs next to nothing. */
    private Thread[] liveThreads() {
        if (root == null) {
            ThreadGroup group = Thread.currentThread().getThreadGroup();
            while (group.getParent() != null) {
                group = group.getParent();
            }
            root = group;
        }
        Thread[] threads = new Thread[root.activeCount() + 16];
        int count = root.enumerate(threads, true);
        return count == threads.length ? threads : Arrays.copyOf(threads, count);
    }
}
