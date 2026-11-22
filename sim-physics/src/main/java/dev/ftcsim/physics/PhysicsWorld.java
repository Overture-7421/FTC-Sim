package dev.ftcsim.physics;

import com.github.stephengold.joltjni.BodyCreationSettings;
import com.github.stephengold.joltjni.BodyFilter;
import com.github.stephengold.joltjni.BodyInterface;
import com.github.stephengold.joltjni.BodyLockRead;
import com.github.stephengold.joltjni.BoxShape;
import com.github.stephengold.joltjni.BroadPhaseLayerFilter;
import com.github.stephengold.joltjni.BroadPhaseLayerInterfaceTable;
import com.github.stephengold.joltjni.IgnoreMultipleBodiesFilter;
import com.github.stephengold.joltjni.JobSystem;
import com.github.stephengold.joltjni.JobSystemSingleThreaded;
import com.github.stephengold.joltjni.Jolt;
import com.github.stephengold.joltjni.MassProperties;
import com.github.stephengold.joltjni.ObjectLayerFilter;
import com.github.stephengold.joltjni.ObjectLayerPairFilterTable;
import com.github.stephengold.joltjni.ObjectVsBroadPhaseLayerFilterTable;
import com.github.stephengold.joltjni.OffsetCenterOfMassShape;
import com.github.stephengold.joltjni.PhysicsSettings;
import com.github.stephengold.joltjni.PhysicsSystem;
import com.github.stephengold.joltjni.Quat;
import com.github.stephengold.joltjni.RRayCast;
import com.github.stephengold.joltjni.RVec3;
import com.github.stephengold.joltjni.RayCastResult;
import com.github.stephengold.joltjni.ShapeRefC;
import com.github.stephengold.joltjni.ShapeResult;
import com.github.stephengold.joltjni.SpecifiedBroadPhaseLayerFilter;
import com.github.stephengold.joltjni.SpecifiedObjectLayerFilter;
import com.github.stephengold.joltjni.SphereShape;
import com.github.stephengold.joltjni.TempAllocatorImpl;
import com.github.stephengold.joltjni.Vec3;
import com.github.stephengold.joltjni.enumerate.EActivation;
import com.github.stephengold.joltjni.enumerate.EMotionQuality;
import com.github.stephengold.joltjni.enumerate.EMotionType;
import com.github.stephengold.joltjni.enumerate.EOverrideMassProperties;
import com.github.stephengold.joltjni.enumerate.EPhysicsUpdateError;
import com.github.stephengold.joltjni.readonly.ConstBodyLockInterface;
import com.github.stephengold.joltjni.readonly.ConstNarrowPhaseQuery;
import dev.ftcsim.contracts.snapshot.BodyState;
import dev.ftcsim.contracts.snapshot.WorldSnapshot;
import dev.ftcsim.contracts.world.BodyPose;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.DoubleConsumer;

/**
 * Fixed-step rigid-body world on top of Jolt, and the only class that touches jolt-jni. The world is
 * Z-up and metric and advances in steps of 1/240 s on the calling thread. Nothing here depends on callback order or
 * wall-clock time; every loop runs in ascending entity id order.
 *
 * <p>Entities are the bodies a snapshot reports: pieces, pivots, chassis and fixed boxes. Other static field
 * geometry has no entity id. Not thread-safe; call everything from one simulation thread.
 */
public final class PhysicsWorld implements AutoCloseable {

    public static final int STEPS_PER_SECOND = 240;
    static final float STEP_SECONDS = 1.0f / STEPS_PER_SECOND;
    static final float GRAVITY = 9.81f;

    private static final int LAYER_STATIC = 0;
    private static final int LAYER_MOVING = 1;
    private static final int LAYER_PIECE = 2;
    private static final int LAYER_ATTACHMENT = 3;
    private static final int LAYER_COUNT = 4;
    private static final int BROAD_PHASE_COUNT = 2;
    private static final int MAX_BODIES = 4096;
    private static final int MAX_BODY_PAIRS = 65_536;
    private static final int MAX_CONTACT_CONSTRAINTS = 16_384;
    private static final int TEMP_ALLOCATOR_BYTES = 32 * 1024 * 1024;

