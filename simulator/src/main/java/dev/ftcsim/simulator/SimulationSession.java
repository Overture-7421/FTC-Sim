package dev.ftcsim.simulator;

import dev.ftcsim.contracts.field.BodyKind;
import dev.ftcsim.contracts.field.FieldDescription;
import dev.ftcsim.contracts.field.MatchPhase;
import dev.ftcsim.contracts.field.Score;
import dev.ftcsim.contracts.hardware.HardwareDirectory;
import dev.ftcsim.contracts.hardware.MotorPlant;
import dev.ftcsim.contracts.hooks.Hooks;
import dev.ftcsim.contracts.hardware.UnmappedDevice;
import dev.ftcsim.contracts.robot.GamepadState;
import dev.ftcsim.contracts.robot.OpModeDescriptor;
import dev.ftcsim.contracts.robot.RobotHost;
import dev.ftcsim.contracts.robot.RobotPhase;
import dev.ftcsim.contracts.robot.TelemetrySnapshot;
import dev.ftcsim.contracts.session.MatchStatus;
import dev.ftcsim.contracts.session.Readout;
import dev.ftcsim.contracts.snapshot.BodyState;
import dev.ftcsim.contracts.snapshot.WorldSnapshot;
import dev.ftcsim.contracts.world.RayView;
import dev.ftcsim.core.kernel.FixedRateWorld;
import dev.ftcsim.core.kernel.KernelConfig;
import dev.ftcsim.core.kernel.KernelDiagnostics;
import dev.ftcsim.core.kernel.SimulationKernel;
import dev.ftcsim.field.EntityIds;
import dev.ftcsim.field.FieldRun;
import dev.ftcsim.hardware.lynx.LynxHub;
import dev.ftcsim.mechanisms.Mechanisms;
import dev.ftcsim.physics.PhysicsWorld;
import dev.ftcsim.physics.PieceSpec;
import dev.ftcsim.physics.Vector3;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One simulated run: a robot controller running the team's program, a robot rig on a physics world, and the
 * field, all moving through simulated time that only this session's callers advance. The session knows nothing
 * about wall-clock time, rendering or input devices; whoever drives it decides how fast simulated time passes and
 * what the gamepads say.
 *
 * <p>Not thread-safe: every call must come from one thread, the simulator thread.
 */
public final class SimulationSession implements AutoCloseable {

    /** The SDK's event loop passes every 5 ms. */
    public static final long EVENT_LOOP_PERIOD_NANOS = 5_000_000L;

    /**
     * Simulated time the robot is charged for each backward jump of its code: about what one trivial loop iteration
     * takes on a Control Hub's CPU. It is what makes a loop that only waits (a command OpMode's Init loop, the
     * {@code while (!gamepad1.a) {}} a student writes) let simulated time pass. With 1 ns such a loop ran a billion
     * iterations per simulated second and the simulation crawled at 0.4 times real time on a fast desktop; from 100 ns
     * a spinning robot costs no more than a busy one (8 times real time there, the rest being the world itself).
     */
    static final long LOOP_NANOS = 100L;

    private static final long HUB_TICKS_PER_SECOND = 1_000L;
    private static final double FLOOR_HALF_SIZE = 6.0;
    private static final double STEP_SECONDS = 1.0 / PhysicsWorld.STEPS_PER_SECOND;

    private final SessionConfig config;
    /** How much simulated time the robot program gets to stop when the world is reset. */
    private static final long RESET_STOP_LIMIT_NANOS = 3_000_000_000L;

    /** What belongs to the world and not to the robot: replaced as a whole when the world is reset. */
    private static final class LiveWorld {
        PhysicsWorld world;
        FieldRun fieldRun;
        Map<Integer, PieceSpec> pieceSpecs = Map.of();
        Map<Integer, Integer> kindColours = new HashMap<>();
        Mechanisms mechanisms;
        RayView rays;
    }

    private final SimulationKernel kernel;
    private final LiveWorld live;
    private final RobotRig rig;
    private final RayView rays;
    private final RobotWorldLauncher.RobotWorld robotWorld;
    private final RobotHost host;
    private final DriverStation station;
    private TimedMatch match;

    private GamepadState first = GamepadState.NEUTRAL;
    private GamepadState second = GamepadState.NEUTRAL;
    private final AtomicReference<WorldSnapshot> latest;
    private boolean closed;

