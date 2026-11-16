package dev.ftcsim.ui.scene;

import dev.ftcsim.contracts.field.Alliance;
import dev.ftcsim.contracts.field.BodyKind;
import dev.ftcsim.contracts.field.FieldDescription;
import dev.ftcsim.contracts.field.FieldPart;
import dev.ftcsim.contracts.field.FieldTag;
import dev.ftcsim.contracts.field.Pose3;
import dev.ftcsim.contracts.field.Scenery;
import dev.ftcsim.contracts.field.Shape;
import dev.ftcsim.contracts.session.RobotInfo;
import dev.ftcsim.contracts.snapshot.BodyKinds;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What the viewport draws, derived once from a session's {@link FieldDescription} and {@link RobotInfo}: the fixed
 * scenery in field coordinates, and for each kind of moving body the solids that make it up relative to the body.
 * Pure data with no graphics objects, so it can be built on any thread and handed to the render thread.
 */
public final class SceneModel {
    /** The robot's colour when nothing says which alliance it plays for. */
    public static final int DEFAULT_ROBOT_RGB = 0x1E88E5;

    private static final int RED_ROBOT_RGB = 0xE53935;
    private static final float FIELD_FLOOR_HALF = 1.8288f;
    private static final float BARE_FLOOR_HALF = 6.0f;
    private static final float SMALL_SPHERE_BELOW = 0.04f;
    private static final float MEDIUM_SPHERE_BELOW = 0.09f;
    private static final int ROBOT_FRONT_RGB = 0xFFC107;
    private static final Part[] NO_PARTS = new Part[0];
    // A box this wide and flat whose top lies in the floor plane is the floor, which the renderer draws with tile seams.
    private static final float FLOOR_MIN_HALF = 0.9f;
    private static final float FLOOR_PLANE_TOLERANCE = 0.01f;
    private static final float FLAT_TOLERANCE = 1e-3f;
    // An AprilTag is a black square with a white square on it and a black core: layers a fraction of a millimetre apart.
    private static final float TAG_LAYER_HALF_THICKNESS = 0.0015f;
    private static final float TAG_LAYER_STEP = 0.0005f;
    private static final float TAG_WHITE_SHARE = 0.78f;
    private static final float TAG_CORE_SHARE = 0.40f;
    private static final int TAG_BLACK_RGB = 0x101010;
    private static final int TAG_WHITE_RGB = 0xF2F2F2;

    /** The meshes the renderer has; a sphere's detail follows its size. */
    public enum Mesh {
        BOX,
        CYLINDER,
        SPHERE_COARSE,
        SPHERE_MEDIUM,
        SPHERE_FINE
    }

    /**
     * One solid: where its centre is and how it is turned, its half extents along its own axes, and its colour.
     *
     * @param floor whether it is the field's floor, which is drawn with the seams between its tiles
     */
    public record Part(
            Mesh mesh,
            float x, float y, float z,
            float qx, float qy, float qz, float qw,
            float halfX, float halfY, float halfZ,
            float red, float green, float blue,
            boolean floor) {

        /** Writes the pose of this part when its parent sits at the pose found at {@code offset} of {@code parent}. */
        public void placeOn(float[] parent, int offset, float[] out) {
            float px = parent[offset];
            float py = parent[offset + 1];
            float pz = parent[offset + 2];
            float ax = parent[offset + 3];
            float ay = parent[offset + 4];
            float az = parent[offset + 5];
            float aw = parent[offset + 6];
            // v + 2 * cross(a.xyz, cross(a.xyz, v) + a.w * v)
            float cx = ay * z - az * y + aw * x;
            float cy = az * x - ax * z + aw * y;
            float cz = ax * y - ay * x + aw * z;
            out[0] = px + x + 2f * (ay * cz - az * cy);
            out[1] = py + y + 2f * (az * cx - ax * cz);
            out[2] = pz + z + 2f * (ax * cy - ay * cx);
            out[3] = aw * qx + ax * qw + ay * qz - az * qy;
            out[4] = aw * qy - ax * qz + ay * qw + az * qx;
            out[5] = aw * qz + ax * qy - ay * qx + az * qw;
            out[6] = aw * qw - ax * qx - ay * qy - az * qz;
        }

        private float reachAlong(float xx, float xy, float xz) {
            return Math.abs(xx) * halfX + Math.abs(xy) * halfY + Math.abs(xz) * halfZ;
        }

        /** How far the part reaches from its centre along the world X axis. */
        float reachX() {
            return reachAlong(
                    1f - 2f * (qy * qy + qz * qz), 2f * (qx * qy - qz * qw), 2f * (qx * qz + qy * qw));
        }

        /** How far the part reaches from its centre along the world Y axis. */
        float reachY() {
            return reachAlong(
                    2f * (qx * qy + qz * qw), 1f - 2f * (qx * qx + qz * qz), 2f * (qy * qz - qx * qw));
        }

        /** How far the part reaches from its centre along the world Z axis. */
        float reachZ() {
            return reachAlong(
                    2f * (qx * qz - qy * qw), 2f * (qy * qz + qx * qw), 1f - 2f * (qx * qx + qy * qy));
        }

        /** The highest point of the part. */
        public float top() {
            return z + reachZ();
        }
    }

