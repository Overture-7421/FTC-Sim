package dev.ftcsim.mechanisms;

import dev.ftcsim.contracts.snapshot.BodyState;
import dev.ftcsim.contracts.snapshot.WorldSnapshot;
import dev.ftcsim.contracts.world.BodyPose;
import dev.ftcsim.contracts.world.Vec3;
import dev.ftcsim.physics.PhysicsWorld;
import dev.ftcsim.physics.PieceSpec;
import dev.ftcsim.physics.Vector3;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The game-piece side of a robot's mechanisms: intakes that take pieces in, storages that carry them along a path,
 * launchers that put them back into the world. Everything is driven by the state of the robot's motors, through
 * {@link PlantView}, and by where pieces are; none of it knows what the robot program wants.
 *
 * <p>A piece has exactly one owner at any time. While it is in the world it is a free body; the moment an intake
 * takes it, it leaves the world and this class owns it, with its id; the moment a launcher or an entrance lets it
 * go, it re-enters the world under the same id. Mechanisms never push on the chassis.
 *
 * <p>Not thread-safe; call {@link #step} from the simulation thread, once per physics step, after the world has
 * stepped.
 */
public final class Mechanisms {

    /** A piece the robot is carrying and where its centre is, in the field frame. */
    public record Carried(int entityId, int kind, Vec3 center, double radiusM) {}

    private static final double AT_END_TOLERANCE_M = 0.005;
    private static final double EJECT_SPEED_MPS = 0.3;
    private static final double OPPOSED_MPS = 0.5;

    private static final class Piece {
        final int id;
        final int kind;
        final double radius;
        double s;

        Piece(int id, int kind, double radius) {
            this.id = id;
            this.kind = kind;
            this.radius = radius;
        }
    }

    private static final class Storage {
        final StorageSpec spec;
        final double[] cumulative;
        final double length;
        /** Front of the path first, so that each piece is blocked by the one before it. */
        final List<Piece> pieces = new ArrayList<>();

        Storage(StorageSpec spec) {
            this.spec = spec;
            this.cumulative = new double[spec.path().size()];
            for (int i = 1; i < cumulative.length; i++) {
                cumulative[i] = cumulative[i - 1] + spec.path().get(i).minus(spec.path().get(i - 1)).length();
            }
            this.length = cumulative[cumulative.length - 1];
        }

        boolean hasRoomFor(double radius) {
            if (pieces.size() >= spec.capacity()) {
                return false;
            }
            Piece rear = pieces.isEmpty() ? null : pieces.get(pieces.size() - 1);
            return rear == null || rear.s >= rear.radius + radius;
        }

        Vec3 positionAt(double s) {
            int segment = segmentAt(s);
            Vec3 from = spec.path().get(segment);
            Vec3 to = spec.path().get(segment + 1);
            double along = (Math.max(0, Math.min(length, s)) - cumulative[segment]) / (cumulative[segment + 1] - cumulative[segment]);
            return from.plus(to.minus(from).times(along));
        }

        /** The direction of travel at {@code s}, a unit vector. */
        Vec3 tangentAt(double s) {
            int segment = segmentAt(s);
            Vec3 along = spec.path().get(segment + 1).minus(spec.path().get(segment));
            return along.times(1.0 / along.length());
        }

        private int segmentAt(double s) {
            for (int i = 1; i < cumulative.length - 1; i++) {
                if (s < cumulative[i]) {
                    return i - 1;
                }
            }
            return cumulative.length - 2;
        }
    }

    private final Map<String, Storage> storages = new LinkedHashMap<>();
    private final List<CaptureSpec> captures;
    private final List<LaunchSpec> launches;
    private final PlantView plants;
    private final Map<Integer, PieceSpec> pieceSpecs;
    private BodyPose lastPose;
    private final List<String> problems = new ArrayList<>();

    /**
     * @param pieceSpecs the physical properties of every kind of piece the mechanisms may meet; a body whose kind is
     *                   not in the map is not a piece and is left alone
     * @throws IllegalArgumentException if the mechanisms refer to storages or motors that do not exist
     */
    public Mechanisms(
            List<StorageSpec> storageSpecs,
            List<CaptureSpec> captures,
            List<LaunchSpec> launches,
            PlantView plants,
            Map<Integer, PieceSpec> pieceSpecs) {
        for (StorageSpec spec : storageSpecs) {
            if (storages.put(spec.name(), new Storage(spec)) != null) {
                throw new IllegalArgumentException("two storages are called '" + spec.name() + "'");
            }
            for (StorageSpec.Drive drive : spec.drives()) {
                requireShaft(plants, drive.motor(), "storage '" + spec.name() + "'");
            }
        }
        for (CaptureSpec capture : captures) {
            requireStorage(capture.storage(), "intake '" + capture.name() + "'");
            requireConditionsMotors(plants, capture.active(), "intake '" + capture.name() + "'");
        }
        for (LaunchSpec launch : launches) {
            requireStorage(launch.storage(), "launcher '" + launch.name() + "'");
            requireConditionsMotors(plants, launch.active(), "launcher '" + launch.name() + "'");
            launch.wheels().forEach(wheel -> requireShaft(plants, wheel.motor(), "launcher '" + launch.name() + "'"));
            if (launch.turret() != null) {
                requireShaft(plants, launch.turret().motor(), "launcher '" + launch.name() + "'");
            }
        }
        this.captures = List.copyOf(captures);
        this.launches = List.copyOf(launches);
        this.plants = plants;
        this.pieceSpecs = Map.copyOf(pieceSpecs);
    }

    private void requireStorage(String name, String user) {
        if (!storages.containsKey(name)) {
            throw new IllegalArgumentException(user + " uses storage '" + name + "', which does not exist; there are " + storages.keySet());
        }
    }

    private static void requireShaft(PlantView plants, String motor, String user) {
        if (!plants.hasShaft(motor)) {
            throw new IllegalArgumentException(user + " follows motor '" + motor + "', which the robot does not have as a shaft");
        }
    }

    private static void requireConditionsMotors(PlantView plants, List<Condition> conditions, String user) {
        for (Condition condition : conditions) {
            requireShaft(plants, condition.motor(), user);
        }
    }

    /**
     * Moves what the robot carries, lets intakes take in and launchers let go, for one physics step.
     *
     * @param chassis  where the robot is now
     * @param snapshot the world after the step; pieces taken in this call are not removed from it
     */
    public void step(double dtSeconds, BodyPose chassis, WorldSnapshot snapshot, PhysicsWorld world) {
        lastPose = chassis;
        for (Storage storage : storages.values()) {
            advance(storage, dtSeconds, chassis, snapshot, world);
        }
        Set<Integer> takenThisStep = new HashSet<>();
        for (LaunchSpec launch : launches) {
            launch(launch, storages.get(launch.storage()), chassis, world);
        }
        for (CaptureSpec capture : captures) {
            capture(capture, storages.get(capture.storage()), chassis, snapshot, world, takenThisStep);
        }
    }

    private void advance(Storage storage, double dt, BodyPose chassis, WorldSnapshot snapshot, PhysicsWorld world) {
        List<Piece> pieces = storage.pieces;
        for (int i = 0; i < pieces.size(); i++) {
            Piece piece = pieces.get(i);
            double speed = 0.0;
            for (StorageSpec.Drive drive : storage.spec.drives()) {
                if (piece.s >= drive.fromMeters() && piece.s <= drive.toMeters()) {
                    speed += drive.sign() * drive.radiusM() * plants.shaftSpeedRadPerSec(drive.motor());
                }
            }
            double limit = i == 0
                    ? storage.length
                    : pieces.get(i - 1).s - (pieces.get(i - 1).radius + piece.radius);
            piece.s = Math.min(piece.s + speed * dt, limit);
        }
        if (!pieces.isEmpty() && pieces.get(pieces.size() - 1).s < 0.0) {
            Piece rear = pieces.get(pieces.size() - 1);
            if (eject(storage, rear, chassis, snapshot, world)) {
                pieces.remove(pieces.size() - 1);
            }
        }
        for (Piece piece : pieces) {
            piece.s = Math.max(piece.s, 0.0);
        }
    }

    /** Lets the rearmost piece out of the entrance if nothing is in its way; false if it has to wait. */
    private boolean eject(Storage storage, Piece piece, BodyPose chassis, WorldSnapshot snapshot, PhysicsWorld world) {
        Vec3 entrance = storage.positionAt(0.0);
        Vec3 point = chassis.pointToField(entrance);
        for (BodyState body : snapshot.bodies()) {
            PieceSpec other = pieceSpecs.get(body.kind());
            if (other != null && new Vec3(body.x(), body.y(), body.z()).minus(point).length() < piece.radius + other.radiusM()) {
                return false;
            }
        }
        Vec3 outward = chassis.directionToField(storage.tangentAt(0.0)).times(-EJECT_SPEED_MPS);
        world.spawnPiece(piece.id, piece.kind, pieceSpecs.get(piece.kind), vector(point), vector(chassis.velocityOfPoint(entrance).plus(outward)));
        return true;
    }

    private void launch(LaunchSpec launch, Storage storage, BodyPose chassis, PhysicsWorld world) {
        if (storage.pieces.isEmpty() || storage.pieces.get(0).s < storage.length - AT_END_TOLERANCE_M || !allHold(launch.active())) {
            return;
        }
        Piece piece = storage.pieces.get(0);
        PieceSpec spec = pieceSpecs.get(piece.kind);
        double surface = 0.0;
        for (LaunchSpec.Wheel wheel : launch.wheels()) {
            surface += Math.max(0.0, wheel.sign() * wheel.radiusM() * plants.shaftSpeedRadPerSec(wheel.motor()));
        }
        surface = launch.wheels().isEmpty() ? 0.0 : surface / launch.wheels().size();
        noteOpposedWheels(launch);
        double fromWheels = launch.efficiency() * surface;
        double exitSpeed = Math.max(launch.dribbleSpeed(), fromWheels);

        Vec3 exit = storage.positionAt(storage.length);
        Vec3 velocity = chassis.velocityOfPoint(exit).plus(chassis.directionToField(aim(launch)).times(exitSpeed));
        world.spawnPiece(piece.id, piece.kind, spec, vector(chassis.pointToField(exit)), vector(velocity));
        storage.pieces.remove(0);

        double energyPerWheel = 0.5 * spec.massKg() * fromWheels * fromWheels / Math.max(1, launch.wheels().size());
        for (LaunchSpec.Wheel wheel : launch.wheels()) {
            plants.extractEnergy(wheel.motor(), energyPerWheel);
        }
    }

    /** Records a problem when one wheel of a launcher spins against the others as a piece goes out: its Mirrored is likely wrong. */
    private void noteOpposedWheels(LaunchSpec launch) {
        LaunchSpec.Wheel fastest = null;
        double fastestSurface = 0.0;
        for (LaunchSpec.Wheel wheel : launch.wheels()) {
            double surface = wheel.sign() * wheel.radiusM() * plants.shaftSpeedRadPerSec(wheel.motor());
            if (surface > fastestSurface) {
                fastest = wheel;
                fastestSurface = surface;
            }
        }
        for (LaunchSpec.Wheel wheel : launch.wheels()) {
            double surface = wheel.sign() * wheel.radiusM() * plants.shaftSpeedRadPerSec(wheel.motor());
            if (fastest != null && fastestSurface > OPPOSED_MPS && surface < -OPPOSED_MPS) {
                String message = "launcher '" + launch.name() + "': " + wheel.motor() + " turns against " + fastest.motor()
                        + " when a piece goes out, so the shot loses speed; if the code turns them the same way, give both the same Mirrored";
                synchronized (problems) {
                    if (!problems.contains(message)) {
                        problems.add(message);
                    }
                }
            }
        }
    }

    /** What the launcher wheels have warned about, oldest first. */
    public List<String> problems() {
        synchronized (problems) {
            return List.copyOf(problems);
        }
    }

    /** The launch direction in the robot frame: the launcher's own, turned about the robot's vertical axis by its turret. */
    private Vec3 aim(LaunchSpec launch) {
        LaunchSpec.Turret turret = launch.turret();
        if (turret == null) {
            return launch.direction();
        }
        double turn = turret.yawRadians(plants.shaftAngleRad(turret.motor()));
        double cos = StrictMath.cos(turn);
        double sin = StrictMath.sin(turn);
        Vec3 direction = launch.direction();
        return new Vec3(direction.x() * cos - direction.y() * sin, direction.x() * sin + direction.y() * cos, direction.z());
    }

    private void capture(
            CaptureSpec capture,
            Storage storage,
            BodyPose chassis,
            WorldSnapshot snapshot,
            PhysicsWorld world,
            Set<Integer> takenThisStep) {
        if (!allHold(capture.active())) {
            return;
        }
        for (BodyState body : snapshot.bodies()) {
            PieceSpec spec = pieceSpecs.get(body.kind());
            if (spec == null || takenThisStep.contains(body.entityId()) || !capture.takes(2.0 * spec.radiusM())) {
                continue;
            }
            if (!storage.hasRoomFor(spec.radiusM())) {
                return;
            }
            Vec3 local = chassis.directionToBody(new Vec3(body.x(), body.y(), body.z()).minus(chassis.position()));
            if (capture.volume().contains(local)) {
                world.removePiece(body.entityId());
                takenThisStep.add(body.entityId());
                storage.pieces.add(new Piece(body.entityId(), body.kind(), spec.radiusM()));
            }
        }
    }

    private boolean allHold(List<Condition> conditions) {
        for (Condition condition : conditions) {
            if (!condition.holds(plants)) {
                return false;
            }
        }
        return true;
    }

    /** Every piece the robot carries, with its centre in the field frame as of the last step. */
    public List<Carried> carried() {
        List<Carried> carried = new ArrayList<>();
        if (lastPose == null) {
            return carried;
        }
        for (Storage storage : storages.values()) {
            for (Piece piece : storage.pieces) {
                carried.add(new Carried(piece.id, piece.kind, lastPose.pointToField(storage.positionAt(piece.s)), piece.radius));
            }
        }
        carried.sort((a, b) -> Integer.compare(a.entityId(), b.entityId()));
        return carried;
    }

    /** The ids of the pieces in a storage, the one nearest the end of the path first. */
    public List<Integer> contents(String storage) {
        return storage(storage).pieces.stream().map(piece -> piece.id).toList();
    }

    /** The names of the storages, in the order the design lists them. */
    public List<String> storageNames() {
        return List.copyOf(storages.keySet());
    }

    /** How many pieces a storage holds when it is full. */
    public int capacity(String storage) {
        return storage(storage).spec.capacity();
    }

    /**
     * Puts a piece that is not in the world, such as a preload the field handed out, into a storage behind the
     * pieces already there.
     *
     * @return false if the storage is full or the piece does not fit behind the others
     */
    public boolean load(String storage, int entityId, int kind) {
        if (!canLoad(storage, kind)) {
            return false;
        }
        Storage found = storage(storage);
        double radius = pieceSpecs.get(kind).radiusM();
        Piece piece = new Piece(entityId, kind, radius);
        piece.s = nextFreeSpot(found, radius);
        found.pieces.add(piece);
        return true;
    }

    /** Whether {@link #load} would accept a piece of that kind right now. */
    public boolean canLoad(String storage, int kind) {
        Storage found = storage(storage);
        PieceSpec spec = pieceSpecs.get(kind);
        if (spec == null) {
            throw new IllegalArgumentException("kind " + kind + " is not a piece these mechanisms know");
        }
        return found.pieces.size() < found.spec.capacity() && nextFreeSpot(found, spec.radiusM()) >= 0.0;
    }

    /** Where a piece loaded now would sit: as far forward as the pieces already there allow. */
    private static double nextFreeSpot(Storage storage, double radius) {
        if (storage.pieces.isEmpty()) {
            return storage.length;
        }
        Piece rear = storage.pieces.get(storage.pieces.size() - 1);
        return rear.s - (rear.radius + radius);
    }

    private Storage storage(String name) {
        Storage found = storages.get(name);
        if (found == null) {
            throw new IllegalArgumentException("there is no storage '" + name + "'; there are " + storages.keySet());
        }
        return found;
    }

    private static Vector3 vector(Vec3 v) {
        return new Vector3(v.x(), v.y(), v.z());
    }
}
