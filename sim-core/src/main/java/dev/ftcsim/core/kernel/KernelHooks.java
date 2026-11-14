package dev.ftcsim.core.kernel;

import dev.ftcsim.contracts.hooks.RobotExit;
import dev.ftcsim.contracts.hooks.RobotHooks;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What a robot program sees when it asks the JDK about time. The robot thread gets simulated time that moves in
 * step with the world; the simulator thread and every other thread get a read-only view of that same time, so
 * nothing in the robot's world ever sees the real clock.
 */
final class KernelHooks implements RobotHooks {
    private static final long MAX_SLEEP_NANOS = Long.MAX_VALUE / 4;
    private static final long ROBOT_STREAM = 0x524F424F54L;
    private static final long OTHER_STREAM = 0x4F54484552L;
    private static final long MATH_STREAM = 0x4D415448L;

    private final SimulationKernel kernel;
    private final RobotGate gate;
    private final VirtualClock clock;
    private final KernelConfig config;
    private final KernelDiagnostics diagnostics;
    private final AtomicLong robotSeeds = new AtomicLong();
    private final AtomicLong otherSeeds = new AtomicLong();
    private final Random robotMath;
    private final Random otherMath;
    private final Set<String> reported = ConcurrentHashMap.newKeySet();

    KernelHooks(SimulationKernel kernel, RobotGate gate, VirtualClock clock, KernelConfig config, KernelDiagnostics diagnostics) {
        this.kernel = kernel;
        this.gate = gate;
        this.clock = clock;
        this.config = config;
        this.diagnostics = diagnostics;
        this.robotMath = new Random(mix(config.seed() ^ ROBOT_STREAM, MATH_STREAM));
        this.otherMath = new Random(mix(config.seed() ^ OTHER_STREAM, MATH_STREAM));
    }

    @Override
    public long nanoTime() {
        return gate.enter(Thread.currentThread()) ? gate.observe(config.pollCostNanos()) : clock.now();
    }

    @Override
    public long currentTimeMillis() {
        return config.epochMillis() + Math.floorDiv(nanoTime(), 1_000_000L);
    }

    @Override
    public long currentTimeNanos() {
        return config.epochMillis() * 1_000_000L + nanoTime();
    }

    /**
     * The robot thread has a stream of seeds that nothing else draws from, so what it gets never depends on what a
     * library's thread asked for first; every other thread draws from one stream of its own. A stream is a function of
     * the kernel's seed and of how many seeds it handed out before.
     */
    @Override
    public long randomSeed() {
        boolean robot = gate.isRobotLike(Thread.currentThread());
        long index = (robot ? robotSeeds : otherSeeds).getAndIncrement();
        return mix(config.seed() ^ (robot ? ROBOT_STREAM : OTHER_STREAM), index);
    }

    @Override
    public double mathRandom() {
        return (gate.isRobotLike(Thread.currentThread()) ? robotMath : otherMath).nextDouble();
    }

    @Override
    public void nondeterministic(String description) {
        if (reported.add(description)) {
            diagnostics.problem(description);
        }
    }

