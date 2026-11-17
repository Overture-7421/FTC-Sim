package dev.ftcsim.ui.model;

import dev.ftcsim.contracts.snapshot.RobotJoint;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * A robot's picture made from its CAD: rigid groups of triangles, each turning about a pivot on its parent, as
 * {@code tools/cad/convert_biobuzz.py} writes them. A group's vertices are quantised and relative to its pivot, so the
 * group is drawn at {@code parent frame * translate(pivot - parent pivot) * rotate(axis, sign * (joint - rest))}. The
 * model's frame is the simulator's: X forward, Y left, Z up, origin at the chassis body's centre.
 */
public final class RobotModel {
    /** The resource of the BIOBUZZ robot's model. */
    public static final String BIOBUZZ = "/dev/ftcsim/ui/model/biobuzz.model";

    /** The joint that is the turret's yaw rather than one of {@link RobotJoint}. */
    public static final String TURRET_JOINT = "turret";

    private static final int MAGIC = 'F' | 'T' << 8 | 'C' << 16 | 'M' << 24;

    /**
     * A run of at most 65536 vertices. {@code positions} holds 3 shorts per vertex that map linearly onto {@code min}
     * to {@code max}, {@code colours} 3 bytes and {@code indices} 3 unsigned shorts per triangle; all are little-endian
     * direct buffers positioned at their start.
     */
    public record Chunk(int vertices, int triangles, float[] min, float[] max, ByteBuffer positions, ByteBuffer colours, ByteBuffer indices) {}

    /**
     * @param parent the index of the parent group, -1 for the root
     * @param jointName the joint that turns the group: a {@link RobotJoint} model name, {@link #TURRET_JOINT}, or empty for none
     * @param sign multiplies the joint's value
     * @param rest the joint's value at which the group stands as the CAD has it
     */
    public record Group(
            String name, int parent, String jointName, RobotJoint joint, float sign, float rest, float[] pivot, float[] axis,
            List<Chunk> chunks) {

        /** Triangles of the group. */
        public int triangles() {
            return chunks.stream().mapToInt(Chunk::triangles).sum();
        }
    }

    private final List<Group> groups;

    private RobotModel(List<Group> groups) {
        this.groups = List.copyOf(groups);
    }

    /** The groups, parents before their children. */
    public List<Group> groups() {
        return groups;
    }

    /** Triangles of the whole model. */
    public int triangles() {
        return groups.stream().mapToInt(Group::triangles).sum();
    }

    /** The model in the class path resource, or null when the build has none. */
    public static RobotModel fromResource(String resource) {
        try (InputStream in = RobotModel.class.getResourceAsStream(resource)) {
            return in == null ? null : read(in);
        } catch (IOException failure) {
            throw new IllegalStateException("cannot read the robot model " + resource + ": " + failure.getMessage(), failure);
        }
    }

    /** Reads a model from a stream of gzip compressed bytes. */
    public static RobotModel read(InputStream compressed) throws IOException {
        byte[] all;
        try (GZIPInputStream in = new GZIPInputStream(compressed)) {
            all = in.readAllBytes();
        }
        ByteBuffer heap = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
        if (heap.getInt() != MAGIC) {
            throw new IOException("not a robot model");
        }
        int version = heap.getInt();
        if (version != 1) {
            throw new IOException("robot model version " + version + " is not known");
        }
        int count = heap.getInt();
        record Header(String name, String parent, String joint, float sign, float rest, float[] pivot, float[] axis, List<int[]> counts,
                List<float[]> boxes) {}
        List<Header> headers = new ArrayList<>();
        for (int g = 0; g < count; g++) {
            String name = text(heap);
            String parent = text(heap);
            String joint = text(heap);
            float sign = heap.getFloat();
            float rest = heap.getFloat();
            float[] pivot = floats(heap, 3);
            float[] axis = floats(heap, 3);
            int chunks = heap.get() & 0xFF;
            List<int[]> counts = new ArrayList<>();
            List<float[]> boxes = new ArrayList<>();
            for (int c = 0; c < chunks; c++) {
                counts.add(new int[] {heap.getInt(), heap.getInt()});
                boxes.add(floats(heap, 6));
            }
            headers.add(new Header(name, parent, joint, sign, rest, pivot, axis, counts, boxes));
        }
        List<Group> groups = new ArrayList<>();
        for (Header header : headers) {
            int parent = -1;
            for (int i = 0; i < groups.size(); i++) {
                if (groups.get(i).name().equals(header.parent())) {
                    parent = i;
                }
            }
            if (parent < 0 && !header.parent().isEmpty()) {
                throw new IOException("group " + header.name() + " comes before its parent " + header.parent());
            }
            List<Chunk> chunks = new ArrayList<>();
            for (int c = 0; c < header.counts().size(); c++) {
                int vertices = header.counts().get(c)[0];
                int triangles = header.counts().get(c)[1];
                float[] box = header.boxes().get(c);
                ByteBuffer positions = direct(heap, vertices * 6);
                ByteBuffer colours = direct(heap, vertices * 3);
                ByteBuffer indices = direct(heap, triangles * 6);
                chunks.add(new Chunk(
                        vertices, triangles, new float[] {box[0], box[1], box[2]}, new float[] {box[3], box[4], box[5]}, positions,
                        colours, indices));
            }
            RobotJoint joint = RobotJoint.named(header.joint());
            if (joint == null && !header.joint().isEmpty() && !header.joint().equals(TURRET_JOINT)) {
                throw new IOException("group " + header.name() + " is turned by the unknown joint " + header.joint());
            }
            groups.add(new Group(
                    header.name(), parent, header.joint(), joint, header.sign(), header.rest(), header.pivot(), header.axis(),
                    List.copyOf(chunks)));
        }
        return new RobotModel(groups);
    }

    private static String text(ByteBuffer buffer) {
        int length = buffer.get() & 0xFF;
        String value = new String(buffer.array(), buffer.arrayOffset() + buffer.position(), length, StandardCharsets.UTF_8);
        buffer.position(buffer.position() + length);
        return value;
    }

    private static float[] floats(ByteBuffer buffer, int count) {
        float[] out = new float[count];
        for (int i = 0; i < count; i++) {
            out[i] = buffer.getFloat();
        }
        return out;
    }

    private static ByteBuffer direct(ByteBuffer source, int bytes) {
        ByteBuffer copy = ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN);
        copy.put(source.slice(source.position(), bytes));
        copy.flip();
        source.position(source.position() + bytes);
        return copy;
    }
}
