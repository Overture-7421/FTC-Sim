package dev.ftcsim.core.kernel;

import dev.ftcsim.contracts.hooks.RobotHooks;
import java.util.PriorityQueue;

/**
 * Advances simulated time and everything that lives in it: the fixed-step world, scheduled callbacks, and the
 * robot thread. Time moves only here and the robot thread is run strictly between moves, so the robot sees
 * simulated time however fast or slowly the host drives it; the host can pause, slow down or speed up a run, and the
 * simulation needs no locks because only one of the two threads runs at a time.
 *
 * <p>At any instant the order is fixed: world steps up to that time, then scheduled callbacks, then the robot
 * thread. Time then jumps to the earliest of the next world step, the next callback, the robot's wake-up time
 * and the caller's target.
 */
public final class SimulationKernel {
    private final VirtualClock clock = new VirtualClock();
    private final KernelDiagnostics diagnostics = new KernelDiagnostics();
    private final WorldStepper world;
    private final RobotGate gate;
    private final KernelHooks hooks;

    private final Object eventLock = new Object();
    private final PriorityQueue<ScheduledEvent> events = new PriorityQueue<>();
    private final PriorityQueue<ScheduledEvent> watchdogs = new PriorityQueue<>();
    private long nextSequence;

    private volatile Thread simulatorThread;
    private final Thread creator = Thread.currentThread();
    private int depth;

    public SimulationKernel(WorldStepper world, KernelConfig config) {
        this.world = world;
        this.gate = new RobotGate(clock, diagnostics, config.robotThread(), config.robotLivenessMillis());
        this.hooks = new KernelHooks(this, gate, clock, config, diagnostics);
    }

    public VirtualClock clock() {
        return clock;
    }

    /** The replacement for the JDK's clock and sleep calls; install it with {@code Hooks.install}. */
    public RobotHooks hooks() {
        return hooks;
    }

    public KernelDiagnostics diagnostics() {
        return diagnostics;
    }

    /**
     * Runs {@code callback} on the simulator thread once simulated time reaches {@code atNanos}. Callbacks due
     * at the same time run in the order they were scheduled.
     */
    public void schedule(long atNanos, Runnable callback) {
        synchronized (eventLock) {
            events.add(new ScheduledEvent(atNanos, nextSequence++, callback));
        }
        gate.lowerHorizon(atNanos);
    }

    /**
     * Runs {@code action} on the simulator thread once simulated time has advanced by {@code delayNanos}; the returned
     * action cancels it. Unlike {@link #schedule}, a watchdog also runs while the simulator thread waits inside
     * another action, which is where a timeout on that action has to be measured.
     */
    Runnable watchdog(long delayNanos, Runnable action) {
        long now = clock.now();
        long at = delayNanos > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + delayNanos;
        ScheduledEvent watchdog;
        synchronized (eventLock) {
            watchdog = new ScheduledEvent(at, nextSequence++, action);
            watchdogs.add(watchdog);
        }
        gate.lowerHorizon(at);
        return () -> {
            synchronized (eventLock) {
                watchdogs.remove(watchdog);
            }
        };
    }

    /** See {@link RobotGate#requestUnwind}. */
    boolean unwindRobot() {
        return gate.requestUnwind();
    }

    /** See {@link RobotGate#abandon}. */
    boolean abandonRobot() {
        return gate.abandon();
    }

    /** Advances simulated time to {@code targetNanos}, running everything due on the way. */
    public void runUntil(long targetNanos) {
        bindSimulatorThread();
        depth++;
        try {
            advance(targetNanos);
        } finally {
            depth--;
        }
    }

    /**
     * Runs {@code action} on the simulator thread as though it were a scheduled callback that fell due now.
     * Simulated time can pass inside it, for instance when the SDK sleeps while stopping an OpMode, and the
     * periodic callbacks stay out of it, as they would if the action had been run by the event loop itself.
     */
    public void runAsEvent(Runnable action) {
        bindSimulatorThread();
        if (depth > 0) {
            action.run();
            return;
        }
        schedule(clock.now(), action);
        runUntil(clock.now());
    }

