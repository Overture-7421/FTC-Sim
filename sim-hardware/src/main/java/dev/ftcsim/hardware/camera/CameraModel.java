package dev.ftcsim.hardware.camera;

import dev.ftcsim.contracts.hardware.CameraWire;
import java.util.List;

/**
 * One smart camera, as a computer that answers HTTP requests: what its own web server would reply to each request,
 * and nothing about how the SDK's driver sends it. All times are simulated nanoseconds; a request is answered as of
 * the time it is sent, plus the latency the reply reports.
 */
public interface CameraModel {

    CameraWire.Reply get(String path, long nowNanos);

    /** @param body the request body, or null for a request without one */
    CameraWire.Reply post(String path, String body, long nowNanos);

    CameraWire.Reply delete(String path, long nowNanos);

    /** AprilTag ids in the latest completed frame at the given simulated instant, without polling the camera. */
    default List<Integer> visibleTagIds(long nowNanos) {
        return List.of();
    }

    /** What robot code did with the camera that the model does not honour, for the simulator's list of problems. */
    List<String> problems();
}
