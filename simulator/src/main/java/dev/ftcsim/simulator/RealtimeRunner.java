package dev.ftcsim.simulator;

import dev.ftcsim.contracts.robot.GamepadState;
import dev.ftcsim.contracts.session.SessionStatus;
import dev.ftcsim.contracts.snapshot.WorldSnapshot;
import dev.ftcsim.physics.PhysicsWorld;
import dev.ftcsim.platform.DeadlineWaiter;
import dev.ftcsim.platform.WindowsWaitableTimer;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * Shows a {@link SimulationSession} in real time: a thread of its own opens the session, advances simulated time at
 * the chosen speed, applies what other threads ask for and publishes what a viewer needs. It is the one place
 * where wall-clock time touches the simulation, and it only decides <em>when</em> simulated time moves, never what
 * happens in it: a run is the same however slowly or quickly it is shown.
 *
 * <p>Everything that touches the session runs on the runner's thread. Other threads post commands, set the
 * gamepads and read the latest status and snapshot, which are immutable.
 */
public final class RealtimeRunner implements AutoCloseable {

    private static final long SLICE_NANOS = 1_000_000_000L / PhysicsWorld.STEPS_PER_SECOND;
    private static final long MAX_BURST_NANOS = 50_000_000L;
    private static final long MAX_LAG_NANOS = 250_000_000L;
    private static final long STATUS_PERIOD_NANOS = 33_000_000L;
    private static final long SPEED_WINDOW_NANOS = 250_000_000L;
    private static final long PAUSED_POLL_NANOS = 5_000_000L;
    private static final int KEPT_ERRORS = 5;

    private final SessionConfig config;
    private final Thread thread;
    private final CountDownLatch opened = new CountDownLatch(1);
    private final ConcurrentLinkedQueue<Consumer<SimulationSession>> commands = new ConcurrentLinkedQueue<>();
    private final AtomicReference<GamepadState> firstPad = new AtomicReference<>(GamepadState.NEUTRAL);
    private final AtomicReference<GamepadState> secondPad = new AtomicReference<>(GamepadState.NEUTRAL);
    private final AtomicReference<SessionStatus> status = new AtomicReference<>();
    private final AtomicReference<WorldSnapshot> snapshot = new AtomicReference<>();

    private volatile double speed;
    private volatile boolean paused;
    private volatile boolean closing;
    private volatile Throwable openFailure;
    private volatile Throwable fatal;

    private RealtimeRunner(SessionConfig config, double speed) {
        this.config = config;
        this.speed = speed;
        this.thread = new Thread(this::run, "ftcsim-simulator");
        this.thread.setDaemon(true);
    }