    private static final float FIELD_FRICTION = 0.8f;
    private static final float FIELD_RESTITUTION = 0.0f;
    private static final float CHASSIS_FRICTION = 0.4f;
    private static final float CHASSIS_RESTITUTION = 0.0f;
    // Rounded edges let the box slide over the ramp seams instead of snagging on them.
    private static final float CHASSIS_EDGE_RADIUS = 0.02f;
    private static final Vec3 ZERO_VELOCITY = new Vec3();
    // Foam balls roll at up to 15 m/s, i.e. several hundred rad/s, far above Jolt's default clamp.
    private static final float MAX_ANGULAR_SPEED = 600.0f;
    // Pieces launched faster than this get continuous collision so they cannot tunnel through walls.
    private static final float FAST_PIECE_SPEED = 4.0f;

    private static boolean engineStarted;

    private enum Role { PIECE, CHASSIS, PIVOT, FIXED }

    private record Entity(int id, int kind, Role role, int bodyId) {}

    private record ChassisBody(int bodyId, MecanumChassis model) {}

    private final BroadPhaseLayerInterfaceTable broadPhaseLayers = new BroadPhaseLayerInterfaceTable(LAYER_COUNT, BROAD_PHASE_COUNT);
    private final ObjectLayerPairFilterTable layerPairs = new ObjectLayerPairFilterTable(LAYER_COUNT);
    private final ObjectVsBroadPhaseLayerFilterTable objectVsBroadPhase;
    private final PhysicsSystem system = new PhysicsSystem();
    private final BodyInterface bodies;
    private final ConstNarrowPhaseQuery groundQuery;
    private final ConstBodyLockInterface bodyLocks;
    private final PivotBodies pivots;
    private final RollingResistance rolling;
    private final TempAllocatorImpl tempAllocator = new TempAllocatorImpl(TEMP_ALLOCATOR_BYTES);
    private final JobSystem jobSystem;
    // Wheels stand on static field geometry only; these built-in filters keep the probe rays out of Java callbacks.
    private final BroadPhaseLayerFilter groundBroadPhase = new SpecifiedBroadPhaseLayerFilter(LAYER_STATIC);
    private final ObjectLayerFilter groundLayer = new SpecifiedObjectLayerFilter(LAYER_STATIC);
    private final BodyFilter anyBody = new BodyFilter();
    private final BroadPhaseLayerFilter anyBroadPhase = new BroadPhaseLayerFilter();
    private final ObjectLayerFilter anyLayer = new ObjectLayerFilter();
    private final IgnoreMultipleBodiesFilter rayIgnored = new IgnoreMultipleBodiesFilter();
    private final Map<Integer, List<Integer>> attachments = new HashMap<>();
    private final Map<PieceSpec, SphereShape> pieceShapes = new HashMap<>();
    private final TreeMap<Integer, Entity> entities = new TreeMap<>();
    private final TreeMap<Integer, ChassisBody> chassis = new TreeMap<>();
    private final String dllSha256;

    private final RVec3 scratchPosition = new RVec3();
    private final Quat scratchRotation = new Quat();
    private final Vec3 scratchLinearVelocity = new Vec3();
    private final Vec3 scratchAngularVelocity = new Vec3();
    private long stepIndex;
    private boolean closed;

