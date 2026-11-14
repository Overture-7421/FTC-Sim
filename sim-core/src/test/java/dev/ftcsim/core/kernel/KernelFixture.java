package dev.ftcsim.core.kernel;

import dev.ftcsim.contracts.hooks.Hooks;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

/**
 * A kernel, a ticking world and robot threads whose bodies call the hooks facade exactly as rewritten robot
 * code does. Robot threads are named "robot..." so the kernel's matcher recognises them.
 */
final class KernelFixture implements AutoCloseable {
    static final long POLL_COST = 200L;
    static final long YIELD_COST = 1_000L;
    static final long BACK_EDGE_COST = 100L;
    private static final long CLEANUP_NANOS = 60_000_000_000L;

    final TickWorld world = new TickWorld();
    final SimulationKernel kernel;
    final List<String> trace = Collections.synchronizedList(new ArrayList<>());
    private final List<Thread> robots = new ArrayList<>();
    private final AtomicReference<Throwable> robotFailure = new AtomicReference<>();

    KernelFixture() {
        this(config -> config);
    }

    /** A fixture whose kernel is configured differently from the defaults the other tests share. */
    KernelFixture(UnaryOperator<KernelConfig> tune) {
        kernel = new SimulationKernel(
                world,
                tune.apply(new KernelConfig(
                        thread -> thread.getName().startsWith("robot"), POLL_COST, YIELD_COST, BACK_EDGE_COST, 1_000_000L)));
        Hooks.install(kernel.hooks());
    }

    interface RobotBody {
        void run() throws Exception;
    }

    /** Starts a robot thread and returns once it is parked at its first synchronisation point. */
    Thread startRobot(String name, RobotBody body) {
        Thread thread = new Thread(() -> {
            try {
                body.run();
            } catch (Throwable failure) {
                robotFailure.compareAndSet(null, failure);
            }
        }, name);
        thread.setDaemon(true);
        robots.add(thread);
        thread.start();
        kernel.settleRobot(5_000);
        return thread;
    }

    /** What ended a robot thread, if something did. */
    Throwable robotFailure() {
        return robotFailure.get();
    }

    void assertRobotDidNotFail() {
        Throwable failure = robotFailure.get();
        if (failure != null) {
            throw new AssertionError("the robot thread failed: " + failure, failure);
        }
    }

    /** Lets any robot still parked in the gate run to its end instead of leaking into later tests. */
    @Override
    public void close() {
        for (Thread robot : robots) {
            robot.interrupt();
        }
        kernel.runUntil(kernel.clock().now() + CLEANUP_NANOS);
        List<String> abandoned = kernel.diagnostics().abandonedThreads();
        for (Thread robot : robots) {
            if (!abandoned.contains(robot.getName())) {
                joinQuietly(robot);
            }
        }
        Hooks.installDefault();
    }

    static void joinQuietly(Thread thread) {
        try {
            thread.join(2_000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