    private final List<Part> scenery;
    private final int[] kindIds;
    private final Part[][] kindParts;
    private final int[] hostIds;
    private final Part[][] hostParts;
    private final float floorHalf;
    private final boolean ownFloor;

    private SceneModel(
            List<Part> scenery, int[] kindIds, Part[][] kindParts, int[] hostIds, Part[][] hostParts, float floorHalf) {
        this.scenery = scenery;
        this.kindIds = kindIds;
        this.kindParts = kindParts;
        this.hostIds = hostIds;
        this.hostParts = hostParts;
        this.floorHalf = floorHalf;
        this.ownFloor = scenery.stream().anyMatch(part -> part.floor() && covers(part, floorHalf));
    }

    private static boolean covers(Part floor, float half) {
        float slack = 1e-3f;
        return floor.x() - floor.reachX() <= -half + slack && floor.x() + floor.reachX() >= half - slack
                && floor.y() - floor.reachY() <= -half + slack && floor.y() + floor.reachY() >= half - slack;
    }

    /**
     * A scene of fixed solids only, already in world coordinates, on the renderer's own floor: what a picture of something
     * other than a field is made of, such as the robot editor's. Nothing moves, so no snapshot is needed to draw it.
     *
     * @param floorHalf half the width of the floor that is drawn, metres
     */
    public static SceneModel ofParts(List<Part> parts, float floorHalf) {
        return new SceneModel(List.copyOf(parts), new int[0], new Part[0][], new int[0], new Part[0][], floorHalf);
    }

    /** The same as {@link #of(FieldDescription, RobotInfo, int)} with the robot in {@link #DEFAULT_ROBOT_RGB}. */
    public static SceneModel of(FieldDescription field, RobotInfo robot) {
        return of(field, robot, DEFAULT_ROBOT_RGB);
    }

    /** The colour the robot is drawn in for the alliance it plays for; blue when there is none. */
    public static int robotRgb(Alliance alliance) {
        return alliance == Alliance.RED ? RED_ROBOT_RGB : DEFAULT_ROBOT_RGB;
    }

