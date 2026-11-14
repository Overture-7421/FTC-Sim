package dev.ftcsim.hardware.camera;

import dev.ftcsim.contracts.field.FieldTag;
import dev.ftcsim.contracts.field.Pose3;
import dev.ftcsim.contracts.hardware.CameraWire;
import dev.ftcsim.contracts.world.BodyPose;
import dev.ftcsim.contracts.world.RayView;
import dev.ftcsim.contracts.world.SurfaceHit;
import dev.ftcsim.contracts.world.Vec3;
import dev.ftcsim.hardware.device.WorldRig;
import dev.ftcsim.hardware.device.scripted.ScriptedBody;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongFunction;

/** A Limelight on a scripted chassis in a field of tags, stepped like the simulator steps its world. */
final class CameraWorld {

    static final long MS = WorldRig.MS;
    static final double SIZE = 0.1651;

    final ScriptedBody body;
    final WorldRig rig;
    final CameraModel camera;

    CameraWorld(ScriptedBody body, RayView rays, List<FieldTag> tags, Map<String, Object> settings, long seed) {
        this(body, rays, tags, settings, seed, Map.of());
    }

    /** As above, with bodies that tags are stuck on: each entity id has its trajectory, the pose by simulated time. */
    CameraWorld(
            ScriptedBody body, RayView rays, List<FieldTag> tags, Map<String, Object> settings, long seed,
            Map<Integer, LongFunction<BodyPose>> hosts) {
        this.body = body;
        this.rig = new WorldRig(body, rays);
        rig.host.provideFieldTags(tags);
        rig.host.provideGameMap("decode", tags);
        rig.host.provideEntities(id -> {
            LongFunction<BodyPose> trajectory = hosts.get(id);
            if (trajectory == null) {
                throw new IllegalArgumentException("no entity " + id);
            }
            return () -> trajectory.apply(rig.now());
        });
        this.camera = CameraModels.standard().create("limelight-3a", "limelight", settings, rig.host, seed);
    }

    /** The settings of a camera like the one the tests of the solver use, with one AprilTag pipeline. */
    static Map<String, Object> settings(String fieldMap) {
        Map<String, Object> settings = new HashMap<>();
        CameraMount mount = TestScene.MOUNT;
        settings.put("mount.x", mount.x());
        settings.put("mount.y", mount.y());
        settings.put("mount.z", mount.z());
        settings.put("mount.yawDegrees", mount.yawDegrees());
        settings.put("mount.pitchDegrees", mount.pitchDegrees());
        settings.put("mount.rollDegrees", mount.rollDegrees());
        settings.put("pipeline0.type", "apriltag");
        settings.put("pipeline0.fieldMap", fieldMap);
        return settings;
    }

    /** A tag standing upright at the place, facing {@code facingYaw}, stuck on the field or on a body. */
    static FieldTag tag(int id, double x, double y, double z, double facingYaw, int host) {
        double c = StrictMath.cos(0.5 * facingYaw);
        double s = StrictMath.sin(0.5 * facingYaw);
        return new FieldTag(id, SIZE, host, new Pose3(x, y, z, 0.5 * (c - s), 0.5 * (c - s), 0.5 * (c + s), 0.5 * (c + s)));
    }

    static FieldTag blueGoal() {
        return tag(20, -1.4826, -1.4133, 0.7493, StrictMath.toRadians(54.0), -1);
    }

    static FieldTag redGoal() {
        return tag(24, -1.4826, 1.4133, 0.7493, StrictMath.toRadians(-54.0), -1);
    }

    /** A chassis that stands still, {@code distance} metres from the blue goal's tag and looking at it. */
    static ScriptedBody parkedFacing(FieldTag tag, double distance, double sideways, double extraYaw) {
        Rigid robot = TestScene.robotFacing(SceneTag.of(tag), distance, sideways, extraYaw);
        return ScriptedBody.startingAt(robot.translation().x(), robot.translation().y(), robot.rotation().yaw()).stay(30).build();
    }

    static final RayView OPEN_SPACE = (origin, direction, maxMeters) -> null;

    /** Something round in the way: a ball of the given radius at the given place. */
    static RayView ball(Vec3 centre, double radius) {
        return (origin, direction, maxMeters) -> {
            Vec3 toOrigin = origin.minus(centre);
            double along = toOrigin.dot(direction);
            double outside = toOrigin.dot(toOrigin) - radius * radius;
            double discriminant = along * along - outside;
            if (discriminant < 0) {
                return null;
            }
            double distance = -along - Math.sqrt(discriminant);
            return distance >= 0 && distance <= maxMeters ? new SurfaceHit(distance, SurfaceHit.SCENERY, -1, 0x808080) : null;
        };
    }

    Map<String, Object> results(long nowNanos) {
        CameraWire.Reply reply = camera.get("/results", nowNanos);
        if (!reply.ok()) {
            throw new AssertionError("GET /results answered " + reply.status());
        }
        return Json.object(reply.body());
    }

    CameraWire.Reply get(String path, long nowNanos) {
        return camera.get(path, nowNanos);
    }

    CameraWire.Reply post(String path, String body, long nowNanos) {
        return camera.post(path, body, nowNanos);
    }
}
