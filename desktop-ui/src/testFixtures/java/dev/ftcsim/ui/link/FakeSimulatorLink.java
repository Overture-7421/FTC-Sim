package dev.ftcsim.ui.link;

import dev.ftcsim.contracts.field.Alliance;
import dev.ftcsim.contracts.field.FieldDescription;
import dev.ftcsim.contracts.robot.GamepadState;
import dev.ftcsim.contracts.session.SessionInfo;
import dev.ftcsim.contracts.session.SessionRequest;
import dev.ftcsim.contracts.session.RobotMapping;
import dev.ftcsim.contracts.session.SessionStatus;
import dev.ftcsim.contracts.session.SimulatorLink;
import dev.ftcsim.contracts.snapshot.WorldSnapshot;
import dev.ftcsim.platform.DeadlineWaiter;
import dev.ftcsim.ui.pacing.HighResolutionParker;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * An in-process {@link SimulatorLink} for developing and testing the UI without a worker: it serves a made-up field,
 * a chassis that gamepad 1 drives on a simple kinematic model while an OpMode runs, a dozen pieces and canned
 * OpModes, with the cadence and the threading of the real port. A thread of its own steps the world at 240 Hz of
 * simulated time, paced against the wall clock at the chosen speed, and every listener call comes from that thread,
 * one at a time. Calls to the link only queue work, so they never block.
 *
 * <p>Like the real link, it tells its listeners {@code opened} again after {@link #reset()}: a reset is a new session
 * with the same request, at the speed set last, and a listener must treat it as one. The request's field decides what
 * is served: {@code none} is a bare floor with only the robot, anything else is the made-up field reporting that id;
 * the seed fixes which pieces are which colour; the robot starts at the requested pose.
 *
 * <p>It follows the port's rules for what is told how. The catalogue (a bare floor and two fields of the fake kind,
 * with start poses named for their alliance, and a few motors) goes to every listener as soon as it is added.
 * A session that cannot be opened is {@code failed}; a command it cannot do (an OpMode that is already running, a
 * match that is already armed, an OpMode that does not exist) is {@code refused} and nothing else changes. A timed
 * match runs in simulated time, as the real one does, with periods of the length given at construction.
 */
public final class FakeSimulatorLink implements SimulatorLink {
    /** The field id of a session on a bare floor, as the port names it. */
    public static final String NO_FIELD = "none";

    private static final long STATUS_PERIOD_NANOS = 33_000_000L;
    private static final long SPEED_WINDOW_NANOS = 250_000_000L;
    private static final long MAX_LAG_NANOS = 250_000_000L;
    private static final long MIN_PASS_NANOS = 1_500_000L;
    private static final long REST_PASS_NANOS = 33_000_000L;
    private static final long IDLE_PARK_NANOS = 20_000_000L;
    private static final long JOIN_MILLIS = 2_000L;
    private static final int MAX_STEPS_PER_PASS = 60;

    private final long openDelayNanos;
    private final String openFailure;
    private final MatchTimes matchTimes;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedQueue<Runnable> commands = new ConcurrentLinkedQueue<>();
    private final AtomicReference<GamepadState> firstPad = new AtomicReference<>(GamepadState.NEUTRAL);
    private final Thread thread = new Thread(this::run, "fake-simulator-link");
    private volatile boolean closing;

    // Everything below belongs to the link's thread.
    private SessionRequest request;
    private FakeWorld world;
    private long openAtNanos = -1;
    private double speed = 1.0;
    private boolean paused;
    private long wallAnchor;
    private long simAnchor;
    private long windowWall;
    private long windowSim;
    private double achieved = 1.0;
    private boolean statusDue;

    public FakeSimulatorLink() {
        this(0L, null);
    }

    /** The request that opens the fake session: the fake field standing in for DECODE, the red alliance, its first start pose. */
    public static SessionRequest defaultRequest() {
        return new SessionRequest(
                RobotMapping.empty("Fake robot"), "", "decode", Alliance.RED, 0L, FakeField.START_X, FakeField.START_Y,
                FakeField.START_HEADING, 1.0);
    }