    private PhysicsWorld(String dllSha256) {
        this.dllSha256 = dllSha256;
        broadPhaseLayers.mapObjectToBroadPhaseLayer(LAYER_STATIC, LAYER_STATIC);
        broadPhaseLayers.mapObjectToBroadPhaseLayer(LAYER_MOVING, LAYER_MOVING);
        broadPhaseLayers.mapObjectToBroadPhaseLayer(LAYER_PIECE, LAYER_MOVING);
        broadPhaseLayers.mapObjectToBroadPhaseLayer(LAYER_ATTACHMENT, LAYER_MOVING);
        layerPairs.enableCollision(LAYER_PIECE, LAYER_ATTACHMENT);
        layerPairs.enableCollision(LAYER_PIECE, LAYER_STATIC);
        layerPairs.enableCollision(LAYER_PIECE, LAYER_MOVING);
        layerPairs.enableCollision(LAYER_PIECE, LAYER_PIECE);
        layerPairs.enableCollision(LAYER_MOVING, LAYER_STATIC);
        layerPairs.enableCollision(LAYER_MOVING, LAYER_MOVING);
        objectVsBroadPhase = new ObjectVsBroadPhaseLayerFilterTable(broadPhaseLayers, BROAD_PHASE_COUNT, layerPairs, LAYER_COUNT);
        system.init(MAX_BODIES, 0, MAX_BODY_PAIRS, MAX_CONTACT_CONSTRAINTS, broadPhaseLayers, objectVsBroadPhase, layerPairs);
        system.setGravity(0f, 0f, -GRAVITY);
        applyPhysicsSettings();
        bodies = system.getBodyInterfaceNoLock();
        groundQuery = system.getNarrowPhaseQueryNoLock();
        bodyLocks = system.getBodyLockInterfaceNoLock();
        pivots = new PivotBodies(system, bodies);
        rolling = new RollingResistance(bodies, this::castGroundRay);
        jobSystem = new JobSystemSingleThreaded(Jolt.cMaxPhysicsJobs);
    }

    /**
     * Creates a world with the native library that comes with this build, single threaded, which is the
     * configuration the build is tested with.
     *
     * @param nativeDirectory directory the DLL is extracted into before it is loaded
     */
    public static PhysicsWorld create(Path nativeDirectory) {
        String loaded = NativeLibrary.load(nativeDirectory);
        startEngine();
        if (!Jolt.implementsCrossPlatformDeterminism()) {
            throw new IllegalStateException("the native library was not built for cross-platform determinism");
        }
        if (Jolt.isDoublePrecision()) {
            throw new IllegalStateException("the native library is double precision; single precision is pinned");
        }
        return new PhysicsWorld(loaded);
    }

    private static synchronized void startEngine() {
        if (engineStarted) {
            return;
        }
        Jolt.registerDefaultAllocator();
        Jolt.installDefaultTraceCallback();
        Jolt.installTraceAssertCallback();
        if (!Jolt.newFactory()) {
            throw new IllegalStateException("Jolt could not create its factory");
        }
        Jolt.registerTypes();
        engineStarted = true;
    }

    // The defaults are tuned for human-sized objects; foam balls of 7 to 13 cm need far finer contacts.
    private void applyPhysicsSettings() {
        try (PhysicsSettings settings = new PhysicsSettings()) {
            settings.setPenetrationSlop(0.001f);
            settings.setSpeculativeContactDistance(0.005f);
            settings.setMinVelocityForRestitution(0.1f);
            system.setPhysicsSettings(settings);
        }
    }

    /** Describes the pinned engine build, for logs and reports. */
    public String engineInfo() {
        return "Jolt " + Jolt.versionString() + " " + Jolt.buildType() + " [" + Jolt.getConfigurationString().trim()
                + "] joltjni.dll sha256=" + dllSha256;
    }

    public long stepIndex() {
        return stepIndex;
    }

    /** Advances the world by one fixed step. */
    public void step() {
        driveChassis();
        pivots.applyDampers();
        rolling.apply(STEP_SECONDS);
        int error = system.update(STEP_SECONDS, 1, tempAllocator, jobSystem);
        if (error != EPhysicsUpdateError.None) {
            throw new IllegalStateException("Jolt reported update error bits " + error + " at step " + stepIndex);
        }
        stepIndex++;
    }

