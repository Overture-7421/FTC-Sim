package dev.ftcsim.physics;

import dev.ftcsim.contracts.snapshot.BodyState;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * The scripted world behind the determinism gate and the benchmark: a closed arena with 150 balls
 * of the three game sizes, a ramp, a gravity-closed gate, a tipping hive and a mecanum robot that
 * crosses the ramp, pushes against a wall, strafes, turns, ploughs through the balls and feeds them
 * through an intake zone and a shooter that fires at 15 m/s. Everything is driven by the step
 * counter and one seeded generator, so a run is a pure function of the build under test.
 */
final class GoldenScenario {

    static final int STEPS = 5000;
    static final int HASH_INTERVAL = 100;

    static final int KIND_HIVE = 10;
    static final int KIND_GATE = 11;
    static final int KIND_ROBOT = 12;
    static final int ROBOT_ID = 3;
    static final int FIRST_BALL_ID = 1000;
    static final int BALL_COUNT = 150;

    static final double FIELD_HALF = 1.8288;
    static final double CEILING = 3.2;

    // Ball kinds 1 to 3: the 12.45 cm game ball, the 7.1 cm pollen and the 9.1 cm nectar.
    private static final PieceSpec[] BALLS = {
        new PieceSpec(0.06225f, 0.075f, 0.5f, 0.5f, 0.5f),
        new PieceSpec(0.0355f, 0.030f, 0.5f, 0.5f, 0.5f),
        new PieceSpec(0.0455f, 0.060f, 0.5f, 0.5f, 0.5f),
    };
    private static final int HIVE_ID = 1;
    private static final int GATE_ID = 2;
    private static final int PILE_BALLS = 120;
    private static final int HIVE_BALLS = 18;

    private static final double WALL_HALF_HEIGHT = 0.5 * CEILING;
    private static final double RAMP_TILT = Math.toRadians(15);
    private static final double SHOT_SPEED = 15.0;
    private static final double SHOT_LIFT = 1.0;
    private static final double SHOT_SPREAD = 0.3;
    private static final int PIECES_PER_VOLLEY = 4;
    private static final int INTAKE_PERIOD = 6;
    private static final int SHOOT_PERIOD = 60;
    private static final int HOPPER_CAPACITY = 12;

    private record Command(int untilStep, double frontLeft, double frontRight, double rearLeft, double rearRight) {}

    private record Stored(int entityId, int kind) {}

    // Cross the ramp and push against the far wall, strafe along it into the pile, back off, turn,
    // plough east through the balls, strafe, spin in the open, drive a diagonal and stop.
    private static final List<Command> SCRIPT = List.of(
            new Command(900, 1, 1, 1, 1),
            new Command(1120, 1, -1, -1, 1),
            new Command(1190, -1, -1, -1, -1),
            new Command(1325, -1, 1, -1, 1),
            new Command(1700, 1, 1, 1, 1),
            new Command(1850, -1, 1, 1, -1),
            new Command(2150, 1, -1, 1, -1),
            new Command(2400, 1, 0, 0, 1),
            new Command(2800, 0, 1, 1, 0),
            new Command(3300, 1, 1, 1, 1),
            new Command(3600, -1, 1, -1, 1),
            new Command(4200, 1, 1, 1, 1),
            new Command(4600, -1, -1, -1, -1),
            new Command(STEPS, 0, 0, 0, 0));

    private final PhysicsWorld world;
    private final Hive hive;
    private final Gate gate;
    private final MecanumChassis robot;
    private final ArrayDeque<Stored> hopper = new ArrayDeque<>();
    private final SplittableRandom random = new SplittableRandom(20_260_513L);
    private int commandIndex;
    private int piecesTakenIn;
    private int piecesShot;

    GoldenScenario(PhysicsWorld world) {
        this.world = world;
        buildField();
        hive = world.addHive(HIVE_ID, KIND_HIVE,
                new HiveSpec(new Vector3(1.0, 1.0, 0.35), 0.6, 0.4, 0.06, Math.toRadians(30), Math.toRadians(120)));
        gate = world.addGate(GATE_ID, KIND_GATE,
                new GateSpec(new Vector3(-0.7, 0.4, 0.13), 0.3, 0.10, 0.01, 0.04, Math.toRadians(80), 2.0));
        robot = world.addMecanumChassis(ROBOT_ID, KIND_ROBOT, MecanumConfig.generic18Inch(), new Vector3(1.3, -1.0, 0), Math.PI);
        spawnBalls();
    }