    /**
     * @param openDelayMillis how long opening a session takes, so that a UI can be seen waiting for it
     * @param openFailure     when not null, opening fails with this message instead of succeeding
     */
    public FakeSimulatorLink(long openDelayMillis, String openFailure) {
        this(openDelayMillis, openFailure, MatchTimes.DEFAULT);
    }

    /** @param matchTimes how long the periods of a timed match last in simulated time */
    public FakeSimulatorLink(long openDelayMillis, String openFailure, MatchTimes matchTimes) {
        this.openDelayNanos = openDelayMillis * 1_000_000L;
        this.openFailure = openFailure;
        this.matchTimes = matchTimes;
        thread.setDaemon(true);
        thread.start();
    }

    /** The listener hears the catalogue at once, from the link's thread, before anything the commands that follow cause. */
    @Override
    public void addListener(Listener listener) {
        listeners.add(listener);
        post(() -> tell(listener, told -> told.catalogue(FakeField.catalogue())));
    }

    @Override
    public void open(SessionRequest sessionRequest) {
        post(() -> beginOpen(sessionRequest));
    }

    @Override
    public void init(String opMode) {
        post(() -> {
            command(w -> w.init(opMode));
            logged("I/OpModeManager: init " + opMode);
        });
    }

    @Override
    public void start() {
        post(() -> {
            command(FakeWorld::start);
            logged("I/OpModeManager: start");
        });
    }

    @Override
    public void stop() {
        post(() -> command(w -> {
            w.stop();
            return null;
        }));
    }

    @Override
    public void armMatch(String autoOpMode, String teleOpMode) {
        post(() -> command(w -> w.armMatch(autoOpMode == null ? "" : autoOpMode, teleOpMode)));
    }

    @Override
    public void startMatch() {
        post(() -> command(FakeWorld::startMatch));
    }

    @Override
    public void setGamepad(int gamepad, GamepadState state) {
        if (gamepad == 1) {
            firstPad.set(state);
        } else if (gamepad != 2) {
            throw new IllegalArgumentException("gamepad is 1 or 2: " + gamepad);
        }
    }

    @Override
    public void humanAction(String actionId) {
        post(() -> command(w -> w.humanAction(actionId)));
    }

    @Override
    public void setPaused(boolean pause) {
        post(() -> {
            paused = pause;
            anchor(System.nanoTime());
            statusDue = true;
        });
    }

    @Override
    public void setSpeed(double newSpeed) {
        if (!(newSpeed > 0.0)) {
            throw new IllegalArgumentException("speed must be positive: " + newSpeed);
        }
        post(() -> {
            speed = newSpeed;
            anchor(System.nanoTime());
            statusDue = true;
        });
    }

    @Override
    public void reset() {
        // A new session with the same request, announced with opened() just as open() is.
        post(() -> {
            if (request != null) {
                beginOpen(request);
            }
        });
    }