    /** Pose of every entity, ordered by entity id. */
    public WorldSnapshot snapshot() {
        List<BodyState> states = new ArrayList<>(entities.size());
        for (Entity entity : entities.values()) {
            bodies.getPositionAndRotation(entity.bodyId(), scratchPosition, scratchRotation);
            states.add(new BodyState(
                    entity.id(), entity.kind(),
                    scratchPosition.x(), scratchPosition.y(), scratchPosition.z(),
                    scratchRotation.getX(), scratchRotation.getY(), scratchRotation.getZ(), scratchRotation.getW()));
        }
        return new WorldSnapshot(stepIndex, Math.multiplyExact(stepIndex, 1_000_000_000L) / STEPS_PER_SECOND, states);
    }

    /**
     * The true state of one entity at the current step: its pose, and the velocity of the point of the body that
     * its position denotes (the engine itself reports the velocity of the centre of mass).
     *
     * @throws IllegalArgumentException if no such entity exists
     */
    public BodyPose bodyPose(int entityId) {
        Entity entity = entities.get(entityId);
        if (entity == null) {
            throw new IllegalArgumentException("entity " + entityId + " does not exist");
        }
        bodies.getPositionAndRotation(entity.bodyId(), scratchPosition, scratchRotation);
        bodies.getLinearAndAngularVelocity(entity.bodyId(), scratchLinearVelocity, scratchAngularVelocity);
        Vector3 origin = vector(scratchPosition);
        Vector3 angular = vector(scratchAngularVelocity);
        Vector3 comToOrigin = origin.minus(vector(bodies.getCenterOfMassPosition(entity.bodyId())));
        Vector3 velocity = vector(scratchLinearVelocity).plus(angular.cross(comToOrigin));
        return new BodyPose(
                Math.multiplyExact(stepIndex, 1_000_000_000L) / STEPS_PER_SECOND,
                origin.x(), origin.y(), origin.z(),
                scratchRotation.getW(), scratchRotation.getX(), scratchRotation.getY(), scratchRotation.getZ(),
                velocity.x(), velocity.y(), velocity.z(),
                angular.x(), angular.y(), angular.z());
    }

    /**
     * What a ray meets first: pieces, structures and fixed field geometry, but not the entity it starts from,
     * which is how a sensor on the robot must see the world.
     *
     * @param direction       unit vector the ray travels along
     * @param ignoredEntityId an entity the ray passes through, such as the robot carrying the sensor; any id that
     *                        is not in the world ignores nothing
     * @return the nearest hit within {@code maxMeters}, or null
     */
    public RayHit castRay(Vector3 origin, Vector3 direction, double maxMeters, int ignoredEntityId) {
        Entity ignored = entities.get(ignoredEntityId);
        rayIgnored.clear();
        if (ignored != null) {
            rayIgnored.ignoreBody(ignored.bodyId());
        }
        for (int id : attachments.getOrDefault(ignoredEntityId, List.of())) {
            rayIgnored.ignoreBody(id);
        }
        try (RRayCast ray = new RRayCast(rvec3(origin), vec3(direction.times(maxMeters)));
                RayCastResult hit = new RayCastResult()) {
            if (!groundQuery.castRay(ray, hit, anyBroadPhase, anyLayer, rayIgnored)) {
                return null;
            }
            double distance = hit.getFraction() * maxMeters;
            for (Entity entity : entities.values()) {
                if (entity.bodyId() == hit.getBodyId()) {
                    return new RayHit(distance, entity.id(), entity.kind());
                }
            }
            return new RayHit(distance, RayHit.FIXED, RayHit.FIXED);
        }
    }

    /** Adds immovable field geometry: a box with the given centre, half extents and optional tilt. */
    public void addStaticBox(Vector3 center, Vector3 halfExtents, Vector3 rotationAxis, double rotationRadians) {
        addStaticBox(center, halfExtents, rotationAxis, rotationRadians, FIELD_FRICTION, FIELD_RESTITUTION);
    }