    private SimulationSession(
            SessionConfig config,
            SimulationKernel kernel,
            LiveWorld live,
            RobotRig rig,
            RayView rays,
            RobotWorldLauncher.RobotWorld robotWorld,
            AtomicReference<WorldSnapshot> latest) {
        this.config = config;
        this.kernel = kernel;
        this.live = live;
        this.rig = rig;
        this.rays = rays;
        this.robotWorld = robotWorld;
        this.host = robotWorld.host();
        this.station = new DriverStation(kernel, host);
        this.match = new TimedMatch(kernel, station, live.fieldRun);
        this.latest = latest;
    }

    /** The thread the SDK runs OpMode code on: created as 'OpModeExecutor-#n' and renamed 'OpModeThread'. */
    public static boolean isOpModeThread(Thread thread) {
        return thread.getName().equals("OpModeThread") || thread.getName().startsWith("OpModeExecutor");
    }

    /** Builds the world, the robot and the robot controller. The robot controller is booted but nothing runs yet. */
    public static SimulationSession open(SessionConfig config) throws IOException {
        FixedRateWorld clockwork = new FixedRateWorld();
        SimulationKernel kernel = new SimulationKernel(
                clockwork, KernelConfig.defaults(SimulationSession::isOpModeThread).withBackEdgeCostNanos(LOOP_NANOS));
        LiveWorld live = buildWorld(config, 0);
        try {
            RayView rays = (origin, direction, maxMeters) -> live.rays.cast(origin, direction, maxMeters);
            live.rays = worldRays(live);
            RobotRig rig = RobotRig.build(
                    config.robot(), live.world, EntityIds.CHASSIS, config.startX(), config.startY(), config.startHeading(),
                    kernel.clock()::now, rays, vision(config, live));
            live.mechanisms = config.robot().mechanisms(rig.plantView(), live.pieceSpecs);
            loadStartingPieces(live);

            AtomicReference<WorldSnapshot> snapshots = new AtomicReference<>(live.world.snapshot());
            clockwork.every(PhysicsWorld.STEPS_PER_SECOND, (index, nanos) -> {
                PhysicsWorld world = live.world;
                Mechanisms mechanisms = live.mechanisms;
                world.step();
                WorldSnapshot afterStep = world.snapshot();
                if (live.fieldRun != null) {
                    live.fieldRun.afterStep(afterStep);
                }
                mechanisms.step(STEP_SECONDS, rig.chassisView().sample(), afterStep, world);
                snapshots.set(withCarriedPieces(world.snapshot(), mechanisms.carried(), rig.viewBodies(rig.chassisView().sample())));
                rig.stepDevices(nanos);
            });
            clockwork.every(HUB_TICKS_PER_SECOND, (index, nanos) -> rig.tick(nanos));

            RobotWorldLauncher.RobotWorld robotWorld = RobotWorldLauncher.launch(
                    config.program(), kernel.hooks(), config.storageRoot(), config.log(), rig.environment());
            SimulationSession session = new SimulationSession(config, kernel, live, rig, rays, robotWorld, snapshots);
            robotWorld.alreadyReported(() -> kernel.diagnostics().abandonedThreads());
            kernel.schedule(EVENT_LOOP_PERIOD_NANOS, session::eventLoopPass);
            return session;
        } catch (Throwable failure) {
            live.world.close();
            throw failure;
        }
    }

    /** A new physics world with the field in it, or a bare floor when there is no field. */
    private static LiveWorld buildWorld(SessionConfig config, long nowNanos) {
        LiveWorld built = new LiveWorld();
        built.world = PhysicsWorld.create(config.nativeDirectory());
        try {
            if (config.field() == null) {
                built.world.addStaticBox(new Vector3(0, 0, -0.5), new Vector3(FLOOR_HALF_SIZE, FLOOR_HALF_SIZE, 0.5));
            } else {
                built.fieldRun = config.field().start(built.world, config.fieldSetup());
                built.fieldRun.enterPhase(MatchPhase.PRACTICE, nowNanos);
                built.pieceSpecs = config.field().pieceSpecs();
                for (BodyKind kind : config.field().description().bodyKinds()) {
                    if (!kind.parts().isEmpty()) {
                        built.kindColours.put(kind.id(), kind.parts().get(0).rgb());
                    }
                }
            }
            return built;
        } catch (Throwable failure) {
            built.world.close();
            throw failure;
        }
    }

