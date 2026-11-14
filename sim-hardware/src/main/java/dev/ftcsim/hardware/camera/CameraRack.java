package dev.ftcsim.hardware.camera;

import dev.ftcsim.contracts.hardware.CameraWire;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The cameras of one robot, found by the name robot code asks for them with, behind the {@link CameraWire} that the
 * SDK adapter talks to. A request for a name the robot does not have is unanswered, as a request to an address with
 * nothing plugged in would be, and is remembered for {@link #problems()}.
 */
public final class CameraRack implements CameraWire {

    private final Map<String, CameraModel> cameras = new LinkedHashMap<>();
    private final Set<String> missing = new LinkedHashSet<>();

    /** Plugs a camera in under the name robot code asks the hardware map for. */
    public CameraRack add(String name, CameraModel camera) {
        if (cameras.putIfAbsent(name, Objects.requireNonNull(camera, "camera")) != null) {
            throw new IllegalStateException("the robot already has a camera called \"" + name + "\"");
        }
        return this;
    }

    public List<String> names() {
        return List.copyOf(cameras.keySet());
    }

    @Override
    public boolean connect(String camera) {
        if (cameras.containsKey(camera)) {
            return true;
        }
        missing.add(camera);
        return false;
    }

    @Override
    public Reply get(String camera, String path, long nowNanos) {
        CameraModel model = cameras.get(camera);
        if (model == null) {
            missing.add(camera);
            return Reply.unreachable();
        }
        return model.get(path, nowNanos);
    }

    @Override
    public Reply post(String camera, String path, String body, long nowNanos) {
        CameraModel model = cameras.get(camera);
        if (model == null) {
            missing.add(camera);
            return Reply.unreachable();
        }
        return model.post(path, body, nowNanos);
    }

    @Override
    public Reply delete(String camera, String path, long nowNanos) {
        CameraModel model = cameras.get(camera);
        if (model == null) {
            missing.add(camera);
            return Reply.unreachable();
        }
        return model.delete(path, nowNanos);
    }

    /**
     * Everything that weakens the claim that the robot's cameras behave as its code expects: cameras the code asked
     * for that the robot profile does not have, and requests the camera models do not honour.
     */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        for (String name : missing) {
            problems.add("camera \"" + name + "\": robot code asked for it but the robot profile has no camera by that name,"
                    + " so it is unplugged: no result is ever valid and every request to it fails; it has " + names());
        }
        cameras.forEach((name, camera) -> camera.problems().forEach(problem -> problems.add("camera \"" + name + "\": " + problem)));
        return problems;
    }
}