    /**
     * The same for a surface of its own material. The engine combines the friction of two touching bodies as the
     * square root of their product and their restitution as the larger of the two, so a piece never bounces less than
     * its own restitution says, whatever it hits.
     */
    public void addStaticBox(
            Vector3 center, Vector3 halfExtents, Vector3 rotationAxis, double rotationRadians, float friction, float restitution) {
        try (BoxShape shape = new BoxShape(vec3(halfExtents), 0f);
                BodyCreationSettings settings = new BodyCreationSettings(
                        shape, rvec3(center), quat(Quaternion.axisAngle(rotationAxis, rotationRadians)),
                        EMotionType.Static, LAYER_STATIC)) {
            settings.setFriction(friction).setRestitution(restitution);
            bodies.createAndAddBody(settings, EActivation.DontActivate);
        }
    }

    public void addStaticBox(Vector3 center, Vector3 halfExtents) {
        addStaticBox(center, halfExtents, Vector3.Y, 0.0);
    }

    /**
     * Adds immovable field geometry made of several solids, such as a ring of segments, as one body. The parts are
     * given in the world frame.
     */
    public void addStaticShapes(List<ShapePart> parts, float friction, float restitution) {
        try (ShapeResult compound = Shapes.compoundOf(parts);
                ShapeRefC shape = compound.get();
                BodyCreationSettings settings = new BodyCreationSettings(
                        shape, new RVec3(), Quat.sIdentity(), EMotionType.Static, LAYER_STATIC)) {
            settings.setFriction(friction).setRestitution(restitution);
            bodies.createAndAddBody(settings, EActivation.DontActivate);
        }
    }

    /**
     * Adds a rigid body that swings about a fixed axis between two stops and is moved only by gravity and by what
     * touches it, such as a tipping tray that a load tips over.
     */
    public Pivot addPivot(int entityId, int kind, PivotSpec spec) {
        requireFreeEntity(entityId);
        register(entityId, kind, Role.PIVOT, pivots.create(entityId, spec, LAYER_MOVING));
        return new Pivot(this, entityId);
    }

    double pivotAngle(int entityId) {
        return pivots.angle(entityId);
    }

    double pivotAngularVelocity(int entityId) {
        return pivots.angularVelocity(entityId);
    }

    /**
     * Puts a sphere into the world, as when a shooter releases a piece. A piece that left the world
     * through {@link #removePiece(int)} may re-enter under the same id.
     *
     * @param kind     opaque category reported back in snapshots
     * @param velocity linear velocity; the piece starts without spin
     */
    public void spawnPiece(int entityId, int kind, PieceSpec spec, Vector3 position, Vector3 velocity) {
        requireFreeEntity(entityId);
        SphereShape shape = pieceShapes.computeIfAbsent(spec, s -> new SphereShape(s.radiusM()));
        try (BodyCreationSettings settings = new BodyCreationSettings(
                        shape, rvec3(position), Quat.sIdentity(), EMotionType.Dynamic, LAYER_PIECE);
                MassProperties mass = new MassProperties()) {
            mass.setMass(spec.massKg());
            settings.setOverrideMassProperties(EOverrideMassProperties.CalculateInertia)
                    .setMassPropertiesOverride(mass)
                    .setFriction(spec.friction())
                    .setRestitution(spec.restitution())
                    .setAngularDamping(spec.rollingDamping())
                    .setMaxAngularVelocity(MAX_ANGULAR_SPEED)
                    .setLinearVelocity(vec3(velocity));
            if (velocity.length() > FAST_PIECE_SPEED) {
                settings.setMotionQuality(EMotionQuality.LinearCast);
            }
            int bodyId = bodies.createAndAddBody(settings, EActivation.Activate);
            register(entityId, kind, Role.PIECE, bodyId);
            rolling.add(entityId, bodyId, spec);
        }
    }

