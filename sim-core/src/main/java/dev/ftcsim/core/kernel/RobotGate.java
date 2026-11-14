package dev.ftcsim.core.kernel;

import dev.ftcsim.contracts.hooks.RobotUnwind;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.concurrent.locks.LockSupport;

/**
 * Makes the robot thread and the simulator thread take turns, so that what the robot observes is a function of
 * simulated time and never of how the operating system happened to schedule two threads.
 *
 * <p>The robot thread runs only after the simulator hands it the baton, and gives the baton back at its next
 * synchronisation point: a clock read that reaches the {@linkplain #setHorizon horizon}, a sleep, or a yield.
 * The simulator never decides anything while the robot holds the baton.
 *
 * <p>One robot thread is supported. A second thread that tries to join in, or a robot thread that blocks on
 * something the simulator does not control, is recorded as a {@link KernelDiagnostics#problems() problem}
 * instead of being silently trusted.
 */
final class RobotGate {

    enum State {
        /** No robot thread has been seen. */
        NEW,
        /** The robot thread holds the baton and is executing. */
        RUNNING,
        /** The robot thread is waiting in the gate for the simulator to grant the baton. */
        PARKED,
        /** The simulator found the robot thread blocked in something the gate does not control. */
        EXTERNAL,
        /** The robot thread ended. */
        DONE
    }

    /** Why the simulator is waiting for a robot thread that it had found blocked outside the gate. */
    private enum Wake {
        NONE,
        /** The simulator interrupted it to end it; its reaction is the simulator's own doing. */
        UNWOUND,
        /** It is running again: it was interrupted by the SDK, or something outside woke it. */
        RETURNED
    }

    /** Consecutive 1 ms observations of a blocked robot thread before it is declared externally blocked. */
    private static final int EXTERNAL_CONFIRMATIONS = 3;

    /** How many threads long a chain of lock waits is followed to see whether it ends in a thread that is running. */
    private static final int MAX_LOCK_CHAIN = 8;

    /** Real time the simulator gives a robot thread that is on its way into, or out of, a monitor wait. */
    private static final long MONITOR_SETTLE_NANOS = 5_000_000_000L;

    private final Object lock = new Object();
    private final VirtualClock clock;
    private final KernelDiagnostics diagnostics;
    private final Predicate<Thread> robotThreadMatcher;
    private final long livenessNanos;

    private volatile Thread robot;
    private volatile State state = State.NEW;
    private volatile long horizon = Long.MAX_VALUE;
    /** Set by the simulator to end the robot thread: the thread throws {@link RobotUnwind} at its next hook. */
    private volatile boolean unwindPending;
    /** Set by the simulator when it wakes the externally blocked robot thread; the thread then goes on from the current time. */
    private volatile Wake externalWake = Wake.NONE;
    /** Robot threads the simulator gave up on; each is parked for good at its next hook. */
    private final Set<Thread> abandoned = ConcurrentHashMap.newKeySet();
    private volatile boolean anyAbandoned;

    // Guarded by lock.
    private long wakeAt;
    private boolean parkInterruptible;
    private boolean inGatePark;
    private boolean interruptedWhileParked;
    /** The monitor the robot is waiting on through {@link #waitOn}, or null when it is not in such a wait. */
    private Object waitMonitor;
    private boolean monitorNotified;
    /** The interrupt that woke the externally blocked robot thread has been waited for; it is cleared when the flag is. */
    private boolean interruptAwaited;
    /** Where the robot thread was when it was found blocked outside the gate, to say so if it comes back. */
    private String externalSite = "";

    // Touched only by the simulator thread.
    private final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
    private long startedThreadsSeen = -1;
    private final WorldThreads worldThreads;

    // Touched only by the robot thread while it holds the baton.
    private long base;
    private long local;
    /** Monitors the robot thread currently holds. While it holds any it must not give up the baton. */
    private int lockDepth;

    RobotGate(VirtualClock clock, KernelDiagnostics diagnostics, Predicate<Thread> robotThreadMatcher) {
        this(clock, diagnostics, robotThreadMatcher, KernelConfig.DEFAULT_ROBOT_LIVENESS_MILLIS);
    }

    RobotGate(VirtualClock clock, KernelDiagnostics diagnostics, Predicate<Thread> robotThreadMatcher, long livenessMillis) {
        this(clock, diagnostics, robotThreadMatcher, livenessMillis, KernelConfig.ROBOT_WORLD_FRAME, KernelConfig.DEFAULT_WORLD_QUIET_MILLIS);
    }