    /** What the robot's camera sees of the field: its tags, and the bodies some of them are stuck on, wherever the world now has them. */
    private static RobotRig.Vision vision(SessionConfig config, LiveWorld live) {
        if (config.field() == null) {
            return RobotRig.Vision.NONE;
        }
        return new RobotRig.Vision(
                config.field().description().tags(), id -> () -> live.world.bodyPose(id),
                config.fieldSetup() == null ? 0 : config.fieldSetup().seed());
    }

    private static WorldRays worldRays(LiveWorld live) {
        return new WorldRays(live.world, EntityIds.CHASSIS, () -> carriedBy(live.mechanisms), live.kindColours);
    }

    /** What a viewer sees: the bodies of the world and, drawn where they are, the pieces the robot carries. */
    private static List<Mechanisms.Carried> carriedBy(Mechanisms mechanisms) {
        return mechanisms == null ? List.of() : mechanisms.carried();
    }

    /** What a viewer sees besides the physics world: the pieces the robot carries, drawn where they are, and the robot's turret. */
    private static WorldSnapshot withCarriedPieces(WorldSnapshot world, List<Mechanisms.Carried> carried, List<BodyState> robot) {
        if (carried.isEmpty() && robot.isEmpty()) {
            return world;
        }
        List<BodyState> bodies = new ArrayList<>(world.bodies());
        bodies.addAll(robot);
        for (Mechanisms.Carried piece : carried) {
            bodies.add(new BodyState(
                    piece.entityId(), piece.kind(), (float) piece.center().x(), (float) piece.center().y(), (float) piece.center().z(),
                    0f, 0f, 0f, 1f));
        }
        bodies.sort(Comparator.comparingInt(BodyState::entityId));
        return new WorldSnapshot(world.stepIndex(), world.simTimeNanos(), bodies);
    }

    /** One pass of the SDK's event loop. A late pass runs once and the next follows a full period later. */
    private void eventLoopPass() {
        host.eventLoop(first, second);
        kernel.settleRobot(DriverStation.SETTLE_MILLIS);
        kernel.schedule(kernel.clock().now() + EVENT_LOOP_PERIOD_NANOS, this::eventLoopPass);
    }

    // Driver Station -------------------------------------------------------------------------------------------

    public List<OpModeDescriptor> opModes() {
        return station.opModes();
    }

    /**
     * Presses Init on the Driver Station with the named OpMode selected.
     *
     * @throws IllegalStateException while a match is armed or under way, which own the OpMode
     */
    public void init(String opMode) {
        match.requireNoMatchUnderway("Init");
        station.init(opMode);
    }

    /** @throws IllegalStateException while a match is armed or under way; {@link #startMatch} starts it */
    public void start() {
        match.requireNoMatchUnderway("Start");
        station.start();
    }

    /** Presses Stop. A match that is armed is given up; one under way ends at once, as at its end. */
    public void stop() {
        match.stopPressed();
        station.stop();
    }

    /**
     * Stops the robot program and puts the world back as it was when the session opened: the chassis at its start, the
     * field with its pieces where they began, nothing carried, no match. The robot program stays loaded, the hubs and
     * devices keep their state (a real robot's encoders do not reset when it is carried back), and simulated time goes on.
     */
    public void resetWorld() {
        stop();
        long limit = now() + RESET_STOP_LIMIT_NANOS;
        while (host.phase() != RobotPhase.IDLE && now() < limit) {
            runFor(EVENT_LOOP_PERIOD_NANOS);
        }
        host.clearMessages();
        LiveWorld fresh = buildWorld(config, now());
        PhysicsWorld old = live.world;
        try {
            rig.rebindWorld(fresh.world, config.startX(), config.startY(), config.startHeading());
        } catch (RuntimeException failure) {
            fresh.world.close();
            throw failure;
        }
        live.world = fresh.world;
        live.fieldRun = fresh.fieldRun;
        live.pieceSpecs = fresh.pieceSpecs;
        live.kindColours = fresh.kindColours;
        live.rays = worldRays(live);
        live.mechanisms = config.robot().mechanisms(rig.plantView(), live.pieceSpecs);
        loadStartingPieces(live);
        match = new TimedMatch(kernel, station, live.fieldRun);
        latest.set(withCarriedPieces(live.world.snapshot(), live.mechanisms.carried(), rig.viewBodies(rig.chassisView().sample())));
        old.close();
    }

