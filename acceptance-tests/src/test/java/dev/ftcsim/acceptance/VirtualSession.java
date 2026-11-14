package dev.ftcsim.acceptance;

import dev.ftcsim.contracts.hardware.HubWire;
import dev.ftcsim.contracts.robot.GamepadState;
import dev.ftcsim.contracts.robot.RobotEnvironment;
import dev.ftcsim.contracts.robot.RobotHost;
import dev.ftcsim.contracts.robot.RobotPhase;
import dev.ftcsim.contracts.robot.TelemetrySnapshot;
import dev.ftcsim.core.kernel.KernelConfig;
import dev.ftcsim.core.kernel.SimulationKernel;
import dev.ftcsim.core.kernel.WorldStepper;
import java.io.IOException;
import java.util.concurrent.locks.LockSupport;
import java.util.function.UnaryOperator;

/**
 * The simulated robot controller driven by simulated time: the real SDK host in a robot world whose clock,
 * sleeps and waits are the kernel's, with the event loop running every 5 ms of simulated time as the SDK's
 * does. Nothing here depends on how fast the test machine is.
 */
final class VirtualSession implements AutoCloseable {
    static final long EVENT_LOOP_PERIOD_NANOS = 5_000_000L;

    final SimulationKernel kernel;
    final HostedRobotWorld world;
    final TestRobot robot;
    private final DeferredWorld deferredWorld;
    private final RobotHost host;
    private GamepadState first = GamepadState.NEUTRAL;
    private GamepadState second = GamepadState.NEUTRAL;
    private long becameIdleAt = -1;
    private boolean closed;

    VirtualSession() throws Exception {
        this(TestRobot.builder());
    }

    /** A session whose robot is built by the given builder on top of the session's own clock. */
    VirtualSession(TestRobot.Builder robotBuilder) throws Exception {
        this(robotBuilder, UnaryOperator.identity());
    }

    /** As above, with the robot controller reaching its hubs through what {@code wire} makes of the bus. */
    VirtualSession(TestRobot.Builder robotBuilder, UnaryOperator<HubWire> wire) throws Exception {
        this(robotBuilder, wire, UnaryOperator.identity());
    }

    /** As above, with the kernel configured by {@code tune} instead of by the defaults. */
    VirtualSession(TestRobot.Builder robotBuilder, UnaryOperator<HubWire> wire, UnaryOperator<KernelConfig> tune) throws Exception {
        DeferredWorld deferred = new DeferredWorld();
        deferredWorld = deferred;
        kernel = new SimulationKernel(deferred, tune.apply(KernelConfig.defaults(VirtualSession::isOpModeThread)));
        robot = robotBuilder.build(kernel.clock()::now);
        deferred.delegate = robot.world();
        RobotEnvironment environment = robot.environment();
        world = HostedRobotWorld.launch(
                kernel.hooks(),
                new RobotEnvironment(wire.apply(environment.hubs()), environment.directory(), environment.cameras()));
        host = world.host();
        kernel.schedule(EVENT_LOOP_PERIOD_NANOS, this::eventLoopPass);
    }

    /**
     * A session whose loops cost {@code backEdgeNanos} each, so that a robot that runs away in a loop spends a second of
     * simulated time in thousands of turns instead of billions.
     */
    static VirtualSession withCoarseLoops(TestRobot.Builder robotBuilder, long backEdgeNanos) throws Exception {
        return new VirtualSession(robotBuilder, UnaryOperator.identity(), config -> config.withBackEdgeCostNanos(backEdgeNanos));
    }

    /** Makes every step of the world take this long in real time, as a slow machine or a paused window would. */
    void slowTheWorldDownBy(long realNanosPerStep) {
        deferredWorld.realNanosPerStep = realNanosPerStep;
    }

    /** The thread the SDK runs OpMode code on: created as 'OpModeExecutor-#n' and renamed 'OpModeThread'. */
    static boolean isOpModeThread(Thread thread) {
        return thread.getName().equals("OpModeThread") || thread.getName().startsWith("OpModeExecutor");
    }

    /** One pass of the SDK's event loop. A late pass runs once and the next follows a full period later. */
    private void eventLoopPass() {
        RobotPhase before = host.phase();
        host.eventLoop(first, second);
        if (before != RobotPhase.IDLE && host.phase() == RobotPhase.IDLE && becameIdleAt < 0) {
            becameIdleAt = kernel.clock().now();
        }
        kernel.settleRobot(10_000);
        kernel.schedule(kernel.clock().now() + EVENT_LOOP_PERIOD_NANOS, this::eventLoopPass);
    }

    /** The simulated time of the first event loop pass that found the controller idle after an OpMode ran, or -1. */
    long becameIdleAt() {
        return becameIdleAt;
    }

    /** Presses Init on the Driver Station. */
    void init(String opMode) {
        kernel.runAsEvent(() -> {
            host.init(opMode);
            kernel.settleRobot(10_000);
        });
    }

    void start() {
        kernel.runAsEvent(() -> {
            host.start();
            kernel.settleRobot(10_000);
        });
    }

    void stop() {
        kernel.runAsEvent(host::stop);
    }

    void hold(GamepadState firstGamepad, GamepadState secondGamepad) {
        first = firstGamepad;
        second = secondGamepad;
    }

    void advance(long nanos) {
        kernel.runFor(nanos);
    }

    /** Advances simulated time by {@code total} in steps of {@code slice}; what the robot sees must not depend on the slicing. */
    void advance(long total, long slice) {
        long end = now() + total;
        while (now() < end) {
            kernel.runUntil(Math.min(end, now() + slice));
        }
    }

    long now() {
        return kernel.clock().now();
    }

    RobotPhase phase() {
        return host.phase();
    }

    TelemetrySnapshot telemetry() {
        return host.telemetry();
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        IOException[] failure = new IOException[1];
        // Closing the host stops the running OpMode, which the SDK does by sleeping until it has finished.
        kernel.runAsEvent(() -> {
            try {
                world.close();
            } catch (IOException closeFailure) {
                failure[0] = closeFailure;
            }
        });
        if (failure[0] != null) {
            throw failure[0];
        }
    }

    /** The kernel needs its world before the robot exists, and the robot needs the kernel's clock. */
    private static final class DeferredWorld implements WorldStepper {
        WorldStepper delegate;
        volatile long realNanosPerStep;

        @Override
        public long nextStepNanos() {
            return delegate == null ? Long.MAX_VALUE : delegate.nextStepNanos();
        }

        @Override
        public void advanceTo(long nanos) {
            if (delegate != null) {
                delegate.advanceTo(nanos);
            }
            if (realNanosPerStep > 0) {
                LockSupport.parkNanos(realNanosPerStep);
            }
        }
    }
}