    RobotGate(
            VirtualClock clock,
            KernelDiagnostics diagnostics,
            Predicate<Thread> robotThreadMatcher,
            long livenessMillis,
            Predicate<StackTraceElement> worldFrame,
            long worldQuietMillis) {
        this.clock = clock;
        this.diagnostics = diagnostics;
        this.robotThreadMatcher = robotThreadMatcher;
        this.livenessNanos = livenessMillis * 1_000_000L;
        this.worldThreads = new WorldThreads(diagnostics, worldFrame, worldQuietMillis);
    }

    // ---------------------------------------------------------------------------------------------
    // Robot side
    // ---------------------------------------------------------------------------------------------

    /**
     * Called by every hook. Returns true when the calling thread is the robot thread and now holds the
     * baton; false when it is some other thread, which the caller must treat as an ordinary thread.
     */
    boolean enter(Thread caller) {
        if (caller == robot) {
            rejoin(caller);
            return true;
        }
        if (anyAbandoned && abandoned.contains(caller)) {
            parkForever();
        }
        return bind(caller);
    }

    /**
     * The robot thread has reached a hook. If the simulator found it blocked outside the gate and it has come back on its
     * own, that is reported and it waits here to be granted the baton at the current time. If the simulator is waiting
     * for it, having seen it come back, it goes on from the current time instead of from the one it last saw, and the
     * return is reported unless the simulator woke it itself.
     */
    private void rejoin(Thread caller) {
        if (state == State.EXTERNAL) {
            diagnostics.problem(resumed(caller));
            park(clock.now(), false);
        } else if (externalWake != Wake.NONE) {
            if (externalWake == Wake.RETURNED) {
                diagnostics.problem(resumed(caller));
            }
            externalWake = Wake.NONE;
            base = clock.now();
            local = 0;
        }
    }

    private String resumed(Thread thread) {
        return "the robot thread '" + thread.getName() + "' resumed from a wait the simulator does not control (it was blocked in "
                + externalSite + "); timing from there on is not reproducible";
    }

    /** The innermost frames of where a thread is, for messages, leaving out the JDK's own parking internals. */
    static String site(Thread thread) {
        StringBuilder frames = new StringBuilder();
        int shown = 0;
        for (StackTraceElement frame : thread.getStackTrace()) {
            String owner = frame.getClassName();
            if (owner.startsWith("jdk.internal.") || owner.equals("java.util.concurrent.locks.LockSupport")) {
                continue;
            }
            frames.append(shown == 0 ? "" : " < ").append(owner).append('.').append(frame.getMethodName());
            if (++shown == 6) {
                break;
            }
        }
        return frames.toString();
    }

    /** For the hooks that do nothing for threads other than the robot: a thread the simulator gave up on must not run on. */
    void parkIfAbandoned(Thread caller) {
        if (anyAbandoned && abandoned.contains(caller)) {
            parkForever();
        }
    }

    private boolean bind(Thread caller) {
        if (!robotThreadMatcher.test(caller)) {
            return false;
        }
        synchronized (lock) {
            Thread current = robot;
            boolean replaceable = current == null || !current.isAlive() || state == State.DONE || state == State.EXTERNAL;
            if (!replaceable) {
                diagnostics.problem("thread '" + caller.getName() + "' also looks like a robot thread while '"
                        + current.getName() + "' is still alive; the simulator cannot order the two, so timing is not reproducible");
                return false;
            }
            robot = caller;
            state = State.RUNNING;
            waitMonitor = null;
            monitorNotified = false;
            lockDepth = 0;
        }
        park(clock.now(), false);
        return true;
    }

    /**
     * Charges {@code cost} of simulated time to the robot and returns the time it now observes. Reaching the
     * horizon normally hands the baton back so the world can catch up, but not inside a critical section: the
     * simulator thread may be waiting for a monitor the robot holds, and a robot that gave up the baton while
     * holding it would never get the baton back. The robot's clock then runs on past the horizon until it
     * leaves the section, which is deterministic and delays nothing but when it next sees the world change.
     */
    long observe(long cost) {
        throwIfUnwinding();
        rejoin(Thread.currentThread());
        local += cost;
        long now = base + local;
        if (now >= horizon && lockDepth == 0) {
            park(now, false);
            throwIfUnwinding();
            return base;
        }
        return now;
    }