    Hive hive() {
        return hive;
    }

    Gate gate() {
        return gate;
    }

    MecanumChassis robot() {
        return robot;
    }

    int piecesTakenIn() {
        return piecesTakenIn;
    }

    int piecesShot() {
        return piecesShot;
    }

    /** Runs the script for the current step, then advances the world by that step. */
    void step() {
        script();
        world.step();
    }

    /** Everything the script does before the world advances: wheel commands, hive orders, intake and shooter. */
    void script() {
        long step = world.stepIndex();
        driveRobot(step);
        if (step == 900 || step == 2500 || step == 4100) {
            hive.tipPositive();
        } else if (step == 1700 || step == 3300 || step == 4700) {
            hive.tipNegative();
        }
        if (step % INTAKE_PERIOD == 0 && step < 4800) {
            takeIn();
        }
        if (step % SHOOT_PERIOD == 0 && step >= 600 && step < 4800) {
            shoot();
        }
    }

    /** Hash of the world after every {@link #HASH_INTERVAL} steps of a complete run. */
    static List<String> run(PhysicsWorld world) {
        GoldenScenario scenario = new GoldenScenario(world);
        List<String> hashes = new ArrayList<>();
        for (int i = 1; i <= STEPS; i++) {
            scenario.step();
            if (i % HASH_INTERVAL == 0) {
                hashes.add(world.stateHash());
            }
        }
        return hashes;
    }

    private void driveRobot(long step) {
        while (step >= SCRIPT.get(commandIndex).untilStep()) {
            commandIndex++;
        }
        Command command = SCRIPT.get(commandIndex);
        robot.setWheelCommand(WheelPosition.FRONT_LEFT, command.frontLeft());
        robot.setWheelCommand(WheelPosition.FRONT_RIGHT, command.frontRight());
        robot.setWheelCommand(WheelPosition.REAR_LEFT, command.rearLeft());
        robot.setWheelCommand(WheelPosition.REAR_RIGHT, command.rearRight());
    }

    private void buildField() {
        world.addStaticBox(new Vector3(0, 0, -0.5), new Vector3(2.0, 2.0, 0.5));
        world.addStaticBox(new Vector3(0, 0, CEILING + 0.05), new Vector3(2.0, 2.0, 0.05));
        double half = 0.025;
        double edge = FIELD_HALF + half;
        world.addStaticBox(new Vector3(edge, 0, WALL_HALF_HEIGHT), new Vector3(half, edge, WALL_HALF_HEIGHT));
        world.addStaticBox(new Vector3(-edge, 0, WALL_HALF_HEIGHT), new Vector3(half, edge, WALL_HALF_HEIGHT));
        world.addStaticBox(new Vector3(0, edge, WALL_HALF_HEIGHT), new Vector3(edge, half, WALL_HALF_HEIGHT));
        world.addStaticBox(new Vector3(0, -edge, WALL_HALF_HEIGHT), new Vector3(edge, half, WALL_HALF_HEIGHT));
        // Low end of the ramp at x = 0.3, rising towards -X by 0.8 m * sin(15 degrees).
        double rampCenterZ = 0.4 * StrictMath.sin(RAMP_TILT) - 0.05 * StrictMath.cos(RAMP_TILT);
        world.addStaticBox(new Vector3(-0.1, -1.0, rampCenterZ), new Vector3(0.4, 0.3, 0.05), Vector3.Y, RAMP_TILT);
    }