    // Timed match -------------------------------------------------------------------------------------------------

    /**
     * Prepares a timed match from the idle state: initialises the autonomous OpMode (or, with no autonomous period,
     * the driver-controlled one) and waits for {@link #startMatch}. Free practice does not need this.
     *
     * @param autonomousOpMode the autonomous OpMode, or an empty name for a match with no autonomous period
     * @param teleOpMode       the driver-controlled OpMode
     * @throws IllegalStateException    if a match is already armed, under way or over, or an OpMode is initialised
     *                                  or running
     * @throws IllegalArgumentException if the robot program declares no OpMode of one of the names
     */
    public void armMatch(String autonomousOpMode, String teleOpMode) {
        match.arm(autonomousOpMode, teleOpMode);
    }

    /**
     * Starts the armed match at this instant. The periods are timed in simulated time from here: the OpModes are
     * stopped and started at exactly the instants they end and begin, and the field is told when its own periods
     * begin. With no autonomous period the driver-controlled one begins at once.
     *
     * @throws IllegalStateException if no match is armed
     */
    public void startMatch() {
        match.start();
    }

    /** Where the timed match is; {@link MatchStatus#NONE} in free practice. */
    public MatchStatus match() {
        return match.status();
    }

    /** What a gamepad reports from now on; the robot sees it at the next pass of the event loop. */
    public void setGamepad(int gamepad, GamepadState state) {
        if (gamepad == 1) {
            first = state;
        } else if (gamepad == 2) {
            second = state;
        } else {
            throw new IllegalArgumentException("gamepad is 1 or 2: " + gamepad);
        }
    }

    // Time -----------------------------------------------------------------------------------------------------

    public long now() {
        return kernel.clock().now();
    }

    /** Advances simulated time to {@code targetNanos}, running everything due on the way. */
    public void runUntil(long targetNanos) {
        kernel.runUntil(targetNanos);
    }

    public void runFor(long durationNanos) {
        kernel.runFor(durationNanos);
    }

    // What happens to be going on --------------------------------------------------------------------------------

    public RobotPhase phase() {
        return host.phase();
    }

    public TelemetrySnapshot telemetry() {
        return host.telemetry();
    }

    /** The world as of the latest physics step. The object is immutable and may be read from any thread. */
    public WorldSnapshot snapshot() {
        return latest.get();
    }

    /** What presentation needs to draw the field, or null on a bare floor. */
    public FieldDescription fieldDescription() {
        return config.field() == null ? null : config.field().description();
    }

    public Score score() {
        return live.fieldRun == null ? new Score(List.of()) : live.fieldRun.score();
    }

    /** One line for each motor the mapping wires, and for what the robot carries: what its port and its plant are doing right now. */
    public List<Readout> hardware() {
        List<Readout> readouts = new ArrayList<>();
        rig.spec().motors().keySet().forEach(name -> {
            MotorPlant plant = rig.plant(name);
            readouts.add(new Readout(
                    name, String.format(Locale.ROOT, "%.0f ticks/s  %.1f A", plant.encoderCountsPerSecond(), plant.currentAmps())));
        });
        Mechanisms mechanisms = live.mechanisms;
        for (String storage : mechanisms.storageNames()) {
            int held = mechanisms.contents(storage).size();
            readouts.add(new Readout(
                    "storage " + storage, held + " of " + mechanisms.capacity(storage) + (mechanisms.capacity(storage) == 1 ? " piece" : " pieces")));
        }
        return readouts;
    }

    /** What the robot program asked the hardware map for under a name the robot mapping does not place, in the order it asked. */
    public List<UnmappedDevice> unmapped() {
        return rig.wiring().unmapped();
    }

    public RobotRig rig() {
        return rig;
    }

    /** What sensors may ask of the world: what does a ray from here meet first? Includes the pieces the robot carries. */
    public RayView rays() {
        return rays;
    }