    /** Where an unwind the simulator asked for takes effect: the robot thread's next hook after it was armed. */
    private void throwIfUnwinding() {
        if (unwindPending) {
            unwindPending = false;
            diagnostics.unwound();
            throw new RobotUnwind("the robot thread was asked to end");
        }
    }

    void lockAcquired() {
        lockDepth++;
    }

    void lockReleased() {
        if (lockDepth > 0) {
            lockDepth--;
        }
    }

    /** Sleeps the robot in simulated time; ends early with {@link InterruptedException} if it is interrupted. */
    void sleep(long nanos, long pollCost) throws InterruptedException {
        throwIfUnwinding();
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        if (nanos <= 0) {
            observe(pollCost);
            return;
        }
        park(base + local + nanos, true);
        throwIfUnwinding();
        boolean interrupted;
        synchronized (lock) {
            interrupted = interruptedWhileParked;
            interruptedWhileParked = false;
        }
        if (interrupted || Thread.interrupted()) {
            Thread.interrupted();
            throw new InterruptedException();
        }
    }

    /**
     * {@code Object.wait} for the robot thread, which must own {@code monitor}. The wait ends when the simulator
     * notifies the monitor, when simulated time passes {@code timeoutNanos} (zero meaning no timeout), or when
     * the thread is interrupted, and in every case only once the simulator has handed the baton back, so the
     * robot never resumes at a moment of the operating system's choosing.
     *
     * <p>The monitor is released for real, by really waiting on it, because that is the only way to release a
     * JVM monitor. Early wake-ups of that real wait are harmless: the loop below goes back to waiting until
     * the simulator has granted the baton.
     */
    void waitOn(Object monitor, long timeoutNanos) throws InterruptedException {
        if (!Thread.holdsLock(monitor)) {
            throw new IllegalMonitorStateException("current thread is not owner");
        }
        throwIfUnwinding();
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        Thread self = Thread.currentThread();
        long wake = timeoutNanos <= 0 ? Long.MAX_VALUE : saturatingAdd(base + local, timeoutNanos);
        synchronized (lock) {
            waitMonitor = monitor;
            monitorNotified = false;
            wakeAt = wake;
            parkInterruptible = true;
            inGatePark = true;
            state = State.PARKED;
            lock.notifyAll();
        }
        for (;;) {
            synchronized (lock) {
                // A thread that was abandoned while it waited stays in its wait, whatever happens to the monitor.
                if (state == State.RUNNING && robot == self) {
                    inGatePark = false;
                    waitMonitor = null;
                    break;
                }
            }
            try {
                monitor.wait();
            } catch (InterruptedException interrupted) {
                // The simulator learns of the interrupt from this flag and delivers it at a deterministic moment.
                synchronized (lock) {
                    if (robot == self) {
                        interruptedWhileParked = true;
                    }
                }
            }
        }
        base = clock.now();
        local = 0;
        externalWake = Wake.NONE;
        throwIfUnwinding();
        boolean interrupted;
        synchronized (lock) {
            interrupted = interruptedWhileParked;
            interruptedWhileParked = false;
        }
        if (interrupted || Thread.interrupted()) {
            Thread.interrupted();
            throw new InterruptedException();
        }
    }

    private static long saturatingAdd(long a, long b) {
        long sum = a + b;
        return sum < a ? Long.MAX_VALUE : sum;
    }

    private void park(long wake, boolean interruptible) {
        Thread self = Thread.currentThread();
        synchronized (lock) {
            wakeAt = wake;
            parkInterruptible = interruptible;
            inGatePark = true;
            state = State.PARKED;
            lock.notifyAll();
        }
        for (;;) {
            synchronized (lock) {
                if (robot != self) {
                    break;
                }
                if (state == State.RUNNING) {
                    inGatePark = false;
                    break;
                }
            }
            LockSupport.park(this);
            if (self.isInterrupted()) {
                // Clearing the flag and recording it in one step lets the simulator see an interrupt either way.
                synchronized (lock) {
                    if (Thread.interrupted() && robot == self) {
                        interruptedWhileParked = true;
                    }
                }
            }
        }
        if (robot != self) {
            parkForever();
        }
        base = clock.now();
        local = 0;
        externalWake = Wake.NONE;
        if (!interruptible && interruptedWhileParkedAndClear()) {
            self.interrupt();
        }
    }

    /** What a thread the simulator gave up on does at its next hook: it never runs robot code again. */
    private void parkForever() {
        for (;;) {
            LockSupport.park(this);
            Thread.interrupted();
        }
    }

