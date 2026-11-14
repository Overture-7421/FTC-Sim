package dev.ftcsim.simulator;

import dev.ftcsim.contracts.field.BodyKind;
import dev.ftcsim.contracts.field.FieldDescription;
import dev.ftcsim.contracts.field.MatchPhase;
import dev.ftcsim.contracts.field.Score;
import dev.ftcsim.contracts.hardware.MotorPlant;
import dev.ftcsim.contracts.hooks.Hooks;
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
import dev.ftcsim.hardware.device.DeviceModels;
import dev.ftcsim.hardware.lynx.LynxHub;
import dev.ftcsim.mechanisms.Mechanisms;
import dev.ftcsim.physics.PhysicsWorld;
import dev.ftcsim.physics.PieceSpec;
import dev.ftcsim.physics.Vector3;
import dev.ftcsim.simulator.profile.RobotProfile;
import dev.ftcsim.simulator.profile.RobotProfileLoader;
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
    private final SimulationKernel kernel;
    private final PhysicsWorld world;
    private final FieldRun fieldRun;
    private final RobotRig rig;
    private final Mechanisms mechanisms;
    private final RayView rays;
    private final RobotWorldLauncher.RobotWorld robotWorld;
    private final RobotHost host;
    private final DriverStation station;
    private final TimedMatch match;

    private GamepadState first = GamepadState.NEUTRAL;
    private GamepadState second = GamepadState.NEUTRAL;
    private final AtomicReference<WorldSnapshot> latest;
    private boolean closed;

    private SimulationSession(
            SessionConfig config,
            SimulationKernel kernel,
            PhysicsWorld world,
            FieldRun fieldRun,
            RobotRig rig,
            Mechanisms mechanisms,
            RayView rays,
            RobotWorldLauncher.RobotWorld robotWorld,
            AtomicReference<WorldSnapshot> latest) {
        this.config = config;
        this.kernel = kernel;
        this.world = world;
        this.fieldRun = fieldRun;
        this.rig = rig;
        this.mechanisms = mechanisms;
        this.rays = rays;
        this.robotWorld = robotWorld;
        this.host = robotWorld.host();
        this.station = new DriverStation(kernel, host);
        this.match = new TimedMatch(kernel, station, fieldRun);
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
        PhysicsWorld world = PhysicsWorld.create(config.nativeDirectory());
        try {
            FieldRun fieldRun = null;
            Map<Integer, PieceSpec> pieceSpecs = Map.of();
            Map<Integer, Integer> kindColours = new HashMap<>();
            if (config.field() == null) {
                world.addStaticBox(new Vector3(0, 0, -0.5), new Vector3(FLOOR_HALF_SIZE, FLOOR_HALF_SIZE, 0.5));
            } else {
                fieldRun = config.field().start(world, config.fieldSetup());
                fieldRun.enterPhase(MatchPhase.PRACTICE, 0);
                pieceSpecs = config.field().pieceSpecs();
                for (BodyKind kind : config.field().description().bodyKinds()) {
                    if (!kind.parts().isEmpty()) {
                        kindColours.put(kind.id(), kind.parts().get(0).rgb());
                    }
                }
            }
            AtomicReference<Mechanisms> mechanismsOnce = new AtomicReference<>();
            RayView rays = new WorldRays(
                    world, EntityIds.CHASSIS, () -> carriedBy(mechanismsOnce.get()), kindColours);
            RobotRig rig = RobotRig.build(
                    config.profile(), world, EntityIds.CHASSIS, config.startX(), config.startY(), config.startHeading(),
                    kernel.clock()::now, rays, DeviceModels.standard());
            Mechanisms mechanisms = MechanismFactory.create(config.profile(), rig.plantView(), pieceSpecs);
            mechanismsOnce.set(mechanisms);

            AtomicReference<WorldSnapshot> snapshots = new AtomicReference<>(world.snapshot());
            FieldRun running = fieldRun;
            clockwork.every(PhysicsWorld.STEPS_PER_SECOND, (index, nanos) -> {
                world.step();
                WorldSnapshot afterStep = world.snapshot();
                if (running != null) {
                    running.afterStep(afterStep);
                }
                mechanisms.step(STEP_SECONDS, rig.chassisView().sample(), afterStep, world);
                snapshots.set(withCarriedPieces(world.snapshot(), mechanisms.carried()));
                rig.stepDevices(nanos);
            });
            clockwork.every(HUB_TICKS_PER_SECOND, (index, nanos) -> rig.tick(nanos));

            RobotWorldLauncher.RobotWorld robotWorld = RobotWorldLauncher.launch(
                    config.program(), kernel.hooks(), config.storageRoot(), config.log(), rig.environment());
            SimulationSession session =
                    new SimulationSession(config, kernel, world, fieldRun, rig, mechanisms, rays, robotWorld, snapshots);
            robotWorld.alreadyReported(() -> kernel.diagnostics().abandonedThreads());
            kernel.schedule(EVENT_LOOP_PERIOD_NANOS, session::eventLoopPass);
            return session;
        } catch (Throwable failure) {
            world.close();
            throw failure;
        }
    }

    /** What a viewer sees: the bodies of the world and, drawn where they are, the pieces the robot carries. */
    private static List<Mechanisms.Carried> carriedBy(Mechanisms mechanisms) {
        return mechanisms == null ? List.of() : mechanisms.carried();
    }

    private static WorldSnapshot withCarriedPieces(WorldSnapshot world, List<Mechanisms.Carried> carried) {
        if (carried.isEmpty()) {
            return world;
        }
        List<BodyState> bodies = new ArrayList<>(world.bodies());
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
        return fieldRun == null ? new Score(List.of()) : fieldRun.score();
    }

    /** One line for each motor and servo the profile wires: what its port and its plant are doing right now. */
    public List<Readout> hardware() {
        List<Readout> readouts = new ArrayList<>();
        rig.profile().motors().keySet().forEach(name -> {
            MotorPlant plant = rig.plant(name);
            readouts.add(new Readout(
                    name, String.format(Locale.ROOT, "%.0f ticks/s  %.1f A", plant.encoderCountsPerSecond(), plant.currentAmps())));
        });
        int parent = RobotProfileLoader.parentAddress(rig.profile());
        rig.profile().servos().forEach((name, servo) -> {
            LynxHub hub = rig.hub(servo.hub() == null ? parent : servo.hub());
            readouts.add(new Readout(
                    name, hub.servoEnabled(servo.port()) ? hub.servoPulseWidthMicros(servo.port()) + " us" : "off"));
        });
        return readouts;
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
        if (fieldRun == null) {
            throw new IllegalStateException("there is no field, so there is nothing to preload from");
        }
        int loaded = 0;
        while (loaded < count && mechanisms.canLoad(storage, kind)) {
            OptionalInt piece = fieldRun.takeFromReserve(kind);
            if (piece.isEmpty()) {
                break;
            }
            mechanisms.load(storage, piece.getAsInt(), kind);
            loaded++;
        }
        return loaded;
    }

    /** A person at the field does one of the actions the field's description lists. */
    public void humanAction(String actionId) {
        if (fieldRun == null) {
            throw new IllegalStateException("There is no field, so nobody is standing at it.");
        }
        fieldRun.perform(actionId);
    }

    public PhysicsWorld world() {
        return world;
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
        for (RobotProfile.Hub profileHub : rig.profile().hubs()) {
            LynxHub hub = rig.hub(profileHub.address());
            String name = "hub " + profileHub.address() + " (" + profileHub.name() + ")";
            hub.unsupported().forEach(use -> problems.add(name + " refused a command it does not model: " + use));
            hub.unboundMotorPorts().forEach(port -> problems.add(name + " motor port " + port + " was driven with no motor connected"));
            hub.unboundDigitalPorts().forEach(pin -> problems.add(name + " digital port " + pin + " was read with nothing connected, so it read high"));
            hub.unboundAnalogPorts().forEach(channel -> problems.add(name + " analog port " + channel + " was read with nothing connected, so it read 0 V"));
            hub.unansweredI2cTargets().forEach(target -> problems.add(name + " I2C " + target + " did not answer"));
        }
        rig.wiring().unmappedNames().forEach(name -> problems.add("'" + name + "' is not in the robot profile, so it was given a port of its own"));
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
            world.close();
        }
    }
}