    /** A piece-contacting plate hinged about its owner's lateral axis. Positive joint travel raises the nose. */
    public DoubleConsumer addHingedPlate(int owner, Vector3 halfExtents, Vector3 pivot, Vector3 offset, double angle) {
        PlatePose initial = platePose(owner, pivot, offset, angle);
        try (BoxShape shape = new BoxShape(vec3(halfExtents), 0.001f);
                BodyCreationSettings settings = new BodyCreationSettings(
                        shape, rvec3(initial.center()), quat(initial.rotation()), EMotionType.Kinematic, LAYER_ATTACHMENT)) {
            int id = bodies.createAndAddBody(settings, EActivation.Activate);
            if (id == Jolt.cInvalidBodyId) {
                throw new IllegalStateException("the world is full");
            }
            attachments.computeIfAbsent(owner, key -> new ArrayList<>()).add(id);
            return target -> {
                PlatePose pose = platePose(owner, pivot, offset, target);
                bodies.moveKinematic(id, rvec3(pose.center()), quat(pose.rotation()), STEP_SECONDS);
            };
        }
    }

    private record PlatePose(Vector3 center, Quaternion rotation) {}

    private PlatePose platePose(int owner, Vector3 pivot, Vector3 offset, double angle) {
        BodyPose chassis = bodyPose(owner);
        Quaternion base = new Quaternion(chassis.qx(), chassis.qy(), chassis.qz(), chassis.qw());
        Quaternion hinge = Quaternion.axisAngle(Vector3.Y, -angle);
        Vector3 center = new Vector3(chassis.x(), chassis.y(), chassis.z()).plus(base.rotate(pivot.plus(hinge.rotate(offset))));
        return new PlatePose(center, base.times(hinge));
    }

    /** Takes a piece out of the world, as when an intake swallows it. */
    public void removePiece(int entityId) {
        Entity entity = entities.get(entityId);
        if (entity == null || entity.role() != Role.PIECE) {
            throw new IllegalArgumentException("entity " + entityId + " is not a piece in this world");
        }
        bodies.removeBody(entity.bodyId());
        bodies.destroyBody(entity.bodyId());
        entities.remove(entityId);
        rolling.remove(entityId);
    }

    /** Applies a world-frame impulse in newton seconds at the centre of a free piece. */
    public void pushPiece(int entityId, Vector3 impulse) {
        bodies.addImpulse(pieceBody(entityId), vec3(impulse));
    }

    public boolean hasPiece(int entityId) {
        Entity entity = entities.get(entityId);
        return entity != null && entity.role() == Role.PIECE;
    }

    /** Whether {@link #holdPiece} has taken the piece out of the physics and it has not been released since. */
    public boolean isPieceHeld(int entityId) {
        return bodies.getMotionType(pieceBody(entityId)) == EMotionType.Kinematic;
    }

    /**
     * Takes a piece out of the physics while it stays in the world and in snapshots: it no longer falls or rolls and
     * only goes where {@link #moveHeldPiece} sends it, yet still pushes bodies that touch it aside. A field uses this
     * to guide a piece along a path of its own, such as down a ramp.
     */
    public void holdPiece(int entityId) {
        int bodyId = pieceBody(entityId);
        bodies.setLinearAndAngularVelocity(bodyId, ZERO_VELOCITY, ZERO_VELOCITY);
        bodies.setMotionType(bodyId, EMotionType.Kinematic, EActivation.Activate);
    }

    /**
     * Moves a held piece so that it arrives at {@code position} at the end of the next step, turned by
     * {@code spinRadians} about {@code spinAxis}.
     *
     * @throws IllegalStateException if the piece is not held
     */
    public void moveHeldPiece(int entityId, Vector3 position, Vector3 spinAxis, double spinRadians) {
        int bodyId = heldPieceBody(entityId);
        bodies.moveKinematic(bodyId, rvec3(position), quat(Quaternion.axisAngle(spinAxis, spinRadians)), STEP_SECONDS);
    }