    private boolean interruptedWhileParkedAndClear() {
        synchronized (lock) {
            boolean was = interruptedWhileParked;
            interruptedWhileParked = false;
            return was;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Simulator side
    // ---------------------------------------------------------------------------------------------

    /** The robot thread must park before observing a time at or beyond this one. */
    void setHorizon(long nanos) {
        horizon = nanos;
    }

    /** Pulls the horizon in, never out, so that a robot already running cannot overshoot a new deadline. */
    void lowerHorizon(long nanos) {
        if (nanos < horizon) {
            horizon = nanos;
        }
    }

    /** The earliest time at which the parked robot thread can run, or {@link Long#MAX_VALUE} when it cannot. */
    long nextWake() {
        synchronized (lock) {
            if (state != State.PARKED) {
                return Long.MAX_VALUE;
            }
            return unwindPending || (waitMonitor != null && monitorNotified) ? clock.now() : wakeAt;
        }
    }

    /**
     * Asks the robot thread to end: it throws {@link RobotUnwind} at its next hook, which a parked thread reaches as
     * soon as the simulator hands it the baton, and a thread blocked outside the simulator's control is interrupted
     * to get it there. The request lapses when the thread throws. Returns whether there was a live robot thread.
     *
     * <p>The interrupt of a thread that is blocked outside the gate is the simulator's own act, so the thread's
     * reaction is waited for here, as for a thread that was granted the baton: simulated time must not move while it
     * runs, or the instant at which it ends would depend on how quickly the machine happened to run it.
     */
    boolean requestUnwind() {
        Thread target;
        synchronized (lock) {
            target = robot;
            if (target == null || !target.isAlive() || state == State.DONE) {
                return false;
            }
            unwindPending = true;
            if (state != State.EXTERNAL) {
                return true;
            }
            state = State.RUNNING;
            interruptAwaited = true;
            externalWake = Wake.UNWOUND;
        }
        target.interrupt();
        awaitQuiescent();
        return true;
    }

    /**
     * Stops scheduling the robot thread, so that a new one can take its place; the old one is parked for good the
     * next time it reaches a hook. Returns whether there was a live robot thread.
     */
    boolean abandon() {
        synchronized (lock) {
            return abandonLocked();
        }
    }

    private boolean abandonLocked() {
        Thread target = robot;
        if (target == null || !target.isAlive()) {
            return false;
        }
        abandoned.add(target);
        anyAbandoned = true;
        robot = null;
        state = State.NEW;
        unwindPending = false;
        waitMonitor = null;
        monitorNotified = false;
        interruptedWhileParked = false;
        diagnostics.abandoned(target.getName());
        diagnostics.problem("the robot thread '" + target.getName() + "' did not end when asked to and was given up on; it may still"
                + " be running outside the simulation, so timing from there on is not reproducible");
        return true;
    }

    /**
     * Records that a thread other than the robot notified {@code monitor}. Returns true when the robot is
     * waiting on it: the notification is then held until the simulator grants the baton, which is when it takes
     * effect, so that the robot wakes at a simulated time and not at whatever moment the operating system runs it.
     */
    boolean notifyRobotWaiter(Object monitor) {
        synchronized (lock) {
            if (state == State.PARKED && waitMonitor == monitor) {
                monitorNotified = true;
                return true;
            }
            return false;
        }
    }

    boolean isRobot(Thread thread) {
        return thread == robot;
    }

    /** Whether the thread is the robot thread or one that the matcher takes for it, even before it has reached the gate. */
    boolean isRobotLike(Thread thread) {
        return thread == robot || robotThreadMatcher.test(thread);
    }

    /** Hands the baton to the robot thread if it is due, and returns once it has given it back. */
    boolean runIfDue(long now) {
        awaitMonitorSettled();
        if (awaitResumedExternalThread()) {
            return true;
        }
        Thread target;
        Object monitor;
        synchronized (lock) {
            if (!dueLocked(now)) {
                return false;
            }
            target = robot;
            monitor = waitMonitor;
            if (monitor == null) {
                state = State.RUNNING;
            }
        }
        if (monitor != null) {
            // Granting and notifying under the monitor means the robot, which tests the grant while holding the
            // monitor and only then waits, can neither miss the notification nor act on it before the grant.
            synchronized (monitor) {
                synchronized (lock) {
                    state = State.RUNNING;
                }
                monitor.notifyAll();
            }
        }
        diagnostics.handoff();
        LockSupport.unpark(target);
        awaitQuiescent();
        return true;
    }

    /**
     * A robot thread found blocked outside the gate can come back without being granted the baton: it was interrupted,
     * as the SDK does to end an OpMode, or something outside woke it. Simulated time must not move while it runs, or
     * the instant at which it acts would depend on how fast the machine runs it, so it is waited for as for a thread
     * that was granted the baton, until it reaches the gate, ends or blocks again. If it reaches a hook, that is reported
     * as a {@linkplain KernelDiagnostics#problems() problem} by {@link #rejoin}, because the instant it came back at was
     * not the simulator's to choose; a thread that just ends, as the idle worker of a finished OpMode does, is not.
     */
    private boolean awaitResumedExternalThread() {
        synchronized (lock) {
            Thread target = robot;
            if (state != State.EXTERNAL || target == null || !target.isAlive()) {
                return false;
            }
            boolean interrupted = target.isInterrupted();
            if (!interrupted) {
                interruptAwaited = false;
            }
            boolean running = target.getState() == Thread.State.RUNNABLE;
            if (!running && !(interrupted && !interruptAwaited)) {
                return false;
            }
            interruptAwaited = interrupted;
            externalWake = Wake.RETURNED;
            state = State.RUNNING;
        }
        awaitQuiescent();
        return true;
    }

    /**
     * Call before simulated time moves. A robot thread that was just created has not yet reached its first hook, so the
     * simulator does not know it; time that passed while it started would be time it never saw, and how much passed
     * would depend on how quickly the machine starts a thread. The JVM's count of started threads says cheaply when
     * one may have been created since the last time.
     */
    void awaitStartingThreads() {
        long started = threadBean.getTotalStartedThreadCount();
        if (started != startedThreadsSeen) {
            worldThreads.prune();
            settle(livenessNanos / 1_000_000L);
            startedThreadsSeen = threadBean.getTotalStartedThreadCount();
        }
    }

    private boolean dueLocked(long now) {
        if (state != State.PARKED) {
            return false;
        }
        if (unwindPending || wakeAt <= now) {
            return true;
        }
        if (waitMonitor != null && monitorNotified) {
            return true;
        }
        return parkInterruptible && (robot.isInterrupted() || interruptedWhileParked);
    }

    /**
     * A robot thread entering a monitor wait, or leaving it because of an interrupt, is briefly between two
     * states the simulator can see. Waiting here until it is really waiting, with no interrupt in flight, is
     * what makes the time at which an interrupt takes effect a property of the simulation.
     */
    private void awaitMonitorSettled() {
        long deadline = System.nanoTime() + MONITOR_SETTLE_NANOS;
        for (;;) {
            Thread target;
            synchronized (lock) {
                if (state != State.PARKED || waitMonitor == null) {
                    return;
                }
                target = robot;
            }
            Thread.State threadState = target.getState();
            boolean waiting = threadState == Thread.State.WAITING || threadState == Thread.State.TIMED_WAITING;
            if ((waiting && !target.isInterrupted()) || !target.isAlive()) {
                return;
            }
            if (System.nanoTime() >= deadline) {
                diagnostics.problem("the robot thread '" + target.getName() + "' did not settle into its monitor wait ("
                        + threadState + "); timing from there on is not reproducible");
                return;
            }
            LockSupport.parkNanos(20_000L);
        }
    }

    /**
     * Waits until the robot thread is no longer running: parked in the gate, ended, or blocked in something the
     * gate does not control. Call this after any simulator action that can start or wake the robot thread.
     *
     * @param maxRealMillis how long to wait for a robot thread that has been created but has not yet reached
     *                      its first synchronisation point
     */
    void settle(long maxRealMillis) {
        long limit = maxRealMillis * 1_000_000L;
        long deadline = System.nanoTime() + limit;
        for (;;) {
            awaitQuiescent();
            if (unboundCandidateExists()) {
                if (System.nanoTime() >= deadline) {
                    diagnostics.problem("a thread that looks like the robot thread was started but did not reach the simulator within "
                            + maxRealMillis + " ms; it runs outside the simulation, so timing from there on is not reproducible");
                    return;
                }
                LockSupport.parkNanos(200_000L);
                continue;
            }
            // A thread of the robot world that was running can have woken the robot thread, so look at it again.
            if (!awaitWorldQuiet()) {
                return;
            }
            deadline = System.nanoTime() + limit;
        }
    }

    /**
     * The robot thread is not the only thread that runs robot code; see {@link WorldThreads}. Waits, in real time, until
     * the others have come to rest.
     *
     * @return whether it had to wait
     */
    boolean awaitWorldQuiet() {
        return worldThreads.awaitQuiet(
                thread -> thread == robot || robotThreadMatcher.test(thread) || (anyAbandoned && abandoned.contains(thread)));
    }

    State state() {
        return state;
    }

    /** A one-line account of the robot thread's situation, for diagnostics and failure messages. */
    String describe() {
        synchronized (lock) {
            Thread thread = robot;
            return "robot=" + (thread == null ? "none" : thread.getName() + "/" + thread.getState())
                    + " gate=" + state + " wakeAt=" + wakeAt + " horizon=" + horizon + " clock=" + clock.now()
                    + (waitMonitor == null ? "" : " waitingOn=" + waitMonitor.getClass().getName() + (monitorNotified ? "(notified)" : ""));
        }
    }

    /**
     * Whether the thread waits for a lock that a thread which is running holds, perhaps through a chain of such waits.
     * It goes on as soon as the holder lets go, which is a matter of real time and not a wait the simulator has to give
     * up on: a library's timer that reads a hub holds the hub's lock for a moment, and a timer the operating system has
     * put aside holds it for longer. A lock held by the simulator thread, which is waiting for this one, or by a thread
     * that is itself waiting for something else, is not such a wait.
     */
    private boolean waitsForRunningThread(Thread thread) {
        long simulator = Thread.currentThread().threadId();
        long waiting = thread.threadId();
        for (int hops = 0; hops < MAX_LOCK_CHAIN; hops++) {
            ThreadInfo info = threadBean.getThreadInfo(waiting, 0);
            long owner = info == null ? -1 : info.getLockOwnerId();
            if (owner < 0 || owner == simulator) {
                return false;
            }
            ThreadInfo ownerInfo = threadBean.getThreadInfo(owner, 0);
            if (ownerInfo == null) {
                return false;
            }
            Thread.State ownerState = ownerInfo.getThreadState();
            if (ownerState == Thread.State.RUNNABLE) {
                return true;
            }
            if (ownerState != Thread.State.BLOCKED && ownerState != Thread.State.WAITING && ownerState != Thread.State.TIMED_WAITING) {
                return false;
            }
            waiting = owner;
        }
        return false;
    }

    private boolean unboundCandidateExists() {
        Thread bound = robot;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread != bound
                    && thread.isAlive()
                    && robotThreadMatcher.test(thread)
                    && thread.getState() == Thread.State.RUNNABLE
                    && !abandoned.contains(thread)) {
                return true;
            }
        }
        return false;
    }

