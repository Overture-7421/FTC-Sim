package dev.ftcsim.contracts.hardware;

import java.util.Objects;

/**
 * The link between a robot controller and its smart cameras, at the level of the HTTP requests the camera's own
 * software serves. A Limelight is a computer on its own: the SDK driver sends it a request such as
 * {@code GET /results} and gets JSON text back, so that text, and nothing about how the camera made it, is what
 * crosses this interface. The simulated cameras sit behind it and the SDK adapter in front of it, so neither knows
 * the other's types, and the SDK's own parsing of the JSON is what robot code sees.
 *
 * <p>Cameras are known to the wire by the name robot code asks the hardware map for. Every time is simulated
 * nanoseconds on the sender's clock; the wire may be called with a time slightly ahead of the world's clock, as a
 * robot thread's is, and answers as of that time.
 */
public interface CameraWire {

    /** How the camera answered. */
    record Reply(int status, String body, long latencyNanos) {

        /** The status of a request that nobody answered: no camera at the address, or one that is not listening. */
        public static final int UNREACHABLE = 0;

        public static final int OK = 200;

        public Reply {
            Objects.requireNonNull(body, "body");
            if (latencyNanos < 0) {
                throw new IllegalArgumentException("latency cannot be negative: " + latencyNanos);
            }
        }

        /** No answer at all. The client decides how long it waited for one; its own connection timeout applies. */
        public static Reply unreachable() {
            return new Reply(UNREACHABLE, "", 0);
        }

        /** An HTTP answer without a body. */
        public static Reply status(int status, long latencyNanos) {
            return new Reply(status, "", latencyNanos);
        }

        public static Reply json(String body, long latencyNanos) {
            return new Reply(OK, body, latencyNanos);
        }

        /** Whether the camera answered 200, which is the only answer the SDK's driver accepts. */
        public boolean ok() {
            return status == OK;
        }

        public boolean reachable() {
            return status != UNREACHABLE;
        }
    }

    /**
     * Robot code asked the hardware map for a camera. The answer is whether the robot has a camera by that name;
     * when it has not, the request is recorded as a problem, and every later request to the name goes unanswered.
     */
    boolean connect(String camera);

    /**
     * @param path the path and query exactly as the driver builds them, such as {@code /results} or
     *             {@code /pipeline-switch?index=0}
     */
    Reply get(String camera, String path, long nowNanos);

    /** @param body the request body, or null for a request without one */
    Reply post(String camera, String path, String body, long nowNanos);

    Reply delete(String camera, String path, long nowNanos);

    /** A wire with no cameras on it, for robot controllers whose host has none: every name is unplugged. */
    CameraWire NONE = new CameraWire() {
        @Override
        public boolean connect(String camera) {
            return false;
        }

        @Override
        public Reply get(String camera, String path, long nowNanos) {
            return Reply.unreachable();
        }

        @Override
        public Reply post(String camera, String path, String body, long nowNanos) {
            return Reply.unreachable();
        }

        @Override
        public Reply delete(String camera, String path, long nowNanos) {
            return Reply.unreachable();
        }
    };
}