    private void bindSimulatorThread() {
        Thread self = Thread.currentThread();
        if (simulatorThread == null) {
            simulatorThread = self;
        } else if (simulatorThread != self) {
            throw new IllegalStateException("only the simulator thread may advance simulated time");
        }
    }

    public void runFor(long durationNanos) {
        long now = clock.now();
        runUntil(durationNanos > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + durationNanos);
    }

    /**
     * Waits, in real time, until the robot thread has stopped running: it parked, ended, or blocked on something
     * outside the simulator's control. Call after any action that can start or wake it, such as initialising an
     * OpMode, so that the simulator never races the robot.
     */
    public void settleRobot(long maxRealMillis) {
        gate.settle(maxRealMillis);
    }

    boolean isSimulatorThread(Thread thread) {
        return thread == simulatorThread;
    }

    /**
     * Whether {@code thread} is the one that moves simulated time. Until some thread has done that, the thread that
     * made the kernel is the one that will, so it counts, and a thread that is none of these is a foreign one.
     */
    boolean isScheduledSimulatorThread(Thread thread) {
        Thread simulator = simulatorThread;
        return thread == (simulator == null ? creator : simulator);
    }

    /** A one-line account of where the robot thread stands, for diagnostics and failure messages. */
    public String describeRobot() {
        return gate.describe();
    }

    /** Where the robot thread stands in the gate, for tests and diagnostics. */
    RobotGate.State gateState() {
        return gate.state();
    }

    private void advance(long target) {
        for (;;) {
            long now = clock.now();
            boolean progressed;
            do {
                progressed = false;
                if (depth == 1) {
                    progressed = runDueEvents(now);
                }
                progressed |= runDueWatchdogs(now);
                gate.setHorizon(Math.min(target, Math.min(world.nextStepNanos(), Math.min(nextEventNanos(), nextWatchdogNanos()))));
                progressed |= gate.runIfDue(now);
            } while (progressed);

            // A callback can let simulated time pass, as the SDK does while it stops a crashed OpMode, so what is
            // decided from here on must use the clock and not the time read before the callbacks ran.
            now = clock.now();
            if (now >= target) {
                return;
            }
            // A robot thread that rejoined from an uncontrolled wait can report a wake-up time that the clock has
            // already passed; that means "due now", never "go back in time".
            long next = Math.max(
                    now,
                    Math.min(
                            Math.min(target, world.nextStepNanos()),
                            Math.min(Math.min(nextEventNanos(), nextWatchdogNanos()), gate.nextWake())));
            world.advanceTo(next);
            clock.advanceTo(next);
        }
    }

    /** Callbacks only run in the outermost loop; a nested run is the SDK waiting inside one of them. */
    private long nextEventNanos() {
        if (depth != 1) {
            return Long.MAX_VALUE;
        }
        synchronized (eventLock) {
            ScheduledEvent next = events.peek();
            return next == null ? Long.MAX_VALUE : next.atNanos();
        }
    }

    private long nextWatchdogNanos() {
        synchronized (eventLock) {
            ScheduledEvent next = watchdogs.peek();
            return next == null ? Long.MAX_VALUE : next.atNanos();
        }
    }

    private boolean runDueEvents(long now) {
        return runDue(events, now);
    }

    private boolean runDueWatchdogs(long now) {
        return runDue(watchdogs, now);
    }

    private boolean runDue(PriorityQueue<ScheduledEvent> queue, long now) {
        boolean ran = false;
        for (;;) {
            ScheduledEvent due;
            synchronized (eventLock) {
                due = queue.peek();
                if (due == null || due.atNanos() > now) {
                    return ran;
                }
                queue.poll();
            }
            due.callback().run();
            ran = true;
        }
    }

    private record ScheduledEvent(long atNanos, long sequence, Runnable callback) implements Comparable<ScheduledEvent> {
        @Override
        public int compareTo(ScheduledEvent other) {
            int byTime = Long.compare(atNanos, other.atNanos);
            return byTime != 0 ? byTime : Long.compare(sequence, other.sequence);
        }
    }
}