    private void awaitQuiescent() {
        int blockedObservations = 0;
        long progressedAt = System.nanoTime();
        boolean asked = false;
        synchronized (lock) {
            while (state == State.RUNNING) {
                try {
                    lock.wait(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("the simulator thread was interrupted while the robot thread was running", e);
                }
                if (state != State.RUNNING || inGatePark) {
                    blockedObservations = 0;
                    progressedAt = System.nanoTime();
                    continue;
                }
                Thread running = robot;
                if (!running.isAlive()) {
                    state = State.DONE;
                    break;
                }
                Thread.State threadState = running.getState();
                boolean blocked = (threadState == Thread.State.WAITING
                                || threadState == Thread.State.TIMED_WAITING
                                || threadState == Thread.State.BLOCKED)
                        && !waitsForRunningThread(running);
                blockedObservations = blocked ? blockedObservations + 1 : 0;
                if (blockedObservations >= EXTERNAL_CONFIRMATIONS) {
                    externalSite = site(running);
                    state = State.EXTERNAL;
                    continue;
                }
                // A robot thread that runs and runs without reaching a synchronisation point, which a loop in the
                // JDK does, would hold the simulator here for good.
                long ranFor = System.nanoTime() - progressedAt;
                if (ranFor > 2 * livenessNanos) {
                    abandonLocked();
                    break;
                }
                if (ranFor > livenessNanos && !asked) {
                    asked = true;
                    unwindPending = true;
                    diagnostics.problem("the robot thread '" + running.getName() + "' ran for " + ranFor / 1_000_000_000L
                            + " s of real time without reaching a synchronisation point; it was asked to end");
                }
            }
        }
    }
}