    @Override
    public void close() {
        closing = true;
        LockSupport.unpark(thread);
        if (Thread.currentThread() == thread) {
            return;
        }
        try {
            thread.join(JOIN_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void post(Runnable command) {
        commands.add(command);
        LockSupport.unpark(thread);
    }

    // The link's thread ------------------------------------------------------------------------------------------

    private void run() {
        try (HighResolutionParker parker = new HighResolutionParker()) {
            loop(new DeadlineWaiter(System::nanoTime, parker));
        }
    }

    private void loop(DeadlineWaiter waiter) {
        long nextStatus = 0;
        while (!closing) {
            for (Runnable command = commands.poll(); command != null; command = commands.poll()) {
                command.run();
            }
            long wall = System.nanoTime();
            completeOpen(wall);
            boolean running = world != null && !paused;
            if (running) {
                advance(wall);
            }
            if (world != null && (statusDue || wall >= nextStatus)) {
                statusDue = false;
                nextStatus = wall + STATUS_PERIOD_NANOS;
                publishStatus();
            }
            if (running && commands.isEmpty()) {
                if (world.atRest()) {
                    // Nothing can change before a command arrives, which wakes the thread at once.
                    LockSupport.parkNanos(this, REST_PASS_NANOS);
                } else {
                    long nextStep = wallAnchor + (long) ((FakeWorld.timeOfStep(world.stepIndex() + 1) - simAnchor) / speed);
                    waiter.awaitUntil(Math.max(nextStep, System.nanoTime() + MIN_PASS_NANOS));
                }
            } else if (commands.isEmpty()) {
                LockSupport.parkNanos(this, openAtNanos >= 0 ? Math.max(1L, openAtNanos - System.nanoTime()) : IDLE_PARK_NANOS);
            }
        }
    }

    private void beginOpen(SessionRequest sessionRequest) {
        request = sessionRequest;
        world = null;
        openAtNanos = System.nanoTime() + openDelayNanos;
    }

    private void completeOpen(long wall) {
        if (openAtNanos < 0 || wall < openAtNanos) {
            return;
        }
        openAtNanos = -1;
        if (openFailure != null) {
            notifyListeners(listener -> listener.failed(openFailure));
            return;
        }
        boolean hasField = !NO_FIELD.equals(request.field());
        world = new FakeWorld(request.startX(), request.startY(), request.startHeading(), request.seed(), hasField, matchTimes);
        anchor(wall);
        achieved = paused ? 0.0 : speed;
        FieldDescription field = hasField ? FakeField.description(request.field()) : null;
        SessionInfo info = new SessionInfo(field, FakeField.opModes(), FakeField.robot());
        notifyListeners(listener -> listener.opened(info));
        publishSnapshot();
        publishStatus();
    }

    /** Gives the world a command; one it cannot do comes back to the listeners as a refusal and changes nothing else. */
    private void command(Function<FakeWorld, String> action) {
        if (world == null) {
            refuse("There is no session to give that command to.");
            return;
        }
        String why = action.apply(world);
        statusDue = true;
        if (why != null) {
            refuse(why);
        }
    }

    /** What the robot program would have logged, so that the console has something to show without a worker. */
    private void logged(String line) {
        if (world != null) {
            notifyListeners(listener -> listener.log(List.of(line)));
        }
    }

    private void refuse(String message) {
        notifyListeners(listener -> listener.refused(message));
    }

    private void anchor(long wall) {
        wallAnchor = wall;
        windowWall = wall;
        if (world != null) {
            simAnchor = world.simTimeNanos();
            windowSim = simAnchor;
        }
    }

    private void advance(long wall) {
        long target = simAnchor + (long) ((wall - wallAnchor) * speed);
        if (target - world.simTimeNanos() > MAX_LAG_NANOS) {
            anchor(wall);
            target = world.simTimeNanos();
        }
        GamepadState pad = firstPad.get();
        int steps = 0;
        while (steps < MAX_STEPS_PER_PASS && FakeWorld.timeOfStep(world.stepIndex() + 1) <= target) {
            world.step(pad);
            steps++;
        }
        if (steps > 0) {
            publishSnapshot();
        }
        if (wall - windowWall >= SPEED_WINDOW_NANOS) {
            achieved = (double) (world.simTimeNanos() - windowSim) / (wall - windowWall);
            windowWall = wall;
            windowSim = world.simTimeNanos();
        }
    }

    private void publishSnapshot() {
        WorldSnapshot snapshot = world.snapshot();
        notifyListeners(listener -> listener.snapshot(snapshot));
    }

    private void publishStatus() {
        SessionStatus status = world.status(speed, paused ? 0.0 : achieved, paused);
        notifyListeners(listener -> listener.status(status));
    }

    private void notifyListeners(Consumer<Listener> call) {
        for (Listener listener : listeners) {
            tell(listener, call);
        }
    }

    private static void tell(Listener listener, Consumer<Listener> call) {
        try {
            call.accept(listener);
        } catch (RuntimeException failure) {
            System.err.println("a listener of the fake simulator link failed: " + failure);
        }
    }
}