    /**
     * Opens the session on the runner's own thread and starts showing it.
     *
     * @param speed simulated seconds per wall-clock second; 1 is real time
     * @throws IOException if the session could not be opened
     */
    public static RealtimeRunner open(SessionConfig config, double speed) throws IOException {
        if (!(speed > 0.0)) {
            throw new IllegalArgumentException("speed must be positive: " + speed);
        }
        RealtimeRunner runner = new RealtimeRunner(config, speed);
        runner.thread.start();
        boolean interrupted = false;
        while (true) {
            try {
                runner.opened.await();
                break;
            } catch (InterruptedException interruption) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (runner.openFailure != null) {
            throw runner.openFailure instanceof IOException io
                    ? io
                    : new IOException("the simulation session did not open: " + runner.openFailure, runner.openFailure);
        }
        return runner;
    }

    /** Asks for something to be done to the session on its own thread, soon; the order of posting is the order of doing. */
    public void post(Consumer<SimulationSession> command) {
        commands.add(command);
        LockSupport.unpark(thread);
    }

    /** What a gamepad reports from now on. Only the latest state counts; the session sees it at its next slice. */
    public void setGamepad(int gamepad, GamepadState state) {
        if (gamepad == 1) {
            firstPad.set(state);
        } else if (gamepad == 2) {
            secondPad.set(state);
        } else {
            throw new IllegalArgumentException("gamepad is 1 or 2: " + gamepad);
        }
    }

    public void setSpeed(double newSpeed) {
        if (!(newSpeed > 0.0)) {
            throw new IllegalArgumentException("speed must be positive: " + newSpeed);
        }
        speed = newSpeed;
    }

    public void setPaused(boolean pause) {
        paused = pause;
    }

    /** The latest status; it is never null once {@link #open} has returned. */
    public SessionStatus status() {
        return status.get();
    }

    /** The world as of the latest physics step the runner has shown; a new object whenever a step has run. */
    public WorldSnapshot snapshot() {
        return snapshot.get();
    }

    /** What stopped the run, or null while it is going well. */
    public Throwable failure() {
        return fatal;
    }

    private void run() {
        SimulationSession session;
        try {
            session = SimulationSession.open(config);
        } catch (Throwable failure) {
            openFailure = failure;
            opened.countDown();
            return;
        }
        status.set(describe(session, 1.0, new ArrayDeque<>()));
        snapshot.set(session.snapshot());
        opened.countDown();
        try (WindowsWaitableTimer timer = openTimer()) {
            LongConsumer parker = timer == null ? LockSupport::parkNanos : timer::parkNanos;
            loop(session, new DeadlineWaiter(System::nanoTime, parker));
        } finally {
            session.close();
        }
    }

    private void loop(SimulationSession session, DeadlineWaiter waiter) {
        Pace pace = new Pace(speed, MAX_LAG_NANOS);
        Deque<String> errors = new ArrayDeque<>();
        GamepadState appliedFirst = GamepadState.NEUTRAL;
        GamepadState appliedSecond = GamepadState.NEUTRAL;
        boolean wasPaused = false;
        long wall = System.nanoTime();
        pace.anchor(wall, session.now());
        long windowWall = wall;
        long windowSim = session.now();
        double achieved = speed;
        long nextStatus = wall;

        while (!closing) {
            wall = System.nanoTime();
            drain(session, errors);
            GamepadState first = firstPad.get();
            if (!first.equals(appliedFirst)) {
                session.setGamepad(1, first);
                appliedFirst = first;
            }
            GamepadState second = secondPad.get();
            if (!second.equals(appliedSecond)) {
                session.setGamepad(2, second);
                appliedSecond = second;
            }

            boolean pausedNow = paused;
            if (pace.speed() != speed) {
                pace.setSpeed(speed, wall, session.now());
            }
            if (wasPaused && !pausedNow) {
                pace.anchor(wall, session.now());
                windowWall = wall;
                windowSim = session.now();
            }
            wasPaused = pausedNow;

            if (!pausedNow) {
                pace.lag(wall, session.now());
                long target = Math.min(pace.simAt(wall), session.now() + MAX_BURST_NANOS);
                if (target > session.now()) {
                    try {
                        session.runUntil(target);
                    } catch (Throwable failure) {
                        fatal = failure;
                        errors.addFirst("the simulation stopped: " + failure);
                        status.set(describe(session, achieved, errors));
                        return;
                    }
                    snapshot.set(session.snapshot());
                }
            }

            wall = System.nanoTime();
            if (wall - windowWall >= SPEED_WINDOW_NANOS) {
                achieved = pausedNow ? 0.0 : (double) (session.now() - windowSim) / (wall - windowWall);
                windowWall = wall;
                windowSim = session.now();
            }
            if (wall >= nextStatus) {
                status.set(describe(session, achieved, errors));
                nextStatus = wall + STATUS_PERIOD_NANOS;
            }

            long due = pausedNow ? wall + PAUSED_POLL_NANOS : pace.wallAt(session.now() + SLICE_NANOS);
            if (commands.isEmpty() && due > System.nanoTime()) {
                waiter.awaitUntil(due);
            }
        }
        status.set(describe(session, achieved, errors));
    }

    private void drain(SimulationSession session, Deque<String> errors) {
        for (Consumer<SimulationSession> command = commands.poll(); command != null; command = commands.poll()) {
            try {
                command.accept(session);
            } catch (RuntimeException failure) {
                errors.addFirst("a command failed: " + failure.getMessage());
                while (errors.size() > KEPT_ERRORS) {
                    errors.removeLast();
                }
            }
        }
    }

    private SessionStatus describe(SimulationSession session, double achieved, Deque<String> errors) {
        List<String> problems = new ArrayList<>(errors);
        problems.addAll(session.problems());
        return new SessionStatus(
                session.now(), session.phase(), session.telemetry(), session.score(), problems, session.hardware(), speed,
                achieved, paused, session.match());
    }

    private static WindowsWaitableTimer openTimer() {
        try {
            return WindowsWaitableTimer.openHighResolution();
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    /** Stops the run, closes the session on its own thread and waits for it to finish. */
    @Override
    public void close() {
        closing = true;
        LockSupport.unpark(thread);
        boolean interrupted = false;
        while (thread.isAlive()) {
            try {
                thread.join();
            } catch (InterruptedException interruption) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