    private void spawnBalls() {
        int index = 0;
        for (int layer = 0; layer < 3; layer++) {
            for (int row = 0; row < 5; row++) {
                for (int column = 0; column < 8; column++) {
                    spawn(index++, new Vector3(
                            -1.4 + 0.2 * column + jitter(), 0.2 + 0.25 * row + jitter(), 0.08 + 0.15 * layer));
                }
            }
        }
        for (int i = 0; i < HIVE_BALLS; i++) {
            spawn(index++, new Vector3(
                    0.8 + 0.1 * (i % 5) + jitter(), 0.85 + 0.1 * ((i / 5) % 3) + jitter(), 0.7 + 0.12 * (i / 15)));
        }
        double rampTopX = -0.1 - 0.4 * StrictMath.cos(RAMP_TILT) + 0.05 * StrictMath.sin(RAMP_TILT);
        while (index < BALL_COUNT) {
            int slot = index - PILE_BALLS - HIVE_BALLS;
            double x = rampTopX + 0.1 * (slot % 4) + 0.15;
            double surface = (0.3 - x) * StrictMath.tan(RAMP_TILT);
            spawn(index++, new Vector3(x + jitter(), -1.15 + 0.1 * (slot / 4) + jitter(), surface + 0.1));
        }
    }

    private void spawn(int index, Vector3 position) {
        int kind = 1 + index % 3;
        world.spawnPiece(FIRST_BALL_ID + index, kind, BALLS[kind - 1], position, Vector3.ZERO);
    }

    private double unit() {
        return (random.nextLong() >>> 11) * 0x1.0p-53;
    }

    private double jitter() {
        return 0.06 * (unit() - 0.5);
    }

    private static BodyState bodyOf(List<BodyState> bodies, int entityId) {
        return bodies.stream().filter(b -> b.entityId() == entityId).findFirst().orElseThrow();
    }

    private static Quaternion rotationOf(BodyState body) {
        return new Quaternion(body.qx(), body.qy(), body.qz(), body.qw());
    }

    // The intake swallows the lowest-numbered balls inside a zone in front of the robot; they wait
    // in a hopper until the shooter fires them.
    private void takeIn() {
        if (hopper.size() > HOPPER_CAPACITY - PIECES_PER_VOLLEY) {
            return;
        }
        List<BodyState> bodies = world.snapshot().bodies();
        BodyState body = bodyOf(bodies, ROBOT_ID);
        Quaternion toLocal = new Quaternion(-body.qx(), -body.qy(), -body.qz(), body.qw());
        Vector3 origin = new Vector3(body.x(), body.y(), body.z());
        List<Stored> taken = new ArrayList<>();
        for (BodyState candidate : bodies) {
            if (candidate.entityId() < FIRST_BALL_ID) {
                continue;
            }
            Vector3 local = toLocal.rotate(new Vector3(candidate.x(), candidate.y(), candidate.z()).minus(origin));
            if (local.x() > 0.2 && local.x() < 0.6 && Math.abs(local.y()) < 0.25 && local.z() < -0.1) {
                taken.add(new Stored(candidate.entityId(), candidate.kind()));
                if (taken.size() == PIECES_PER_VOLLEY) {
                    break;
                }
            }
        }
        for (Stored stored : taken) {
            world.removePiece(stored.entityId());
            hopper.add(stored);
            piecesTakenIn++;
        }
    }

    // The shooter releases one stored ball above the robot, along its heading with a little spread.
    private void shoot() {
        Stored stored = hopper.poll();
        if (stored == null) {
            return;
        }
        BodyState body = bodyOf(world.snapshot().bodies(), ROBOT_ID);
        Quaternion rotation = rotationOf(body);
        double spread = SHOT_SPREAD * (unit() - 0.5);
        double flat = Math.sqrt(SHOT_SPEED * SHOT_SPEED - SHOT_LIFT * SHOT_LIFT);
        Vector3 direction = rotation.rotate(new Vector3(StrictMath.cos(spread), StrictMath.sin(spread), 0));
        Vector3 muzzle = new Vector3(body.x(), body.y(), body.z() + 0.28).plus(rotation.rotate(new Vector3(-0.1, 0, 0)));
        world.spawnPiece(stored.entityId(), stored.kind(), BALLS[stored.kind() - 1], muzzle,
                new Vector3(direction.x() * flat, direction.y() * flat, SHOT_LIFT));
        piecesShot++;
    }
}
