package dev.ftcsim.simulator;

import dev.ftcsim.contracts.field.Alliance;
import dev.ftcsim.contracts.snapshot.BodyState;
import dev.ftcsim.contracts.snapshot.WorldSnapshot;
import dev.ftcsim.contracts.world.BodyPose;
import dev.ftcsim.field.EntityIds;
import dev.ftcsim.field.FieldRun;
import dev.ftcsim.field.FieldSetup;
import dev.ftcsim.game.decode.DecodeField;
import dev.ftcsim.mechanisms.Condition;
import dev.ftcsim.mechanisms.LaunchSpec;
import dev.ftcsim.mechanisms.Mechanisms;
import dev.ftcsim.mechanisms.PlantView;
import dev.ftcsim.physics.PhysicsWorld;
import dev.ftcsim.physics.Vector3;
import dev.ftcsim.simulator.robot.MotorCatalog;
import dev.ftcsim.simulator.robot.RobotSpec;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ARTIFACTS let go the way the robot's launcher lets go of them, by a robot that stands still on the DECODE field.
 * The real {@link Mechanisms} do the launching, so the exit point, direction and efficiency come from the robot's design; only
 * the flywheel speed is given.
 */
final class HornetShot {

    static final Path NATIVES = Path.of("build", "jolt-natives-shot-sweep");
    /** The inside face of the goal's end wall and side wall, which carry the backboards. */
    static final double INNER_WALL = 1.7845;

    private static final double STEP_SECONDS = 1.0 / PhysicsWorld.STEPS_PER_SECOND;
    private static final int SETTLE_STEPS = 72;
    private static final int RELEASE_STEPS = 12;
    private static final int FLIGHT_STEPS = 4 * PhysicsWorld.STEPS_PER_SECOND;
    private static final double PLACEMENT_TOLERANCE_M = 0.03;
    private static final double YAW_TOLERANCE_RAD = Math.toRadians(3);
    // A bounce off a board changes the velocity along its normal by more than this in one step.
    private static final double BOUNCE_MPS = 0.4;
    private static final double BALL_RADIUS_M = 0.0635;
    private static final long SEED = 1;

    /** Where the robot stands: its centre and the direction its front points in, counter-clockwise from +X. */
    record Stance(double x, double y, double heading) {}