    /**
     * @param field the field to draw, or null for a bare floor
     * @param robot the robot's chassis, or null when the robot is not drawn; it is the body of kind
     *     {@link BodyKinds#CHASSIS}
     * @param robotRgb the colour of the robot's body, which is the alliance it plays for
     */
    public static SceneModel of(FieldDescription field, RobotInfo robot, int robotRgb) {
        List<Part> fixed = new ArrayList<>();
        Map<Integer, Part[]> kinds = new TreeMap<>();
        Map<Integer, List<Part>> hosted = new TreeMap<>();
        float half = BARE_FLOOR_HALF;
        if (field != null) {
            half = FIELD_FLOOR_HALF;
            float[] placed = new float[7];
            for (Scenery item : field.scenery()) {
                Part local = partOf(item.part());
                local.placeOn(poseOf(item.pose()), 0, placed);
                Part world = withPose(local, placed);
                if (isFloor(world)) {
                    world = asFloor(world);
                }
                fixed.add(world);
                half = Math.max(half, Math.max(Math.abs(world.x()) + world.reachX(), Math.abs(world.y()) + world.reachY()));
            }
            for (BodyKind kind : field.bodyKinds()) {
                kinds.put(kind.id(), kind.parts().stream().map(SceneModel::partOf).toArray(Part[]::new));
            }
            for (FieldTag tag : field.tags()) {
                List<Part> target = tag.hostEntityId() < 0 ? fixed : hosted.computeIfAbsent(tag.hostEntityId(), id -> new ArrayList<>());
                float[] pose = poseOf(tag.local());
                for (Part layer : tagLayers((float) tag.sizeMeters() / 2f)) {
                    layer.placeOn(pose, 0, placed);
                    target.add(withPose(layer, placed));
                }
            }
        }
        if (robot != null) {
            kinds.put(BodyKinds.CHASSIS, robotParts(robot, robotRgb));
        }
        int[] ids = kinds.keySet().stream().mapToInt(Integer::intValue).toArray();
        int[] hostIds = hosted.keySet().stream().mapToInt(Integer::intValue).toArray();
        Part[][] hostParts = hosted.values().stream().map(list -> list.toArray(new Part[0])).toArray(Part[][]::new);
        return new SceneModel(List.copyOf(fixed), ids, kinds.values().toArray(new Part[0][]), hostIds, hostParts, half);
    }

    /** The fixed scenery with every pose in field coordinates. */
    public List<Part> scenery() {
        return scenery;
    }

    /** The solids of one kind of body relative to the body; empty when the field does not describe the kind. */
    public Part[] parts(int kind) {
        int index = Arrays.binarySearch(kindIds, kind);
        return index < 0 ? NO_PARTS : kindParts[index];
    }

    /** What a particular body carries besides what its kind has (the AprilTags that ride on it), relative to the body. */
    public Part[] carriedBy(int entityId) {
        if (hostIds.length == 0) {
            return NO_PARTS;
        }
        int index = Arrays.binarySearch(hostIds, entityId);
        return index < 0 ? NO_PARTS : hostParts[index];
    }

    /** Half the width of the square floor that is drawn, metres. */
    public float floorHalfExtent() {
        return floorHalf;
    }

    /** Whether the scenery has a floor of its own that covers the whole floor square, so that none need be added. */
    public boolean bringsItsOwnFloor() {
        return ownFloor;
    }

    private static Part[] robotParts(RobotInfo robot, int bodyRgb) {
        float halfLength = (float) robot.lengthM() / 2f;
        float halfWidth = (float) robot.widthM() / 2f;
        float halfHeight = (float) robot.heightM() / 2f;
        float stripe = Math.min(0.03f, halfLength / 4f);
        return new Part[] {
            box(0f, 0f, 0f, halfLength, halfWidth, halfHeight, bodyRgb),
            box(halfLength - stripe, 0f, halfHeight, stripe, halfWidth * 0.8f, 0.004f, ROBOT_FRONT_RGB),
        };
    }

    private static Part box(float x, float y, float z, float halfX, float halfY, float halfZ, int rgb) {
        return new Part(Mesh.BOX, x, y, z, 0f, 0f, 0f, 1f, halfX, halfY, halfZ, red(rgb), green(rgb), blue(rgb), false);
    }

    /** The layers of a tag lying on the XY plane of its own frame, facing along +Z. */
    private static Part[] tagLayers(float half) {
        float step = TAG_LAYER_HALF_THICKNESS;
        return new Part[] {
            box(0f, 0f, step, half, half, step, TAG_BLACK_RGB),
            box(0f, 0f, step + TAG_LAYER_STEP, half * TAG_WHITE_SHARE, half * TAG_WHITE_SHARE, step, TAG_WHITE_RGB),
            box(0f, 0f, step + 2 * TAG_LAYER_STEP, half * TAG_CORE_SHARE, half * TAG_CORE_SHARE, step, TAG_BLACK_RGB),
        };
    }