    /**
     * Takes pieces of a kind out of the field's reserve and puts them into one of the robot's storages, as a human
     * loading the robot before a match does.
     *
     * @return how many pieces were loaded, which is fewer than asked when the reserve or the storage runs out
     */
    public int preload(String storage, int kind, int count) {
        if (live.fieldRun == null) {
            throw new IllegalStateException("there is no field, so there is nothing to preload from");
        }
        return loadFromReserve(live, storage, kind, count);
    }

    private static int loadFromReserve(LiveWorld live, String storage, int kind, int count) {
        Mechanisms mechanisms = live.mechanisms;
        int loaded = 0;
        while (loaded < count && mechanisms.canLoad(storage, kind)) {
            OptionalInt piece = live.fieldRun.takeFromReserve(kind);
            if (piece.isEmpty()) {
                break;
            }
            mechanisms.load(storage, piece.getAsInt(), kind);
            loaded++;
        }
        return loaded;
    }

    /** Puts the pieces the field starts the robot with into its storage, as a human loading the robot before a match does. */
    private static void loadStartingPieces(LiveWorld live) {
        if (live.fieldRun == null) {
            return;
        }
        for (String storage : live.mechanisms.storageNames()) {
            for (int kind : live.fieldRun.robotPreloads()) {
                loadFromReserve(live, storage, kind, 1);
            }
        }
    }

    /** A person at the field does one of the actions the field's description lists. */
    public void humanAction(String actionId) {
        if (live.fieldRun == null) {
            throw new IllegalStateException("There is no field, so nobody is standing at it.");
        }
        live.fieldRun.perform(actionId);
    }

    public PhysicsWorld world() {
        return live.world;
    }

    public SimulationKernel kernel() {
        return kernel;
    }

    /**
     * Everything that weakens the claim that this run can be repeated exactly or that the robot is wired as its
     * code expects: kernel diagnostics, hub commands that were refused, ports driven or read with nothing connected.
     */
    public List<String> problems() {
        List<String> problems = new ArrayList<>(kernel.diagnostics().problems());
        for (KernelDiagnostics.ExitRequest exit : kernel.diagnostics().exitRequests()) {
            problems.add("the robot program called " + (exit.halt() ? "Runtime.halt" : "System.exit or Runtime.exit") + "(" + exit.status()
                    + ") on thread '" + exit.thread() + "'; the simulator's process is not the program's to end, so that thread was"
                    + " unwound and the program stopped instead");
        }
        int shutdownHooks = Hooks.shutdownHooks().size();
        if (shutdownHooks > 0) {
            problems.add("the robot program registered " + shutdownHooks + " shutdown hook" + (shutdownHooks == 1 ? "" : "s")
                    + ", which never run: the process is the simulator's and ends when the simulator does");
        }
        problems.addAll(match.problems());
        problems.addAll(robotWorld.shutdownProblems());
        for (HardwareDirectory.Hub robotHub : rig.spec().hubs()) {
            LynxHub hub = rig.hub(robotHub.address());
            String name = "hub " + robotHub.address() + " (" + robotHub.name() + ")";
            hub.unsupported().forEach(use -> problems.add(name + " refused a command it does not model: " + use));
            hub.unboundMotorPorts().forEach(port -> problems.add(name + " motor port " + port + " was driven with no motor connected"));
            hub.unboundDigitalPorts().forEach(pin -> problems.add(name + " digital port " + pin + " was read with nothing connected, so it read high"));
            hub.unboundAnalogPorts().forEach(channel -> problems.add(name + " analog port " + channel + " was read with nothing connected, so it read 0 V"));
            hub.unansweredI2cTargets().forEach(target -> problems.add(name + " I2C " + target + " did not answer"));
        }
        rig.wiring().unmappedNames().forEach(name -> problems.add("'" + name + "' is not in the robot mapping, so it was given a port of its own"));
        problems.addAll(rig.wiring().unsupported());
        problems.addAll(rig.cameras().problems());
        problems.addAll(live.mechanisms.problems());
        return problems;
    }

    public KernelDiagnostics diagnostics() {
        return kernel.diagnostics();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            // Closing the controller stops the running OpMode, which the SDK does by sleeping until it has finished.
            kernel.runAsEvent(() -> {
                try {
                    robotWorld.close();
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            });
        } finally {
            live.world.close();
        }
    }
}