    /**
     * Gives a held piece back to the physics, moving with the given velocities.
     *
     * @throws IllegalStateException if the piece is not held
     */
    public void releasePiece(int entityId, Vector3 velocity, Vector3 angularVelocity) {
        int bodyId = heldPieceBody(entityId);
        bodies.setMotionType(bodyId, EMotionType.Dynamic, EActivation.Activate);
        bodies.setLinearAndAngularVelocity(bodyId, vec3(velocity), vec3(angularVelocity));
    }

    private int pieceBody(int entityId) {
        Entity entity = entities.get(entityId);
        if (entity == null || entity.role() != Role.PIECE) {
            throw new IllegalArgumentException("entity " + entityId + " is not a piece in this world");
        }
        return entity.bodyId();
    }

    private int heldPieceBody(int entityId) {
        int bodyId = pieceBody(entityId);
        if (bodies.getMotionType(bodyId) != EMotionType.Kinematic) {
            throw new IllegalStateException("piece " + entityId + " is not held");
        }
        return bodyId;
    }

    /**
     * Adds an immovable box that is also an entity, so that snapshots report its pose, rays report what they hit
     * and tags can be mounted on it. Unlike a plain static box, its yaw may differ from one run to the next.
     *
     * @param yawRadians turn about +Z
     */
    public void addFixedBox(int entityId, int kind, Vector3 center, Vector3 halfExtents, double yawRadians) {
        requireFreeEntity(entityId);
        try (BoxShape shape = new BoxShape(vec3(halfExtents), 0f);
                BodyCreationSettings settings = new BodyCreationSettings(
                        shape, rvec3(center), quat(Quaternion.axisAngle(Vector3.Z, yawRadians)),
                        EMotionType.Static, LAYER_STATIC)) {
            settings.setFriction(FIELD_FRICTION).setRestitution(FIELD_RESTITUTION);
            register(entityId, kind, Role.FIXED, bodies.createAndAddBody(settings, EActivation.DontActivate));
        }
    }

    /**
     * Stands a mecanum robot on the ground. The robot is a rounded box collider whose centre of mass
     * sits low; its wheels act only through {@link MecanumChassis}, which is driven by the commands
     * set before each {@link #step()}.
     *
     * @param groundPoint point on the ground under the centre of the robot; a higher point drops it
     * @param yawRadians  heading about +Z; zero faces +X
     */
    public MecanumChassis addMecanumChassis(
            int entityId, int kind, MecanumConfig config, Vector3 groundPoint, double yawRadians) {
        requireFreeEntity(entityId);
        Vector3 center = groundPoint.plus(new Vector3(0, 0, config.restBoxCenterHeightM()));
        Vector3 halfExtents = new Vector3(0.5 * config.lengthM(), 0.5 * config.widthM(), 0.5 * config.heightM());
        try (BoxShape box = new BoxShape(vec3(halfExtents), CHASSIS_EDGE_RADIUS);
                OffsetCenterOfMassShape shape = new OffsetCenterOfMassShape(box, vec3(config.centerOfMassOffset()));
                BodyCreationSettings settings = new BodyCreationSettings(
                        shape, rvec3(center), quat(Quaternion.axisAngle(Vector3.Z, yawRadians)),
                        EMotionType.Dynamic, LAYER_MOVING);
                MassProperties mass = new MassProperties()) {
            mass.setMass((float) config.massKg());
            settings.setOverrideMassProperties(EOverrideMassProperties.CalculateInertia)
                    .setMassPropertiesOverride(mass)
                    .setFriction(CHASSIS_FRICTION)
                    .setRestitution(CHASSIS_RESTITUTION)
                    .setLinearDamping(0f)
                    .setAngularDamping(0f)
                    .setAllowSleeping(false);
            int bodyId = bodies.createAndAddBody(settings, EActivation.Activate);
            register(entityId, kind, Role.CHASSIS, bodyId);
            MecanumChassis model = new MecanumChassis(entityId, config);
            chassis.put(entityId, new ChassisBody(bodyId, model));
            return model;
        }
    }