    /** SplitMix64's finaliser over {@code seed + (index + 1) * golden gamma}: distinct for distinct indexes. */
    private static long mix(long seed, long index) {
        long z = seed + (index + 1) * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    @Override
    public void sleep(long millis) throws InterruptedException {
        sleepNanos(saturatingNanos(millis, TimeUnit.MILLISECONDS));
    }

    @Override
    public void sleep(long millis, int nanos) throws InterruptedException {
        sleepNanos(saturatingNanos(millis, TimeUnit.MILLISECONDS) + nanos);
    }

    @Override
    public void sleep(TimeUnit unit, long duration) throws InterruptedException {
        sleepNanos(saturatingNanos(duration, unit));
    }

    private void sleepNanos(long nanos) throws InterruptedException {
        if (nanos < 0) {
            throw new IllegalArgumentException("timeout value is negative");
        }
        Thread caller = Thread.currentThread();
        if (gate.enter(caller)) {
            gate.sleep(nanos, config.pollCostNanos());
        } else if (kernel.isSimulatorThread(caller)) {
            // The simulator thread sleeping means "let the world run for that long", as when the SDK waits for
            // an OpMode to finish stopping.
            kernel.runFor(nanos);
        } else {
            TimeUnit.NANOSECONDS.sleep(nanos);
        }
    }

    @Override
    public void yieldThread() {
        if (gate.enter(Thread.currentThread())) {
            gate.observe(config.yieldCostNanos());
        } else {
            Thread.yield();
        }
    }

    @Override
    public boolean isScheduledThread() {
        Thread caller = Thread.currentThread();
        return gate.enter(caller) || kernel.isScheduledSimulatorThread(caller);
    }

    @Override
    public Runnable watchdog(long delayNanos, Runnable action) {
        return kernel.watchdog(delayNanos, action);
    }

    @Override
    public boolean unwindRobotThread() {
        return kernel.unwindRobot();
    }

    @Override
    public boolean abandonRobotThread() {
        return kernel.abandonRobot();
    }

    @Override
    public void exit(int status) {
        exitRequested(status, false);
    }

    @Override
    public void halt(int status) {
        exitRequested(status, true);
    }

    /** The robot's process is not the simulator's to end; the request is recorded and the caller unwinds instead. */
    private void exitRequested(int status, boolean halt) {
        diagnostics.exitRequested(new KernelDiagnostics.ExitRequest(status, halt, Thread.currentThread().getName()));
        throw new RobotExit(status, halt);
    }

    @Override
    public void loadLibrary(String name) {
        diagnostics.nativeLibraryDeclined(name);
    }

    @Override
    public void load(String absolutePath) {
        diagnostics.nativeLibraryDeclined(absolutePath);
    }

    @Override
    public void backEdge() {
        Thread caller = Thread.currentThread();
        if (gate.isRobot(caller)) {
            gate.observe(config.backEdgeCostNanos());
        } else {
            gate.parkIfAbandoned(caller);
        }
    }

    @Override
    public void lockAcquired() {
        Thread caller = Thread.currentThread();
        if (gate.isRobot(caller)) {
            gate.lockAcquired();
        } else {
            gate.parkIfAbandoned(caller);
        }
    }

    @Override
    public void lockReleased() {
        if (gate.isRobot(Thread.currentThread())) {
            gate.lockReleased();
        }
    }

    @Override
    public void threadStarted() {
        // Binding the thread here, before it runs any robot code, is what keeps it from ever running unscheduled.
        gate.enter(Thread.currentThread());
    }

    @Override
    public void elapse(long nanos) {
        if (nanos > 0 && gate.enter(Thread.currentThread())) {
            gate.observe(nanos);
        }
    }

    @Override
    public void wait(Object monitor, long timeoutMillis, int nanos) throws InterruptedException {
        if (gate.enter(Thread.currentThread())) {
            gate.waitOn(monitor, saturatingNanos(timeoutMillis, TimeUnit.MILLISECONDS) + nanos);
        } else if (timeoutMillis == 0 && nanos == 0) {
            monitor.wait();
        } else {
            monitor.wait(timeoutMillis, nanos);
        }
    }

    @Override
    public void notify(Object monitor) {
        notifyMonitor(monitor, false);
    }

    @Override
    public void notifyAll(Object monitor) {
        notifyMonitor(monitor, true);
    }

    private void notifyMonitor(Object monitor, boolean all) {
        if (!Thread.holdsLock(monitor)) {
            throw new IllegalMonitorStateException("current thread is not owner");
        }
        Thread caller = Thread.currentThread();
        if (!gate.enter(caller) && gate.notifyRobotWaiter(monitor)) {
            if (kernel.isOutsider(caller)) {
                diagnostics.problem("thread '" + caller.getName() + "' woke the robot thread from a wait; the robot resumes at"
                        + " whatever simulated time the simulator next looks, which is not reproducible");
            }
            return;
        }
        if (all) {
            monitor.notifyAll();
        } else {
            monitor.notify();
        }
    }

    private static long saturatingNanos(long duration, TimeUnit unit) {
        if (duration < 0) {
            throw new IllegalArgumentException("timeout value is negative");
        }
        long nanos = unit.toNanos(duration);
        return Math.min(nanos, MAX_SLEEP_NANOS);
    }
}