    private static boolean isFloor(Part part) {
        return part.mesh() == Mesh.BOX
                && part.halfX() >= FLOOR_MIN_HALF
                && part.halfY() >= FLOOR_MIN_HALF
                && Math.abs(part.qx()) < FLAT_TOLERANCE
                && Math.abs(part.qy()) < FLAT_TOLERANCE
                && Math.abs(part.z() + part.halfZ()) <= FLOOR_PLANE_TOLERANCE;
    }

    private static Part asFloor(Part part) {
        return new Part(
                part.mesh(), part.x(), part.y(), part.z(), part.qx(), part.qy(), part.qz(), part.qw(),
                part.halfX(), part.halfY(), part.halfZ(), part.red(), part.green(), part.blue(), true);
    }

    private static Part partOf(FieldPart part) {
        Pose3 local = part.local();
        float qx = (float) local.qx();
        float qy = (float) local.qy();
        float qz = (float) local.qz();
        float qw = (float) local.qw();
        float length = (float) Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
        if (!(length > 0f)) {
            qx = 0f;
            qy = 0f;
            qz = 0f;
            qw = 1f;
            length = 1f;
        }
        int rgb = part.rgb();
        return switch (part.shape()) {
            case Shape.Box shape -> new Part(
                    Mesh.BOX, (float) local.x(), (float) local.y(), (float) local.z(),
                    qx / length, qy / length, qz / length, qw / length,
                    (float) shape.halfX(), (float) shape.halfY(), (float) shape.halfZ(), red(rgb), green(rgb), blue(rgb), false);
            case Shape.Sphere shape -> new Part(
                    sphereMesh((float) shape.radius()), (float) local.x(), (float) local.y(), (float) local.z(),
                    qx / length, qy / length, qz / length, qw / length,
                    (float) shape.radius(), (float) shape.radius(), (float) shape.radius(), red(rgb), green(rgb), blue(rgb), false);
            case Shape.Cylinder shape -> new Part(
                    Mesh.CYLINDER, (float) local.x(), (float) local.y(), (float) local.z(),
                    qx / length, qy / length, qz / length, qw / length,
                    (float) shape.radius(), (float) shape.radius(), (float) shape.halfHeight(), red(rgb), green(rgb), blue(rgb), false);
        };
    }

    private static Mesh sphereMesh(float radius) {
        if (radius < SMALL_SPHERE_BELOW) {
            return Mesh.SPHERE_COARSE;
        }
        return radius < MEDIUM_SPHERE_BELOW ? Mesh.SPHERE_MEDIUM : Mesh.SPHERE_FINE;
    }

    private static float[] poseOf(Pose3 pose) {
        float qx = (float) pose.qx();
        float qy = (float) pose.qy();
        float qz = (float) pose.qz();
        float qw = (float) pose.qw();
        float length = (float) Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
        if (!(length > 0f)) {
            return new float[] {(float) pose.x(), (float) pose.y(), (float) pose.z(), 0f, 0f, 0f, 1f};
        }
        return new float[] {
            (float) pose.x(), (float) pose.y(), (float) pose.z(), qx / length, qy / length, qz / length, qw / length
        };
    }

    private static Part withPose(Part part, float[] pose) {
        return new Part(
                part.mesh(), pose[0], pose[1], pose[2], pose[3], pose[4], pose[5], pose[6],
                part.halfX(), part.halfY(), part.halfZ(), part.red(), part.green(), part.blue(), part.floor());
    }

    private static float red(int rgb) {
        return ((rgb >> 16) & 0xFF) / 255f;
    }

    private static float green(int rgb) {
        return ((rgb >> 8) & 0xFF) / 255f;
    }

    private static float blue(int rgb) {
        return (rgb & 0xFF) / 255f;
    }
}