    private void driveChassis() {
        for (ChassisBody body : chassis.values()) {
            bodies.getPositionAndRotation(body.bodyId(), scratchPosition, scratchRotation);
            bodies.getLinearAndAngularVelocity(body.bodyId(), scratchLinearVelocity, scratchAngularVelocity);
            MecanumChassis.Motion motion = new MecanumChassis.Motion(
                    vector(scratchPosition), quaternion(scratchRotation),
                    vector(scratchLinearVelocity), vector(scratchAngularVelocity));
            for (MecanumChassis.ContactForce contact : body.model().advance(motion, this::castGroundRay, STEP_SECONDS)) {
                Vector3 force = contact.force();
                // A NaN or infinite force would poison the whole world, so it stops the simulation here.
                if (!Double.isFinite(force.x() + force.y() + force.z())) {
                    throw new IllegalStateException("wheel force is not finite at step " + stepIndex);
                }
                bodies.addForce(body.bodyId(), vec3(force), rvec3(contact.point()));
            }
        }
    }

    /** Whether a segment crosses fixed field geometry, for example the lip between a loose ball and an intake. */
    public boolean crossesField(Vector3 from, Vector3 to) {
        try (RRayCast ray = new RRayCast(rvec3(from), vec3(to.minus(from)));
                RayCastResult hit = new RayCastResult()) {
            return groundQuery.castRay(ray, hit, groundBroadPhase, groundLayer, anyBody);
        }
    }

    private MecanumChassis.GroundHit castGroundRay(Vector3 origin, Vector3 direction, double length) {
        try (RRayCast ray = new RRayCast(rvec3(origin), vec3(direction.times(length)));
                RayCastResult hit = new RayCastResult()) {
            if (!groundQuery.castRay(ray, hit, groundBroadPhase, groundLayer, anyBody)) {
                return null;
            }
            RVec3 point = ray.getPointOnRay(hit.getFraction());
            try (BodyLockRead lock = new BodyLockRead(bodyLocks, hit.getBodyId())) {
                return new MecanumChassis.GroundHit(
                        vector(point), vector(lock.getBody().getWorldSpaceSurfaceNormal(hit.getSubShapeId2(), point)));
            }
        }
    }

    private void closeNativeObjects() {
        pivots.close();
        system.close();
        pieceShapes.values().forEach(SphereShape::close);
        groundBroadPhase.close();
        groundLayer.close();
        anyBody.close();
        anyBroadPhase.close();
        anyLayer.close();
        rayIgnored.close();
        jobSystem.close();
        tempAllocator.close();
        objectVsBroadPhase.close();
        layerPairs.close();
        broadPhaseLayers.close();
    }

    private void requireFreeEntity(int entityId) {
        if (entities.containsKey(entityId)) {
            throw new IllegalArgumentException("entity " + entityId + " already exists");
        }
    }

    private void register(int entityId, int kind, Role role, int bodyId) {
        if (bodyId == Jolt.cInvalidBodyId) {
            throw new IllegalStateException("the world is full: " + MAX_BODIES + " bodies");
        }
        entities.put(entityId, new Entity(entityId, kind, role, bodyId));
    }

    private static Vec3 vec3(Vector3 v) {
        return new Vec3((float) v.x(), (float) v.y(), (float) v.z());
    }

    private static RVec3 rvec3(Vector3 v) {
        return new RVec3(v.x(), v.y(), v.z());
    }

    private static Quat quat(Quaternion q) {
        return new Quat((float) q.x(), (float) q.y(), (float) q.z(), (float) q.w());
    }

    private static Vector3 vector(Vec3 v) {
        return new Vector3(v.getX(), v.getY(), v.getZ());
    }

    private static Vector3 vector(RVec3 v) {
        return new Vector3(v.xx(), v.yy(), v.zz());
    }

    private static Quaternion quaternion(Quat q) {
        return new Quaternion(q.getX(), q.getY(), q.getZ(), q.getW());
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        closeNativeObjects();
    }
}