    /**
     * What happened to the ball.
     *
     * @param placed      the robot stayed where it was put; false if it stood in something, and then nothing else is known
     * @param scored      the field counted the ball as CLASSIFIED or OVERFLOW within the four seconds
     * @param leftField   the ball left the field and was handed back
     * @param boardHeight height of the ball's centre at its first bounce off a backboard plane, NaN if it had none
     * @param restX       where the ball was after the four seconds, or when it left the field
     */
    record Outcome(
            boolean placed, boolean scored, boolean leftField, double boardHeight, double restX, double restY, double restZ) {

        static Outcome blocked() {
            return new Outcome(false, false, false, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }
    }

    /** What a number of balls let go one after another came to: how many the field counted, and where each lay in the end. */
    record Volley(boolean placed, int counted, List<double[]> positions) {}

    private HornetShot() {}

    /**
     * @param ticksPerSecond the velocity both flywheels turn at, in encoder ticks of their motor
     */
    static Outcome fire(RobotSpec profile, Alliance alliance, Stance stance, double ticksPerSecond) {
        try (Scene scene = Scene.open(profile, alliance, stance)) {
            if (scene == null) {
                return Outcome.blocked();
            }
            return scene.flight(scene.release(ticksPerSecond), alliance == Alliance.BLUE ? 1 : -1);
        }
    }

    /**
     * Lets go of {@code balls} balls one after another, {@code intervalSeconds} apart, then waits {@code settleSeconds}
     * for the last to find its way.
     */
    static Volley volley(
            RobotSpec profile, Alliance alliance, Stance stance, double ticksPerSecond, int balls, double intervalSeconds,
            double settleSeconds) {
        try (Scene scene = Scene.open(profile, alliance, stance)) {
            if (scene == null) {
                return new Volley(false, 0, List.of());
            }
            List<Integer> ids = new ArrayList<>();
            for (int i = 0; i < balls; i++) {
                ids.add(scene.release(ticksPerSecond));
                scene.advance((int) Math.round(intervalSeconds * PhysicsWorld.STEPS_PER_SECOND));
            }
            scene.advance((int) Math.round(settleSeconds * PhysicsWorld.STEPS_PER_SECOND));
            List<double[]> positions = new ArrayList<>();
            for (BodyState body : scene.world.snapshot().bodies()) {
                if (ids.contains(body.entityId())) {
                    positions.add(new double[] {body.x(), body.y(), body.z()});
                }
            }
            return new Volley(true, scene.counted(), positions);
        }
    }

    /** A world with the field, the robot standing at its stance, its mechanisms and a stub for the shafts under them. */
    private static final class Scene implements AutoCloseable {
        final PhysicsWorld world;
        final FieldRun run;
        final Mechanisms mechanisms;
        final Plants plants;
        final LaunchSpec launcher;
        final int kind;

        private Scene(PhysicsWorld world, FieldRun run, Mechanisms mechanisms, Plants plants, RobotSpec robot, int kind) {
            this.world = world;
            this.run = run;
            this.mechanisms = mechanisms;
            this.plants = plants;
            this.launcher = robot.launches().get(0);
            this.kind = kind;
        }

        /** The scene once the robot has settled where it was put, or null if it stood in something. */
        static Scene open(RobotSpec profile, Alliance alliance, Stance stance) {
            DecodeField field = new DecodeField();
            PhysicsWorld world = PhysicsWorld.create(NATIVES);
            try {
                FieldRun run = field.start(world, new FieldSetup(SEED, alliance));
                for (BodyState body : world.snapshot().bodies()) {
                    if (field.pieceSpecs().containsKey(body.kind())) {
                        world.removePiece(body.entityId());
                    }
                }
                world.addMecanumChassis(
                        EntityIds.CHASSIS, RobotRig.CHASSIS_KIND, profile.chassis(), new Vector3(stance.x(), stance.y(), 0),
                        stance.heading());
                Plants plants = new Plants(profile);
                Mechanisms mechanisms = profile.mechanisms(plants, field.pieceSpecs());
                Scene scene = new Scene(world, run, mechanisms, plants, profile, field.pieceSpecs().keySet().iterator().next());
                scene.advance(SETTLE_STEPS);
                BodyPose settled = world.bodyPose(EntityIds.CHASSIS);
                double turn = settled.yaw() - stance.heading();
                if (Math.hypot(settled.x() - stance.x(), settled.y() - stance.y()) > PLACEMENT_TOLERANCE_M
                        || Math.abs(Math.atan2(Math.sin(turn), Math.cos(turn))) > YAW_TOLERANCE_RAD) {
                    world.close();
                    return null;
                }
                return scene;
            } catch (RuntimeException failure) {
                world.close();
                throw failure;
            }
        }

        void advance(int steps) {
            for (int i = 0; i < steps; i++) {
                world.step();
                WorldSnapshot snapshot = world.snapshot();
                run.afterStep(snapshot);
                mechanisms.step(STEP_SECONDS, world.bodyPose(EntityIds.CHASSIS), snapshot, world);
            }
        }

        /** Loads the next ball from the field's reserve into the magazine, spins the launcher up and returns the ball's id once it flies. */
        int release(double ticksPerSecond) {
            int id = run.takeFromReserve(kind).orElseThrow();
            mechanisms.load(launcher.storage(), id, kind);
            plants.spinUp(launcher, ticksPerSecond);
            for (int i = 0; i < RELEASE_STEPS && !world.hasPiece(id); i++) {
                advance(1);
            }
            if (!world.hasPiece(id)) {
                throw new IllegalStateException("the launcher '" + launcher.name() + "' did not let the ball go");
            }
            return id;
        }

        int counted() {
            return run.score().lines().stream()
                    .filter(line -> line.label().endsWith("CLASSIFIED") || line.label().endsWith("OVERFLOW"))
                    .mapToInt(line -> line.count())
                    .sum();
        }

        Outcome flight(int id, int side) {
            double boardHeight = Double.NaN;
            BodyPose last = world.bodyPose(id);
            BodyPose before = null;
            boolean left = false;
            for (int step = 1; step <= FLIGHT_STEPS; step++) {
                advance(1);
                if (!world.hasPiece(id)) {
                    left = true;
                    break;
                }
                BodyPose now = world.bodyPose(id);
                if (before != null && Double.isNaN(boardHeight) && bouncedOffBoard(before, now, side)) {
                    boardHeight = now.z();
                }
                before = last;
                last = now;
            }
            return new Outcome(true, counted() > 0, left, boardHeight, last.x(), last.y(), last.z());
        }

        @Override
        public void close() {
            world.close();
        }
    }

    /** Whether the ball bounced off the end board or the side board in this step: its velocity against the board reversed. */
    private static boolean bouncedOffBoard(BodyPose before, BodyPose now, int side) {
        double nearBoard = -INNER_WALL + BALL_RADIUS_M + 0.03;
        boolean endBoard = now.x() < nearBoard && before.vx() < -BOUNCE_MPS && now.vx() > before.vx() + BOUNCE_MPS;
        double vyBefore = side * before.vy();
        double vyNow = side * now.vy();
        boolean sideBoard = side * now.y() < nearBoard && vyBefore < -BOUNCE_MPS && vyNow > vyBefore + BOUNCE_MPS;
        return endBoard || sideBoard;
    }

    /** The shafts of the robot: still, until the launcher is spun up and its feed started. */
    private static final class Plants implements PlantView {
        private final RobotSpec robot;
        private final Map<String, Double> speeds = new HashMap<>();

        Plants(RobotSpec robot) {
            this.robot = robot;
        }

        /** Both flywheels at the given tick rate and every motor a launch condition names at a speed that satisfies it. */
        void spinUp(LaunchSpec launcher, double ticksPerSecond) {
            for (LaunchSpec.Wheel wheel : launcher.wheels()) {
                RobotSpec.Motor motor = robot.motors().get(wheel.motor());
                String name = motor.model() == null ? MotorCatalog.GOBILDA_435 : motor.model();
                double radPerSec = ticksPerSecond * 2 * Math.PI / MotorCatalog.motor(name).encoderTicksPerRevolution();
                speeds.put(wheel.motor(), wheel.sign() * radPerSec);
            }
            for (Condition speed : launcher.active()) {
                boolean capped = speed.maxRadPerSec() != Double.POSITIVE_INFINITY;
                speeds.put(speed.motor(), capped ? speed.maxRadPerSec() - 80 : speed.minRadPerSec() + 80);
            }
        }

        @Override
        public boolean hasShaft(String motor) {
            RobotSpec.Motor found = robot.motors().get(motor);
            return found != null && found.shaft() != null;
        }

        @Override
        public double shaftSpeedRadPerSec(String motor) {
            return speeds.getOrDefault(motor, 0.0);
        }

        @Override
        public double shaftAngleRad(String motor) {
            return 0.0;
        }

        @Override
        public void extractEnergy(String motor, double joules) {}
    }
}
